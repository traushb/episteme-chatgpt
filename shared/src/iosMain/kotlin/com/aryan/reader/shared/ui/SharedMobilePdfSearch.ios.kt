@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.aryan.reader.shared.ui

import com.aryan.reader.shared.BookItem
import com.aryan.reader.shared.pdf.IosPdfOcrLanguagePreferences
import com.aryan.reader.shared.pdf.IosPdfOcrTextPage
import com.aryan.reader.shared.pdf.boundsForRange
import com.aryan.reader.shared.pdf.buildIosPdfOcrTextPage
import com.aryan.reader.shared.pdf.IosPdfOcrPageCache
import com.aryan.reader.shared.pdf.SharedPdfSearchResult
import com.aryan.reader.shared.pdf.IosPdfiumDocuments
import com.aryan.reader.shared.pdf.IosPdfiumRuntime
import com.aryan.reader.shared.pdfium.c.FPDF_ClosePage
import com.aryan.reader.shared.pdfium.c.FPDF_GetPageCount
import com.aryan.reader.shared.pdfium.c.FPDF_DOCUMENT
import com.aryan.reader.shared.pdfium.c.FPDF_LoadPage
import com.aryan.reader.shared.pdfium.c.FPDFText_LoadPage
import com.aryan.reader.shared.pdfium.c.FPDFText_ClosePage
import com.aryan.reader.shared.pdfium.c.FPDFText_CountChars
import com.aryan.reader.shared.pdfium.c.FPDFText_GetText
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import com.aryan.reader.shared.ios.resolveIosReadablePath

private data class IosPdfSearchCacheKey(
    val path: String,
    val fileSize: Long,
    val modifiedAt: Long,
    val passwordHash: Int,
    val languages: List<String>,
)

private data class IosPdfSearchCacheEntry(
    val key: IosPdfSearchCacheKey,
    val index: com.aryan.reader.shared.pdf.SharedPdfSearchIndex,
    val ocrPages: Map<Int, IosPdfOcrTextPage>,
)

/**
 * Search requests can overlap (for example, a debounced query and highlight-all). Keep the
 * bounded one-document cache as one atomic entry and serialize index construction so callers
 * never observe a key/index/OCR-pages mismatch or duplicate a full-document scan.
 */
private val iosPdfSearchCacheMutex = Mutex()
private var iosPdfSearchCacheEntry: IosPdfSearchCacheEntry? = null

internal actual suspend fun searchSharedMobilePdf(
    book: BookItem,
    query: String,
    password: String?,
): List<SharedPdfSearchResult> = withContext(Dispatchers.Default) {
    iosPdfSearchCacheMutex.withLock {
        searchIosPdfLocked(book, query, password)
    }
}

private suspend fun searchIosPdfLocked(
    book: BookItem,
    query: String,
    password: String?,
): List<SharedPdfSearchResult> {
    val resolvedPath = book.path.resolveIosReadablePath() ?: return emptyList()
    if (!NSFileManager.defaultManager.fileExistsAtPath(resolvedPath)) {
        return emptyList()
    }

    val cacheKey = IosPdfSearchCacheKey(
        path = resolvedPath,
        fileSize = book.fileSize,
        modifiedAt = book.fileContentModifiedTimestamp,
        passwordHash = password?.hashCode() ?: 0,
        languages = IosPdfOcrLanguagePreferences.languages,
    )
    iosPdfSearchCacheEntry?.takeIf { it.key == cacheKey }?.let { cached ->
        return cached.index.search(query).withIosPdfOcrBounds(cached.ocrPages)
    }

    // The document stays open across pages, but the PDFium lock is taken per
    // page: indexing a long book used to hold it for seconds, stalling text
    // selection (which waits for it on the main thread) and page text layers.
    val handle = IosPdfiumRuntime.withPdfium { IosPdfiumDocuments.acquire(resolvedPath, password) }
        ?: return emptyList()
    val indexed = try {
        val pageCount = IosPdfiumRuntime.withPdfium { FPDF_GetPageCount(handle.document) }
        val index = com.aryan.reader.shared.pdf.SharedPdfSearchIndex(pageCount)
        val ocrCandidates = mutableListOf<Int>()
        for (pageIndex in 0 until pageCount) {
            currentCoroutineContext().ensureActive()
            val pageText = IosPdfiumRuntime.withPdfium { readIosPdfPageText(handle.document, pageIndex) }
            index.putPage(pageIndex, pageText.orEmpty())
            if (pageText.isNullOrBlank()) ocrCandidates += pageIndex
        }
        index to ocrCandidates
    } finally {
        withContext(NonCancellable) {
            IosPdfiumRuntime.withPdfium { IosPdfiumDocuments.release(handle) }
        }
    }
    val (index, ocrCandidates) = indexed

    val ocrPages = buildMap {
        ocrCandidates.forEach { pageIndex ->
            currentCoroutineContext().ensureActive()
            val words = IosPdfOcrPageCache.getOrRecognize(
                resolvedPath,
                pageIndex,
                password,
                IosPdfOcrLanguagePreferences.languages,
            )
            val page = buildIosPdfOcrTextPage(words)
            if (page.text.isNotBlank()) {
                index.putPage(pageIndex, page.text)
                put(pageIndex, page)
            }
        }
    }
    iosPdfSearchCacheEntry = IosPdfSearchCacheEntry(cacheKey, index, ocrPages)
    return index.search(query).withIosPdfOcrBounds(ocrPages)
}

/** The page's text layer; null when PDFium cannot load the page or its text. Caller holds the PDFium lock. */
private fun readIosPdfPageText(document: FPDF_DOCUMENT, pageIndex: Int): String? {
    val page = FPDF_LoadPage(document, pageIndex) ?: return null
    try {
        val textPage = FPDFText_LoadPage(page) ?: return null
        try {
            val charCount = FPDFText_CountChars(textPage)
            if (charCount <= 0) return ""
            return memScoped {
                val buffer = allocArray<UShortVar>(charCount + 1)
                val written = FPDFText_GetText(textPage, 0, charCount, buffer)
                if (written > 0) {
                    CharArray(written) { index -> buffer[index].toInt().toChar() }
                        .concatToString()
                        .trimEnd('\u0000')
                } else {
                    ""
                }
            }
        } finally {
            FPDFText_ClosePage(textPage)
        }
    } finally {
        FPDF_ClosePage(page)
    }
}

private fun List<SharedPdfSearchResult>.withIosPdfOcrBounds(
    ocrPages: Map<Int, IosPdfOcrTextPage>,
): List<SharedPdfSearchResult> = map { result ->
    val page = ocrPages[result.pageIndex]
    if (page == null) result else result.copy(
        boundsList = page.boundsForRange(result.matchIndex, result.matchLength),
    )
}
