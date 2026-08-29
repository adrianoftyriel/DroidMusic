package org.droidmusic.app.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.droidmusic.library.FileKind
import org.droidmusic.library.SongRef
import org.droidmusic.library.SourceKind
import org.droidmusic.library.SourceRef
import org.droidmusic.music.ChartAnalyzer
import org.droidmusic.music.SongParser

/**
 * Reaching the user's files, wherever they keep them.
 *
 * **Why there is no Google Drive SDK in this app, and no Dropbox one either.**
 *
 * The obvious way to build "open charts from Google Drive, OneDrive, Dropbox,
 * Box and Proton Drive" is five SDKs, five OAuth flows, five sets of API keys
 * shipped in the APK, and five separate things to re-certify every time one of
 * those vendors changes their terms. It is also worse for the user, who has to
 * sign in to each service again inside this app and grant it standing access to
 * their whole drive.
 *
 * Android already solved this. Every one of those providers ships a
 * DocumentsProvider, so all of them appear inside the system file picker, and a
 * tree granted through that picker is readable with the ordinary ContentResolver
 * regardless of who is behind it. One integration, no keys, no vendor sign-in,
 * access scoped to the one folder the user chose, and a provider that does not
 * exist yet works on the day it ships.
 *
 * The cost is real and worth stating: what the app gets is whatever the provider
 * chose to expose. Some providers are slow to list a large folder, and some only
 * expose files that have been made available offline. That is why the app also
 * offers to copy a chart into managed storage - see [copyIntoManagedStorage] -
 * which is the answer for anything that has to open on a stage with no signal.
 */
object DocumentSources {

    /**
     * The picker intent for adding a folder. Any provider on the device shows up
     * in it, which is the whole point.
     */
    fun pickTreeIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
    }

    /** The picker intent for adding individual files. */
    fun pickFilesIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf("application/pdf", "text/*", "image/*", "application/octet-stream"),
        )
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
    }

    /**
     * Holds on to the grant across reboots.
     *
     * Without this the app can read the folder until the process dies and then
     * quietly cannot, which presents as "my library is empty" the next morning.
     */
    fun persistPermission(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        true
    }.getOrDefault(false)

    /** Whether a previously granted source is still readable. */
    fun hasPermission(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }

    fun sourceFromTree(context: Context, treeUri: Uri, label: String?): SourceRef = SourceRef(
        id = UUID.randomUUID().toString(),
        kind = SourceKind.EXTERNAL_TREE,
        uri = treeUri.toString(),
        label = label ?: displayNameOfTree(context, treeUri) ?: "Folder",
        authority = treeUri.authority,
        addedAt = System.currentTimeMillis(),
    )

    private fun displayNameOfTree(context: Context, treeUri: Uri): String? = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        context.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    /**
     * Walks a granted tree and returns every chart in it.
     *
     * Iterative rather than recursive, with a depth cap. A document tree can
     * contain a cycle - a provider is free to expose a shortcut that points at an
     * ancestor - and a recursive walk over one does not come back.
     */
    suspend fun scanTree(
        context: Context,
        source: SourceRef,
        maxDepth: Int = 8,
        maxFiles: Int = 20_000,
    ): List<SongRef> = withContext(Dispatchers.IO) {
        val treeUri = Uri.parse(source.uri)
        val resolver = context.contentResolver
        val found = mutableListOf<SongRef>()
        val seen = mutableSetOf<String>()

        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return@withContext emptyList()

        val queue = ArrayDeque<Pair<String, Int>>()
        queue += rootId to 0

        while (queue.isNotEmpty() && found.size < maxFiles) {
            val (documentId, depth) = queue.removeFirst()
            if (!seen.add(documentId)) continue
            if (depth > maxDepth) continue

            val childrenUri = runCatching {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            }.getOrNull() ?: continue

            val cursor = runCatching {
                resolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    ),
                    null,
                    null,
                    null,
                )
            }.getOrNull() ?: continue

            cursor.use {
                while (it.moveToNext() && found.size < maxFiles) {
                    val childId = it.getString(0) ?: continue
                    val name = it.getString(1) ?: continue
                    val mime = it.getString(2)
                    val size = if (it.isNull(3)) 0L else it.getLong(3)
                    val modified = if (it.isNull(4)) 0L else it.getLong(4)

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        queue += childId to (depth + 1)
                        continue
                    }

                    val kind = SongRef.kindOf(name, mime)
                    if (kind == FileKind.UNKNOWN) continue

                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    found += SongRef(
                        id = stableId(source.id, childId),
                        sourceId = source.id,
                        uri = docUri.toString(),
                        displayName = name,
                        kind = kind,
                        sizeBytes = size,
                        modifiedAt = modified,
                    )
                }
            }
        }
        found
    }

    /**
     * Fills in title, artist, key and content hash for the charts we can read.
     *
     * Only text charts are parsed. A PDF's title would need the whole file
     * decoded, and on a folder of four hundred scans that is minutes of work for
     * a field the filename usually already carries.
     */
    suspend fun enrich(
        context: Context,
        songs: List<SongRef>,
        parseContents: Boolean,
    ): List<SongRef> = withContext(Dispatchers.IO) {
        songs.map { song ->
            runCatching {
                when {
                    !parseContents -> song
                    song.isTransposable -> {
                        val text = readText(context.contentResolver, Uri.parse(song.uri))
                            ?: return@runCatching song
                        val parsed = SongParser.parse(text)
                        val analysis = ChartAnalyzer.analyze(parsed)
                        song.copy(
                            title = parsed.meta.title,
                            artist = parsed.meta.artist,
                            keyText = analysis.effectiveKey?.toString(),
                            contentHash = hashOf(text.toByteArray()),
                        )
                    }
                    else -> song.copy(contentHash = hashOfFile(context.contentResolver, Uri.parse(song.uri)))
                }
            }.getOrDefault(song)
        }
    }

    /**
     * Reads a text chart, up to [maxBytes].
     *
     * The read loop is written out rather than using `InputStream.readNBytes`,
     * which is a Java 9 API and does not reach Android until API 33 - on a
     * minSdk 26 build that is a crash on most devices in the field, not a
     * compile error.
     *
     * The cap matters: this runs over every text file in a folder the user
     * chose, and one of them being a 200MB log somebody dropped in there should
     * cost a truncated chart, not the process.
     */
    fun readText(resolver: ContentResolver, uri: Uri, maxBytes: Int = 2 * 1024 * 1024): String? =
        runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                var total = 0
                while (total < maxBytes) {
                    val read = stream.read(buffer, 0, minOf(buffer.size, maxBytes - total))
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    total += read
                }
                String(out.toByteArray(), Charsets.UTF_8)
            }
        }.getOrNull()

    /**
     * A content hash for matching the same chart across devices.
     *
     * Only the first megabyte is hashed, together with the length. Hashing a
     * 60MB scanned songbook in full, for every file, on the phone, to decide
     * whether two devices have the same chart is not a trade worth making; a
     * collision between two different charts that share their first megabyte
     * *and* their exact length is not a thing that happens in a music library,
     * and the set list matcher falls back to the title anyway.
     */
    fun hashOfFile(resolver: ContentResolver, uri: Uri, prefixBytes: Int = 1024 * 1024): String? =
        runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var total = 0
                while (total < prefixBytes) {
                    val read = stream.read(buffer, 0, minOf(buffer.size, prefixBytes - total))
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    total += read
                }
                digest.update(total.toString().toByteArray())
                digest.digest().toHex()
            }
        }.getOrNull()

    fun hashOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * A song id that survives a rescan.
     *
     * Derived from the source and the provider's own document id rather than
     * generated fresh, so that rescanning a folder does not invalidate every set
     * list that referred to something in it.
     */
    fun stableId(sourceId: String, documentId: String): String =
        hashOf("$sourceId|$documentId".toByteArray()).take(32)

    /**
     * Copies a chart into the app's own storage.
     *
     * The escape hatch for the one thing a document provider cannot promise: that
     * the file will still open when the venue has no signal and the provider
     * wants to fetch it. A managed copy is on the device and will open.
     */
    suspend fun copyIntoManagedStorage(
        context: Context,
        song: SongRef,
        managedSourceId: String,
    ): SongRef? = withContext(Dispatchers.IO) {
        runCatching {
            val directory = java.io.File(context.filesDir, "managed").apply { mkdirs() }
            val target = java.io.File(directory, "${song.id}_${song.displayName}")
            context.contentResolver.openInputStream(Uri.parse(song.uri))?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching null

            song.copy(
                id = stableId(managedSourceId, target.name),
                sourceId = managedSourceId,
                uri = Uri.fromFile(target).toString(),
                sizeBytes = target.length(),
                modifiedAt = target.lastModified(),
            )
        }.getOrNull()
    }

    /** The always-present source that managed copies belong to. */
    fun managedSource(): SourceRef = SourceRef(
        id = MANAGED_SOURCE_ID,
        kind = SourceKind.MANAGED,
        uri = "",
        label = "On this device",
        addedAt = 0L,
    )

    const val MANAGED_SOURCE_ID = "managed"

    /**
     * A friendly name for the provider behind a URI, so the library can say
     * "Google Drive" rather than showing an authority string.
     */
    fun providerLabel(authority: String?): String = when {
        authority == null -> "Files"
        authority.contains("google.android.apps.docs") -> "Google Drive"
        authority.contains("com.microsoft.skydrive") -> "OneDrive"
        authority.contains("dropbox") -> "Dropbox"
        authority.contains("com.box.android") -> "Box"
        authority.contains("protonmail") || authority.contains("proton.android") -> "Proton Drive"
        authority.contains("nextcloud") -> "Nextcloud"
        authority.contains("com.android.externalstorage") -> "This device"
        authority.contains("com.android.providers.downloads") -> "Downloads"
        else -> "Files"
    }
}
