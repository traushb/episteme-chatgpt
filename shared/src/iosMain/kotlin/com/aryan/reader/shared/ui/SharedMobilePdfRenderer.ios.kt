@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.aryan.reader.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.aryan.reader.shared.BookItem
import com.aryan.reader.shared.pdf.IosPdfRasterPage
import com.aryan.reader.shared.pdf.IosPdfRasterResult
import com.aryan.reader.shared.pdf.IosPdfRenderEngine
import com.aryan.reader.shared.pdf.IosPdfRenderEnginePreference
import com.aryan.reader.shared.pdf.PdfPageBounds
import com.aryan.reader.shared.pdf.PdfReverseColorMode
import com.aryan.reader.shared.pdf.PdfZoomTileRequest
import com.aryan.reader.shared.pdf.PDF_ZOOM_RENDER_SETTLE_MILLIS
import com.aryan.reader.shared.pdf.PDF_ZOOM_TILE_CACHE_MAX_BYTES
import com.aryan.reader.shared.pdf.PdfTileLruCache
import com.aryan.reader.shared.pdf.planPdfZoomTiles
import com.aryan.reader.shared.pdf.IosPdfOcrLanguagePreferences
import com.aryan.reader.shared.pdf.IosPdfOcrPageCache
import com.aryan.reader.shared.pdf.renderBitmap
import com.aryan.reader.shared.pdf.valueOrNull
import com.aryan.reader.shared.ios.IosPdfPerformanceMetrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import kotlin.coroutines.coroutineContext
import com.aryan.reader.shared.ios.resolveIosReadablePath

@Composable
internal actual fun rememberSharedMobilePdfPageRender(
    book: BookItem,
    pageIndex: Int,
    zoomScale: Float,
    password: String?,
    reverseColorMode: PdfReverseColorMode,
    preserveImageColors: Boolean,
): SharedMobilePdfPageRender {
    val engine = IosPdfRenderEnginePreference.engine
    // Start at the page's final size: a cached render, or a placeholder with the
    // page's own aspect ratio (see IosPdfPageAspectCache).
    var render by remember(book.path, pageIndex, password, reverseColorMode, preserveImageColors, engine) {
        mutableStateOf(
            IosPdfPageCache.get(book.path, pageIndex, password, reverseColorMode, preserveImageColors, engine)
                ?: SharedMobilePdfPageRender(aspectRatio = IosPdfPageAspectCache.aspectRatio(book.path, pageIndex))
        )
    }

    LaunchedEffect(book.path, pageIndex, password, reverseColorMode, preserveImageColors, engine) {
        if (render.bitmap != null) return@LaunchedEffect
        val cached = IosPdfPageCache.get(book.path, pageIndex, password, reverseColorMode, preserveImageColors, engine)
        if (cached != null) {
            render = cached
            return@LaunchedEffect
        }
        val rendered = IosPdfPageRenderer.render(engine, book.path, pageIndex, password, reverseColorMode, preserveImageColors)
        if (rendered.bitmap != null) {
            IosPdfPageCache.put(
                book.path,
                pageIndex,
                password,
                reverseColorMode,
                preserveImageColors,
                engine,
                rendered,
            )
        }
        render = rendered
    }

    return render
}

@Composable
internal actual fun rememberSharedMobilePdfPageThumbnail(
    book: BookItem,
    pageIndex: Int,
    password: String?,
    reverseColorMode: PdfReverseColorMode,
    preserveImageColors: Boolean,
): SharedMobilePdfPageThumbnail {
    val engine = IosPdfRenderEnginePreference.engine
    var thumbnail by remember(book.path, pageIndex, password, reverseColorMode, preserveImageColors, engine) {
        mutableStateOf<SharedMobilePdfPageThumbnail?>(
            IosPdfThumbnailCache.get(book.path, pageIndex, password, reverseColorMode, preserveImageColors, engine)
        )
    }

    LaunchedEffect(book.path, pageIndex, password, reverseColorMode, preserveImageColors, engine) {
        if (thumbnail == null) {
            thumbnail = IosPdfThumbnailCache.get(book.path, pageIndex, password, reverseColorMode, preserveImageColors, engine)
                ?: IosPdfPageRenderer.renderThumbnail(engine, book.path, pageIndex, password, reverseColorMode, preserveImageColors)?.also { rendered ->
                    IosPdfThumbnailCache.put(book.path, pageIndex, password, reverseColorMode, preserveImageColors, engine, rendered)
                }
                ?: SharedMobilePdfPageThumbnail()
        }
    }

    return thumbnail ?: SharedMobilePdfPageThumbnail()
}

@Composable
internal actual fun rememberSharedMobilePdfTileRenders(
    book: BookItem,
    pageIndex: Int,
    pageAspectRatio: Float,
    zoomScale: Float,
    visibleBounds: PdfPageBounds?,
    password: String?,
    reverseColorMode: PdfReverseColorMode,
    preserveImageColors: Boolean,
): List<SharedMobilePdfTileRender> {
    val engine = IosPdfRenderEnginePreference.engine
    var settledZoomScale by remember(book.path, pageIndex, password, reverseColorMode, preserveImageColors) { mutableStateOf(zoomScale) }
    var zoomIsSettling by remember(book.path, pageIndex, password, reverseColorMode, preserveImageColors) { mutableStateOf(false) }
    LaunchedEffect(zoomScale) {
        zoomIsSettling = true
        delay(PDF_ZOOM_RENDER_SETTLE_MILLIS)
        settledZoomScale = zoomScale
        zoomIsSettling = false
    }
    val requests = remember(pageAspectRatio, settledZoomScale, visibleBounds) {
        visibleBounds?.let { planPdfZoomTiles(pageAspectRatio, settledZoomScale, it) }.orEmpty()
    }
    var tiles by remember(book.path, pageIndex, password, reverseColorMode, preserveImageColors, engine) { mutableStateOf<List<SharedMobilePdfTileRender>>(emptyList()) }
    LaunchedEffect(book.path, pageIndex, requests, password, zoomIsSettling, reverseColorMode, preserveImageColors, engine) {
        if (zoomIsSettling) return@LaunchedEffect
        if (requests.isEmpty()) {
            // Distinguishes "nothing to do" (unzoomed) from a starved planner
            // (zoomed but no visible bounds): the latter means tiles can never
            // crisp, so it is worth one diagnostic line in Xcode logs.
            if (settledZoomScale > 1.01f && visibleBounds == null) {
                println("[PdfTiles] page=$pageIndex starved scale=$settledZoomScale visibleBounds=null")
            }
            tiles = emptyList()
            return@LaunchedEffect
        }
        val fetchStartedAt = TimeSource.Monotonic.markNow()
        val cached = IosPdfTileCache.get(book, pageIndex, password, reverseColorMode, preserveImageColors, engine, requests)
        val cachedIds = cached.mapTo(mutableSetOf()) { it.request.id }
        val missing = requests.filterNot { it.id in cachedIds }
        if (missing.isEmpty()) {
            tiles = cached
            return@LaunchedEffect
        }
        val rendered = IosPdfPageRenderer.renderTiles(engine, book.path, pageIndex, missing, password, reverseColorMode, preserveImageColors)
        coroutineContext.ensureActive()
        IosPdfTileCache.put(book, pageIndex, password, reverseColorMode, preserveImageColors, engine, rendered)
        tiles = cached + rendered
        println(
            "[PdfTiles] page=$pageIndex scale=$settledZoomScale engine=${engine.id} " +
                "requests=${requests.size} cached=${cached.size} rendered=${rendered.size} " +
                "fetchMs=${fetchStartedAt.elapsedNow().inWholeMilliseconds}"
        )
    }
    return tiles
}

/**
 * Byte-budgeted LRU for full-page renders.
 *
 * Without it every page that scrolls out of the LazyColumn/Pager window is
 * disposed and re-rasterized when it returns, which shows up as a white flash
 * on each scroll and page turn - twice as often in split view where both
 * panes recycle pages. Caching rendered bitmaps keeps returning pages instant.
 */
private object IosPdfPageCache {
    // A 2048px-tall BGRA page is roughly 8-16MB; 6 entries cover both panes'
    // working sets while staying well under iOS memory pressure limits.
    private const val MaxEntries = 6
    private val entries = LinkedHashMap<String, SharedMobilePdfPageRender>()
    private val lock = NSLock()

    fun get(
        path: String?,
        pageIndex: Int,
        password: String?,
        reverseColorMode: PdfReverseColorMode,
        preserveImageColors: Boolean,
        engine: IosPdfRenderEngine,
    ): SharedMobilePdfPageRender? = lock.withLock {
        val entryKey = key(path, pageIndex, password, reverseColorMode, preserveImageColors, engine)
        entries.remove(entryKey)?.also { cached -> entries[entryKey] = cached }
    }

    fun put(
        path: String?,
        pageIndex: Int,
        password: String?,
        reverseColorMode: PdfReverseColorMode,
        preserveImageColors: Boolean,
        engine: IosPdfRenderEngine,
        render: SharedMobilePdfPageRender,
    ) {
        lock.withLock {
            val entryKey = key(path, pageIndex, password, reverseColorMode, preserveImageColors, engine)
            entries.remove(entryKey)
            entries[entryKey] = render
            while (entries.size > MaxEntries) {
                entries.remove(entries.keys.first())
            }
        }
    }

    private fun key(
        path: String?,
        pageIndex: Int,
        password: String?,
        reverseColorMode: PdfReverseColorMode,
        preserveImageColors: Boolean,
        engine: IosPdfRenderEngine,
    ): String = "${path.orEmpty()}|$pageIndex|${password.orEmpty()}|${reverseColorMode.id}|$preserveImageColors|${engine.id}"
}

/**
 * Page aspect ratios per document, read from the page tree on the first render.
 *
 * The vertical reader lays a page out before its bitmap exists. With a generic
 * placeholder ratio the page changed height once its render landed, and a page
 * entering above the list's anchor then pushed everything below it back down:
 * slow upward scrolls were undone on every page and only a hard fling got past.
 */
private object IosPdfPageAspectCache {
    private const val MaxDocuments = 8
    private val documents = LinkedHashMap<String, FloatArray>()
    private val lock = NSLock()

    /** The page's ratio, else the document's first known one, else the default. */
    fun aspectRatio(path: String?, pageIndex: Int): Float = lock.withLock {
        val aspects = documents[path.orEmpty()] ?: return@withLock DefaultSharedMobilePdfPageAspectRatio
        aspects.getOrNull(pageIndex)?.takeIf { it > 0f }
            ?: aspects.firstOrNull { it > 0f }
            ?: DefaultSharedMobilePdfPageAspectRatio
    }

    fun covers(path: String?, pageCount: Int): Boolean = lock.withLock {
        documents[path.orEmpty()]?.size == pageCount
    }

    fun put(path: String?, aspects: FloatArray) {
        lock.withLock {
            val key = path.orEmpty()
            documents.remove(key)
            documents[key] = aspects
            while (documents.size > MaxDocuments) {
                documents.remove(documents.keys.first())
            }
        }
    }

    /** A finished render is authoritative for its page (the file may have changed). */
    fun update(path: String?, pageIndex: Int, aspectRatio: Float) {
        lock.withLock {
            val aspects = documents[path.orEmpty()] ?: return@withLock
            if (pageIndex in aspects.indices) aspects[pageIndex] = aspectRatio
        }
    }
}

private object IosPdfThumbnailCache {
    private const val MaxEntries = 96
    private val entries = LinkedHashMap<String, SharedMobilePdfPageThumbnail>()
    private val lock = NSLock()

    fun get(
        path: String?, pageIndex: Int, password: String?, reverseColorMode: PdfReverseColorMode,
        preserveImageColors: Boolean, engine: IosPdfRenderEngine,
    ): SharedMobilePdfPageThumbnail? = lock.withLock {
        entries[key(path, pageIndex, password, reverseColorMode, preserveImageColors, engine)]
    }

    fun put(
        path: String?, pageIndex: Int, password: String?, reverseColorMode: PdfReverseColorMode,
        preserveImageColors: Boolean, engine: IosPdfRenderEngine, thumbnail: SharedMobilePdfPageThumbnail,
    ) {
        lock.withLock {
            val key = key(path, pageIndex, password, reverseColorMode, preserveImageColors, engine)
            entries[key] = thumbnail
            while (entries.size > MaxEntries) {
                entries.remove(entries.keys.first())
            }
        }
    }

    private fun key(
        path: String?, pageIndex: Int, password: String?, reverseColorMode: PdfReverseColorMode,
        preserveImageColors: Boolean, engine: IosPdfRenderEngine,
    ): String = "${path.orEmpty()}|$pageIndex|${password.orEmpty()}|${reverseColorMode.id}|$preserveImageColors|${engine.id}"
}

private object IosPdfTileCache {
    private val entries = PdfTileLruCache<SharedMobilePdfTileRender>(PDF_ZOOM_TILE_CACHE_MAX_BYTES)
    private val lock = NSLock()

    fun get(
        book: BookItem,
        pageIndex: Int,
        password: String?,
        reverseColorMode: PdfReverseColorMode,
        preserveImageColors: Boolean,
        engine: IosPdfRenderEngine,
        requests: List<PdfZoomTileRequest>,
    ): List<SharedMobilePdfTileRender> = lock.withLock {
        requests.mapNotNull { request ->
            val key = key(book, pageIndex, password, reverseColorMode, preserveImageColors, engine, request)
            entries.get(key)
        }
    }

    fun put(
        book: BookItem,
        pageIndex: Int,
        password: String?,
        reverseColorMode: PdfReverseColorMode,
        preserveImageColors: Boolean,
        engine: IosPdfRenderEngine,
        renders: List<SharedMobilePdfTileRender>,
    ) {
        lock.withLock {
            renders.forEach { render ->
                val key = key(book, pageIndex, password, reverseColorMode, preserveImageColors, engine, render.request)
                val bytes = render.request.widthPx.toLong() * render.request.heightPx * 4L
                entries.put(key, render, bytes)
            }
        }
    }

    private fun key(
        book: BookItem,
        pageIndex: Int,
        password: String?,
        reverseColorMode: PdfReverseColorMode,
        preserveImageColors: Boolean,
        engine: IosPdfRenderEngine,
        request: PdfZoomTileRequest,
    ) =
        "${book.path.orEmpty()}|${book.fileContentModifiedTimestamp}|$pageIndex|${password.orEmpty()}|" +
            "${reverseColorMode.id}|$preserveImageColors|${engine.id}|" +
            "${request.fullWidthPx}x${request.fullHeightPx}|${request.id}"
}

/** Page, tile and thumbnail renders through the engine chosen in [IosPdfRenderEnginePreference]. */
private object IosPdfPageRenderer {
    suspend fun renderTiles(
        engine: IosPdfRenderEngine,
        path: String?,
        pageIndex: Int,
        requests: List<PdfZoomTileRequest>,
        password: String?,
        reverseColorMode: PdfReverseColorMode = PdfReverseColorMode.RGB,
        preserveImageColors: Boolean = false,
    ): List<SharedMobilePdfTileRender> {
        val resolvedPath = path.resolveIosReadablePath() ?: return emptyList()
        if (!NSFileManager.defaultManager.fileExistsAtPath(resolvedPath)) return emptyList()
        return engine.rasterizer.withPage(resolvedPath, password, pageIndex) { page ->
            val first = requests.firstOrNull()
            val imageRects = if (preserveImageColors && first != null) {
                page.imageRects(first.fullWidthPx, first.fullHeightPx)
            } else {
                emptyList()
            }
            requests.mapNotNull { request -> renderTile(page, request, reverseColorMode, imageRects) }
        }.valueOrNull().orEmpty()
    }

    private fun renderTile(
        page: IosPdfRasterPage,
        request: PdfZoomTileRequest,
        reverseColorMode: PdfReverseColorMode,
        imageRects: List<IosPdfImageRect>,
    ): SharedMobilePdfTileRender? {
        val renderStartedAt = TimeSource.Monotonic.markNow()
        val transform = if (iosPdfBitmapNeedsTransform(reverseColorMode, imageRects)) {
            { bytes: ByteArray, stride: Int ->
                transformIosPdfBitmapBytes(
                    bytes,
                    stride,
                    request.widthPx,
                    request.heightPx,
                    reverseColorMode,
                    protectedRects = imageRects,
                    targetWidth = request.fullWidthPx,
                    targetHeight = request.fullHeightPx,
                    targetOriginX = request.leftPx,
                    targetOriginY = request.topPx,
                )
            }
        } else {
            null
        }
        val bitmap = page.renderBitmap(
            fullWidth = request.fullWidthPx,
            fullHeight = request.fullHeightPx,
            left = request.leftPx,
            top = request.topPx,
            width = request.widthPx,
            height = request.heightPx,
            transform = transform,
        ) ?: return null
        val rendered = SharedMobilePdfTileRender(
            request = request,
            bitmap = bitmap.asComposeImageBitmap(),
            rasterizedReverseColorMode = reverseColorMode.takeIf {
                it != PdfReverseColorMode.RGB || imageRects.isNotEmpty()
            },
        )
        IosPdfPerformanceMetrics.recordRender(
            widthPx = request.widthPx,
            heightPx = request.heightPx,
            estimatedBytes = request.widthPx.toLong() * request.heightPx * 4L,
            durationMillis = renderStartedAt.elapsedNow().inWholeMilliseconds,
            tile = true,
        )
        return rendered
    }

    suspend fun render(
        engine: IosPdfRenderEngine,
        path: String?,
        pageIndex: Int,
        password: String?,
        reverseColorMode: PdfReverseColorMode = PdfReverseColorMode.RGB,
        preserveImageColors: Boolean = false,
    ): SharedMobilePdfPageRender {
        val resolvedPath = path.resolveIosReadablePath()
        if (resolvedPath.isNullOrBlank()) {
            return SharedMobilePdfPageRender(errorMessage = "PDF path is unavailable")
        }
        if (!NSFileManager.defaultManager.fileExistsAtPath(resolvedPath)) {
            return SharedMobilePdfPageRender(errorMessage = "PDF file is missing: $resolvedPath")
        }
        val result = engine.rasterizer.withPage(resolvedPath, password, pageIndex) { page ->
            if (!IosPdfPageAspectCache.covers(path, page.pageCount)) {
                IosPdfPageAspectCache.put(path, page.documentAspectRatios())
            }
            val aspectRatio = (page.width / page.height).coerceIn(0.1f, 10f)
            IosPdfPageAspectCache.update(path, page.pageIndex, aspectRatio)
            val renderStartedAt = TimeSource.Monotonic.markNow()
            val scale = MaxRenderedPageHeightPx / page.height
            val bitmapWidth = (page.width * scale).roundToInt().coerceAtLeast(1)
            val bitmapHeight = MaxRenderedPageHeightPx.roundToInt().coerceAtLeast(1)
            val imageRects = if (preserveImageColors) {
                page.imageRects(bitmapWidth, bitmapHeight)
            } else {
                emptyList()
            }
            val transform = if (iosPdfBitmapNeedsTransform(reverseColorMode, imageRects)) {
                { bytes: ByteArray, stride: Int ->
                    transformIosPdfBitmapBytes(
                        bytes,
                        stride,
                        bitmapWidth,
                        bitmapHeight,
                        reverseColorMode,
                        protectedRects = imageRects,
                        targetWidth = bitmapWidth,
                        targetHeight = bitmapHeight,
                    )
                }
            } else {
                null
            }
            val bitmap = page.renderBitmap(bitmapWidth, bitmapHeight, transform = transform)
                ?: return@withPage SharedMobilePdfPageRender(
                    pageCount = page.pageCount,
                    errorMessage = "${engine.label} could not draw page ${page.pageIndex + 1}",
                )
            val rendered = SharedMobilePdfPageRender(
                pageCount = page.pageCount,
                aspectRatio = aspectRatio,
                bitmap = bitmap.asComposeImageBitmap(),
                rasterizedReverseColorMode = reverseColorMode.takeIf {
                    it != PdfReverseColorMode.RGB || imageRects.isNotEmpty()
                },
            )
            IosPdfPerformanceMetrics.recordRender(
                widthPx = bitmapWidth,
                heightPx = bitmapHeight,
                estimatedBytes = bitmapWidth.toLong() * bitmapHeight * 4L,
                durationMillis = renderStartedAt.elapsedNow().inWholeMilliseconds,
                tile = false,
            )
            rendered
        }
        return when (result) {
            is IosPdfRasterResult.Opened -> result.value
            is IosPdfRasterResult.Failed -> SharedMobilePdfPageRender(
                pageCount = result.pageCount.coerceAtLeast(1),
                errorMessage = result.message,
                openError = result.openError,
            )
        }
    }

    suspend fun renderThumbnail(
        engine: IosPdfRenderEngine,
        path: String?,
        pageIndex: Int,
        password: String?,
        reverseColorMode: PdfReverseColorMode = PdfReverseColorMode.RGB,
        preserveImageColors: Boolean = false,
    ): SharedMobilePdfPageThumbnail? {
        val resolvedPath = path.resolveIosReadablePath() ?: return null
        if (!NSFileManager.defaultManager.fileExistsAtPath(resolvedPath)) return null
        return engine.rasterizer.withPage(resolvedPath, password, pageIndex) { page ->
            val aspectRatio = (page.width / page.height).coerceIn(0.1f, 10f)
            val renderStartedAt = TimeSource.Monotonic.markNow()
            val scale = ThumbnailTargetWidthPx / page.width
            val bitmapWidth = (page.width * scale).roundToInt().coerceAtLeast(1)
            val bitmapHeight = (page.height * scale).roundToInt().coerceAtLeast(1)
            val imageRects = if (preserveImageColors) {
                page.imageRects(bitmapWidth, bitmapHeight)
            } else {
                emptyList()
            }
            val transform = if (iosPdfBitmapNeedsTransform(reverseColorMode, imageRects)) {
                { bytes: ByteArray, stride: Int ->
                    transformIosPdfBitmapBytes(
                        bytes,
                        stride,
                        bitmapWidth,
                        bitmapHeight,
                        reverseColorMode,
                        protectedRects = imageRects,
                        targetWidth = bitmapWidth,
                        targetHeight = bitmapHeight,
                        forceRgbTransform = false,
                    )
                }
            } else {
                null
            }
            val bitmap = page.renderBitmap(bitmapWidth, bitmapHeight, transform = transform)
                ?: return@withPage null
            // Match full-page renders: RGB is applied via Compose color
            // filter unless image colors are preserved (protected rects
            // bake the negative). Nonlinear modes are always baked.
            val rasterizedMode = reverseColorMode.takeIf {
                it != PdfReverseColorMode.RGB || imageRects.isNotEmpty()
            }
            val rendered = SharedMobilePdfPageThumbnail(
                bitmap = bitmap.asComposeImageBitmap(),
                aspectRatio = aspectRatio,
                rasterizedReverseColorMode = rasterizedMode,
            )
            IosPdfPerformanceMetrics.recordRender(
                widthPx = bitmapWidth,
                heightPx = bitmapHeight,
                estimatedBytes = bitmapWidth.toLong() * bitmapHeight * 4L,
                durationMillis = renderStartedAt.elapsedNow().inWholeMilliseconds,
                tile = false,
            )
            rendered
        }.valueOrNull()
    }
}

internal actual suspend fun sharedMobilePdfOcrTextBounds(
    book: BookItem,
    pageIndex: Int,
    password: String?,
): List<PdfPageBounds> {
    return IosPdfOcrPageCache.getOrRecognize(
        path = book.path,
        pageIndex = pageIndex,
        password = password,
        languages = IosPdfOcrLanguagePreferences.languages,
    ).map { it.bounds }
}

private const val MaxRenderedPageHeightPx = 2048f
private const val ThumbnailTargetWidthPx = 240
private const val SearchPreviewRadius = 42
private const val MaxSearchResults = 500

private inline fun <T> NSLock.withLock(block: () -> T): T {
    lock()
    return try {
        block()
    } finally {
        unlock()
    }
}
