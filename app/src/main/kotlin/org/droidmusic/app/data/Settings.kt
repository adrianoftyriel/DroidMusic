package org.droidmusic.app.data

import kotlinx.serialization.Serializable
import org.droidmusic.app.input.FootSwitchMap
import org.droidmusic.app.ui.viewer.ViewerPreferences

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
)
