#ifndef READER_MUPDF_BRIDGE_H
#define READER_MUPDF_BRIDGE_H

/*
 * The part of MuPDF the iOS reader uses to draw PDF pages.
 *
 * Every call is safe from any thread: calls share one MuPDF context and run
 * one at a time. Errors come back as RMU_ERROR_* codes; MuPDF's own error
 * handling never leaves this bridge.
 */

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct rmu_document rmu_document;

/* Plain int macros, so Kotlin sees them as Int constants. */
#define RMU_OK 0
#define RMU_ERROR_UNKNOWN 1
#define RMU_ERROR_FILE 2
#define RMU_ERROR_FORMAT 3
#define RMU_ERROR_PASSWORD 4
#define RMU_ERROR_MEMORY 5
#define RMU_ERROR_ARGUMENT 6
#define RMU_ERROR_NOT_INITIALIZED 7

/*
 * Creates the shared context once; later calls do nothing. cache_limit_bytes
 * caps MuPDF's cache of decoded images and fonts.
 */
int rmu_init(size_t cache_limit_bytes);

/* Opens a document, or returns NULL and sets *error. password may be NULL. */
rmu_document *rmu_open(const char *path, const char *password, int *error);

void rmu_close(rmu_document *document);

int rmu_page_count(const rmu_document *document);

/* Page size in points as displayed: the crop box after /Rotate. */
int rmu_page_size(rmu_document *document, int page_index, float *width, float *height);

/*
 * Fills widths[i] and heights[i] for pages 0..count-1 without loading their
 * contents. A page whose size cannot be read gets 0 x 0.
 */
int rmu_page_sizes(rmu_document *document, float *widths, float *heights, int count);

/*
 * Draws the part [left, left + width) x [top, top + height) of the page
 * scaled to full_width x full_height pixels into bgra: premultiplied BGRA,
 * stride bytes per row, opaque over white. Annotations and form fields are
 * not drawn; the reader draws its own.
 */
int rmu_render_bgra(
    rmu_document *document,
    int page_index,
    int full_width,
    int full_height,
    int left,
    int top,
    int width,
    int height,
    unsigned char *bgra,
    int stride
);

/*
 * Pixel rectangles (left, top, right, bottom) of the images drawn on the
 * page, in a full_width x full_height page bitmap. Writes at most max_rects
 * rectangles (4 ints each) and returns how many it wrote, or a negative
 * RMU_ERROR_* code.
 */
int rmu_image_rects(
    rmu_document *document,
    int page_index,
    int full_width,
    int full_height,
    int *rects,
    int max_rects
);

/* Drops everything MuPDF has cached, for memory warnings. */
void rmu_empty_cache(void);

#ifdef __cplusplus
}
#endif

#endif
