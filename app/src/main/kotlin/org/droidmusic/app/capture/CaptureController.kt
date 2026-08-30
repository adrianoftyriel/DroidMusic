package org.droidmusic.app.capture

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.droidmusic.app.data.DocumentSources
import org.droidmusic.app.data.LibraryRepository
import org.droidmusic.library.FileKind
import org.droidmusic.library.SongRef

/**
 * Photographing a piece of music and filing it as a PDF.
 *
 * The working files live in the cache: a raw photograph while it is being
 * processed, then one JPEG per straightened page. Only the finished PDF goes
 * into the app's own storage, because only the finished PDF is worth keeping if
 * the system reclaims the cache overnight.
 */
class CaptureController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
) {
    var pages by mutableStateOf<List<ScannedPage>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf("")

    /** Set when a scan has been filed, so the screen can leave and open it. */
    var saved by mutableStateOf<SongRef?>(null)
        private set

    private var pendingPhoto: File? = null

    private val workingDirectory: File
        get() = File(context.cacheDir, WORKING_DIRECTORY).apply { mkdirs() }

    /**
     * Makes a file for the camera to write into and returns the URI to hand it.
     *
     * Through a FileProvider because the camera is a different app, and a
     * `file://` URI across that boundary has thrown since Android 7.
     */
    fun beginCapture(): Uri? = runCatching {
        val photo = File(workingDirectory, "photo-${System.currentTimeMillis()}.jpg")
        pendingPhoto = photo
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photo)
    }.getOrElse {
        error = "Could not prepare the camera."
        null
    }

    /**
     * Called when the camera app comes back.
     *
     * A cancelled photograph is not an error and says nothing; the player
     * changed their mind, which is a normal thing to do while pointing a phone
     * at a music stand.
     */
    fun onPhotoTaken(success: Boolean) {
        val photo = pendingPhoto
        pendingPhoto = null
        if (!success || photo == null) {
            photo?.delete()
            return
        }

        scope.launch {
            busy = true
            error = null

            val into = File(workingDirectory, "page-${System.currentTimeMillis()}.jpg")
            val scanned = PageScanner.scan(context, photo, into)
            photo.delete()

            if (scanned == null) {
                into.delete()
                error = "That photo could not be read. Try taking it again."
            } else {
                pages = pages + scanned
            }
            busy = false
        }
    }

    fun removePage(index: Int) {
        val page = pages.getOrNull(index) ?: return
        page.file.delete()
        pages = pages.filterIndexed { at, _ -> at != index }
    }

    /**
     * Writes the PDF, files it in the library, and clears the working files.
     *
     * The scan goes into the library's managed storage rather than into one of
     * the user's own folders. It is the app's file - nothing else put it there
     * and nothing else will miss it - and a folder in somebody's Drive is not
     * somewhere an app should start writing to uninvited.
     */
    fun save() {
        if (pages.isEmpty() || busy) return
        scope.launch {
            busy = true
            error = null

            val fallback = "Scan ${DATE_FORMAT.format(Date())}"
            val name = uniqueName(PagePdf.fileNameFor(title, fallback))
            val target = File(File(context.filesDir, MANAGED_DIRECTORY), name)

            val written = PagePdf.write(pages.map { it.file }, target)
            if (!written) {
                error = "Could not write the PDF."
                busy = false
                return@launch
            }

            val song = fileInLibrary(target)
            pages.forEach { it.file.delete() }
            pages = emptyList()
            title = ""
            saved = song
            busy = false
        }
    }

    private suspend fun fileInLibrary(target: File): SongRef {
        val source = DocumentSources.managedSource()
        if (library.index.value.sources.none { it.id == source.id }) {
            library.addSource(source)
        }

        val uri = Uri.fromFile(target)
        val song = SongRef(
            id = DocumentSources.stableId(source.id, target.name),
            sourceId = source.id,
            uri = uri.toString(),
            displayName = target.name,
            kind = FileKind.PDF,
            sizeBytes = target.length(),
            modifiedAt = target.lastModified(),
            contentHash = DocumentSources.hashOfFile(context.contentResolver, uri),
        )

        val existing = library.index.value.songsFrom(source.id)
        library.replaceSongsFrom(source.id, existing + song, System.currentTimeMillis())
        return song
    }

    /** Never overwrites: two scans on the same day are two pieces of music. */
    private fun uniqueName(name: String): String {
        val directory = File(context.filesDir, MANAGED_DIRECTORY).apply { mkdirs() }
        if (!File(directory, name).exists()) return name

        val stem = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "pdf")
        var index = 2
        while (File(directory, "$stem ($index).$extension").exists()) index++
        return "$stem ($index).$extension"
    }

    fun consumeSaved() {
        saved = null
    }

    fun dismissError() {
        error = null
    }

    /** Throws the working files away, for a capture that is being abandoned. */
    fun discardAll() {
        pages.forEach { it.file.delete() }
        pages = emptyList()
        pendingPhoto?.delete()
        pendingPhoto = null
        title = ""
        error = null
    }

    private companion object {
        const val WORKING_DIRECTORY = "captures"
        const val MANAGED_DIRECTORY = "managed"
        val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US)
    }
}
