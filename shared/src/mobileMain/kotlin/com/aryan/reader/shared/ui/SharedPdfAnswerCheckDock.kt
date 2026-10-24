package com.aryan.reader.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.aryan.reader.shared.chatgpt.CHATGPT_PANEL_DEFAULT_WIDTH_FRACTION
import com.aryan.reader.shared.chatgpt.SharedPdfAnswerCheckHost
import kotlin.math.roundToInt

private val AnswerCheckDividerTouchTarget = 20.dp
private val AnswerCheckDividerLineThickness = 1.dp
private val AnswerCheckDividerHandleWidth = 6.dp
private val AnswerCheckDividerHandleHeight = 44.dp
private val AnswerCheckPanelMinWidth = 280.dp
private val AnswerCheckReaderMinWidth = 320.dp
private const val AnswerCheckDividerDoubleTapMillis = 300L
private const val AnswerCheckPanelWidthStep = 0.05f

/**
 * The PDF reader with the answer check beside it, as a tile: the reader keeps
 * the start side and ChatGPT's panel the end side, split by a divider the
 * reader drags to resize the panel (a double tap restores the default width).
 * Where both do not fit, the check opens in the bottom sheet instead.
 *
 * [content] is the first child in every state, so opening and closing the
 * panel resizes the reader without recreating it. As in the PDF split view,
 * the panes follow the drag while the divider's touch target stays where the
 * drag began until release.
 */
@Composable
internal fun SharedPdfAnswerCheckDock(
    host: SharedPdfAnswerCheckHost?,
    bookTitle: String,
    isMainTtsActive: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val totalWidthPx = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        val dividerPx = with(density) { AnswerCheckDividerTouchTarget.roundToPx() }
        val minPanelPx = with(density) { AnswerCheckPanelMinWidth.roundToPx() }
        val maxPanelPx = totalWidthPx - dividerPx - with(density) { AnswerCheckReaderMinWidth.roundToPx() }
        val visible = host != null && host.state.isVisible
        val fits = maxPanelPx >= minPanelPx
        val committedFraction = host?.panelWidthFraction ?: CHATGPT_PANEL_DEFAULT_WIDTH_FRACTION
        val onWidthChange = rememberUpdatedState(host?.onPanelWidthFractionChange ?: {})
        var previewFraction by remember { mutableStateOf<Float?>(null) }

        fun panelWidthPx(fraction: Float): Int =
            (totalWidthPx * fraction).roundToInt().coerceIn(minPanelPx, maxPanelPx.coerceAtLeast(minPanelPx))

        /** Where the divider starts, from the left edge, for a panel this wide. */
        fun dividerStartPx(panelPx: Int): Int = if (isRtl) panelPx else totalWidthPx - panelPx - dividerPx

        val panelPx = panelWidthPx(previewFraction ?: committedFraction)
        val committedDividerStartPx = rememberUpdatedState(dividerStartPx(panelWidthPx(committedFraction)))

        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
                if (visible && fits) {
                    SharedPdfAnswerCheckDivider(isDragging = previewFraction != null)
                    SharedPdfAnswerCheckPanel(
                        host = host,
                        bookTitle = bookTitle,
                        isMainTtsActive = isMainTtsActive,
                        modifier = Modifier.width(with(density) { panelPx.toDp() }).fillMaxHeight(),
                    )
                }
            }
            if (visible && fits) {
                val dividerDescription = readerString("chatgpt_panel_divider", "ChatGPT panel divider")
                val widerDescription = readerString("chatgpt_panel_wider", "Widen the ChatGPT panel")
                val narrowerDescription = readerString("chatgpt_panel_narrower", "Narrow the ChatGPT panel")
                fun changeBy(step: Float): Boolean {
                    val fraction = panelWidthPx(committedFraction).toFloat() / totalWidthPx + step
                    onWidthChange.value(panelWidthPx(fraction).toFloat() / totalWidthPx)
                    return true
                }
                Box(
                    Modifier
                        .align(AbsoluteAlignment.TopLeft)
                        .absoluteOffset(x = with(density) { committedDividerStartPx.value.toDp() })
                        .width(AnswerCheckDividerTouchTarget)
                        .fillMaxHeight()
                        .semantics {
                            contentDescription = dividerDescription
                            customActions = listOf(
                                CustomAccessibilityAction(widerDescription) { changeBy(AnswerCheckPanelWidthStep) },
                                CustomAccessibilityAction(narrowerDescription) { changeBy(-AnswerCheckPanelWidthStep) },
                            )
                        }
                        .pointerInput(totalWidthPx, dividerPx, isRtl, minPanelPx, maxPanelPx) {
                            var lastTapMillis = Long.MIN_VALUE
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var dragging = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!dragging &&
                                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                                    ) {
                                        dragging = true
                                    }
                                    if (dragging) {
                                        change.consume()
                                        val pointerPx = committedDividerStartPx.value + change.position.x
                                        val widthPx = if (isRtl) {
                                            pointerPx - dividerPx / 2f
                                        } else {
                                            totalWidthPx - pointerPx - dividerPx / 2f
                                        }
                                        previewFraction = panelWidthPx(widthPx / totalWidthPx).toFloat() / totalWidthPx
                                    }
                                    if (change.changedToUp() || !change.pressed) {
                                        if (dragging) {
                                            previewFraction?.let { onWidthChange.value(it) }
                                        } else if (change.changedToUp()) {
                                            val doubleTap = lastTapMillis != Long.MIN_VALUE &&
                                                down.uptimeMillis - lastTapMillis in 1..AnswerCheckDividerDoubleTapMillis
                                            lastTapMillis = if (doubleTap) {
                                                onWidthChange.value(CHATGPT_PANEL_DEFAULT_WIDTH_FRACTION)
                                                Long.MIN_VALUE
                                            } else {
                                                down.uptimeMillis
                                            }
                                        }
                                        break
                                    }
                                }
                                // Released, cancelled or taken by another gesture: the
                                // committed width (saved above when released) applies.
                                previewFraction = null
                            }
                        },
                )
            }
        }
        if (visible && !fits) {
            SharedPdfAnswerCheckSheet(host = host, bookTitle = bookTitle, isMainTtsActive = isMainTtsActive)
        }
    }
}

/** The line between the reader and the panel, with the grip that shows it can be dragged. */
@Composable
private fun SharedPdfAnswerCheckDivider(isDragging: Boolean) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val handleColor = if (isDragging) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }
    Box(
        Modifier
            .width(AnswerCheckDividerTouchTarget)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .drawBehind {
                val centerX = size.width / 2f
                drawLine(
                    color = lineColor,
                    start = Offset(centerX, 0f),
                    end = Offset(centerX, size.height),
                    strokeWidth = AnswerCheckDividerLineThickness.toPx(),
                )
                val handleWidth = AnswerCheckDividerHandleWidth.toPx()
                val handleHeight = AnswerCheckDividerHandleHeight.toPx()
                drawRoundRect(
                    color = handleColor,
                    topLeft = Offset(centerX - handleWidth / 2f, (size.height - handleHeight) / 2f),
                    size = Size(handleWidth, handleHeight),
                    cornerRadius = CornerRadius(handleWidth / 2f),
                )
            },
    )
}
