package org.droidmusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.droidmusic.app.ui.common.Pill

/**
 * Where the app starts.
 *
 * Four rows, one per thing the app does, each with a line saying what is behind
 * it. Not a bottom bar or a drawer: those are for moving between places you are
 * already in, and the first question here is which place to be in at all. It is
 * also the screen most often looked at in a hurry in bad light, so the targets
 * are large and there is nothing on it that is not one of the four.
 *
 * The subtitles carry live counts on purpose. "Set lists" tells you nothing you
 * did not know; "Set lists - 4 saved, Friday at the Bassment" is the answer to
 * the question you opened the app to ask, without a tap.
 */
@Composable
fun MainMenuScreen(
    librarySummary: String,
    setlistSummary: String,
    sessionSummary: String,
    settingsSummary: String,
    sessionActive: Boolean,
    versionName: String,
    onOpenLibrary: () -> Unit,
    onOpenSetlists: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 24.dp)) {
            Text(
                "DroidMusic",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                versionName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MenuEntry(
                icon = Icons.AutoMirrored.Filled.LibraryBooks,
                title = "Library",
                subtitle = librarySummary,
                onClick = onOpenLibrary,
            )
            MenuEntry(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                title = "Set Lists",
                subtitle = setlistSummary,
                onClick = onOpenSetlists,
            )
            MenuEntry(
                icon = Icons.Filled.Groups,
                title = "Sessions",
                subtitle = sessionSummary,
                onClick = onOpenSessions,
                // The one row that ever needs to shout. A player who does not
                // realise they are still in somebody else's session cannot work
                // out why their pages keep turning themselves.
                badge = if (sessionActive) "live" else null,
            )
            MenuEntry(
                icon = Icons.Filled.Settings,
                title = "Settings",
                subtitle = settingsSummary,
                onClick = onOpenSettings,
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun MenuEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    badge: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            // The title beside it already says what this is; a second
            // announcement of the same word is only something to swipe past.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Box(Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        if (badge != null) {
            Pill(
                text = badge,
                background = MaterialTheme.colorScheme.primary,
                foreground = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}
