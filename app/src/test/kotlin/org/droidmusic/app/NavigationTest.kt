package org.droidmusic.app

import org.droidmusic.app.ui.Screen
import org.droidmusic.app.ui.closingViewer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Where closing a chart goes.
 *
 * The cases are the ways a chart gets opened: found in the library, started from
 * a running order, and pushed onto a follower's screen by the leader of a
 * session - the last of which leaves nothing under the viewer at all. Landing on
 * the front door of the app from any of them is two taps back to what the player
 * was doing, and it is only ever noticed with an instrument in the other hand.
 */
class NavigationTest {

    private val viewer = Screen.Viewer("song-1")

    @Test
    fun `a chart opened from the library closes back to the library`() {
        val stack = listOf(Screen.MainMenu, Screen.Library, viewer)
        assertEquals(listOf(Screen.MainMenu, Screen.Library), closingViewer(stack))
    }

    @Test
    fun `a chart opened from a running order closes back to that running order`() {
        val detail = Screen.SetlistDetail("set-9")
        val stack = listOf(
            Screen.MainMenu,
            Screen.Setlists,
            detail,
            Screen.Viewer("song-1", setlistId = "set-9", setlistIndex = 3),
        )
        assertEquals(listOf(Screen.MainMenu, Screen.Setlists, detail), closingViewer(stack))
    }

    /**
     * The follower's case: the leader's chart replaced whatever was on top, so
     * the only thing under the viewer is the menu.
     */
    @Test
    fun `a chart with nothing under it closes to the library`() {
        val stack = listOf(Screen.MainMenu, viewer)
        assertEquals(listOf(Screen.MainMenu, Screen.Library), closingViewer(stack))
    }

    @Test
    fun `a chart in a set list with nothing under it closes to that set list`() {
        val stack = listOf(Screen.MainMenu, Screen.Viewer("song-1", setlistId = "set-9"))

        assertEquals(
            listOf(Screen.MainMenu, Screen.Setlists, Screen.SetlistDetail("set-9")),
            closingViewer(stack),
        )
    }

    @Test
    fun `closing leaves every stacked chart, not just the top one`() {
        val stack = listOf(
            Screen.MainMenu,
            Screen.Library,
            Screen.Viewer("song-1"),
            Screen.Viewer("song-2"),
        )
        assertEquals(listOf(Screen.MainMenu, Screen.Library), closingViewer(stack))
    }

    @Test
    fun `a chart opened from the pre-set check closes back to the check`() {
        val stack = listOf(Screen.MainMenu, Screen.Session, Screen.Backstage, viewer)
        assertEquals(
            listOf(Screen.MainMenu, Screen.Session, Screen.Backstage),
            closingViewer(stack),
        )
    }

    @Test
    fun `a stack that is not showing a chart is left alone`() {
        val stack = listOf(Screen.MainMenu, Screen.Library)
        assertSame(stack, closingViewer(stack))
    }
}
