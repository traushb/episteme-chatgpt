package com.aryan.reader.shared.chatgpt

import com.aryan.reader.shared.BookItem
import com.aryan.reader.shared.pdf.SharedPdfAnnotation

/** What the PDF reader hands its host when the reader taps "Check answers". */
data class SharedPdfAnswerCheckRequest(
    val book: BookItem,
    /** Index in the PDF itself, the key annotations are stored under. */
    val pdfPageIndex: Int,
    /** Pages in the PDF itself (inserted blank pages not counted). */
    val pdfPageCount: Int,
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

enum class ChatGptFollowUpStatus {
    ANSWERING,
    ANSWERED,
    FAILED,
}

/** A question the reader asked after the check, with ChatGPT's answer so far. */
data class ChatGptFollowUp(
    val question: String,
    val answer: String = "",
    val status: ChatGptFollowUpStatus = ChatGptFollowUpStatus.ANSWERING,
    val failure: ChatGptFailure? = null,
    /** The model stopped early (for example on an output limit); [answer] is partial. */
    val isTruncated: Boolean = false,
)

data class SharedChatGptAnswerCheckState(
    val phase: SharedChatGptAnswerCheckPhase = SharedChatGptAnswerCheckPhase.IDLE,
    val pageLabel: String = "",
    val text: String = "",
    val failure: ChatGptFailure? = null,
    /** The model stopped early (for example on an output limit); [text] is partial. */
    val isTruncated: Boolean = false,
    val modelName: String? = null,
    /** The effort sent with the request; null when the model used its default. */
    val reasoningEffort: String? = null,
    /** 1-based numbers of the context pages sent with the current check. */
    val contextPageNumbers: List<Int> = emptyList(),
    /** Pages prepared so far, while [phase] is PREPARING. */
    val preparedPages: Int = 0,
    val pagesToPrepare: Int = 0,
    /** Questions asked after the check, oldest first. */
    val followUps: List<ChatGptFollowUp> = emptyList(),
) {
    val isVisible: Boolean get() = phase != SharedChatGptAnswerCheckPhase.IDLE
    val isAnsweringFollowUp: Boolean get() = followUps.any { it.status == ChatGptFollowUpStatus.ANSWERING }
    val isBusy: Boolean
        get() = phase == SharedChatGptAnswerCheckPhase.PREPARING ||
            phase == SharedChatGptAnswerCheckPhase.ANALYZING ||
            isAnsweringFollowUp

    /** Questions can follow a finished check, one at a time. */
    val canAskFollowUp: Boolean
        get() = phase == SharedChatGptAnswerCheckPhase.COMPLETED && !isAnsweringFollowUp
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
    /** The context saved for the book of the last check. */
    val context: ChatGptAnswerCheckContextSetting,
    /** Pages in that book, for the manual range. */
    val pageCount: Int,
    val onCheck: (SharedPdfAnswerCheckRequest) -> Unit,
    /** Runs the last request again; after "no answers on this page" it checks anyway. */
    val onRetry: () -> Unit,
    /** Saves the context for that book; it applies from the next check. */
    val onContextChange: (ChatGptAnswerCheckContextSetting) -> Unit,
    /** Asks ChatGPT a question about the finished check. */
    val onAskFollowUp: (String) -> Unit,
    val onSignIn: () -> Unit,
    val onDismiss: () -> Unit,
)
