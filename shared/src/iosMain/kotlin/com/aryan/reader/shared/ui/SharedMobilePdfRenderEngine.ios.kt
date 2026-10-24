package com.aryan.reader.shared.ui

import com.aryan.reader.shared.pdf.IosPdfRenderEngine
import com.aryan.reader.shared.pdf.IosPdfRenderEnginePreference

internal actual fun sharedMobilePdfRenderEngineControl(): SharedMobilePdfRenderEngineControl? =
    SharedMobilePdfRenderEngineControl(
        options = IosPdfRenderEngine.entries.map { SharedPdfRenderEngineOption(it.id, it.label) },
        selectedId = IosPdfRenderEnginePreference.engine.id,
        select = { id -> IosPdfRenderEnginePreference.select(IosPdfRenderEngine.fromId(id)) },
    )
