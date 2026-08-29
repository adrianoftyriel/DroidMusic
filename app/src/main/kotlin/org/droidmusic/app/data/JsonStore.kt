package org.droidmusic.app.data

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * A small file-backed store for one serializable value, published as a
 * [StateFlow].
 *
 * A database would be the reflexive choice and would be the wrong one here. The
 * three things being persisted - settings, set lists, and an index of at most a
 * few thousand files - are read whole, written whole, and never queried. What a
 * database would add is a schema to migrate; what it would not add is anything
 * this app needs.
 *
 * Writes go through a temporary file and a rename, because the moment a phone is
 * most likely to be killed by the system is the moment its screen has been on
 * for three hours in a hot pub, and a half-written set list on the night of a
 * gig is not a recoverable situation.
 */
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: T,
    private val scope: CoroutineScope,
    private val json: Json = DEFAULT_JSON,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(default)
    val state: StateFlow<T> = _state.asStateFlow()

    /**
     * Reads from disk. A file that will not parse is moved aside rather than
     * deleted: it is somebody's set list, and a corrupt one they can send us
     * beats one that silently became an empty list.
     */
    suspend fun load(): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val loaded = runCatching {
                if (!file.exists()) default else json.decodeFromString(serializer, file.readText())
            }.getOrElse {
                if (file.exists()) {
                    runCatching { file.renameTo(File(file.parentFile, file.name + ".corrupt")) }
                }
                default
            }
            _state.value = loaded
            loaded
        }
    }

    suspend fun update(transform: (T) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = transform(_state.value)
            _state.value = next
            writeLocked(next)
            next
        }
    }

    suspend fun set(value: T): T = update { value }

    /** Fire-and-forget update, for UI callbacks that must not block a page turn. */
    fun updateAsync(transform: (T) -> T) {
        scope.launch { update(transform) }
    }

    private fun writeLocked(value: T) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.encodeToString(serializer, value))
            if (!temp.renameTo(file)) {
                // A rename across the same directory should not fail, but if it
                // does, a direct write is better than losing the change.
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    companion object {
        val DEFAULT_JSON = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
