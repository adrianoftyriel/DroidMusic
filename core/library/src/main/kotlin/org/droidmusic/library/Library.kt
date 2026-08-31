package org.droidmusic.library

import kotlinx.serialization.Serializable
import org.droidmusic.music.Key

/**
 * Where a song's file physically lives.
 *
 * The distinction the app cares about is not "which cloud provider" but "can I
 * open this without a network". A managed file has been copied into the app's
 * own storage and will open on a stage with no signal; an external one is a
 * pointer into someone else's document provider and may not.
 */
@Serializable
enum class SourceKind {
    /** Copied into the app's storage. Always available. */
    MANAGED,

    /**
     * A document tree the user granted through the system file picker. This is
     * how every cloud provider is reached - see [SourceRef].
     */
    EXTERNAL_TREE,

    /** A single file granted individually rather than as part of a tree. */
    EXTERNAL_FILE,
}

/**
 * A reference to a place files come from.
 *
 * [uri] is deliberately an opaque string rather than anything provider-specific.
 * Google Drive, OneDrive, Dropbox, Box and Proton Drive all publish a
 * DocumentsProvider on Android, so all of them are reachable through one
 * system-granted tree URI and none of them needs its own SDK, its own OAuth
 * client, or its own API key baked into the app. See docs/DESIGN.md for why
 * that trade is the right one.
 */
@Serializable
data class SourceRef(
    val id: String,
    val kind: SourceKind,
    val uri: String,
    /** What to call it in the UI: "Band Drive", "Downloads", "On this device". */
    val label: String,
    /** The authority of the provider, kept for display: "com.google.android.apps.docs". */
    val authority: String? = null,
    val addedAt: Long = 0L,
)

@Serializable
enum class FileKind {
    PDF,
    IMAGE,
    CHORDPRO,
    TEXT,

    /**
     * A Word document. Read as text and treated as a chart from there on - see
     * [DocxText] for what is and is not recovered from one.
     */
    DOCX,

    UNKNOWN,
}

/**
 * One chart in the library.
 *
 * [contentHash] is what makes a set list portable. Sending a set list to another
 * device sends a list of hashes and titles, and the receiving device matches
 * them against its own library; without a content hash the only thing to match
 * on is a file path, which is meaningless on a different phone.
 */
@Serializable
data class SongRef(
    val id: String,
    val sourceId: String,
    /** Provider URI for the document itself. */
    val uri: String,
    val displayName: String,
    val kind: FileKind,
    val sizeBytes: Long = 0L,
    val modifiedAt: Long = 0L,
    val contentHash: String? = null,

    // Filled in by the indexer for text charts, left null for PDFs and images.
    val title: String? = null,
    val artist: String? = null,
    val keyText: String? = null,
    val pageCount: Int = 0,
    val tags: List<String> = emptyList(),
    /** Set by the user; overrides anything detected. */
    val userKeyText: String? = null,
    val favourite: Boolean = false,
) {
    val key: Key? get() = (userKeyText ?: keyText)?.let { Key.parse(it) }

    /** Title if the file said what it was, filename otherwise. */
    val bestTitle: String get() = title?.takeIf { it.isNotBlank() } ?: displayName.substringBeforeLast('.')

    /**
     * Whether the chart is text the app can rewrite.
     *
     * A Word document counts: what comes out of it is characters, and once it is
     * characters it goes through the same parser, the same key detection and the
     * same transposer as a `.txt`. A PDF does not, because there is nothing in it
     * to rewrite.
     */
    val isTransposable: Boolean get() =
        kind == FileKind.CHORDPRO || kind == FileKind.TEXT || kind == FileKind.DOCX

    companion object {
        fun kindOf(displayName: String, mimeType: String? = null): FileKind {
            val ext = displayName.substringAfterLast('.', "").lowercase()
            return when {
                ext == "pdf" || mimeType == "application/pdf" -> FileKind.PDF
                ext in IMAGE_EXTENSIONS || mimeType?.startsWith("image/") == true -> FileKind.IMAGE
                ext in CHORDPRO_EXTENSIONS -> FileKind.CHORDPRO
                ext in DOCUMENT_EXTENSIONS || mimeType == DOCX_MIME_TYPE -> FileKind.DOCX
                ext in TEXT_EXTENSIONS || mimeType?.startsWith("text/") == true -> FileKind.TEXT
                else -> FileKind.UNKNOWN
            }
        }

        /**
         * Whether the start of a file looks like text rather than a binary blob.
         *
         * Needed because a chart's extension is not a reliable thing to ask.
         * ChordPro has six extensions in common use and no registered MIME type
         * at all, so a provider hands one over as `application/octet-stream` -
         * the same answer it gives for a firmware image. Every editor that
         * writes them picks its own favourite, and somebody's own convention
         * (`.song`, `.cp`, no extension at all) is not wrong, it is just not on
         * a list this app happened to write down.
         *
         * So when the name says nothing, the content is asked. A NUL byte is
         * the giveaway - text does not contain them and nearly every binary
         * format does within its first few bytes - with a small tolerance for
         * stray control characters, because a chart exported from a word
         * processor sometimes carries one.
         */
        fun looksLikeText(bytes: ByteArray, length: Int = bytes.size): Boolean {
            val end = length.coerceIn(0, bytes.size)
            if (end == 0) return false
            var control = 0
            for (i in 0 until end) {
                val byte = bytes[i].toInt() and 0xFF
                if (byte == 0) return false
                // Tab, newline, carriage return and form feed are text.
                val printable = byte >= 0x20 || byte == 0x09 || byte == 0x0A ||
                    byte == 0x0D || byte == 0x0C
                if (!printable) control++
            }
            return control * 100 < end
        }

        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")
        val CHORDPRO_EXTENSIONS = setOf("cho", "chopro", "chord", "chordpro", "crd", "pro")
        val TEXT_EXTENSIONS = setOf("txt", "text", "tab", "md")

        /**
         * Word documents. `.doc` is deliberately absent: the old binary format
         * is not a zip and not XML, and claiming to open one only to fail on the
         * stand is worse than not offering it.
         */
        val DOCUMENT_EXTENSIONS = setOf("docx")

        const val DOCX_MIME_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

        /** Everything the viewer can open, for filtering a document picker. */
        val ALL_EXTENSIONS =
            IMAGE_EXTENSIONS + CHORDPRO_EXTENSIONS + TEXT_EXTENSIONS + DOCUMENT_EXTENSIONS + "pdf"
    }
}

/** The whole library index, as persisted. */
@Serializable
data class LibraryIndex(
    val sources: List<SourceRef> = emptyList(),
    val songs: List<SongRef> = emptyList(),
    val updatedAt: Long = 0L,
) {
    fun songsFrom(sourceId: String): List<SongRef> = songs.filter { it.sourceId == sourceId }

    fun findById(id: String): SongRef? = songs.firstOrNull { it.id == id }

    /**
     * Finds the local copy of a song described by another device.
     *
     * Content hash first, because it is exact. Title second, because two bands
     * will have the same chart from different sources with different bytes -
     * different scans of the same page, or a PDF against a ChordPro of the same
     * song - and matching those is the whole point of the fallback.
     */
    fun match(hash: String?, title: String): SongRef? {
        if (hash != null) {
            songs.firstOrNull { it.contentHash == hash }?.let { return it }
        }
        val wanted = title.normaliseForMatching()
        return songs.firstOrNull { it.bestTitle.normaliseForMatching() == wanted }
    }
}

/**
 * Loose title matching: case, punctuation and a leading article are all things
 * that differ between two people's copies of the same chart without meaning
 * anything.
 */
fun String.normaliseForMatching(): String =
    lowercase()
        .replace(Regex("^(the|a|an)\\s+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
