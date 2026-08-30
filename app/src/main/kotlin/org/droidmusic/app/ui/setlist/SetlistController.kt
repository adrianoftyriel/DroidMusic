package org.droidmusic.app.ui.setlist

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.droidmusic.app.data.DocumentSources
import org.droidmusic.app.data.LibraryRepository
import org.droidmusic.app.data.SettingsRepository
import org.droidmusic.app.data.SetlistBook
import org.droidmusic.app.data.SetlistRepository
import org.droidmusic.library.Setlist
import org.droidmusic.library.SetlistBundle
import org.droidmusic.library.SetlistCodec
import org.droidmusic.library.SetlistEntry
import org.droidmusic.library.SongRef

/**
 * Building set lists, and getting them onto other people's phones.
 *
 * There are two ways off this device and both matter. A file, through the system
 * share sheet, works with anyone by any means - email, a messaging app, a shared
 * Drive folder - and works when the band are not in the same room. A push over a
 * live session is instant and works when they are. Neither replaces the other.
 */
class SetlistController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: SetlistRepository,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val appVersion: String,
) {
    val book: StateFlow<SetlistBook> get() = repository.book

    var importMessage by mutableStateOf<String?>(null)
        private set

    /**
     * Makes a new set list, optionally with its first songs already in it.
     *
     * Creating and then adding would be two writes racing each other through the
     * same store, which on a phone that is about to be locked and put in a
     * pocket is a set list that comes back empty. One write cannot half-happen.
     */
    fun create(name: String, songs: List<SongRef> = emptyList()): Setlist {
        val now = System.currentTimeMillis()
        val setlist = Setlist(
            id = UUID.randomUUID().toString(),
            name = name,
            entries = songs.map(::entryFor),
            createdAt = now,
            updatedAt = now,
        )
        scope.launch { repository.save(setlist) }
        return setlist
    }

    fun delete(id: String) {
        scope.launch { repository.delete(id) }
    }

    fun add(setlist: Setlist, song: SongRef) {
        save(setlist.copy(entries = setlist.entries + entryFor(song)))
    }

    /**
     * A song as it enters a set list.
     *
     * The title and content hash are copied in rather than looked up later, so
     * the entry still says what it is on a device whose library has never seen
     * this chart - which is every device the list gets sent to.
     */
    private fun entryFor(song: SongRef) = SetlistEntry(
        songId = song.id,
        title = song.bestTitle,
        contentHash = song.contentHash,
        artist = song.artist,
    )

    fun move(setlist: Setlist, from: Int, to: Int) = save(setlist.moved(from, to))

    fun remove(setlist: Setlist, index: Int) = save(setlist.removedAt(index))

    /** Records the key a song was actually played in, from the viewer. */
    fun setEntryTranspose(setlist: Setlist, index: Int, semitones: Int, capo: Int) =
        save(setlist.withEntryAt(index) { it.copy(transposeSemitones = semitones, capo = capo) })

    fun save(setlist: Setlist) {
        scope.launch { repository.save(setlist.copy(updatedAt = System.currentTimeMillis())) }
    }

    /**
     * Writes the set list to a shareable file and opens the share sheet.
     *
     * The file goes into the cache directory behind a FileProvider, so the
     * receiving app gets a temporary read grant to one file and nothing else.
     */
    fun export(setlist: Setlist) {
        scope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    val directory = File(context.cacheDir, "exports").apply { mkdirs() }
                    val file = File(directory, SetlistCodec.fileName(setlist))
                    val bundle = SetlistCodec.bundle(
                        setlist = setlist,
                        exportedBy = settings.settings.value.deviceName.ifEmpty { null },
                        producer = "DroidMusic $appVersion",
                        now = System.currentTimeMillis(),
                    )
                    file.writeText(SetlistCodec.encode(bundle))
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    )
                }.getOrNull()
            } ?: run {
                importMessage = "Could not write the set list file."
                return@launch
            }

            val share = Intent(Intent.ACTION_SEND).apply {
                type = SetlistBundle.MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, setlist.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(share, "Send ${setlist.name}")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Reads a set list somebody sent, and says plainly what is missing.
     *
     * Importing a list that refers to charts this device does not have is the
     * normal case, not an error - the songs are usually in a shared folder the
     * player has not added yet. So the list is saved either way and the missing
     * titles are named, which is enough for them to go and find them.
     */
    fun import(uri: Uri) {
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                DocumentSources.readText(context.contentResolver, uri)
            }
            if (text == null) {
                importMessage = "Could not read that file."
                return@launch
            }

            val bundle = SetlistCodec.decode(text)
            if (bundle == null) {
                importMessage = "That does not look like a DroidMusic set list."
                return@launch
            }
            if (!SetlistCodec.canRead(bundle)) {
                importMessage = "That set list was made by a newer version of DroidMusic. " +
                    "Update the app to open it without losing anything."
                return@launch
            }

            adopt(bundle.setlist, bundle.exportedBy)
        }
    }

    /**
     * Takes a set list from elsewhere - a file or a leader's push - and rewrites
     * its song ids to point at this device's own copies.
     */
    fun adopt(incoming: Setlist, from: String?) {
        scope.launch {
            val resolution = SetlistCodec.resolve(incoming, library.index.value)
            val localised = incoming.copy(
                id = UUID.randomUUID().toString(),
                entries = resolution.resolved.map { resolved ->
                    resolved.localSongId?.let { resolved.entry.copy(songId = it) } ?: resolved.entry
                },
                updatedAt = System.currentTimeMillis(),
            )
            repository.save(localised)

            importMessage = when {
                resolution.allPresent ->
                    "Added \"${incoming.name}\"" + (from?.let { " from $it" } ?: "") +
                        ". All ${incoming.size} charts are here."
                else ->
                    "Added \"${incoming.name}\", but ${resolution.missing.size} of " +
                        "${incoming.size} charts are not in your library: " +
                        resolution.missing.take(5).joinToString(", ") { it.entry.title } +
                        if (resolution.missing.size > 5) ", and more." else "."
            }
        }
    }

    fun clearMessage() {
        importMessage = null
    }
}
