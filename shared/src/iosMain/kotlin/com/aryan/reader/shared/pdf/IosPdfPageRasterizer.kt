@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.aryan.reader.shared.pdf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aryan.reader.shared.ui.IosPdfImageRect
import com.aryan.reader.shared.ui.SharedMobilePdfOpenError
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import platform.Foundation.NSUserDefaults
import platform.posix.memcpy

/**
 * The library that draws PDF pages on iOS: pages, zoom tiles, thumbnails and
 * the page pictures for OCR and answer checks. Text, search, selection and
 * annotations use PDFium either way.
 */
internal enum class IosPdfRenderEngine(val id: String, val label: String) {
    MUPDF("mupdf", "MuPDF"),
    PDFIUM("pdfium", "PDFium");

    val rasterizer: IosPdfPageRasterizer
        get() = when (this) {
            MUPDF -> IosMupdfPageRasterizer
            PDFIUM -> IosPdfiumPageRasterizer
        }

    companion object {
        fun fromId(id: String?): IosPdfRenderEngine = entries.firstOrNull { it.id == id } ?: MUPDF
    }
}

/** The reader's engine choice, kept per device. Compose observes [engine]. */
internal object IosPdfRenderEnginePreference {
    private const val DefaultsKey = "reader.pdf.render_engine.v1"
    private const val MupdfFirstUseKey = "reader.pdf.mupdf_first_use_running.v1"

    var engine by mutableStateOf(loadEngine())
        private set

    fun select(engine: IosPdfRenderEngine) {
        NSUserDefaults.standardUserDefaults.setObject(engine.id, forKey = DefaultsKey)
        this.engine = engine
    }

    private fun loadEngine(): IosPdfRenderEngine {
        val defaults = NSUserDefaults.standardUserDefaults
        if (defaults.boolForKey(MupdfFirstUseKey)) {
            // The previous launch died inside its first MuPDF call. The engine
            // switch lives in an open book, so without this the reader could
            // not reach it: draw with PDFium, and let them switch back.
            defaults.removeObjectForKey(MupdfFirstUseKey)
            defaults.setObject(IosPdfRenderEngine.PDFIUM.id, forKey = DefaultsKey)
            return IosPdfRenderEngine.PDFIUM
        }
        return IosPdfRenderEngine.fromId(defaults.stringForKey(DefaultsKey))
    }

    /** Brackets the first MuPDF call of a launch; see [loadEngine]. */
    fun beginMupdfFirstUse() {
        NSUserDefaults.standardUserDefaults.setBool(true, forKey = MupdfFirstUseKey)
        // It has to be on disk before a crash could happen.
        NSUserDefaults.standardUserDefaults.synchronize()
    }

    fun endMupdfFirstUse() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(MupdfFirstUseKey)
    }
}

/** A page opened by an engine. Valid only inside [IosPdfPageRasterizer.withPage]. */
internal interface IosPdfRasterPage {
    /** The page that was opened: the requested one clamped to the document. */
    val pageIndex: Int
    val pageCount: Int

    /** Displayed size in points: the crop box after /Rotate. */
    val width: Float
    val height: Float

    /**
     * Draws the part [left, left + width) x [top, top + height) of the page
     * scaled to [fullWidth] x [fullHeight] pixels into [pixels]: BGRA,
     * [stride] bytes per row, opaque over white, without annotations.
     */
    fun render(
        pixels: CPointer<UByteVar>,
        stride: Int,
        fullWidth: Int,
        fullHeight: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): Boolean

    /** Where images land in a [fullWidth] x [fullHeight] render, so dark themes can keep their colours. */
    fun imageRects(fullWidth: Int, fullHeight: Int): List<IosPdfImageRect>

    /** Width divided by height for every page of the document; 0 where a size cannot be read. */
    fun documentAspectRatios(): FloatArray
}

internal sealed interface IosPdfRasterResult<out T> {
    data class Opened<T>(val value: T) : IosPdfRasterResult<T>

    data class Failed(
        val message: String,
        val openError: SharedMobilePdfOpenError? = null,
        val pageCount: Int = 0,
    ) : IosPdfRasterResult<Nothing>
}

internal fun <T> IosPdfRasterResult<T>.valueOrNull(): T? = (this as? IosPdfRasterResult.Opened<T>)?.value

internal interface IosPdfPageRasterizer {
    /**
     * Opens page [pageIndex] of the PDF at [path] (clamped to the document)
     * and runs [block] with it, off the main thread and one call at a time.
     */
    suspend fun <T> withPage(
        path: String,
        password: String?,
        pageIndex: Int,
        block: (IosPdfRasterPage) -> T,
    ): IosPdfRasterResult<T>
}

/**
 * Draws part of the page into a new immutable Skia bitmap, or returns null.
 *
 * Without [transform] the engine draws straight into the bitmap's memory, so a
 * page costs one buffer instead of the three copies an Image round trip makes.
 * With it the pixels pass through a byte array that [transform] edits.
 */
internal fun IosPdfRasterPage.renderBitmap(
    fullWidth: Int,
    fullHeight: Int,
    left: Int = 0,
    top: Int = 0,
    width: Int = fullWidth,
    height: Int = fullHeight,
    transform: ((bytes: ByteArray, stride: Int) -> Unit)? = null,
): Bitmap? {
    if (width <= 0 || height <= 0) return null
    val bitmap = Bitmap()
    var drawn = false
    try {
        if (!bitmap.allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL))) return null
        val pixmap = bitmap.peekPixels() ?: return null
        try {
            val target = interpretCPointer<UByteVar>(pixmap.addr) ?: return null
            val stride = pixmap.rowBytes
            if (transform == null) {
                if (!render(target, stride, fullWidth, fullHeight, left, top, width, height)) return null
            } else {
                val bytes = ByteArray(stride * height)
                val rendered = bytes.usePinned { pinned ->
                    render(pinned.addressOf(0).reinterpret(), stride, fullWidth, fullHeight, left, top, width, height)
                }
                if (!rendered) return null
                transform(bytes, stride)
                bytes.usePinned { pinned -> memcpy(target, pinned.addressOf(0), bytes.size.convert()) }
            }
        } finally {
            pixmap.close()
        }
        bitmap.setImmutable()
        drawn = true
        return bitmap
    } finally {
        if (!drawn) bitmap.close()
    }
}
