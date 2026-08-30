package org.droidmusic.app.data

import kotlinx.serialization.Serializable
import org.droidmusic.app.input.FootSwitchMap
import org.droidmusic.app.ui.viewer.ViewerPreferences
import org.droidmusic.update.UpdateChannel

@Serializable
data class AppSettings(
    val viewer: ViewerPreferences = ViewerPreferences(),
    val footSwitch: FootSwitchMap = FootSwitchMap(),
    /** What this device calls itself in a session. Defaults to the model name. */
    val deviceName: String = "",
    /** Stable per-install id, so a reconnecting device is recognised as itself. */
    val deviceId: String = "",
    val lastSessionName: String = "",
    val autoJoinLastSession: Boolean = false,
    /** Index the contents of text charts on import, for search and key detection. */
    val indexChartContents: Boolean = true,
    /**
     * Which builds the in-app updater will offer.
     *
     * Pre-releases by default, because at the moment they are the only thing
     * published: every push to `dev` cuts one and there has never been a full
     * release. Defaulting to releases-only would ship an update feature that
     * correctly and permanently reports that there is nothing to install.
     */
    val updateChannel: UpdateChannel = UpdateChannel.PRERELEASE,
)
