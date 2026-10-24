@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.aryan.reader.shared.pdf

import com.aryan.reader.shared.pdfium.c.FPDF_CloseDocument
import com.aryan.reader.shared.pdfium.c.FPDF_DOCUMENT
import com.aryan.reader.shared.pdfium.c.FPDF_InitLibrary
import com.aryan.reader.shared.pdfium.c.FPDF_LoadDocument
import platform.Foundation.NSLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * PDFium has process-wide initialization state. Keep initialization and document operations that
 * can be started concurrently by Compose (page rendering and outline loading) behind one lock.
 */
internal object IosPdfiumRuntime {
    val mutex = Mutex()
    private val initializationLock = NSLock()

    private var initialized = false

    fun ensureInitialized() {
        initializationLock.lock()
        try {
            if (!initialized) {
                FPDF_InitLibrary()
                initialized = true
            }
        } finally {
            initializationLock.unlock()
        }
    }

    /**
     * Runs exclusive PDFium work on a background dispatcher, mirroring Android where
     * `PdfiumCoreKt(Dispatchers.Default)` keeps rasterization off the UI thread while the
     * runtime mutex serializes access. Compose call sites (LaunchedEffect) run on Main, so
     * every direct `FPDF_*` caller must go through this helper.
     */
    suspend fun <T> withPdfium(block: suspend () -> T): T = withContext(Dispatchers.Default) {
        mutex.withLock {
            ensureInitialized()
            block()
        }
    }
}

/**
 * PDFium documents kept open between calls, at most two (books side by side).
 *
 * Opening a PDF parses its cross-reference table and then, page by page, its
 * fonts all over again; the reader used to do that for every page render, text
 * layer and annotation read. Call everything here while holding
 * [IosPdfiumRuntime.mutex]. A document that leaves the cache (another book, a
 * changed file or password) is closed once its last user releases it.
 *
 * Only readers share documents. The annotation exporter edits the document it
 * saves, so it keeps opening its own.
 */
internal object IosPdfiumDocuments {
    private const val MaxOpenDocuments = 2

    class Handle internal constructor(
        val document: FPDF_DOCUMENT,
        internal val revision: String,
        internal val password: String?,
    ) {
        internal var users = 0
        internal var retired = false
    }

    private val documents = LinkedHashMap<String, Handle>()

    /**
     * The open document for [path], opened now if needed, or null when PDFium
     * cannot open it (FPDF_GetLastError says why). Pair with [release].
     */
    fun acquire(path: String, password: String?): Handle? {
        IosPdfiumRuntime.ensureInitialized()
        val revision = iosPdfFileRevision(path)
        val cached = documents.remove(path)
        if (cached != null) {
            if (cached.revision == revision && cached.password.orEmpty() == password.orEmpty()) {
                // Re-inserted last: the map stays in least recently used order.
                documents[path] = cached
                cached.users++
                return cached
            }
            retire(cached)
        }
        val document = FPDF_LoadDocument(path, password) ?: return null
        val handle = Handle(document, revision, password)
        handle.users = 1
        documents[path] = handle
        while (documents.size > MaxOpenDocuments) {
            documents.remove(documents.keys.first())?.let { retire(it) }
        }
        return handle
    }

    fun release(handle: Handle) {
        handle.users--
        if (handle.retired && handle.users <= 0) FPDF_CloseDocument(handle.document)
    }

    private fun retire(handle: Handle) {
        handle.retired = true
        if (handle.users <= 0) FPDF_CloseDocument(handle.document)
    }
}
