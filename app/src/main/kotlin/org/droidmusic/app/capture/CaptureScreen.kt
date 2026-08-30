package org.droidmusic.app.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.droidmusic.app.ui.common.Header
import org.droidmusic.app.ui.common.Pill
import org.droidmusic.app.ui.common.SectionLabel
import org.droidmusic.library.SongRef

/**
 * Photographing music, page by page, and filing the result.
 *
 * Every page is shown back before anything is kept. The camera is the system's,
 * so there is no live preview of the detected edges to correct against, which
 * makes seeing the finished page the only chance to notice that a corner was cut
 * off - and noticing it here costs one more photograph rather than a surprise on
 * a stand.
 */
@Composable
fun CaptureScreen(
    controller: CaptureController,
    onBack: () -> Unit,
    onOpenSaved: (SongRef) -> Unit,
) {
    val camera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success -> controller.onPhotoTaken(success) }

    val saved = controller.saved
    LaunchedEffect(saved) {
        if (saved != null) {
            controller.consumeSaved()
            onOpenSaved(saved)
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        Header(
            title = "Scan music",
            subtitle = if (controller.pages.isEmpty()) {
                "Photograph a page"
            } else {
                "${controller.pages.size} page${if (controller.pages.size == 1) "" else "s"}"
            },
            onBack = {
                controller.discardAll()
                onBack()
            },
        )

        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {

            controller.error?.let { message ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { controller.dismissError() }) { Text("Dismiss") }
                }
            }

            if (controller.busy) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.width(20.dp).height(20.dp))
                    Text("Straightening the page.", style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (controller.pages.isEmpty()) {
                Text(
                    "Lay the music flat, fill the frame with it, and keep the whole page in " +
                        "shot. DroidMusic finds the edges of the page, straightens out the " +
                        "angle you held the phone at, and saves what it finds as a PDF.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                SectionLabel("Pages")
                LazyRow(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                ) {
                    itemsIndexed(controller.pages) { index, page ->
                        PageCard(
                            page = page,
                            number = index + 1,
                            onRemove = { controller.removePage(index) },
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { controller.beginCapture()?.let { camera.launch(it) } },
                    enabled = !controller.busy,
                ) {
                    Text(if (controller.pages.isEmpty()) "Take a photo" else "Add another page")
                }
            }

            if (controller.pages.isNotEmpty()) {
                SectionLabel("Save as")
                OutlinedTextField(
                    value = controller.title,
                    onValueChange = { controller.title = it },
                    label = { Text("Title (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Text(
                    "Saved on this device as a PDF, and added to the library. Nothing is " +
                        "written to any folder you have added.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { controller.save() },
                        enabled = !controller.busy,
                    ) {
                        Text("Save to library")
                    }
                    OutlinedButton(
                        onClick = { controller.discardAll() },
                        enabled = !controller.busy,
                    ) {
                        Text("Discard")
                    }
                }
            }
        }
    }
}

@Composable
private fun PageCard(page: ScannedPage, number: Int, onRemove: () -> Unit) {
    Column(Modifier.width(140.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .background(Color(0xFFEDEDF2)),
            contentAlignment = Alignment.Center,
        ) {
            Thumbnail(page.file)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Page $number",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Remove page $number",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (!page.straightened) {
            // Said rather than hidden. The page is still perfectly usable, but a
            // player who knows the edges were not found knows why it looks like
            // the photograph they took.
            Pill("kept whole")
        }
    }
}

/**
 * A small decode of the page, for the strip.
 *
 * Deliberately not the full page: a handful of two thousand pixel bitmaps held
 * open at once to draw thumbnails the size of a matchbox is how a scanner runs
 * a phone out of memory on the fourth page.
 */
@Composable
private fun Thumbnail(file: File) {
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(file) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (bounds.outHeight / (sample * 2) >= THUMBNAIL_EDGE) sample *= 2
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }.getOrNull()
        }
    }

    val current = bitmap
    if (current == null) {
        CircularProgressIndicator(Modifier.width(20.dp).height(20.dp))
    } else {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private const val THUMBNAIL_EDGE = 320
