@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.aryan.reader.shared.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.aryan.reader.shared.BookItem
import com.aryan.reader.shared.ios.resolveIosReadablePath
import com.aryan.reader.shared.pdf.IosPdfRenderEnginePreference
import com.aryan.reader.shared.pdf.IosPdfTextPage
import com.aryan.reader.shared.pdf.IosPdfiumRuntime
import com.aryan.reader.shared.pdf.PdfAnnotationKind
import com.aryan.reader.shared.pdf.SharedPdfAnnotation
import com.aryan.reader.shared.pdf.SharedPdfInkRenderer
import com.aryan.reader.shared.pdf.renderBitmap
import com.aryan.reader.shared.pdf.valueOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.Foundation.NSFileManager
import kotlin.math.roundToInt

/**
 * Longest side of the picture of the page being checked. Large enough for
 * handwriting to survive the model's high-detail downscaling, small enough to
 * keep the upload (and the plan usage it costs) modest.
 */
internal const val ANSWER_CHECK_PAGE_LONG_SIDE_PX = 1600
internal const val ANSWER_CHECK_JPEG_QUALITY = 85

/** Context pages only carry printed formulas and diagrams; a lighter picture is enough. */
internal const val ANSWER_CHECK_CONTEXT_PAGE_LONG_SIDE_PX = 1280
internal const val ANSWER_CHECK_CONTEXT_JPEG_QUALITY = 80

/** One page picture to render for an answer check. */
internal data class IosAnswerCheckPicture(
    val book: BookItem,
    val pdfPageIndex: Int,
    val password: String?,
    /** The reader's work drawn over the page; empty for context pages. */
    val annotations: List<SharedPdfAnnotation>,
    val longSidePx: Int,
    val jpegQuality: Int,
)

/**
 * The page as a JPEG: the PDF page rendered on white with the reader's ink
 * strokes and highlights drawn on top by the same renderer the page overlay
 * uses. Typed text boxes are not drawn; they travel as text. Null when the
 * page cannot be rendered.
 */
internal suspend fun renderIosAnswerCheckPageJpeg(picture: IosAnswerCheckPicture): ByteArray? {
    val page = renderIosAnswerCheckPdfPage(picture.book.path, picture.pdfPageIndex, picture.password, picture.longSidePx)
        ?: return null
    return withContext(Dispatchers.Default) {
        try {
            runCatching {
                if (picture.annotations.isEmpty()) {
                    // Nothing to draw over it: encode the page raster as it is
                    // rather than holding a second full-size copy of it.
                    page.encodeJpeg(picture.jpegQuality)
                } else {
                    composeIosAnswerCheckPage(page, picture.annotations, picture.jpegQuality)
                }
            }.getOrNull()
        } finally {
            // Native raster memory is otherwise only reclaimed by a later GC
            // cycle; free it before the next page of the check is rendered.
            page.close()
        }
    }
}

/** The page's own text layer, or "" for scanned pages (the picture covers those). */
internal suspend fun extractIosAnswerCheckPageText(
    book: BookItem,
    pdfPageIndex: Int,
    password: String?,
): String = withContext(Dispatchers.Default) {
    // Opening needs the PDFium gate; the session's own calls take it themselves.
    val session = IosPdfiumRuntime.withPdfium {
        IosPdfTextPage.open(book.path, pdfPageIndex, password)
    } ?: return@withContext ""
    try {
        val count = session.pageCharCount
        if (count <= 0) "" else session.textForRange(0, count).orEmpty()
    } finally {
        session.close()
    }
}

/**
 * The page on white by the reader's PDF engine, so the picture shows exactly
 * what the reader saw under their ink: the same engine and flags as the page
 * renders.
 */
private suspend fun renderIosAnswerCheckPdfPage(
    path: String?,
    pageIndex: Int,
    password: String?,
    longSidePx: Int,
): Bitmap? {
    val resolvedPath = path.resolveIosReadablePath() ?: return null
    if (!NSFileManager.defaultManager.fileExistsAtPath(resolvedPath)) return null
    return IosPdfRenderEnginePreference.engine.rasterizer.withPage(resolvedPath, password, pageIndex) { page ->
        if (page.pageIndex != pageIndex) return@withPage null
        val scale = longSidePx.coerceIn(256, 4096) / maxOf(page.width, page.height)
        val width = (page.width * scale).roundToInt().coerceAtLeast(1)
        val height = (page.height * scale).roundToInt().coerceAtLeast(1)
        page.renderBitmap(width, height)
    }.valueOrNull()
}

private fun composeIosAnswerCheckPage(
    page: Bitmap,
    annotations: List<SharedPdfAnnotation>,
    jpegQuality: Int,
): ByteArray? {
    val canvasSize = IntSize(page.width, page.height)
    val target = ImageBitmap(page.width, page.height)
    // Density only scales dp-based decoration widths; 2 matches a phone page.
    CanvasDrawScope().draw(
        density = Density(2f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(target),
        size = Size(page.width.toFloat(), page.height.toFloat()),
    ) {
        drawImage(page.asComposeImageBitmap())
        annotations.forEach { annotation ->
            when (annotation.kind) {
                PdfAnnotationKind.HIGHLIGHT -> drawSharedPdfHighlightAnnotation(
                    annotation = annotation,
                    bounds = annotation.boundsList.ifEmpty { listOfNotNull(annotation.bounds) },
                    canvasSize = canvasSize,
                    overlayStyle = sharedPdfHighlightAnnotationOverlayStyle(annotation),
                )
                PdfAnnotationKind.INK -> SharedPdfInkRenderer.createRenderData(annotation, canvasSize)
                    ?.let { drawInkRenderData(it) }
                PdfAnnotationKind.TEXT -> Unit
            }
        }
    }
    val composed = Image.makeFromBitmap(target.asSkiaBitmap())
    return try {
        composed.encodeJpeg(jpegQuality)
    } finally {
        composed.close()
    }
}

private fun Image.encodeJpeg(quality: Int): ByteArray? {
    val data = encodeToData(EncodedImageFormat.JPEG, quality) ?: return null
    return try {
        data.bytes
    } finally {
        data.close()
    }
}

/** The bitmap is immutable, so the image shares its pixels instead of copying them. */
private fun Bitmap.encodeJpeg(quality: Int): ByteArray? {
    val image = Image.makeFromBitmap(this)
    return try {
        image.encodeJpeg(quality)
    } finally {
        image.close()
    }
}
