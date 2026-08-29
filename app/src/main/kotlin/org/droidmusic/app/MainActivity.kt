package org.droidmusic.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.droidmusic.app.input.FootSwitchReader
import org.droidmusic.app.input.PageAction
import org.droidmusic.app.ui.DroidMusicRoot

/**
 * The single activity.
 *
 * It exists mostly to be the place key events arrive, which is the only part of
 * foot-switch support that cannot be done in Compose: a Bluetooth or USB pedal
 * sends its key events to the window, and the window belongs to the activity.
 */
class MainActivity : ComponentActivity() {

    private val footSwitch = FootSwitchReader()

    /**
     * Foot-switch actions on their way to whichever screen is listening. A
     * SharedFlow rather than a callback because the viewer comes and goes and a
     * pedal press that arrives while nothing is listening should be dropped, not
     * queued up to fire later.
     */
    private val pedalActions = MutableSharedFlow<PageAction>(extraBufferCapacity = 8)

    /**
     * Every key code the window sees, mapped or not.
     *
     * The foot-switch setup screen listens to this rather than to [pedalActions],
     * because the pedal it is being used to configure is by definition one whose
     * keys produce no action yet.
     */
    private val rawKeys = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    private val app: DroidMusicApp get() = application as DroidMusicApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        lifecycleScope.launch {
            app.settings.settings.collect { settings ->
                footSwitch.updateMap(settings.footSwitch)
                keepScreenOn(settings.viewer.keepScreenOn)
            }
        }

        setContent {
            val settings by app.settings.settings.collectAsState()
            DroidMusicRoot(
                app = app,
                settings = settings,
                pedalActions = pedalActions,
                rawKeys = rawKeys,
                onImmersive = ::applyImmersiveMode,
            )
        }
    }

    /**
     * A phone that sleeps mid-song is worse than useless, and a player cannot
     * reach it with both hands full.
     */
    private fun keepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Hides the system bars while a chart is open, so the page gets the screen.
     *
     * Not called `setImmersive`: `Activity` already has a member of that name -
     * the setter for its own `immersive` property - and shadowing it would mean
     * this method could be called when the platform meant the other one.
     */
    private fun applyImmersiveMode(immersive: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (immersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Where pedals arrive.
     *
     * Returning true consumes the event. The distinction the reader draws
     * between "not mapped" (null) and "mapped but suppressed" (NONE) matters
     * here: an unmapped key has to go back to the system so the volume rocker
     * and the back button still work, while a debounced repeat has to be
     * swallowed so it does not also scroll something.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount == 0) rawKeys.tryEmit(keyCode)
        val action = footSwitch.onKeyDown(keyCode, event.repeatCount, event.eventTime)
            ?: return super.onKeyDown(keyCode, event)
        if (action != PageAction.NONE) pedalActions.tryEmit(action)
        return true
    }

    /**
     * Pedals send key-up too. Consumed for anything mapped, so the system does
     * not act on the release of a key whose press was handled here.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val mapped = footSwitch.currentMap().actionFor(keyCode) != PageAction.NONE
        return if (mapped) true else super.onKeyUp(keyCode, event)
    }

    /**
     * Some pedals emit long-press as a distinct event; ignoring it stops a held
     * switch from being treated as a second press.
     */
    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        val mapped = footSwitch.currentMap().actionFor(keyCode) != PageAction.NONE
        return if (mapped) true else super.onKeyLongPress(keyCode, event)
    }
}
