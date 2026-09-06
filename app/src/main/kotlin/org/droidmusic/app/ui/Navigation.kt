package org.droidmusic.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * Where the app can be.
 *
 * Screens carry their arguments as objects rather than as strings in a route.
 * The alternative - a URI-style navigation graph - would mean encoding song ids
 * and set list positions into a path and parsing them back out, and the only
 * thing that buys in an app with no deep links into individual charts is a class
 * of bugs where a title with a slash in it breaks navigation.
 */
sealed interface Screen {
    /**
     * The four things the app does, and nothing else.
     *
     * A root that is one of the four - the library, as it used to be - makes
     * that one the app and the other three a detour, which is the wrong shape
     * the moment somebody opens DroidMusic to join a session rather than to
     * find a chart. Which is most rehearsals.
     */
    data object MainMenu : Screen

    data object Library : Screen
    data object Setlists : Screen
    data class SetlistDetail(val setlistId: String) : Screen
    data object Session : Screen

    /**
     * The pre-set check. It carries no arguments because the set list being
     * checked is not always one of this device's own - a follower checks the
     * running order the leader sent, which has no local id - so it lives on the
     * controller rather than in the route.
     */
    data object Backstage : Screen
    data object Settings : Screen
    data object FootSwitchSetup : Screen

    /** How the page is turned and how it is laid out: everything under the hand. */
    data object Controls : Screen

    data object Updates : Screen

    /** The log of what the app just did, for sending to somebody who can read it. */
    data object Diagnostics : Screen
    data object Capture : Screen

    /**
     * The chart editor, on an existing song or on a new one.
     *
     * A null [songId] is a chart that does not exist yet; [seedText] is what it
     * starts life containing, which is empty for a blank song and the pasted or
     * downloaded text for an import. Carrying the seed here rather than writing
     * a file first means an import that is abandoned leaves nothing behind.
     */
    data class SongEditor(
        val songId: String? = null,
        val seedText: String = "",
        val seedTitle: String = "",
    ) : Screen

    /**
     * The viewer, opened either on a single song or on a position in a set list.
     * The set list case carries the index so "next song" has somewhere to go.
     */
    data class Viewer(
        val songId: String,
        val setlistId: String? = null,
        val setlistIndex: Int = -1,
    ) : Screen
}

/** A back stack that is a list, because that is all a back stack is. */
class Navigator(initial: Screen) {
    val stack: SnapshotStateList<Screen> = mutableStateListOf(initial)

    val current: Screen get() = stack.last()

    val canGoBack: Boolean get() = stack.size > 1

    fun go(screen: Screen) {
        stack.add(screen)
    }

    /** Replaces the top of the stack, for a move that should not deepen it. */
    fun replace(screen: Screen) {
        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
        stack.add(screen)
    }

    fun back(): Boolean {
        if (!canGoBack) return false
        stack.removeAt(stack.size - 1)
        return true
    }

    fun backToRoot() {
        while (stack.size > 1) stack.removeAt(stack.size - 1)
    }

    /** Closes the chart on top of the stack, per [closingViewer]. */
    fun closeViewer() {
        val next = closingViewer(stack.toList())
        if (next == stack.toList()) return
        stack.clear()
        stack.addAll(next)
    }
}

/**
 * The back stack after closing the chart on top of [stack].
 *
 * **Closing a chart is not going home.** It used to be: the way out of the
 * viewer emptied the stack, which put a player who had opened one song out of
 * twenty back at the front door of the app, with the library or the running
 * order they were working through two taps away. The screen a chart was opened
 * from is the screen closing it should return to, and that screen is sitting
 * directly underneath it on the stack.
 *
 * **Except when it is not.** A chart opened by the leader of a session arrives
 * by replacing whatever was on top, so a follower can end up with nothing under
 * the viewer but the menu. That is the case the fallback is for, and what it
 * falls back to is where the chart belongs rather than where the app starts: the
 * set list the viewer is working through, or the library. The set list gets the
 * list of set lists put underneath it, so backing out of it goes somewhere
 * sensible rather than straight to the menu.
 *
 * Written as a function over the stack, and not as a method poking at it, so the
 * rule has a test - navigation that lands one screen off is the kind of thing
 * that is only noticed at a rehearsal.
 */
fun closingViewer(stack: List<Screen>): List<Screen> {
    val viewer = stack.lastOrNull() as? Screen.Viewer ?: return stack

    // Several viewers can stack up when charts are opened from one another;
    // closing the chart means leaving all of them, not peeling off one.
    val remaining = stack.dropLastWhile { it is Screen.Viewer }
    val opener = remaining.lastOrNull()
    if (opener != null && opener != Screen.MainMenu) return remaining

    return remaining + when (val setlistId = viewer.setlistId) {
        null -> listOf(Screen.Library)
        else -> listOf(Screen.Setlists, Screen.SetlistDetail(setlistId))
    }
}

@Composable
fun rememberNavigator(initial: Screen = Screen.MainMenu): Navigator = remember { Navigator(initial) }
