package org.droidmusic.app.data

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.droidmusic.library.LibraryIndex
import org.droidmusic.library.Setlist
import org.droidmusic.library.SongRef
import org.droidmusic.library.SourceRef

class SettingsRepository(directory: File, scope: CoroutineScope) {
    private val store = JsonStore(
        file = File(directory, "settings.json"),
        serializer = AppSettings.serializer(),
        default = AppSettings(),
        scope = scope,
    )

    val settings: StateFlow<AppSettings> get() = store.state

    suspend fun load(): AppSettings = store.load()

    suspend fun update(transform: (AppSettings) -> AppSettings) = store.update(transform)

    fun updateAsync(transform: (AppSettings) -> AppSettings) = store.updateAsync(transform)
}

class LibraryRepository(directory: File, scope: CoroutineScope) {
    private val store = JsonStore(
        file = File(directory, "library.json"),
        serializer = LibraryIndex.serializer(),
        default = LibraryIndex(),
        scope = scope,
    )

    val index: StateFlow<LibraryIndex> get() = store.state

    suspend fun load(): LibraryIndex = store.load()

    suspend fun addSource(source: SourceRef) = store.update { current ->
        current.copy(sources = current.sources.filterNot { it.id == source.id } + source)
    }

    /**
     * Removes a source and everything indexed from it. The files themselves are
     * left alone - they belong to the user's Drive or their Downloads folder,
     * and forgetting about them is not the same as deleting them.
     */
    suspend fun removeSource(sourceId: String) = store.update { current ->
        current.copy(
            sources = current.sources.filterNot { it.id == sourceId },
            songs = current.songs.filterNot { it.sourceId == sourceId },
        )
    }

    /**
     * Replaces everything known about one source after a rescan.
     *
     * User-set fields are carried across by uri, because a rescan must not
     * silently discard the key somebody corrected by hand or the songs they
     * starred.
     */
    suspend fun replaceSongsFrom(sourceId: String, songs: List<SongRef>, now: Long) =
        store.update { current ->
            val previous = current.songsFrom(sourceId).associateBy { it.uri }
            val merged = songs.map { fresh ->
                val old = previous[fresh.uri] ?: return@map fresh
                fresh.copy(
                    userKeyText = old.userKeyText,
                    favourite = old.favourite,
                    tags = old.tags,
                )
            }
            current.copy(
                songs = current.songs.filterNot { it.sourceId == sourceId } + merged,
                updatedAt = now,
            )
        }

    suspend fun updateSong(id: String, transform: (SongRef) -> SongRef) = store.update { current ->
        current.copy(songs = current.songs.map { if (it.id == id) transform(it) else it })
    }
}

@kotlinx.serialization.Serializable
data class SetlistBook(val setlists: List<Setlist> = emptyList())

class SetlistRepository(directory: File, scope: CoroutineScope) {
    private val store = JsonStore(
        file = File(directory, "setlists/setlists.json"),
        serializer = SetlistBook.serializer(),
        default = SetlistBook(),
        scope = scope,
    )

    val book: StateFlow<SetlistBook> get() = store.state

    suspend fun load(): SetlistBook = store.load()

    suspend fun save(setlist: Setlist) = store.update { current ->
        current.copy(
            setlists = current.setlists.filterNot { it.id == setlist.id } + setlist,
        )
    }

    suspend fun delete(id: String) = store.update { current ->
        current.copy(setlists = current.setlists.filterNot { it.id == id })
    }

    fun find(id: String): Setlist? = store.state.value.setlists.firstOrNull { it.id == id }
}
