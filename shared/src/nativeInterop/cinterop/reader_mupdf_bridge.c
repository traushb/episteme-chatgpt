#include "reader_mupdf_bridge.h"

#include <mupdf/fitz.h>
#include <mupdf/pdf.h>

#include <limits.h>
#include <math.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

/*
 * MuPDF throws with longjmp (fz_try/fz_catch). Unwinding through Kotlin frames
 * is undefined, so each entry point catches here and returns a code. MuPDF
 * contexts are single-threaded; one mutex serializes every call.
 */

struct rmu_document {
    fz_document *document;
    int page_count;
};

static pthread_mutex_t rmu_lock = PTHREAD_MUTEX_INITIALIZER;
static fz_context *rmu_context;

static int rmu_error_from_caught(fz_context *ctx, int system_error) {
    switch (fz_caught(ctx)) {
    case FZ_ERROR_SYSTEM:
        return system_error;
    case FZ_ERROR_FORMAT:
    case FZ_ERROR_SYNTAX:
    case FZ_ERROR_UNSUPPORTED:
        return RMU_ERROR_FORMAT;
    case FZ_ERROR_LIMIT:
        return RMU_ERROR_MEMORY;
    case FZ_ERROR_ARGUMENT:
        return RMU_ERROR_ARGUMENT;
    default:
        return RMU_ERROR_UNKNOWN;
    }
}

/* Maps the displayed page (crop box after /Rotate) onto full_width x full_height pixels. */
static fz_matrix rmu_page_matrix(fz_rect bounds, int full_width, int full_height) {
    float page_width = bounds.x1 - bounds.x0;
    float page_height = bounds.y1 - bounds.y0;
    fz_matrix ctm = fz_translate(-bounds.x0, -bounds.y0);
    return fz_concat(ctm, fz_scale(full_width / page_width, full_height / page_height));
}

int rmu_init(size_t cache_limit_bytes) {
    int error = RMU_OK;
    pthread_mutex_lock(&rmu_lock);
    if (!rmu_context) {
        fz_context *ctx = fz_new_context(NULL, NULL, cache_limit_bytes);
        if (!ctx) {
            error = RMU_ERROR_MEMORY;
        } else {
            fz_var(error);
            fz_try(ctx) {
                fz_register_document_handlers(ctx);
            }
            fz_catch(ctx) {
                fz_report_error(ctx);
                error = RMU_ERROR_UNKNOWN;
            }
            if (error == RMU_OK) {
                rmu_context = ctx;
            } else {
                fz_drop_context(ctx);
            }
        }
    }
    pthread_mutex_unlock(&rmu_lock);
    return error;
}

rmu_document *rmu_open(const char *path, const char *password, int *error_out) {
    rmu_document *result = NULL;
    fz_document *document = NULL;
    int error = RMU_OK;

    pthread_mutex_lock(&rmu_lock);
    fz_context *ctx = rmu_context;
    if (!ctx) {
        error = RMU_ERROR_NOT_INITIALIZED;
    } else if (!path || !*path) {
        error = RMU_ERROR_ARGUMENT;
    } else {
        fz_var(result);
        fz_var(document);
        fz_var(error);
        fz_try(ctx) {
            document = fz_open_document(ctx, path);
            if (fz_needs_password(ctx, document) &&
                !fz_authenticate_password(ctx, document, password ? password : "")) {
                error = RMU_ERROR_PASSWORD;
            } else {
                int page_count = fz_count_pages(ctx, document);
                if (page_count <= 0) {
                    error = RMU_ERROR_FORMAT;
                } else {
                    result = calloc(1, sizeof(*result));
                    if (!result) {
                        error = RMU_ERROR_MEMORY;
                    } else {
                        result->document = document;
                        result->page_count = page_count;
                        document = NULL;
                    }
                }
            }
        }
        fz_always(ctx) {
            fz_drop_document(ctx, document);
        }
        fz_catch(ctx) {
            error = rmu_error_from_caught(ctx, RMU_ERROR_FILE);
            fz_report_error(ctx);
            free(result);
            result = NULL;
        }
    }
    pthread_mutex_unlock(&rmu_lock);

    if (error_out) *error_out = error;
    return error == RMU_OK ? result : NULL;
}

void rmu_close(rmu_document *document) {
    if (!document) return;
    pthread_mutex_lock(&rmu_lock);
    if (rmu_context) fz_drop_document(rmu_context, document->document);
    pthread_mutex_unlock(&rmu_lock);
    free(document);
}

int rmu_page_count(const rmu_document *document) {
    return document ? document->page_count : 0;
}

/* Caller holds rmu_lock. Throws on failure. */
static fz_rect rmu_bound_page_locked(fz_context *ctx, rmu_document *document, int page_index) {
    pdf_document *pdf = pdf_specifics(ctx, document->document);
    if (pdf) {
        /* Reads the page dictionary only, like pdf_bound_page, without loading the page. */
        fz_rect box;
        fz_matrix ctm;
        pdf_obj *page_object = pdf_lookup_page_obj(ctx, pdf, page_index);
        pdf_page_obj_transform(ctx, page_object, &box, &ctm);
        return fz_transform_rect(box, ctm);
    }
    fz_page *page = fz_load_page(ctx, document->document, page_index);
    fz_rect bounds;
    fz_try(ctx) {
        bounds = fz_bound_page(ctx, page);
    }
    fz_always(ctx) {
        fz_drop_page(ctx, page);
    }
    fz_catch(ctx) {
        fz_rethrow(ctx);
    }
    return bounds;
}

int rmu_page_size(rmu_document *document, int page_index, float *width, float *height) {
    if (!document || !width || !height || page_index < 0 || page_index >= document->page_count) {
        return RMU_ERROR_ARGUMENT;
    }
    int error = RMU_OK;
    pthread_mutex_lock(&rmu_lock);
    fz_context *ctx = rmu_context;
    if (!ctx) {
        error = RMU_ERROR_NOT_INITIALIZED;
    } else {
        fz_var(error);
        fz_try(ctx) {
            fz_rect bounds = rmu_bound_page_locked(ctx, document, page_index);
            *width = bounds.x1 - bounds.x0;
            *height = bounds.y1 - bounds.y0;
        }
        fz_catch(ctx) {
            error = rmu_error_from_caught(ctx, RMU_ERROR_MEMORY);
            fz_report_error(ctx);
        }
    }
    pthread_mutex_unlock(&rmu_lock);
    return error;
}

int rmu_page_sizes(rmu_document *document, float *widths, float *heights, int count) {
    if (!document || !widths || !heights || count < 0) return RMU_ERROR_ARGUMENT;
    if (count > document->page_count) count = document->page_count;
    pthread_mutex_lock(&rmu_lock);
    fz_context *ctx = rmu_context;
    if (!ctx) {
        pthread_mutex_unlock(&rmu_lock);
        return RMU_ERROR_NOT_INITIALIZED;
    }
    for (int index = 0; index < count; index++) {
        widths[index] = 0;
        heights[index] = 0;
        fz_try(ctx) {
            fz_rect bounds = rmu_bound_page_locked(ctx, document, index);
            widths[index] = bounds.x1 - bounds.x0;
            heights[index] = bounds.y1 - bounds.y0;
        }
        fz_catch(ctx) {
            fz_report_error(ctx);
        }
    }
    pthread_mutex_unlock(&rmu_lock);
    return RMU_OK;
}

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
) {
    if (!document || !bgra || page_index < 0 || page_index >= document->page_count ||
        full_width <= 0 || full_height <= 0 || width <= 0 || height <= 0 ||
        width > INT_MAX / 4 || stride < width * 4) {
        return RMU_ERROR_ARGUMENT;
    }
    int error = RMU_OK;
    fz_page *page = NULL;
    fz_pixmap *pixmap = NULL;
    fz_device *device = NULL;

    pthread_mutex_lock(&rmu_lock);
    fz_context *ctx = rmu_context;
    if (!ctx) {
        error = RMU_ERROR_NOT_INITIALIZED;
    } else {
        fz_var(page);
        fz_var(pixmap);
        fz_var(device);
        fz_var(error);
        fz_try(ctx) {
            page = fz_load_page(ctx, document->document, page_index);
            fz_rect bounds = fz_bound_page(ctx, page);
            if (bounds.x1 - bounds.x0 <= 0 || bounds.y1 - bounds.y0 <= 0) {
                fz_throw(ctx, FZ_ERROR_FORMAT, "page %d has no area", page_index);
            }
            fz_matrix ctm = fz_concat(
                rmu_page_matrix(bounds, full_width, full_height),
                fz_translate((float) -left, (float) -top)
            );
            fz_irect area = fz_make_irect(0, 0, width, height);
            if (stride == width * 4) {
                /* Draw straight into the caller's rows. */
                pixmap = fz_new_pixmap_with_bbox_and_data(ctx, fz_device_bgr(ctx), area, NULL, 1, bgra);
            } else {
                pixmap = fz_new_pixmap_with_bbox(ctx, fz_device_bgr(ctx), area, NULL, 1);
            }
            fz_clear_pixmap_with_value(ctx, pixmap, 0xff);
            device = fz_new_draw_device(ctx, fz_identity, pixmap);
            fz_run_page_contents(ctx, page, device, ctm, NULL);
            fz_close_device(ctx, device);
            if (stride != width * 4) {
                const unsigned char *source = fz_pixmap_samples(ctx, pixmap);
                int source_stride = fz_pixmap_stride(ctx, pixmap);
                for (int row = 0; row < height; row++) {
                    memcpy(bgra + (size_t) row * stride, source + (size_t) row * source_stride, (size_t) width * 4);
                }
            }
        }
        fz_always(ctx) {
            fz_drop_device(ctx, device);
            fz_drop_pixmap(ctx, pixmap);
            fz_drop_page(ctx, page);
        }
        fz_catch(ctx) {
            error = rmu_error_from_caught(ctx, RMU_ERROR_MEMORY);
            fz_report_error(ctx);
        }
    }
    pthread_mutex_unlock(&rmu_lock);
    return error;
}

/* A device that only records where images land on the page. */
typedef struct {
    fz_device super;
    int *rects;
    int max_rects;
    int count;
    int full_width;
    int full_height;
} rmu_image_device;

static int rmu_clamp(long value, int limit) {
    if (value < 0) return 0;
    if (value > limit) return limit;
    return (int) value;
}

static void rmu_record_image(fz_device *base, fz_matrix ctm) {
    rmu_image_device *device = (rmu_image_device *) base;
    if (device->count >= device->max_rects) return;
    fz_rect area = fz_transform_rect(fz_unit_rect, ctm);
    int left = rmu_clamp(lroundf(area.x0), device->full_width);
    int top = rmu_clamp(lroundf(area.y0), device->full_height);
    int right = rmu_clamp(lroundf(area.x1), device->full_width);
    int bottom = rmu_clamp(lroundf(area.y1), device->full_height);
    if (right <= left || bottom <= top) return;
    int *slot = device->rects + device->count * 4;
    slot[0] = left;
    slot[1] = top;
    slot[2] = right;
    slot[3] = bottom;
    device->count++;
}

static void rmu_fill_image(fz_context *ctx, fz_device *device, fz_image *image, fz_matrix ctm, float alpha, fz_color_params params) {
    rmu_record_image(device, ctm);
}

static void rmu_fill_image_mask(fz_context *ctx, fz_device *device, fz_image *image, fz_matrix ctm, fz_colorspace *colorspace, const float *color, float alpha, fz_color_params params) {
    rmu_record_image(device, ctm);
}

static void rmu_clip_image_mask(fz_context *ctx, fz_device *device, fz_image *image, fz_matrix ctm, fz_rect scissor) {
    rmu_record_image(device, ctm);
}

int rmu_image_rects(
    rmu_document *document,
    int page_index,
    int full_width,
    int full_height,
    int *rects,
    int max_rects
) {
    if (!document || !rects || max_rects < 0 || page_index < 0 || page_index >= document->page_count ||
        full_width <= 0 || full_height <= 0) {
        return -RMU_ERROR_ARGUMENT;
    }
    int result = 0;
    fz_page *page = NULL;
    rmu_image_device *device = NULL;

    pthread_mutex_lock(&rmu_lock);
    fz_context *ctx = rmu_context;
    if (!ctx) {
        result = -RMU_ERROR_NOT_INITIALIZED;
    } else {
        fz_var(page);
        fz_var(device);
        fz_var(result);
        fz_try(ctx) {
            page = fz_load_page(ctx, document->document, page_index);
            fz_rect bounds = fz_bound_page(ctx, page);
            if (bounds.x1 - bounds.x0 <= 0 || bounds.y1 - bounds.y0 <= 0) {
                fz_throw(ctx, FZ_ERROR_FORMAT, "page %d has no area", page_index);
            }
            device = fz_new_derived_device(ctx, rmu_image_device);
            device->super.fill_image = rmu_fill_image;
            device->super.fill_image_mask = rmu_fill_image_mask;
            device->super.clip_image_mask = rmu_clip_image_mask;
            device->rects = rects;
            device->max_rects = max_rects;
            device->full_width = full_width;
            device->full_height = full_height;
            fz_run_page_contents(ctx, page, &device->super, rmu_page_matrix(bounds, full_width, full_height), NULL);
            fz_close_device(ctx, &device->super);
            result = device->count;
        }
        fz_always(ctx) {
            fz_drop_device(ctx, device ? &device->super : NULL);
            fz_drop_page(ctx, page);
        }
        fz_catch(ctx) {
            result = -rmu_error_from_caught(ctx, RMU_ERROR_MEMORY);
            fz_report_error(ctx);
        }
    }
    pthread_mutex_unlock(&rmu_lock);
    return result;
}

void rmu_empty_cache(void) {
    pthread_mutex_lock(&rmu_lock);
    if (rmu_context) fz_empty_store(rmu_context);
    pthread_mutex_unlock(&rmu_lock);
}
