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
    data object Updates : Screen
    data object Capture : Screen

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
}

@Composable
fun rememberNavigator(initial: Screen = Screen.Library): Navigator = remember { Navigator(initial) }
