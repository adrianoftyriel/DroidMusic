package org.droidmusic.app.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.droidmusic.library.DocxText
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
     * The picker intent for adding a folder.
     *
     * **Not every provider offers its folders here, and that is not a bug in
     * this intent.** The system folder picker only lists roots whose provider
     * declares it can answer "is this document inside this tree"
     * (`Root.FLAG_SUPPORTS_IS_CHILD`); a provider that does not declare it is
     * filtered out before this app sees anything. OneDrive is the one people hit
     * - it appears when picking *files* and not when picking a *folder*, which
     * looks exactly like the app forgetting to offer it.
     *
     * There is no flag to pass that changes this, and no amount of retrying the
     * picker helps. What the app can do is stop pretending a folder is the only
     * way in: [pickFilesIntent] reaches every provider, and the library screen
     * offers it alongside this one rather than only when the library is empty.
     */
    fun pickTreeIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
    }

    /**
     * The picker intent for adding individual files.
     *
     * This is also the way into a provider that does not offer folders - see
     * [pickTreeIntent] - so it is worth it being generous. `application/octet-stream`
     * is in the list because that is what most providers report for a `.cho` or
     * a `.pro`, which they have never heard of.
     */
    fun pickFilesIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf(
                "application/pdf",
                "text/*",
                "image/*",
                SongRef.DOCX_MIME_TYPE,
                "application/octet-stream",
            ),
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

    /**
     * Hands a grant back when a folder is removed from the library.
     *
     * Not tidiness. A persistable grant this app no longer uses still shows up in
     * Android's own storage-access screens as this app having access to that
     * folder, which is untrue and unnerving the moment somebody goes looking. And
     * the platform caps how many an app may hold at once, so a library that has
     * been rearranged a few times over a couple of years can quietly stop being
     * able to take a new one.
     *
     * URIs that were never persisted are skipped rather than released, because
     * releasing one that is not held throws - and this runs on a path where the
     * removal has already happened and there is nothing useful to do about it.
     */
    fun releasePermissions(context: Context, uris: List<Uri>) {
        val held = runCatching {
            context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
        }.getOrDefault(emptySet())

        for (uri in uris) {
            if (uri !in held) continue
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    /**
     * Deletes the copies this app made in its own storage.
     *
     * Managed copies are the one kind of file the app owns outright. Nothing
     * outside it has a reference to them, so forgetting the source they belong to
     * without deleting them would leave them on the device for good, taking up
     * the space of a scanned songbook, with nothing left that could ever open
     * them.
     *
     * The parent check is deliberate. This is the only code in the app that
     * deletes a user's file, and confining it to the managed directory means a
     * mistake somewhere else - a song row that kept a `file://` URI from
     * somewhere it should not have - cannot turn into a deleted chart.
     */
    fun deleteManagedCopies(context: Context, songs: List<SongRef>) {
        for (song in songs) {
            managedFile(context, Uri.parse(song.uri))?.let { file -> runCatching { file.delete() } }
        }
    }

    /**
     * The file behind a URI, but only if it is one of this app's own managed
     * copies.
     *
     * The parent check is the whole point and is deliberately the only way to a
     * `File.delete()` in this app. A song row that had picked up a `file://` URI
     * from somewhere it should not have cannot turn into a deleted chart, because
     * the answer here is null for anything outside the managed directory.
     */
    private fun managedFile(context: Context, uri: Uri): java.io.File? {
        if (uri.scheme != "file") return null
        val managed = runCatching {
            java.io.File(context.filesDir, "managed").canonicalFile
        }.getOrNull() ?: return null
        val path = uri.path ?: return null
        val file = runCatching { java.io.File(path).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it.parentFile == managed }
    }

    /**
     * Whether this app can actually delete the file behind a chart.
     *
     * Asked before offering to, rather than discovered by trying. A menu item
     * that fails when tapped is worse than one that is not there, and for most
     * charts in most libraries the honest answer here is no:
     *
     *  - A **managed copy** - a photographed page, or a chart imported from a
     *    link - belongs to this app outright, and can go.
     *  - A chart in **one of the user's folders**, local or cloud, cannot. The
     *    app asks the system file picker for read access and nothing more (see
     *    [pickTreeIntent]), so it holds no write grant to delete with. Deleting
     *    those is the file manager's job, or Drive's.
     *
     * The provider's own flag is checked as well as the grant, because a provider
     * may refuse deletion on a file it has otherwise shared for writing. Both
     * halves are required, so if this app ever does start asking for write
     * access, this begins returning true on its own and the menu item appears
     * without anything here changing.
     */
    fun canDeleteFile(context: Context, song: SongRef): Boolean {
        val uri = Uri.parse(song.uri)
        if (uri.scheme == "file") return managedFile(context, uri) != null
        if (!hasWritePermission(context, uri)) return false
        return (documentFlags(context, uri) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0
    }

    /**
     * Deletes the file behind a chart, returning whether it went.
     *
     * Guarded by the same [managedFile] check as everything else that deletes,
     * so this cannot be talked into removing a file outside the app's own storage
     * by handing it a `file://` URI.
     */
    suspend fun deleteFile(context: Context, song: SongRef): Boolean =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(song.uri)
            if (uri.scheme == "file") {
                val file = managedFile(context, uri) ?: return@withContext false
                runCatching { file.delete() }.getOrDefault(false)
            } else {
                runCatching {
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                }.getOrDefault(false)
            }
        }

    private fun hasWritePermission(context: Context, uri: Uri): Boolean = runCatching {
        context.checkUriPermission(
            uri,
            Process.myPid(),
            Process.myUid(),
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** A document's capability flags, or zero when the provider will not say. */
    private fun documentFlags(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 } ?: 0
    }.getOrDefault(0)

    /**
     * Why a chart would not open, asked only once one already has not.
     *
     * The viewer used to offer one explanation for every failure - that the file
     * might be in a cloud folder and need to be available offline. That is a
     * real cause and it is not the commonest one, and the others each have a
     * different thing to do about them: a grant Android is no longer honouring
     * wants the file adding again, a file that has moved wants finding, and a
     * file that reads perfectly well but is not a chart wants neither. One
     * message covering all of them tells somebody standing at a music stand to
     * go and check a setting that was never the problem.
     *
     * The probe is one byte. This runs only on a failure, so the cost is paid
     * once, by somebody who is already stuck.
     */
    suspend fun describeOpenFailure(
        resolver: ContentResolver,
        song: SongRef,
    ): String = withContext(Dispatchers.IO) {
        val probe = runCatching {
            resolver.openInputStream(Uri.parse(song.uri))?.use { it.read() }
        }

        when (val failure = probe.exceptionOrNull()) {
            is SecurityException ->
                "Android is no longer letting DroidMusic read it. This happens to a " +
                    "file shared into the app from somewhere else, because the " +
                    "permission that came with it only lasted for that share. Add the " +
                    "file, or the folder it sits in, again."

            is java.io.FileNotFoundException ->
                "It is not where it was when it was added. If it was moved, renamed or " +
                    "deleted, add it again from wherever it lives now."

            null -> if (probe.getOrNull() == null) {
                "The app that provides it would not hand it over. If it lives in a " +
                    "cloud folder, it may need to be made available offline."
            } else {
                // The bytes are there and were readable, so nothing about storage
                // or permissions is wrong: this file defeated the chart reader.
                "The file was read but could not be laid out as a chart. If it is a " +
                    "PDF or an image, it may be damaged."
            }

            else -> "It could not be read: ${failure.message ?: "the file would not open"}. " +
                "If it lives in a cloud folder, it may need to be made available offline."
        }
    }

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
                        val text = readChartText(
                            context.contentResolver,
                            Uri.parse(song.uri),
                            song.kind,
                        ) ?: return@runCatching song
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
     * What a file the user handed over directly turns out to be.
     *
     * The name is asked first, and usually answers. When it does not - which for
     * ChordPro is common, because the format has six extensions in use, no
     * registered MIME type, and every provider therefore calls one an
     * `application/octet-stream` - the first few kilobytes are read and asked
     * instead. Anything that is text is opened as a chart, and the parser works
     * out from the content whether it is ChordPro, chords over lyrics or plain
     * words; it never needed the extension to tell it that.
     *
     * Only for files picked or opened deliberately. A folder scan stays on
     * extensions, because sniffing every file in somebody's Documents folder
     * would mean opening every file in somebody's Documents folder.
     */
    suspend fun kindOfPickedFile(
        resolver: ContentResolver,
        uri: Uri,
        displayName: String,
        mimeType: String?,
    ): FileKind = withContext(Dispatchers.IO) {
        val byName = SongRef.kindOf(displayName, mimeType)
        if (byName != FileKind.UNKNOWN) {
            byName
        } else if (looksLikeText(resolver, uri)) {
            FileKind.TEXT
        } else {
            FileKind.UNKNOWN
        }
    }

    /**
     * Whether a file handed to the app is one of its own set lists.
     *
     * Asked of the content rather than the name because a set list shared out of
     * a messaging app arrives with its name stripped and its type reduced to
     * `application/octet-stream`, and the difference between a set list and a
     * chart decides which half of the app opens it.
     */
    fun looksLikeSetlist(resolver: ContentResolver, uri: Uri): Boolean {
        val head = firstBytes(resolver, uri) ?: return false
        val text = String(head, Charsets.UTF_8)
        return text.trimStart().startsWith("{") &&
            text.contains("\"setlist\"") &&
            text.contains("\"formatVersion\"")
    }

    /** Reads the first few kilobytes and asks whether they are text. */
    private fun looksLikeText(resolver: ContentResolver, uri: Uri): Boolean {
        val head = firstBytes(resolver, uri) ?: return false
        return SongRef.looksLikeText(head, head.size)
    }

    private fun firstBytes(resolver: ContentResolver, uri: Uri): ByteArray? = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(SNIFF_BYTES)
            var total = 0
            while (total < buffer.size) {
                val read = stream.read(buffer, total, buffer.size - total)
                if (read <= 0) break
                total += read
            }
            if (total == 0) null else buffer.copyOf(total)
        }
    }.getOrNull()

    /** Enough to see a chart's first directive, and small enough to be free. */
    private const val SNIFF_BYTES = 4096

    /**
     * Reads a chart as text, whatever it is stored as.
     *
     * The one place that knows a Word document is not a text file. Everything
     * downstream - the parser, key detection, the transposer, the layout engine -
     * sees characters and does not care where they came from, which is why DOCX
     * support is this small: it is a decoder, not a second code path.
     */
    fun readChartText(resolver: ContentResolver, uri: Uri, kind: FileKind): String? =
        if (kind == FileKind.DOCX) readDocx(resolver, uri) else readText(resolver, uri)

    private fun readDocx(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.openInputStream(uri)?.use { DocxText.extract(it) }
    }.getOrNull()

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
        authority.contains("com.microsoft.skydrive") ||
            authority.contains("onedrive") -> "OneDrive"
        authority.contains("dropbox") -> "Dropbox"
        authority.contains("com.box.android") -> "Box"
        authority.contains("protonmail") || authority.contains("proton.android") -> "Proton Drive"
        authority.contains("nextcloud") -> "Nextcloud"
        authority.contains("com.android.externalstorage") -> "This device"
        authority.contains("com.android.providers.downloads") -> "Downloads"
        else -> "Files"
    }

    /**
     * What to call the bucket that individually picked files land in.
     *
     * Named after the provider when there is a recognisable one, because picking
     * files one at a time is how a service that offers no folders gets used at
     * all, and "OneDrive files" is then a real place in the library rather than
     * an anonymous pile. Anything local, or anything unrecognised, stays "Picked
     * files": "This device files" is not English, and naming a bucket after an
     * authority string helps nobody.
     */
    fun pickedFilesLabel(authority: String?): String = when (val provider = providerLabel(authority)) {
        "Files", "This device", "Downloads" -> "Picked files"
        else -> "$provider files"
    }
}
