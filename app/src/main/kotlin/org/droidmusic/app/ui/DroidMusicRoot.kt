package org.droidmusic.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.SharedFlow
import org.droidmusic.app.DroidMusicApp
import org.droidmusic.app.data.AppSettings
import org.droidmusic.app.input.PageAction
import org.droidmusic.app.ui.library.LibraryController
import org.droidmusic.app.ui.library.LibraryScreen
import org.droidmusic.app.ui.session.SessionCoordinator
import org.droidmusic.app.ui.session.SessionRole
import org.droidmusic.app.ui.session.SessionScreen
import org.droidmusic.app.ui.setlist.SetlistController
import org.droidmusic.app.ui.setlist.SetlistDetailScreen
import org.droidmusic.app.ui.setlist.SetlistsScreen
import org.droidmusic.app.ui.settings.FootSwitchSetupScreen
import org.droidmusic.app.ui.settings.SettingsScreen
import org.droidmusic.app.ui.viewer.ViewerControls
import org.droidmusic.app.ui.viewer.ViewerController
import org.droidmusic.app.ui.viewer.ViewerSurface

/**
 * The whole app, assembled.
 *
 * Controllers are remembered here rather than created per screen, so a chart
 * stays open while the player checks the set list and comes back. The viewer in
 * particular holds an open file descriptor, and reopening it on every visit
 * would put a visible stall in the middle of a set.
 */
@Composable
fun DroidMusicRoot(
    app: DroidMusicApp,
    settings: AppSettings,
    pedalActions: SharedFlow<PageAction>,
    rawKeys: SharedFlow<Int>,
    incomingFiles: SharedFlow<android.net.Uri>,
    onImmersive: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navigator = rememberNavigator()

    val sessionCoordinator = remember {
        SessionCoordinator(
            scope = app.appScope,
            discovery = app.discovery,
            settings = app.settings,
            appVersion = DroidMusicApp.VERSION,
        )
    }

    val libraryController = remember {
        LibraryController(context, app.appScope, app.library, app.settings)
    }

    val setlistController = remember {
        SetlistController(
            context = context,
            scope = app.appScope,
            repository = app.setlists,
            library = app.library,
            settings = app.settings,
            appVersion = DroidMusicApp.VERSION,
        )
    }

    val viewerController = remember {
        ViewerController(context = context, scope = app.appScope, library = app.library)
    }

    var controlsVisible by remember { mutableStateOf(false) }

    // The viewer reports every move; the coordinator decides whether anyone else
    // needs to hear about it. Wiring it here keeps the viewer ignorant of
    // sessions entirely.
    viewerController.positionReporter = { page, userInitiated ->
        val song = viewerController.song
        sessionCoordinator.onLocalPosition(
            songId = song?.id,
            songTitle = song?.bestTitle,
            contentHash = song?.contentHash,
            page = page,
            setlistIndex = viewerController.setlistIndex,
            transposeSemitones = viewerController.transposeSemitones,
            capo = viewerController.capo,
            userInitiated = userInitiated,
        )
    }

    // Collected here, not read inside a helper, so every change to any of them
    // recomposes the status strip. See SessionCoordinator.statusLine.
    val sessionRole by sessionCoordinator.role.collectAsState()
    val leaderState by sessionCoordinator.leaderState.collectAsState()
    val followerState by sessionCoordinator.followerState.collectAsState()
    val sessionLabel by sessionCoordinator.sessionLabel.collectAsState()
    val sessionStatus = SessionCoordinator.statusLine(
        role = sessionRole,
        leader = leaderState,
        follower = followerState,
        sessionLabel = sessionLabel,
    )

    val currentScreen = navigator.current
    val inViewer = currentScreen is Screen.Viewer

    LaunchedEffect(inViewer, controlsVisible) {
        onImmersive(inViewer && !controlsVisible)
    }

    // Foot switches only ever mean something in the viewer, and only when the
    // controls are not covering it.
    LaunchedEffect(inViewer, controlsVisible) {
        if (!inViewer) return@LaunchedEffect
        pedalActions.collect { action ->
            if (controlsVisible) return@collect
            when (action) {
                PageAction.NEXT_PAGE ->
                    viewerController.turn(true, settings.viewer.unicodeAccidentals)
                PageAction.PREVIOUS_PAGE ->
                    viewerController.turn(false, settings.viewer.unicodeAccidentals)
                PageAction.NEXT_SONG ->
                    viewerController.nextSong(settings.viewer.unicodeAccidentals)
                PageAction.PREVIOUS_SONG ->
                    viewerController.previousSong(settings.viewer.unicodeAccidentals)
                PageAction.TOGGLE_CONTROLS -> controlsVisible = true
                PageAction.NONE -> Unit
            }
        }
    }

    // The band leader moved. Follow, unless the state machine already decided we
    // should not - it only publishes positions that ought to be applied.
    val remotePosition by sessionCoordinator.remotePosition.collectAsState()
    LaunchedEffect(remotePosition) {
        val position = remotePosition ?: return@LaunchedEffect
        val songId = position.songId
        if (songId != null && songId != viewerController.song?.id) {
            navigator.replace(Screen.Viewer(songId, setlistIndex = position.setlistIndex))
            viewerController.open(songId, null, position.setlistIndex, settings.viewer.unicodeAccidentals)
        }
        viewerController.applyRemote(
            page = position.page,
            transposeSemitones = position.transposeSemitones,
            capo = position.capo,
            unicodeAccidentals = settings.viewer.unicodeAccidentals,
        )
    }

    // A set list pushed by the leader is adopted straight away; the player is on
    // stage and does not want a dialog.
    val pushed by sessionCoordinator.pushedSetlist.collectAsState()
    LaunchedEffect(pushed) {
        val setlist = pushed ?: return@LaunchedEffect
        setlistController.adopt(setlist, sessionCoordinator.sessionLabel.value)
        sessionCoordinator.consumePushedSetlist()
    }

    // A file arrived from outside the app - an attachment, a share, a file
    // manager. A set list is imported; anything else is added to the library,
    // because the alternative is telling somebody who just tapped a chart that
    // the app cannot open it when it plainly can.
    LaunchedEffect(Unit) {
        incomingFiles.collect { uri ->
            val name = uri.toString().lowercase()
            if (name.endsWith(".dmset") || name.endsWith(".json") ||
                context.contentResolver.getType(uri) == "application/json"
            ) {
                setlistController.import(uri)
                navigator.go(Screen.Setlists)
            } else {
                libraryController.addFiles(listOf(uri))
            }
        }
    }

    BackHandler(enabled = navigator.canGoBack || controlsVisible) {
        if (controlsVisible) controlsVisible = false else navigator.back()
    }

    DroidMusicTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (val screen = currentScreen) {
                Screen.Library -> LibraryScreen(
                    controller = libraryController,
                    onOpenSong = { song ->
                        viewerController.open(song.id, null, -1, settings.viewer.unicodeAccidentals)
                        navigator.go(Screen.Viewer(song.id))
                    },
                    onOpenSetlists = { navigator.go(Screen.Setlists) },
                    onOpenSession = { navigator.go(Screen.Session) },
                    onOpenSettings = { navigator.go(Screen.Settings) },
                )

                Screen.Setlists -> SetlistsScreen(
                    controller = setlistController,
                    onBack = { navigator.back() },
                    onOpen = { navigator.go(Screen.SetlistDetail(it.id)) },
                )

                is Screen.SetlistDetail -> {
                    val book by setlistController.book.collectAsState()
                    val setlist = book.setlists.firstOrNull { it.id == screen.setlistId }
                    if (setlist == null) {
                        LaunchedEffect(Unit) { navigator.back() }
                    } else {
                        val index by app.library.index.collectAsState()
                        SetlistDetailScreen(
                            setlist = setlist,
                            controller = setlistController,
                            songFor = { id -> index.findById(id) },
                            onBack = { navigator.back() },
                            onPlay = { position ->
                                val entry = setlist.entries.getOrNull(position) ?: return@SetlistDetailScreen
                                viewerController.open(
                                    entry.songId,
                                    setlist,
                                    position,
                                    settings.viewer.unicodeAccidentals,
                                )
                                // The leader shares the running order as soon as
                                // they start it, so nobody has to be sent a file
                                // in the ninety seconds before the first song.
                                if (sessionRole == SessionRole.LEADER) {
                                    sessionCoordinator.pushSetlist(setlist)
                                }
                                navigator.go(Screen.Viewer(entry.songId, setlist.id, position))
                            },
                            onAddSongs = { navigator.go(Screen.Library) },
                        )
                    }
                }

                Screen.Session -> SessionScreen(
                    coordinator = sessionCoordinator,
                    deviceName = settings.deviceName.ifEmpty { "This device" },
                    onBack = { navigator.back() },
                )

                Screen.Settings -> SettingsScreen(
                    settings = settings,
                    onChange = { transform -> app.settings.updateAsync(transform) },
                    onOpenFootSwitchSetup = { navigator.go(Screen.FootSwitchSetup) },
                    onBack = { navigator.back() },
                    versionName = DroidMusicApp.VERSION,
                )

                Screen.FootSwitchSetup -> FootSwitchSetupScreen(
                    settings = settings,
                    pedalEvents = pedalActions,
                    rawKeys = rawKeys,
                    onChange = { transform -> app.settings.updateAsync(transform) },
                    onBack = { navigator.back() },
                )

                is Screen.Viewer -> Box(Modifier.fillMaxSize()) {
                    ViewerSurface(
                        controller = viewerController,
                        preferences = settings.viewer,
                        onToggleControls = { controlsVisible = !controlsVisible },
                    )

                    AnimatedVisibility(
                        visible = controlsVisible,
                        modifier = Modifier.align(Alignment.TopCenter),
                    ) {
                        ViewerControls(
                            controller = viewerController,
                            sessionStatus = sessionStatus,
                            canRejoin = followerState?.canRejoin == true,
                            onRejoin = { sessionCoordinator.rejoin() },
                            onOpenSetlist = {
                                controlsVisible = false
                                navigator.go(Screen.Setlists)
                            },
                            onOpenSession = {
                                controlsVisible = false
                                navigator.go(Screen.Session)
                            },
                            onOpenSettings = {
                                controlsVisible = false
                                navigator.go(Screen.Settings)
                            },
                            onClose = { controlsVisible = false },
                            onBack = {
                                controlsVisible = false
                                navigator.backToRoot()
                            },
                            unicodeAccidentals = settings.viewer.unicodeAccidentals,
                        )
                    }

                    // A permanent, quiet reminder of who is driving. Always
                    // visible, because a player who cannot tell whether their own
                    // page turns will stick has no way to trust the app.
                    if (sessionStatus != null && !controlsVisible) {
                        org.droidmusic.app.ui.viewer.ViewerStatusStrip(
                            text = sessionStatus,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
    }
}
