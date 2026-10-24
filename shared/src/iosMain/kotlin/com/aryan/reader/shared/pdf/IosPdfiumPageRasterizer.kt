@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.aryan.reader.shared.pdf

import com.aryan.reader.shared.pdfium.c.FPDFBitmap_BGRA
import com.aryan.reader.shared.pdfium.c.FPDFBitmap_CreateEx
import com.aryan.reader.shared.pdfium.c.FPDFBitmap_Destroy
import com.aryan.reader.shared.pdfium.c.FPDFBitmap_FillRect
import com.aryan.reader.shared.pdfium.c.FPDF_CloseDocument
import com.aryan.reader.shared.pdfium.c.FPDF_ClosePage
import com.aryan.reader.shared.pdfium.c.FPDF_DOCUMENT
import com.aryan.reader.shared.pdfium.c.FPDF_GetLastError
import com.aryan.reader.shared.pdfium.c.FPDF_GetPageCount
import com.aryan.reader.shared.pdfium.c.FPDF_GetPageHeightF
import com.aryan.reader.shared.pdfium.c.FPDF_GetPageSizeByIndex
import com.aryan.reader.shared.pdfium.c.FPDF_GetPageWidthF
import com.aryan.reader.shared.pdfium.c.FPDF_LoadDocument
import com.aryan.reader.shared.pdfium.c.FPDF_LoadPage
import com.aryan.reader.shared.pdfium.c.FPDF_PAGE
import com.aryan.reader.shared.pdfium.c.FPDF_RenderPageBitmap
import com.aryan.reader.shared.ui.IosPdfImageRect
import com.aryan.reader.shared.ui.SharedMobilePdfOpenError
import com.aryan.reader.shared.ui.extractIosPdfImageRects
import com.aryan.reader.shared.ui.sharedMobilePdfOpenErrorForPdfiumCode
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value

/** PDFium, as the reader used it before MuPDF: the file is opened for each call. */
internal object IosPdfiumPageRasterizer : IosPdfPageRasterizer {
    override suspend fun <T> withPage(
        path: String,
        password: String?,
        pageIndex: Int,
        block: (IosPdfRasterPage) -> T,
    ): IosPdfRasterResult<T> = IosPdfiumRuntime.withPdfium {
        val document = FPDF_LoadDocument(path, password)
            ?: return@withPdfium IosPdfRasterResult.Failed(
                message = "Pdfium could not open this PDF",
                openError = sharedMobilePdfOpenErrorForPdfiumCode(FPDF_GetLastError().toLong()),
            )
        try {
            val pageCount = FPDF_GetPageCount(document)
            if (pageCount <= 0) {
                return@withPdfium IosPdfRasterResult.Failed(
                    message = "Pdfium could not read pages from this document",
                    openError = SharedMobilePdfOpenError.INVALID_DOCUMENT,
                )
            }
            val index = pageIndex.coerceIn(0, pageCount - 1)
            val page = FPDF_LoadPage(document, index)
                ?: return@withPdfium IosPdfRasterResult.Failed(
                    message = "Pdfium could not load page ${index + 1}",
                    pageCount = pageCount,
                )
            try {
                IosPdfRasterResult.Opened(block(PdfiumRasterPage(document, page, index, pageCount)))
            } finally {
                FPDF_ClosePage(page)
            }
        } finally {
            FPDF_CloseDocument(document)
        }
    }
}

private class PdfiumRasterPage(
    private val document: FPDF_DOCUMENT,
    private val page: FPDF_PAGE,
    override val pageIndex: Int,
    override val pageCount: Int,
) : IosPdfRasterPage {
    override val width: Float = FPDF_GetPageWidthF(page).coerceAtLeast(1f)
    override val height: Float = FPDF_GetPageHeightF(page).coerceAtLeast(1f)

    override fun render(
        pixels: CPointer<UByteVar>,
        stride: Int,
        fullWidth: Int,
        fullHeight: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): Boolean {
        // PDFium draws into the caller's rows; nothing is copied afterwards.
        val bitmap = FPDFBitmap_CreateEx(width, height, FPDFBitmap_BGRA, pixels, stride) ?: return false
        try {
            FPDFBitmap_FillRect(bitmap, 0, 0, width, height, 0xFFFFFFFFu)
            FPDF_RenderPageBitmap(bitmap, page, -left, -top, fullWidth, fullHeight, 0, 0)
            return true
        } finally {
            FPDFBitmap_Destroy(bitmap)
        }
    }

    override fun imageRects(fullWidth: Int, fullHeight: Int): List<IosPdfImageRect> =
        extractIosPdfImageRects(page, width, height, fullWidth, fullHeight)

    override fun documentAspectRatios(): FloatArray = memScoped {
        val pageWidth = alloc<DoubleVar>()
        val pageHeight = alloc<DoubleVar>()
        FloatArray(pageCount) { index ->
            val read = FPDF_GetPageSizeByIndex(document, index, pageWidth.ptr, pageHeight.ptr) != 0
            if (read && pageWidth.value > 0.0 && pageHeight.value > 0.0) {
                (pageWidth.value / pageHeight.value).toFloat().coerceIn(0.1f, 10f)
            } else {
                0f
            }
        }
    }
}
