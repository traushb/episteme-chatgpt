package com.aryan.reader.shared.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aryan.reader.shared.BookItem
import com.aryan.reader.shared.chatgpt.CHATGPT_MODELS_URL
import com.aryan.reader.shared.chatgpt.CHATGPT_RESPONSES_URL
import com.aryan.reader.shared.chatgpt.ChatGptAnswerCheckContextSetting
import com.aryan.reader.shared.chatgpt.ChatGptConnectionState
import com.aryan.reader.shared.chatgpt.ChatGptConnectionStatus
import com.aryan.reader.shared.chatgpt.ChatGptContextPage
import com.aryan.reader.shared.chatgpt.ChatGptFailure
import com.aryan.reader.shared.chatgpt.ChatGptFailureKind
import com.aryan.reader.shared.chatgpt.ChatGptModel
import com.aryan.reader.shared.chatgpt.ChatGptResponseStreamParser
import com.aryan.reader.shared.chatgpt.ChatGptStreamEvent
import com.aryan.reader.shared.chatgpt.SharedChatGptAnswerCheckPhase
import com.aryan.reader.shared.chatgpt.SharedChatGptAnswerCheckState
import com.aryan.reader.shared.chatgpt.SharedPdfAnswerCheckHost
import com.aryan.reader.shared.chatgpt.SharedPdfAnswerCheckRequest
import com.aryan.reader.shared.chatgpt.buildChatGptAnswerCheckPageContext
import com.aryan.reader.shared.chatgpt.buildChatGptAnswerCheckRequestBody
import com.aryan.reader.shared.chatgpt.chatGptAnswerCheckInstructions
import com.aryan.reader.shared.chatgpt.chatGptAnswerCheckUserContent
import com.aryan.reader.shared.chatgpt.chatGptContextPageTextLimit
import com.aryan.reader.shared.chatgpt.chatGptPageHasTextLayer
import com.aryan.reader.shared.chatgpt.chooseChatGptModel
import com.aryan.reader.shared.chatgpt.chooseChatGptReasoningEffort
import com.aryan.reader.shared.chatgpt.classifyChatGptFailure
import com.aryan.reader.shared.chatgpt.decodeChatGptAnswerCheckContextSetting
import com.aryan.reader.shared.chatgpt.decodeChatGptReasoningEfforts
import com.aryan.reader.shared.chatgpt.encodeChatGptReasoningEfforts
import com.aryan.reader.shared.chatgpt.parseChatGptApiError
import com.aryan.reader.shared.chatgpt.parseChatGptModelCatalog
import com.aryan.reader.shared.ui.ANSWER_CHECK_CONTEXT_JPEG_QUALITY
import com.aryan.reader.shared.ui.ANSWER_CHECK_CONTEXT_PAGE_LONG_SIDE_PX
import com.aryan.reader.shared.ui.ANSWER_CHECK_JPEG_QUALITY
import com.aryan.reader.shared.ui.ANSWER_CHECK_PAGE_LONG_SIDE_PX
import com.aryan.reader.shared.ui.IosAnswerCheckPicture
import com.aryan.reader.shared.ui.extractIosAnswerCheckPageText
import com.aryan.reader.shared.ui.renderIosAnswerCheckPageJpeg
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleLanguageCode
import platform.Foundation.NSUserDefaults

private const val CHATGPT_MODEL_DEFAULTS_KEY = "reader.chatgpt.answer_check_model.v1"

/** JSON object of model slug to the reasoning effort chosen for it. */
private const val CHATGPT_REASONING_EFFORTS_DEFAULTS_KEY = "reader.chatgpt.reasoning_effort.v1"

/** Prefix of the per-book context setting; the book id follows. */
private const val CHATGPT_CONTEXT_DEFAULTS_KEY_PREFIX = "reader.chatgpt.answer_check_context.v1."

/** Coalesces streamed text into UI updates at most this often. */
private const val STREAM_PUBLISH_INTERVAL_MS = 60L

/**
 * Runs "Check my answers" for the iOS PDF reader: reads the page's printed
 * text and the reader's annotations, renders the page with their handwriting,
 * adds the context pages saved for the book, and streams ChatGPT's feedback
 * into [state] using the token of the account signed in with ChatGPT. One
 * check runs at a time; a new one replaces it.
 */
internal class IosChatGptAnswerCheckController(
    private val chatGpt: IosChatGptBridge,
    private val scope: CoroutineScope,
    private val isNetworkAvailable: () -> Boolean,
    /** The app's chosen UI language tag, or null to follow the system. */
    private val appLanguageTag: () -> String?,
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val http: IosChatGptHttpTransport = IosChatGptUrlSessionTransport,
    private val pageText: suspend (book: BookItem, pdfPageIndex: Int, password: String?) -> String =
        { book, pdfPageIndex, password -> extractIosAnswerCheckPageText(book, pdfPageIndex, password) },
    private val pageJpeg: suspend (IosAnswerCheckPicture) -> ByteArray? = { picture ->
        renderIosAnswerCheckPageJpeg(picture)
    },
) {
    var state by mutableStateOf(SharedChatGptAnswerCheckState())
        private set

    /** The account's model catalog; empty until first loaded. */
    var models by mutableStateOf<List<ChatGptModel>>(emptyList())
        private set
    var isLoadingModels by mutableStateOf(false)
        private set
    var selectedModelSlug by mutableStateOf(defaults.stringForKey(CHATGPT_MODEL_DEFAULTS_KEY))
        private set

    /** Reasoning effort chosen per model slug; a model without an entry uses its default. */
    var reasoningEfforts by mutableStateOf(
        decodeChatGptReasoningEfforts(defaults.stringForKey(CHATGPT_REASONING_EFFORTS_DEFAULTS_KEY))
    )
        private set

    /** The context saved for the book of the last check. */
    var contextSetting by mutableStateOf(ChatGptAnswerCheckContextSetting())
        private set
    private var contextBookId: String? = null
    private var contextPageCount = 1

    private var job: Job? = null
    private var lastRequest: SharedPdfAnswerCheckRequest? = null
    private var lastRequestForced = false
    private var resumeAfterSignIn = false

    init {
        // Every published transition, not a recomposition sample: a sign-in
        // that fails fast must still be seen leaving CONNECTING.
        chatGpt.connectionListener = ::onConnectionChanged
    }

    val host: SharedPdfAnswerCheckHost
        get() = SharedPdfAnswerCheckHost(
            state = state,
            connection = chatGpt.connection,
            context = contextSetting,
            pageCount = contextPageCount,
            onCheck = { request -> check(request, force = false) },
            onRetry = ::retry,
            onContextChange = ::updateContext,
            onSignIn = ::signIn,
            onDismiss = ::dismiss,
        )

    fun check(request: SharedPdfAnswerCheckRequest, force: Boolean) {
        job?.cancel()
        lastRequest = request
        lastRequestForced = force
        if (contextBookId != request.book.id) {
            contextBookId = request.book.id
            contextSetting = decodeChatGptAnswerCheckContextSetting(
                defaults.stringForKey(CHATGPT_CONTEXT_DEFAULTS_KEY_PREFIX + request.book.id)
            )
        }
        contextPageCount = request.pdfPageCount.coerceAtLeast(1)
        resumeAfterSignIn = false
        state = SharedChatGptAnswerCheckState(
            phase = SharedChatGptAnswerCheckPhase.PREPARING,
            pageLabel = request.pageLabel,
        )
        job = scope.launch { runCheck(request, force) }
    }

    /** Runs the last request again; after "no answers on this page" it checks anyway. */
    fun retry() {
        val request = lastRequest ?: return
        val force = lastRequestForced || state.failure?.kind == ChatGptFailureKind.NO_ANSWERS_ON_PAGE
        check(request, force)
    }

    fun signIn() {
        // Finishing sign-in from the sheet carries on with the check the
        // reader asked for instead of making them tap the button again.
        resumeAfterSignIn = state.isVisible && lastRequest != null
        chatGpt.requestSignIn()
    }

    fun signOut() {
        dismiss()
        models = emptyList()
        chatGpt.requestSignOut()
    }

    fun dismiss() {
        job?.cancel()
        job = null
        resumeAfterSignIn = false
        state = SharedChatGptAnswerCheckState()
    }

    fun selectModel(slug: String) {
        selectedModelSlug = slug
        defaults.setObject(slug, forKey = CHATGPT_MODEL_DEFAULTS_KEY)
    }

    /** [effort] null returns [slug] to its default: no effort is sent for it. */
    fun selectReasoningEffort(slug: String, effort: String?) {
        val updated = if (effort == null) reasoningEfforts - slug else reasoningEfforts + (slug to effort)
        reasoningEfforts = updated
        defaults.setObject(encodeChatGptReasoningEfforts(updated), forKey = CHATGPT_REASONING_EFFORTS_DEFAULTS_KEY)
    }

    /** Saves the context for the book of the last check; the next check uses it. */
    fun updateContext(setting: ChatGptAnswerCheckContextSetting) {
        val bookId = contextBookId ?: return
        contextSetting = setting
        defaults.setObject(setting.encode(), forKey = CHATGPT_CONTEXT_DEFAULTS_KEY_PREFIX + bookId)
    }

    /** Loads the model catalog for the settings picker. */
    fun refreshModels() {
        if (isLoadingModels || !chatGpt.connection.isConnected) return
        scope.launch {
            val token = (chatGpt.freshAccessToken() as? IosChatGptAccessToken.Ready)?.value ?: return@launch
            fetchModels(token)
        }
    }

    private fun onConnectionChanged(previous: ChatGptConnectionState, connection: ChatGptConnectionState) {
        if (!connection.isConnected) models = emptyList()
        if (previous.status != ChatGptConnectionStatus.CONNECTING || connection.status == ChatGptConnectionStatus.CONNECTING) {
            return
        }
        val request = lastRequest
        if (resumeAfterSignIn && request != null && connection.isConnected && connection.planUsageAllowed) {
            check(request, lastRequestForced)
        } else if (resumeAfterSignIn && state.isVisible) {
            // Sign-in ended without a usable connection; say why in the sheet.
            resumeAfterSignIn = false
            state = state.copy(
                phase = SharedChatGptAnswerCheckPhase.FAILED,
                failure = when {
                    connection.isConnected && !connection.planUsageAllowed ->
                        ChatGptFailure(ChatGptFailureKind.PLAN_USAGE_NOT_ALLOWED)
                    else -> ChatGptFailure(ChatGptFailureKind.SIGN_IN_REQUIRED, code = connection.errorCode)
                },
            )
        }
    }

    private suspend fun runCheck(request: SharedPdfAnswerCheckRequest, force: Boolean) {
        val bookTitle = request.book.title?.takeIf(String::isNotBlank) ?: request.book.displayName
        val context = buildChatGptAnswerCheckPageContext(
            bookTitle,
            request.pageLabel,
            pageText(request.book, request.pdfPageIndex, request.password),
            request.annotations,
        )
        if (!force && !context.hasStudentWork) {
            fail(ChatGptFailure(ChatGptFailureKind.NO_ANSWERS_ON_PAGE))
            return
        }
        if (!isNetworkAvailable()) {
            fail(ChatGptFailure(ChatGptFailureKind.NETWORK))
            return
        }
        val token = when (val access = chatGpt.freshAccessToken()) {
            is IosChatGptAccessToken.Ready -> access.value
            is IosChatGptAccessToken.Unavailable -> {
                fail(failureForTokenError(access.errorCode))
                return
            }
        }
        val catalog = models.ifEmpty { fetchModels(token) ?: return }
        val model = chooseChatGptModel(catalog, selectedModelSlug) ?: run {
            fail(ChatGptFailure(ChatGptFailureKind.MODEL_UNAVAILABLE))
            return
        }
        val reasoningEffort = chooseChatGptReasoningEffort(model, reasoningEfforts[model.slug])
        val setting = contextSetting
        val contextIndices = setting.contextPageIndices(request.pdfPageIndex, request.pdfPageCount)
        state = state.copy(preparedPages = 0, pagesToPrepare = contextIndices.size + 1)

        // One page at a time: each picture is encoded and its raster freed
        // before the next page is read, so a long context stays within the
        // memory of older iPads.
        val pagePicture = pageJpeg(
            IosAnswerCheckPicture(
                book = request.book,
                pdfPageIndex = request.pdfPageIndex,
                password = request.password,
                annotations = request.annotations,
                longSidePx = ANSWER_CHECK_PAGE_LONG_SIDE_PX,
                jpegQuality = ANSWER_CHECK_JPEG_QUALITY,
            )
        )?.let(::jpegDataUrl)
        if (pagePicture == null && context.pageText.isBlank()) {
            fail(ChatGptFailure(ChatGptFailureKind.PAGE_RENDER_FAILED))
            return
        }
        state = state.copy(preparedPages = 1)
        val textLimit = chatGptContextPageTextLimit(contextIndices.size)
        val contextPages = contextIndices.mapNotNull { pageIndex ->
            val text = pageText(request.book, pageIndex, request.password).trim()
            val hasTextLayer = chatGptPageHasTextLayer(text)
            // Pages without a text layer only exist as pictures.
            val picture = if (setting.includeImages || !hasTextLayer) {
                pageJpeg(
                    IosAnswerCheckPicture(
                        book = request.book,
                        pdfPageIndex = pageIndex,
                        password = request.password,
                        annotations = emptyList(),
                        longSidePx = ANSWER_CHECK_CONTEXT_PAGE_LONG_SIDE_PX,
                        jpegQuality = ANSWER_CHECK_CONTEXT_JPEG_QUALITY,
                    )
                )?.let(::jpegDataUrl)
            } else {
                null
            }
            state = state.copy(preparedPages = state.preparedPages + 1)
            if (!hasTextLayer && picture == null) {
                iosChatGptLog("context.page_skipped page=${pageIndex + 1}")
                return@mapNotNull null
            }
            ChatGptContextPage(
                pageNumber = pageIndex + 1,
                text = if (hasTextLayer) text.take(textLimit) else "",
                isTextShortened = hasTextLayer && text.length > textLimit,
                imageDataUrl = picture,
            )
        }
        val body = buildChatGptAnswerCheckRequestBody(
            model = model.slug,
            instructions = chatGptAnswerCheckInstructions(replyLanguageName()),
            content = chatGptAnswerCheckUserContent(context, pagePicture, contextPages),
            reasoningEffort = reasoningEffort,
        )
        state = state.copy(
            phase = SharedChatGptAnswerCheckPhase.ANALYZING,
            modelName = model.displayName,
            reasoningEffort = reasoningEffort,
            contextPageNumbers = contextPages.map { it.pageNumber },
        )
        streamAnswerCheck(token, body)
    }

    private suspend fun streamAnswerCheck(token: String, body: String) {
        val parser = ChatGptResponseStreamParser()
        val text = StringBuilder()
        val errorBody = StringBuilder()
        var status = 0
        var completed = false
        var incomplete = false
        var streamFailure: ChatGptFailure? = null
        var lastPublish = TimeSource.Monotonic.markNow()

        fun handle(event: ChatGptStreamEvent) {
            when (event) {
                is ChatGptStreamEvent.TextDelta -> {
                    text.append(event.text)
                    if (lastPublish.elapsedNow().inWholeMilliseconds >= STREAM_PUBLISH_INTERVAL_MS) {
                        lastPublish = TimeSource.Monotonic.markNow()
                        state = state.copy(text = text.toString())
                    }
                }
                ChatGptStreamEvent.Completed -> completed = true
                is ChatGptStreamEvent.Incomplete -> incomplete = true
                is ChatGptStreamEvent.Failed -> streamFailure = classifyChatGptFailure(status, event.code)
            }
        }

        try {
            http(
                "POST",
                CHATGPT_RESPONSES_URL,
                mapOf(
                    "Authorization" to "Bearer $token",
                    "Content-Type" to "application/json",
                    "Accept" to "text/event-stream",
                ),
                body,
            ).collect { event ->
                when (event) {
                    is IosChatGptHttpEvent.Status -> status = event.code
                    is IosChatGptHttpEvent.Body ->
                        if (status in 200..299) {
                            parser.feed(event.bytes).forEach(::handle)
                        } else if (errorBody.length < 16_384) {
                            errorBody.append(event.bytes.decodeToString())
                        }
                }
            }
            if (status in 200..299) parser.finish().forEach(::handle)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (transport: IosChatGptTransportException) {
            streamFailure = streamFailure ?: ChatGptFailure(ChatGptFailureKind.NETWORK)
        }

        val failure = when {
            status !in 200..299 && status != 0 -> {
                val error = parseChatGptApiError(errorBody.toString())
                iosChatGptLog("responses.http_error status=$status code=${error.code} param=${error.param}")
                classifyChatGptFailure(status, error.code, error.param)
            }
            streamFailure != null -> streamFailure
            status == 0 -> ChatGptFailure(ChatGptFailureKind.NETWORK)
            text.isBlank() -> ChatGptFailure(ChatGptFailureKind.EMPTY_RESPONSE)
            else -> null
        }
        if (failure?.kind == ChatGptFailureKind.MODEL_UNAVAILABLE) models = emptyList()
        state = if (failure != null) {
            state.copy(
                phase = SharedChatGptAnswerCheckPhase.FAILED,
                text = text.toString(),
                failure = failure,
            )
        } else {
            state.copy(
                phase = SharedChatGptAnswerCheckPhase.COMPLETED,
                text = text.toString(),
                isTruncated = incomplete || !completed,
            )
        }
    }

    private suspend fun fetchModels(token: String): List<ChatGptModel>? {
        isLoadingModels = true
        try {
            val result = collectIosChatGptResponse(
                http("GET", CHATGPT_MODELS_URL, mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"), null)
            )
            if (result == null) {
                fail(ChatGptFailure(ChatGptFailureKind.NETWORK))
                return null
            }
            if (result.status !in 200..299) {
                val code = parseChatGptApiError(result.body).code
                iosChatGptLog("models.http_error status=${result.status} code=$code")
                fail(classifyChatGptFailure(result.status, code))
                return null
            }
            val catalog = parseChatGptModelCatalog(result.body).orEmpty()
            if (catalog.isEmpty()) {
                fail(ChatGptFailure(ChatGptFailureKind.MODEL_UNAVAILABLE))
                return null
            }
            models = catalog
            return catalog
        } finally {
            isLoadingModels = false
        }
    }

    /** Only reports into the sheet while a check is on screen. */
    private fun fail(failure: ChatGptFailure) {
        if (!state.isVisible) return
        state = state.copy(phase = SharedChatGptAnswerCheckPhase.FAILED, failure = failure)
    }

    private fun failureForTokenError(code: String): ChatGptFailure = when (code) {
        "sign_in_required", "reauth_required" -> ChatGptFailure(ChatGptFailureKind.SIGN_IN_REQUIRED, code = code)
        "plan_usage_not_allowed" -> ChatGptFailure(ChatGptFailureKind.PLAN_USAGE_NOT_ALLOWED, code = code)
        "network_error" -> ChatGptFailure(ChatGptFailureKind.NETWORK, code = code)
        else -> classifyChatGptFailure(httpStatus = null, code = code).let { classified ->
            if (classified.kind == ChatGptFailureKind.UNKNOWN) classified.copy(kind = ChatGptFailureKind.AUTH_REJECTED) else classified
        }
    }

    /** English name of the reader's UI language, e.g. "Russian". */
    private fun replyLanguageName(): String {
        val tag = appLanguageTag()?.takeIf(String::isNotBlank) ?: currentIosLanguageTag()
        val language = tag.substringBefore('-')
        return NSLocale(localeIdentifier = "en_US").displayNameForKey(NSLocaleLanguageCode, language)
            ?.takeIf { it.isNotBlank() }
            ?: "English"
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun jpegDataUrl(bytes: ByteArray): String = "data:image/jpeg;base64,${Base64.Default.encode(bytes)}"

private fun iosChatGptLog(message: String) {
    IosDiagnosticLogStore.record("ChatGPT", message)
}
