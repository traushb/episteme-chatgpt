package com.aryan.reader.shared.ui

/** The PDF engine choice offered in the reader's visual options. */
internal class SharedMobilePdfRenderEngineControl(
    val options: List<SharedPdfRenderEngineOption>,
    val selectedId: String,
    val select: (String) -> Unit,
)

/**
 * Null where the platform draws pages with a single engine. Reading it in
 * composition follows the current choice.
 */
internal expect fun sharedMobilePdfRenderEngineControl(): SharedMobilePdfRenderEngineControl?
