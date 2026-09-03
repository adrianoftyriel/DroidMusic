package org.droidmusic.app.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.droidmusic.app.input.PageAction
import org.droidmusic.app.render.RasterPageSource
import org.droidmusic.app.render.TextPageSource
import org.droidmusic.music.ChartLayout
import org.droidmusic.music.ChartRow
import org.droidmusic.music.RowKind

/** The size a chart is drawn at before the player's preference and any zoom. */
private const val BASE_CHART_FONT_SP = 15f

/**
 * Line spacing, as a multiple of the font size.
 *
 * One number, used for the on-screen line height *and* for working out how many
 * lines fit on a page. They have to be the same number or the page holds a
 * different amount than it was paginated for, which shows up as the last line of
 * every page being cut in half.
 */
private const val CHART_LINE_SPACING = 1.45f

private val CHART_PADDING_H = 12.dp
private val CHART_PADDING_V = 8.dp

/**
 * A string long enough that measuring it and dividing averages away the rounding
 * in a single character's advance width.
 */
private const val MEASURING_STICK = "0000000000000000"

/**
 * The page itself, and the tap surface over it.
 *
 * Everything else in the app is a means to getting here, so this screen gives
 * the chart the entire window: no chrome, no bars, nothing that is not the
 * music, until the player asks for the controls.
 */
@Composable
fun ViewerSurface(
    controller: ViewerController,
    preferences: ViewerPreferences,
    onToggleControls: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(if (preferences.darkChart) Color(0xFF0A0A0C) else Color.White),
    ) {
        val widthDp = maxWidth.value.toInt()
        val heightDp = maxHeight.value.toInt()
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }

        // The size the chart would be drawn at with no zoom: the player's chosen
        // size, scaled by whatever they have pinched to. Measured at *this* size
        // rather than the final one because the fit-to-width zoom is worked out
        // from the measurement, and measuring at a size that depended on it
        // would be circular. A monospaced font scales linearly, so one
        // measurement serves every scale.
        val baseFontSizeSp = BASE_CHART_FONT_SP * preferences.chartFontScale * controller.chartZoom
        val charWidthPx = remember(measurer, baseFontSizeSp) {
            measurer.measure(
                text = AnnotatedString(MEASURING_STICK),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = baseFontSizeSp.sp,
                ),
            ).size.width.toFloat() / MEASURING_STICK.length
        }

        // Charts are laid out per page column, not per window: in a two-page
        // spread each chart has half the width to wrap into.
        val pagesShown = PageLayoutRules
            .modeFor(widthDp, heightDp, preferences.pageModeOverride)
            .pagesShown
        val chartWidthPx = (
            widthPx.toFloat() / pagesShown - with(density) { CHART_PADDING_H.toPx() } * 2
            ).coerceAtLeast(1f)

        LaunchedEffect(
            controller.source,
            controller.transposeSemitones,
            controller.capo,
            chartWidthPx,
            charWidthPx,
        ) {
            controller.onChartMeasured(chartWidthPx, charWidthPx)
        }

        val fontSizeSp = baseFontSizeSp * controller.chartFitZoom
        val lineHeightPx = with(density) { (fontSizeSp * CHART_LINE_SPACING).sp.toPx() }

        // How many lines and characters of chart fit, which is what pagination
        // and wrapping need. Derived from the actual viewport rather than
        // assumed, so a fold, a split screen and a 12-inch tablet all get a full
        // page rather than a guess.
        val linesThatFit = ChartLayout.linesThatFit(
            heightPx = heightPx - with(density) { CHART_PADDING_V.toPx() } * 2,
            lineHeightPx = lineHeightPx,
        )
        val columnsThatFit = ChartLayout.columnsThatFit(
            widthPx = chartWidthPx,
            charWidthPx = charWidthPx * controller.chartFitZoom,
        )

        LaunchedEffect(
            widthDp,
            heightDp,
            linesThatFit,
            columnsThatFit,
            preferences.wrapChartLines,
            preferences.pageModeOverride,
        ) {
            controller.onViewportChanged(
                widthDp = widthDp,
                heightDp = heightDp,
                linesThatFit = linesThatFit,
                columnsThatFit = columnsThatFit,
                wrapLines = preferences.wrapChartLines,
                override = preferences.pageModeOverride,
            )
        }

        // Only pages with something to gain get a double tap handler.
        //
        // That is not tidiness. Compose can only tell a single tap from the first
        // half of a double tap by waiting out the double tap window, so wherever
        // onDoubleTap is registered, turning the page by tapping is delayed by
        // roughly a third of a second. A scan always has margins to trim; a chart
        // only has width going to waste if its longest line is shorter than the
        // screen, and where it is not, the handler is left off and the taps stay
        // instant. Foot switches never come through here and are never slowed.
        val zoomable = preferences.doubleTapToZoom &&
            (controller.source is RasterPageSource || controller.chartZoomAvailable)

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(preferences.tapZones, controller.pageCount, zoomable) {
                    detectTapGestures(
                        onDoubleTap = if (zoomable) {
                            { controller.toggleZoom() }
                        } else {
                            null
                        },
                        onTap = { offset ->
                            when (
                                preferences.tapZones.actionAt(
                                    x = offset.x,
                                    y = offset.y,
                                    width = size.width.toFloat(),
                                    height = size.height.toFloat(),
                                )
                            ) {
                                PageAction.NEXT_PAGE ->
                                    controller.turn(true, preferences.unicodeAccidentals)
                                PageAction.PREVIOUS_PAGE ->
                                    controller.turn(false, preferences.unicodeAccidentals)
                                PageAction.TOGGLE_CONTROLS -> onToggleControls()
                                else -> Unit
                            }
                        },
                    )
                }
                // After the tap handler in the chain, which makes it the inner
                // one: a second finger is claimed here before the tap detector
                // above can read the gesture as a page turn. On the tap surface
                // rather than on the text, so it covers the whole window - a
                // chart narrower than the screen is exactly the one somebody
                // wants to make bigger.
                .pinchToZoom(
                    enabled = controller.source is TextPageSource,
                    onPinch = controller::pinchChart,
                ),
        ) {
            when {
                controller.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                controller.error != null -> Box(
                    Modifier.fillMaxSize().padding(32.dp),
                    Alignment.Center,
                ) {
                    Text(
                        text = controller.error.orEmpty(),
                        color = if (preferences.darkChart) Color.White else Color.Black,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                else -> Pages(
                    controller = controller,
                    preferences = preferences,
                    fontSizeSp = fontSizeSp,
                    widthPx = widthPx,
                    heightPx = heightPx,
                )
            }
        }
    }
}

@Composable
private fun Pages(
    controller: ViewerController,
    preferences: ViewerPreferences,
    fontSizeSp: Float,
    widthPx: Int,
    heightPx: Int,
) {
    val pages = controller.visiblePages
    if (pages.isEmpty()) return

    Row(Modifier.fillMaxSize()) {
        for ((index, pageIndex) in pages.withIndex()) {
            Box(Modifier.weight(1f).fillMaxSize()) {
                PageContent(
                    controller = controller,
                    preferences = preferences,
                    pageIndex = pageIndex,
                    fontSizeSp = fontSizeSp,
                    widthPx = widthPx / pages.size,
                    heightPx = heightPx,
                )
            }
            if (index < pages.size - 1) {
                // A visible gutter, so two pages read as two pages and a line of
                // music is never mistaken for continuing across the join.
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxSize()
                        .background(
                            if (preferences.darkChart) {
                                Color(0xFF2A2A32)
                            } else {
                                Color(0xFFDDDDE3)
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun PageContent(
    controller: ViewerController,
    preferences: ViewerPreferences,
    pageIndex: Int,
    fontSizeSp: Float,
    widthPx: Int,
    heightPx: Int,
) {
    when (val source = controller.source) {
        is RasterPageSource -> {
            var bitmap by remember(source, pageIndex, widthPx, heightPx) {
                mutableStateOf<Bitmap?>(null)
            }
            var content by remember(source, pageIndex, widthPx, heightPx) {
                mutableStateOf<ContentRect?>(null)
            }
            var croppedBitmap by remember(source, pageIndex, widthPx, heightPx) {
                mutableStateOf<Bitmap?>(null)
            }

            LaunchedEffect(source, pageIndex, widthPx, heightPx) {
                val page = source.render(pageIndex, widthPx, heightPx)
                bitmap = page
                // Measured off the fitted page rather than the file, so the
                // answer is in page coordinates and one scan serves both the
                // PDF and the image path.
                content = page?.let { withContext(Dispatchers.Default) { contentRectOf(it) } }
            }

            // Re-rendered rather than magnified. Scaling up the bitmap already on
            // screen would give the same geometry and none of the detail, which
            // on a scan is the difference between reading it and squinting at it.
            LaunchedEffect(controller.zoomed, content, source, pageIndex, widthPx, heightPx) {
                val region = content
                croppedBitmap = if (
                    controller.zoomed && region != null && region.trimsEnoughToZoom()
                ) {
                    source.render(pageIndex, widthPx, heightPx, region)
                } else {
                    null
                }
            }

            val current = croppedBitmap ?: bitmap
            if (current != null) {
                androidx.compose.foundation.Image(
                    bitmap = current.asImageBitmap(),
                    contentDescription = "Page ${pageIndex + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            }
        }

        // The rows come from the controller rather than from the source: they
        // are what a transposition rewrites, and only the controller publishes
        // them as state Compose can see.
        is TextPageSource -> ChartPage(
            rows = controller.rowsFor(pageIndex),
            fontSizeSp = fontSizeSp,
            dark = preferences.darkChart,
        )

        else -> Unit
    }
}

/**
 * Draws one page of a chord chart.
 *
 * The chord row and the lyric row are drawn as two separate monospaced lines
 * with no space between them, which is what makes the chord sit above its
 * syllable. Neither is allowed to wrap here: any line that needed breaking was
 * broken by ChartLayout.wrap, which cuts both rows at the same character column
 * so the chords stay over their words. Compose wrapping them independently is
 * exactly the failure that has to be avoided.
 *
 * The page still scrolls sideways, for the two cases wrapping leaves behind:
 * tablature, which is never reflowed because its columns are the notation, and a
 * player who has turned wrapping off.
 */
@Composable
private fun ChartPage(rows: List<ChartRow>, fontSizeSp: Float, dark: Boolean) {
    val ink = if (dark) Color(0xFFE8E8EE) else Color(0xFF16161C)
    val chordInk = if (dark) Color(0xFF8FB4FF) else Color(0xFF1F4BB8)
    val quietInk = if (dark) Color(0xFF9A9AA8) else Color(0xFF5A5A66)
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .horizontalScroll(scroll)
            .padding(horizontal = CHART_PADDING_H, vertical = CHART_PADDING_V),
        verticalArrangement = Arrangement.Top,
    ) {
        for (row in rows) {
            when (row.kind) {
                RowKind.CHORD_AND_LYRIC -> {
                    ChartLine(row.chordText, fontSizeSp, chordInk, bold = true)
                    ChartLine(row.text, fontSizeSp, ink)
                }
                RowKind.CHORDS -> ChartLine(row.chordText, fontSizeSp, chordInk, bold = true)
                RowKind.LYRIC -> ChartLine(row.text, fontSizeSp, ink)
                RowKind.TAB -> ChartLine(row.text, fontSizeSp, ink)
                // A grid is chords in a rectangle of cells. Drawn in the chord
                // colour, since that is what it is made of, and never reflowed -
                // the columns are the notation, exactly as in tablature.
                RowKind.GRID -> ChartLine(row.text, fontSizeSp, chordInk)
                RowKind.COMMENT -> ChartLine(row.text, fontSizeSp, quietInk, italic = true)
                RowKind.HEADER -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = row.text,
                        fontSize = (fontSizeSp * 1.05f).sp,
                        fontWeight = FontWeight.Bold,
                        color = chordInk,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                // The title block, on the first page of a ChordPro chart. Set in
                // the default proportional face rather than the chart's
                // monospace: nothing below it lines up with a title, and a
                // proportional one fits more of a long name across a phone.
                RowKind.TITLE -> Text(
                    text = row.text,
                    fontSize = (fontSizeSp * 1.45f).sp,
                    lineHeight = (fontSizeSp * 1.75f).sp,
                    fontWeight = FontWeight.Bold,
                    color = ink,
                    maxLines = 1,
                    softWrap = false,
                )
                RowKind.CREDIT -> Text(
                    text = row.text,
                    fontSize = (fontSizeSp * 0.95f).sp,
                    lineHeight = (fontSizeSp * 1.45f).sp,
                    color = quietInk,
                    maxLines = 1,
                    softWrap = false,
                )
                RowKind.BLANK -> Spacer(Modifier.height((fontSizeSp * 0.7f).dp))
                // A break has already done its work in ChartLayout.paginate,
                // which started a fresh page at it. There is nothing left to draw.
                RowKind.BREAK -> Unit
            }
        }
    }
}

/**
 * A two-finger pinch, and deliberately nothing else.
 *
 * Written out rather than using `detectTransformGestures` because that one also
 * claims single-finger drags, which on this screen are how the page is scrolled
 * sideways and - through the tap zones over it - how it is turned. Nothing is
 * consumed until a second finger is down and the distance between the two has
 * actually changed, so a tap, a drag and a foot switch all behave exactly as they
 * did before.
 */
private fun Modifier.pinchToZoom(
    enabled: Boolean,
    onPinch: (Float) -> Unit,
): Modifier = pointerInput(enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                val zoom = event.calculateZoom()
                if (zoom > 0f && zoom != 1f) {
                    onPinch(zoom)
                    event.changes.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

@Composable
private fun ChartLine(
    text: String,
    fontSizeSp: Float,
    color: Color,
    bold: Boolean = false,
    italic: Boolean = false,
) {
    Text(
        text = text,
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp * CHART_LINE_SPACING).sp,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (italic) {
            androidx.compose.ui.text.font.FontStyle.Italic
        } else {
            androidx.compose.ui.text.font.FontStyle.Normal
        },
        maxLines = 1,
        // No width modifier on purpose. The column scrolls horizontally, so the
        // line is measured at its natural width and the scroll range comes out
        // as wide as the widest line on the page.
        softWrap = false,
    )
}
