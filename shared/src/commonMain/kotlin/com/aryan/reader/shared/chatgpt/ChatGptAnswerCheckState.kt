package com.aryan.reader.shared.chatgpt

import com.aryan.reader.shared.BookItem
import com.aryan.reader.shared.pdf.SharedPdfAnnotation

/** What the PDF reader hands its host when the reader taps "Check answers". */
data class SharedPdfAnswerCheckRequest(
    val book: BookItem,
    /** Index in the PDF itself, the key annotations are stored under. */
    val pdfPageIndex: Int,
    val pageLabel: String,
    val password: String?,
    val annotations: List<SharedPdfAnnotation>,
)

enum class SharedChatGptAnswerCheckPhase {
    IDLE,
    PREPARING,
    ANALYZING,
    COMPLETED,
    FAILED,
}

data class SharedChatGptAnswerCheckState(
    val phase: SharedChatGptAnswerCheckPhase = SharedChatGptAnswerCheckPhase.IDLE,
    val pageLabel: String = "",
    val text: String = "",
    val failure: ChatGptFailure? = null,
    /** The model stopped early (for example on an output limit); [text] is partial. */
    val isTruncated: Boolean = false,
    val modelName: String? = null,
) {
    val isVisible: Boolean get() = phase != SharedChatGptAnswerCheckPhase.IDLE
    val isBusy: Boolean
        get() = phase == SharedChatGptAnswerCheckPhase.PREPARING || phase == SharedChatGptAnswerCheckPhase.ANALYZING
}

enum class ChatGptConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    /** A saved connection that can no longer be renewed; the user must sign in again. */
    REAUTH_REQUIRED;

    companion object {
        fun fromWire(value: String?): ChatGptConnectionStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DISCONNECTED
    }
}

/** The signed-in ChatGPT account as the UI may show it. Never carries credentials. */
data class ChatGptConnectionState(
    val status: ChatGptConnectionStatus = ChatGptConnectionStatus.DISCONNECTED,
    val email: String? = null,
    val name: String? = null,
    /** Whether the user allowed this app to use their ChatGPT plan. */
    val planUsageAllowed: Boolean = false,
    /** Machine-readable reason of the last connection failure, if any. */
    val errorCode: String? = null,
) {
    val isConnected: Boolean get() = status == ChatGptConnectionStatus.CONNECTED
    val accountLabel: String? get() = email?.takeIf(String::isNotBlank) ?: name?.takeIf(String::isNotBlank)
}

/**
 * The PDF reader's view of the answer-check feature. Hosts without a
 * ChatGPT connection (Android, desktop) pass none and the entry point stays
 * hidden.
 */
class SharedPdfAnswerCheckHost(
    val state: SharedChatGptAnswerCheckState,
    val connection: ChatGptConnectionState,
    val onCheck: (SharedPdfAnswerCheckRequest) -> Unit,
    /** Runs the last request again; after "no answers on this page" it checks anyway. */
    val onRetry: () -> Unit,
    val onSignIn: () -> Unit,
    val onDismiss: () -> Unit,
)
