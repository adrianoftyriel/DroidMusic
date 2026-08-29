package org.droidmusic.app.input

import android.view.KeyEvent
import kotlinx.serialization.Serializable

/** What a page-turner input is being asked to do. */
enum class PageAction {
    NEXT_PAGE,
    PREVIOUS_PAGE,
    NEXT_SONG,
    PREVIOUS_SONG,
    TOGGLE_CONTROLS,
    NONE,
}

/**
 * The mapping from key codes to actions.
 *
 * Both Bluetooth and USB foot switches present themselves to Android as HID
 * keyboards, which is the single fact that makes this feature tractable: there
 * is no pedal SDK to integrate and no USB protocol to implement, because the
 * platform has already turned the pedal into key events by the time the app sees
 * anything. A USB pedal and a Bluetooth pedal are the same code path.
 *
 * What differs between pedals is only *which* keys they send, and there is no
 * standard. The common defaults below cover most of the pedals sold for this
 * purpose; anything else is handled by the learn screen, which watches for a raw
 * key code and binds whatever arrives.
 */
@Serializable
data class FootSwitchMap(
    val next: Set<Int> = DEFAULT_NEXT,
    val previous: Set<Int> = DEFAULT_PREVIOUS,
    val nextSong: Set<Int> = emptySet(),
    val previousSong: Set<Int> = emptySet(),
    /**
     * Volume keys are not bound by default. Some pedals do send them, but so
     * does the volume rocker, and silently stealing it from a player who wants
     * to turn their backing track down is not a good trade.
     */
    val allowVolumeKeys: Boolean = false,
) {
    fun actionFor(keyCode: Int): PageAction = when {
        keyCode in next -> PageAction.NEXT_PAGE
        keyCode in previous -> PageAction.PREVIOUS_PAGE
        keyCode in nextSong -> PageAction.NEXT_SONG
        keyCode in previousSong -> PageAction.PREVIOUS_SONG
        !allowVolumeKeys && isVolumeKey(keyCode) -> PageAction.NONE
        allowVolumeKeys && keyCode == KeyEvent.KEYCODE_VOLUME_UP -> PageAction.NEXT_PAGE
        allowVolumeKeys && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN -> PageAction.PREVIOUS_PAGE
        else -> PageAction.NONE
    }

    /** Binds a learned key code, removing it from wherever else it was bound. */
    fun bind(keyCode: Int, action: PageAction): FootSwitchMap {
        val cleared = copy(
            next = next - keyCode,
            previous = previous - keyCode,
            nextSong = nextSong - keyCode,
            previousSong = previousSong - keyCode,
        )
        return when (action) {
            PageAction.NEXT_PAGE -> cleared.copy(next = cleared.next + keyCode)
            PageAction.PREVIOUS_PAGE -> cleared.copy(previous = cleared.previous + keyCode)
            PageAction.NEXT_SONG -> cleared.copy(nextSong = cleared.nextSong + keyCode)
            PageAction.PREVIOUS_SONG -> cleared.copy(previousSong = cleared.previousSong + keyCode)
            else -> cleared
        }
    }

    companion object {
        fun isVolumeKey(keyCode: Int): Boolean =
            keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

        /**
         * What the pedals people actually own send when the right switch is
         * pressed. AirTurn and PageFlip default to page down / right arrow;
         * others send space, enter or a letter.
         */
        val DEFAULT_NEXT: Set<Int> = setOf(
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_N,
        )

        val DEFAULT_PREVIOUS: Set<Int> = setOf(
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_B,
            KeyEvent.KEYCODE_P,
        )

        /** A readable name for a key code, for the settings screen. */
        fun describe(keyCode: Int): String = when (keyCode) {
            KeyEvent.KEYCODE_PAGE_DOWN -> "Page Down"
            KeyEvent.KEYCODE_PAGE_UP -> "Page Up"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
            KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
            KeyEvent.KEYCODE_DPAD_UP -> "Up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
            KeyEvent.KEYCODE_DPAD_CENTER -> "Centre"
            KeyEvent.KEYCODE_SPACE -> "Space"
            KeyEvent.KEYCODE_ENTER -> "Enter"
            KeyEvent.KEYCODE_MEDIA_NEXT -> "Media next"
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Media previous"
            KeyEvent.KEYCODE_VOLUME_UP -> "Volume up"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "Volume down"
            else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace('_', ' ')
        }
    }
}

/**
 * Filters the stream of key events down to page turns.
 *
 * Two problems this solves that a bare `onKeyDown` does not.
 *
 * **Auto-repeat.** A pedal held down, or one with a sticky switch, produces a
 * repeating key event at the system's typematic rate. Turning thirty pages
 * because somebody rested their foot on the pedal is the single worst thing this
 * feature could do, so repeats are dropped outright.
 *
 * **Double-triggering.** Cheap pedals bounce, sending two presses a few
 * milliseconds apart. A short guard window absorbs the second one. It is set at
 * 120 ms, which is below the fastest anybody deliberately turns two pages and
 * well above a contact bounce.
 */
class FootSwitchReader(
    private var map: FootSwitchMap = FootSwitchMap(),
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) {
    private var lastKeyCode = -1
    private var lastEventAt = 0L

    fun updateMap(newMap: FootSwitchMap) {
        map = newMap
    }

    fun currentMap(): FootSwitchMap = map

    /**
     * Returns the action to take, or null if the event should be passed on to
     * the system - which matters for the volume keys and the back button.
     */
    fun onKeyDown(keyCode: Int, repeatCount: Int, eventTimeMs: Long): PageAction? {
        if (repeatCount > 0) return PageAction.NONE
        val action = map.actionFor(keyCode)
        if (action == PageAction.NONE) return null

        if (keyCode == lastKeyCode && eventTimeMs - lastEventAt < debounceMs) {
            return PageAction.NONE
        }
        lastKeyCode = keyCode
        lastEventAt = eventTimeMs
        return action
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 120L
    }
}
