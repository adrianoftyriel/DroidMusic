package org.droidmusic.app.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.droidmusic.app.ui.common.ChoicePill
import org.droidmusic.app.ui.common.Header
import org.droidmusic.app.ui.common.Pill
import org.droidmusic.app.ui.common.SectionLabel
import org.droidmusic.update.AvailableUpdate
import org.droidmusic.update.UpdateChannel
import org.droidmusic.update.UpdateStatus

/**
 * Checking for, fetching and installing a newer build.
 *
 * A screen rather than a row in Settings, because the flow has real states -
 * checking, something found, downloading, ready to install, and several ways of
 * being unable to proceed - and each of them needs a sentence. A settings row
 * that can only say "Update available" leaves somebody tapping it and hoping.
 */
@Composable
fun UpdatesScreen(
    controller: UpdateController,
    channel: UpdateChannel,
    onChannelChange: (UpdateChannel) -> Unit,
    onBack: () -> Unit,
    versionName: String,
) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        Header(title = "Updates", onBack = onBack)

        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
        ) {
            SectionLabel("Running now")
            Body(
                controller.currentTag?.let { "DroidMusic $versionName, from release $it" }
                    ?: "DroidMusic $versionName. This build did not come from a release, " +
                    "so there is no tag to compare against - it was built from source.",
            )
            Body("Updates come from ${controller.repository}.", secondary = true)

            SectionLabel("Which builds to offer")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChoicePill(
                    text = "Releases only",
                    selected = channel == UpdateChannel.STABLE,
                    onClick = { onChannelChange(UpdateChannel.STABLE) },
                )
                ChoicePill(
                    text = "Include pre-releases",
                    selected = channel == UpdateChannel.PRERELEASE,
                    onClick = { onChannelChange(UpdateChannel.PRERELEASE) },
                )
            }
            Body(
                when (channel) {
                    UpdateChannel.STABLE ->
                        "Only full releases. There are none yet, so this will find nothing " +
                            "until the first one is published."
                    UpdateChannel.PRERELEASE ->
                        "Every push to the dev branch publishes a pre-release. Built and " +
                            "tested, but not a stable release, and there may be several a day."
                },
                secondary = true,
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            if (!controller.canUpdateThisBuild) {
                Notice(
                    "This is a debug build, and it installs under a different name than the " +
                        "released app. Installing a release APK here would add a second copy " +
                        "of DroidMusic rather than update this one, so the button is off.",
                )
            }

            controller.error?.let { message ->
                Notice(message, onDismiss = { controller.dismissError() })
            }

            if (controller.phase == UpdatePhase.CHECKING) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                Body("Asking GitHub what has been released.", secondary = true)
            }

            when (val status = controller.status) {
                null -> Unit

                is UpdateStatus.UpToDate ->
                    Body("Up to date. ${status.current} is the newest on this channel.")

                is UpdateStatus.NothingPublished ->
                    Body(
                        if (channel == UpdateChannel.STABLE) {
                            "Nothing has been published as a full release yet. Switch to " +
                                "pre-releases to see what the dev branch has built."
                        } else {
                            "There are no releases with an installable APK attached."
                        },
                    )

                is UpdateStatus.Ahead ->
                    Body(
                        "This build (${status.current}) is newer than anything published " +
                            "(${status.newest}), so there is nothing to install. Going " +
                            "backwards is not offered.",
                    )

                is UpdateStatus.UnknownCurrent -> {
                    Body(
                        "This build has no release tag, so it cannot be compared with what " +
                            "is published. The newest release is below; install it only if " +
                            "you know it is what you want.",
                    )
                    status.newest?.let { UpdateCard(controller, it.release.name, it) }
                }

                is UpdateStatus.Available -> UpdateCard(
                    controller,
                    "New: ${status.update.tag}",
                    status.update,
                )
            }

            Box(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { controller.check(channel) },
                    enabled = controller.phase == UpdatePhase.IDLE ||
                        controller.phase == UpdatePhase.READY,
                ) {
                    Text(if (controller.status == null) "Check for updates" else "Check again")
                }
                if (controller.downloaded != null) {
                    TextButton(onClick = { controller.discard() }) { Text("Discard download") }
                }
            }

            SectionLabel("How this works")
            Body(
                "Nothing is checked or downloaded unless you press the button. This app does " +
                    "not look for updates on its own, because a page turner that decides to " +
                    "fetch nine megabytes over a venue's wifi before the first song has done " +
                    "something unforgivable.",
                secondary = true,
            )
            Body(
                "An update is a normal Android install, so Android asks you to confirm it and " +
                    "will refuse it outright if the new APK was signed with a different key " +
                    "than the one already on the phone. That refusal is the real protection " +
                    "here; the checksum below it only catches a download that arrived broken.",
                secondary = true,
            )
        }
    }
}

@Composable
private fun UpdateCard(
    controller: UpdateController,
    title: String,
    update: AvailableUpdate,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (update.isPreRelease) Pill("pre-release")
        }

        Text(
            "${update.apk.name} - ${megabytes(update.apk.size)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (update.checksums == null) {
            Text(
                "This release has no checksum file, so the download cannot be checked for " +
                    "corruption before it is installed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        update.release.body.takeIf { it.isNotBlank() }?.let { notes ->
            Box(Modifier.height(8.dp))
            Text(
                notes.lineSequence().take(12).joinToString("\n").trim(),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Box(Modifier.height(10.dp))

        when (controller.phase) {
            UpdatePhase.DOWNLOADING -> {
                if (controller.progress >= 0f) {
                    LinearProgressIndicator(
                        progress = { controller.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Downloading - ${(controller.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Downloading.", style = MaterialTheme.typography.bodySmall)
                }
            }

            UpdatePhase.READY -> {
                Text(
                    if (controller.verified) {
                        "Downloaded, and it matches the checksum published with it."
                    } else {
                        "Downloaded. There was no checksum published to check it against."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Box(Modifier.height(8.dp))
                if (controller.canInstall()) {
                    Button(onClick = { controller.install() }) { Text("Install") }
                } else {
                    Text(
                        "Android needs your permission for DroidMusic to install an app. " +
                            "It is granted per app, in Settings, and cannot be asked for here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Box(Modifier.height(6.dp))
                    Button(onClick = { controller.openUnknownSourcesSettings() }) {
                        Text("Open that setting")
                    }
                }
            }

            else -> Button(
                onClick = { controller.download() },
                enabled = controller.canUpdateThisBuild,
            ) {
                Text("Download ${megabytes(update.apk.size)}")
            }
        }
    }
}

@Composable
private fun Body(text: String, secondary: Boolean = false) {
    Text(
        text,
        style = if (secondary) {
            MaterialTheme.typography.bodySmall
        } else {
            MaterialTheme.typography.bodyMedium
        },
        color = if (secondary) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onBackground
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun Notice(text: String, onDismiss: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        if (onDismiss != null) {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/** "9.1 MB". A size in bytes tells nobody whether to wait for better wifi. */
private fun megabytes(bytes: Long): String =
    if (bytes <= 0L) "unknown size" else "%.1f MB".format(bytes / 1_048_576.0)
