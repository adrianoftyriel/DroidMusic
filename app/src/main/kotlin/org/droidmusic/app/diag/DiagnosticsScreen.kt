package org.droidmusic.app.diag

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import org.droidmusic.app.ui.common.EmptyState
import org.droidmusic.app.ui.common.Header

/**
 * The log, on screen, with a way to send it to somebody.
 *
 * It is shown as well as shared on purpose. Half of what this is for is a player
 * at a rehearsal reading the last few lines themselves and finding out that the
 * chart they are missing is one nobody pushed - which is a five second answer
 * rather than a round trip through somebody else's inbox.
 */
@Composable
fun DiagnosticsScreen(
    about: List<Pair<String, String>>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // Rendered once per visit and after an explicit refresh, rather than on
    // every recomposition: the log is a few hundred lines of string building and
    // it is not worth doing while somebody scrolls it.
    var text by remember { mutableStateOf(Diagnostics.render(about)) }

    Column(Modifier.fillMaxSize()) {
        Header(
            title = "Diagnostics",
            subtitle = "The last ${Diagnostics.CAPACITY} things the app did",
            onBack = onBack,
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { share(context, Diagnostics.render(about)) }) { Text("Send it") }
            OutlinedButton(onClick = { text = Diagnostics.render(about) }) { Text("Refresh") }
            OutlinedButton(
                onClick = {
                    Diagnostics.clear()
                    text = Diagnostics.render(about)
                },
            ) { Text("Clear") }
        }

        Text(
            "Kept in memory only, and lost when the app closes. It names your device, the " +
                "others in the session, the session, the songs by title and the addresses on " +
                "your local network. No chart is read into it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        if (Diagnostics.isEmpty) {
            EmptyState(
                title = "Nothing recorded yet",
                body = "Start or join a session, or open a set list, and what happens is " +
                    "written down here. Come back to this screen after the thing goes wrong " +
                    "rather than before.",
            )
            return@Column
        }

        // Monospaced, and scrolling both ways rather than wrapping: a wrapped
        // log is unreadable, and the columns are what make it scannable.
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            softWrap = false,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        )
    }
}

/**
 * Writes the log to a file and hands it to the share sheet.
 *
 * Through the same cache directory and FileProvider a set list export uses, so
 * the receiving app gets a temporary read grant to one file and nothing else.
 */
private fun share(context: Context, text: String) {
    val uri = runCatching {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(directory, "droidmusic-log-${System.currentTimeMillis()}.txt")
        file.writeText(text)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return

    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "DroidMusic diagnostic log")
        // The text as well as the file, so a messaging app that will not take an
        // attachment still carries something readable.
        putExtra(Intent.EXTRA_TEXT, text.take(MAX_INLINE_CHARS))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, "Send the log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** Enough to be useful inline; short of what an intent extra will refuse to carry. */
private const val MAX_INLINE_CHARS = 40_000
