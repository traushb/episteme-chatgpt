@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.aryan.reader.shared.pdf

import cnames.structs.rmu_document
import com.aryan.reader.shared.mupdf.RMU_ERROR_PASSWORD
import com.aryan.reader.shared.mupdf.RMU_OK
import com.aryan.reader.shared.mupdf.rmu_close
import com.aryan.reader.shared.mupdf.rmu_empty_cache
import com.aryan.reader.shared.mupdf.rmu_image_rects
import com.aryan.reader.shared.mupdf.rmu_init
import com.aryan.reader.shared.mupdf.rmu_open
import com.aryan.reader.shared.mupdf.rmu_page_count
import com.aryan.reader.shared.mupdf.rmu_page_size
import com.aryan.reader.shared.mupdf.rmu_page_sizes
import com.aryan.reader.shared.mupdf.rmu_render_bgra
import com.aryan.reader.shared.ui.IosPdfImageRect
import com.aryan.reader.shared.ui.SharedMobilePdfOpenError
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification

/**
 * MuPDF through reader_mupdf_bridge.c. Unlike the PDFium path, documents stay
 * open between calls, so fonts, images and the page tree are parsed once
 * rather than for every page and tile.
 */
internal object IosMupdfPageRasterizer : IosPdfPageRasterizer {
    /** MuPDF's cache of decoded images and fonts. Its 256 MB default is too much for a 2 GB iPad. */
    private const val CacheLimitBytes = 48L * 1024L * 1024L

    /** Two books can be open side by side. */
    private const val MaxOpenDocuments = 2
    private const val MaxImageRects = 256

    private class OpenDocument(
        val handle: CPointer<rmu_document>,
        val pageCount: Int,
        val revision: String,
        val password: String?,
    )

    private val mutex = Mutex()
    private val documents = LinkedHashMap<String, OpenDocument>()
    private var started = false
    private var usedThisLaunch = false
    private var memoryWarningObserver: Any? = null

    override suspend fun <T> withPage(
        path: String,
        password: String?,
        pageIndex: Int,
        block: (IosPdfRasterPage) -> T,
    ): IosPdfRasterResult<T> = withContext(Dispatchers.Default) {
        mutex.withLock {
            val firstUse = !usedThisLaunch
            if (firstUse) IosPdfRenderEnginePreference.beginMupdfFirstUse()
            try {
                openAndRun(path, password, pageIndex, block)
            } finally {
                // Not reached when MuPDF crashes the app, which is the point.
                if (firstUse) {
                    usedThisLaunch = true
                    IosPdfRenderEnginePreference.endMupdfFirstUse()
                }
            }
        }
    }

    /** Runs under [mutex]. */
    private fun <T> openAndRun(
        path: String,
        password: String?,
        pageIndex: Int,
        block: (IosPdfRasterPage) -> T,
    ): IosPdfRasterResult<T> {
        if (!started) {
            if (rmu_init(CacheLimitBytes.convert()) != RMU_OK) {
                return IosPdfRasterResult.Failed("MuPDF could not start")
            }
            // The bridge serializes calls itself, so the cache can be
            // dropped from the notification queue without this lock.
            memoryWarningObserver = NSNotificationCenter.defaultCenter.addObserverForName(
                name = UIApplicationDidReceiveMemoryWarningNotification,
                `object` = null,
                queue = NSOperationQueue(),
            ) { _ -> rmu_empty_cache() }
            started = true
        }
        val revision = iosPdfFileRevision(path)
        val cached = documents.remove(path)
        if (cached != null) {
            // Re-inserting keeps the map in least recently used order. A
            // changed file or password needs a fresh document.
            if (cached.revision == revision && cached.password.orEmpty() == password.orEmpty()) {
                documents[path] = cached
            } else {
                rmu_close(cached.handle)
            }
        }
        val document = documents[path] ?: memScoped {
            val error = alloc<IntVar>()
            val handle = rmu_open(path, password?.takeIf { it.isNotEmpty() }, error.ptr)
                ?: return IosPdfRasterResult.Failed(
                    message = "MuPDF could not open this PDF",
                    openError = if (error.value == RMU_ERROR_PASSWORD) {
                        SharedMobilePdfOpenError.PASSWORD_REQUIRED
                    } else {
                        SharedMobilePdfOpenError.INVALID_DOCUMENT
                    },
                )
            OpenDocument(handle, rmu_page_count(handle), revision, password).also { opened ->
                documents[path] = opened
                while (documents.size > MaxOpenDocuments) {
                    val eldest = documents.keys.first()
                    documents.remove(eldest)?.let { rmu_close(it.handle) }
                }
            }
        }
        if (document.pageCount <= 0) {
            return IosPdfRasterResult.Failed(
                message = "MuPDF could not read pages from this document",
                openError = SharedMobilePdfOpenError.INVALID_DOCUMENT,
            )
        }
        val index = pageIndex.coerceIn(0, document.pageCount - 1)
        val size = memScoped {
            val width = alloc<FloatVar>()
            val height = alloc<FloatVar>()
            if (rmu_page_size(document.handle, index, width.ptr, height.ptr) == RMU_OK) {
                width.value to height.value
            } else {
                null
            }
        } ?: return IosPdfRasterResult.Failed(
            message = "MuPDF could not load page ${index + 1}",
            pageCount = document.pageCount,
        )
        return IosPdfRasterResult.Opened(
            block(
                MupdfRasterPage(
                    document = document.handle,
                    pageIndex = index,
                    pageCount = document.pageCount,
                    width = size.first.coerceAtLeast(1f),
                    height = size.second.coerceAtLeast(1f),
                )
            )
        )
    }

    private class MupdfRasterPage(
        private val document: CPointer<rmu_document>,
        override val pageIndex: Int,
        override val pageCount: Int,
        override val width: Float,
        override val height: Float,
    ) : IosPdfRasterPage {
        override fun render(
            pixels: CPointer<UByteVar>,
            stride: Int,
            fullWidth: Int,
            fullHeight: Int,
            left: Int,
            top: Int,
            width: Int,
            height: Int,
        ): Boolean = rmu_render_bgra(
            document, pageIndex, fullWidth, fullHeight, left, top, width, height, pixels, stride,
        ) == RMU_OK

        override fun imageRects(fullWidth: Int, fullHeight: Int): List<IosPdfImageRect> {
            val rects = IntArray(MaxImageRects * 4)
            val count = rects.usePinned { pinned ->
                rmu_image_rects(document, pageIndex, fullWidth, fullHeight, pinned.addressOf(0), MaxImageRects)
            }
            return List(count.coerceAtLeast(0)) { index ->
                IosPdfImageRect(
                    left = rects[index * 4],
                    top = rects[index * 4 + 1],
                    right = rects[index * 4 + 2],
                    bottom = rects[index * 4 + 3],
                )
            }
        }

        override fun documentAspectRatios(): FloatArray {
            val widths = FloatArray(pageCount)
            val heights = FloatArray(pageCount)
            widths.usePinned { pinnedWidths ->
                heights.usePinned { pinnedHeights ->
                    rmu_page_sizes(document, pinnedWidths.addressOf(0), pinnedHeights.addressOf(0), pageCount)
                }
            }
            return FloatArray(pageCount) { index ->
                if (widths[index] > 0f && heights[index] > 0f) {
                    (widths[index] / heights[index]).coerceIn(0.1f, 10f)
                } else {
                    0f
                }
            }
        }
    }
}
