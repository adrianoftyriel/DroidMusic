package org.droidmusic.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/**
 * Dark by default, and not as a style choice.
 *
 * This app is read on a stand in a dark room, next to people whose night vision
 * matters, by someone who cannot look away for long. A bright screen at a gig is
 * a light source pointed at the audience. The chart surface itself is switchable
 * - some players want black on white for the page and dark everywhere else, and
 * [org.droidmusic.app.ui.viewer.ViewerPreferences.darkChart] is that switch.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF7AA2F7),
    onPrimary = Color(0xFF06080F),
    primaryContainer = Color(0xFF24304C),
    onPrimaryContainer = Color(0xFFD6E2FF),
    secondary = Color(0xFF9ECE6A),
    onSecondary = Color(0xFF0B1005),
    background = Color(0xFF101014),
    onBackground = Color(0xFFE6E6EA),
    surface = Color(0xFF16161C),
    onSurface = Color(0xFFE6E6EA),
    surfaceVariant = Color(0xFF22222B),
    onSurfaceVariant = Color(0xFFB4B4C0),
    outline = Color(0xFF41414F),
    error = Color(0xFFF7768E),
    onError = Color(0xFF1A0508),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2C4B9B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF00174B),
    secondary = Color(0xFF44662A),
    background = Color(0xFFFBFBFD),
    onBackground = Color(0xFF1A1A20),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A20),
    surfaceVariant = Color(0xFFE3E3EA),
    onSurfaceVariant = Color(0xFF45454F),
    outline = Color(0xFF767680),
    error = Color(0xFFB3261E),
)

/**
 * Charts are drawn monospaced, always.
 *
 * Not for looks. The chords-over-lyrics layout puts a chord above the exact
 * character it belongs to, and that alignment is only true if every character is
 * the same width. In a proportional font the chords drift off their syllables,
 * a little more with every word, which is precisely the information the chart
 * exists to convey.
 */
val ChartTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 16.sp,
)

@Composable
fun DroidMusicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
