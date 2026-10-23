package com.aryan.reader.shared.ios

import com.aryan.reader.shared.BookItem
import com.aryan.reader.shared.FileType
import com.aryan.reader.shared.chatgpt.ChatGptAnswerCheckContextSetting
import com.aryan.reader.shared.chatgpt.ChatGptFailureKind
import com.aryan.reader.shared.chatgpt.SharedChatGptAnswerCheckPhase
import com.aryan.reader.shared.chatgpt.SharedPdfAnswerCheckRequest
import com.aryan.reader.shared.pdf.PdfAnnotationKind
import com.aryan.reader.shared.pdf.PdfInkTool
import com.aryan.reader.shared.pdf.PdfPagePoint
import com.aryan.reader.shared.pdf.SharedPdfAnnotation
import com.aryan.reader.shared.ui.IosAnswerCheckPicture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IosChatGptAnswerCheckTest {
    private val handwriting = SharedPdfAnnotation(
        id = "ink",
        pageIndex = 2,
        kind = PdfAnnotationKind.INK,
        tool = PdfInkTool.PEN,
        points = listOf(PdfPagePoint(0.1f, 0.1f), PdfPagePoint(0.2f, 0.3f)),
        colorArgb = 0xFF000000.toInt(),
    )

    @AfterTest
    fun clearDefaults() {
        NSUserDefaults.standardUserDefaults.removePersistentDomainForName(DEFAULTS_SUITE)
    }

    @Test
    fun streamsFeedbackForThePagePictureWithTheChosenModel() = runTest {
        val http = FakeChatGptHttp(
            responsesBody = sse(
                """{"type":"response.output_text.delta","delta":"### Exercise 1\n"}""",
                """{"type":"response.output_text.delta","delta":"✅ Верно"}""",
                """{"type":"response.completed"}""",
            )
        )
        val controller = controller(this, http, connectedBridge { "token-123" to null })
        controller.selectModel("gpt-b")

        controller.check(request(), force = false)
        advanceUntilIdle()

        val state = controller.state
        assertEquals(SharedChatGptAnswerCheckPhase.COMPLETED, state.phase, state.toString())
        assertEquals("### Exercise 1\n✅ Верно", state.text)
        assertEquals("GPT B", state.modelName)
        assertFalse(state.isTruncated)
        val responses = http.calls.single { it.url.endsWith("/responses") }
        assertEquals("Bearer token-123", responses.headers["Authorization"])
        assertTrue("\"model\":\"gpt-b\"" in responses.body.orEmpty())
        assertTrue("data:image/jpeg;base64,AQID" in responses.body.orEmpty())
        assertEquals(1, http.calls.count { it.url.endsWith("/models") })
        // Default context: up to five pages before page 3, sent as text.
        assertEquals(listOf(1, 2), state.contextPageNumbers)
        assertTrue("=== Context page 1: statements and rules only" in responses.body.orEmpty())
        assertFalse("\"reasoning\"" in responses.body.orEmpty())
    }

    @Test
    fun contextPagesGoAsLabelledTextAndPicturesOnlyWithoutATextLayer() = runTest {
        val http = FakeChatGptHttp(responsesBody = sse(DELTA_OK, COMPLETED))
        val pictures = mutableListOf<IosAnswerCheckPicture>()
        val controller = controller(
            this,
            http,
            connectedBridge { "token" to null },
            pageTexts = mapOf(0 to "Rule 1: modus ponens is valid.", 1 to "  2  "),
            pictures = pictures,
        )

        controller.check(request(), force = false)
        advanceUntilIdle()

        assertEquals(SharedChatGptAnswerCheckPhase.COMPLETED, controller.state.phase, controller.state.toString())
        assertEquals(listOf(1, 2), controller.state.contextPageNumbers)
        // The checked page with the reader's ink, then only the context page
        // without a text layer, clean and smaller.
        assertEquals(listOf(2, 1), pictures.map { it.pdfPageIndex })
        assertEquals(listOf(1600, 1280), pictures.map { it.longSidePx })
        assertEquals(listOf(1, 0), pictures.map { it.annotations.size })
        val body = http.calls.single { it.url.endsWith("/responses") }.body.orEmpty()
        assertTrue("Rule 1: modus ponens is valid." in body)
        assertTrue("Page 2 has no text layer; read it from its picture." in body)
        assertTrue("Picture of page 2:" in body)
        assertTrue("=== Page 3: the page to check ===" in body)
        assertTrue(body.indexOf("=== Context page 1") < body.indexOf("=== Page 3: the page to check ==="))
    }

    @Test
    fun contextIsSavedPerBookAndAppliesFromTheNextCheck() = runTest {
        val defaults = NSUserDefaults(suiteName = DEFAULTS_SUITE)
        val http = FakeChatGptHttp(responsesBody = sse(DELTA_OK, COMPLETED))
        val pictures = mutableListOf<IosAnswerCheckPicture>()
        val controller = controller(this, http, connectedBridge { "token" to null }, pictures = pictures, defaults = defaults)
        val logic = request(book = book("logic"), pdfPageIndex = 9)
        val other = request(book = book("other"), pdfPageIndex = 9)

        controller.check(logic, force = false)
        advanceUntilIdle()
        assertEquals(listOf(5, 6, 7, 8, 9), controller.state.contextPageNumbers)

        controller.host.onContextChange(ChatGptAnswerCheckContextSetting(range = 2..3, includeImages = true))
        assertEquals(ChatGptAnswerCheckContextSetting(range = 2..3, includeImages = true), controller.host.context)
        pictures.clear()
        controller.retry()
        advanceUntilIdle()
        assertEquals(listOf(2, 3), controller.state.contextPageNumbers)
        assertEquals(listOf(9, 1, 2), pictures.map { it.pdfPageIndex })

        controller.check(other, force = false)
        advanceUntilIdle()
        assertEquals(ChatGptAnswerCheckContextSetting(), controller.host.context)
        assertEquals(listOf(5, 6, 7, 8, 9), controller.state.contextPageNumbers)

        val reopened = controller(this, http, connectedBridge { "token" to null }, defaults = defaults)
        reopened.check(logic, force = false)
        advanceUntilIdle()
        assertEquals(listOf(2, 3), reopened.state.contextPageNumbers)
    }

    @Test
    fun picturesAreRenderedOneAtATime() = runTest {
        var active = 0
        var mostAtOnce = 0
        val rendered = mutableListOf<Int>()
        val controller = IosChatGptAnswerCheckController(
            chatGpt = connectedBridge { "token" to null },
            scope = this,
            isNetworkAvailable = { true },
            appLanguageTag = { "en" },
            defaults = NSUserDefaults(suiteName = DEFAULTS_SUITE),
            http = FakeChatGptHttp(responsesBody = sse(DELTA_OK, COMPLETED)).transport,
            pageText = { _, _, _ -> "Some printed text of the page." },
            pageJpeg = { picture ->
                active++
                mostAtOnce = maxOf(mostAtOnce, active)
                delay(25)
                rendered += picture.pdfPageIndex
                active--
                byteArrayOf(1, 2, 3)
            },
        )
        controller.check(request(pdfPageIndex = 20), force = false)
        controller.host.onContextChange(ChatGptAnswerCheckContextSetting(previousPages = 10, includeImages = true))
        controller.retry()
        advanceUntilIdle()

        assertEquals(SharedChatGptAnswerCheckPhase.COMPLETED, controller.state.phase, controller.state.toString())
        assertEquals(1, mostAtOnce)
        assertEquals(listOf(20) + (10..19), rendered.takeLast(11))
    }

    @Test
    fun reasoningEffortChosenForTheModelIsSentWhileTheCatalogOffersIt() = runTest {
        val http = FakeChatGptHttp(responsesBody = sse(DELTA_OK, COMPLETED))
        val controller = controller(this, http, connectedBridge { "token" to null })
        controller.selectModel("gpt-b")
        controller.selectReasoningEffort("gpt-b", "high")

        controller.check(request(), force = false)
        advanceUntilIdle()
        assertEquals("high", controller.state.reasoningEffort)
        assertTrue("\"reasoning\":{\"effort\":\"high\"}" in http.lastResponsesBody())

        controller.selectReasoningEffort("gpt-b", null)
        controller.retry()
        advanceUntilIdle()
        assertNull(controller.state.reasoningEffort)
        assertFalse("\"reasoning\"" in http.lastResponsesBody())

        // A level the catalog does not list for the model is never sent.
        controller.selectReasoningEffort("gpt-b", "xhigh")
        controller.retry()
        advanceUntilIdle()
        assertFalse("\"reasoning\"" in http.lastResponsesBody())
        assertEquals(mapOf("gpt-b" to "xhigh"), controller.reasoningEfforts)
    }

    @Test
    fun rejectedReasoningEffortIsExplained() = runTest {
        val http = FakeChatGptHttp(
            responsesStatus = 400,
            responsesBody = """{"error":{"param":"reasoning.effort","code":"unsupported_value"}}""".encodeToByteArray(),
        )
        val controller = controller(this, http, connectedBridge { "token" to null })
        controller.selectModel("gpt-b")
        controller.selectReasoningEffort("gpt-b", "high")
        controller.check(request(), force = false)
        advanceUntilIdle()
        assertEquals(ChatGptFailureKind.REASONING_EFFORT_REJECTED, controller.state.failure?.kind)
    }

    @Test
    fun pageWithoutAnswersAsksFirstThenChecksAnywayOnRetry() = runTest {
        val http = FakeChatGptHttp(responsesBody = sse(DELTA_OK, COMPLETED))
        val controller = controller(this, http, connectedBridge { "token" to null })

        controller.check(request(annotations = emptyList()), force = false)
        advanceUntilIdle()
        assertEquals(ChatGptFailureKind.NO_ANSWERS_ON_PAGE, controller.state.failure?.kind)
        assertTrue(http.calls.isEmpty())

        controller.retry()
        advanceUntilIdle()
        assertEquals(SharedChatGptAnswerCheckPhase.COMPLETED, controller.state.phase)
        assertEquals("ok", controller.state.text)
    }

    @Test
    fun signingInFromTheSheetResumesTheCheck() = runTest {
        val http = FakeChatGptHttp(responsesBody = sse(DELTA_OK, COMPLETED))
        var token: String? = null
        var signInRequests = 0
        val chatGpt = connectedBridge(onSignIn = { signInRequests++ }) {
            token to (if (token == null) "sign_in_required" else null)
        }
        chatGpt.updateConnection("DISCONNECTED", null, null, false, null)
        val controller = controller(this, http, chatGpt)

        controller.check(request(), force = false)
        advanceUntilIdle()
        assertEquals(ChatGptFailureKind.SIGN_IN_REQUIRED, controller.state.failure?.kind)

        controller.signIn()
        assertEquals(1, signInRequests)
        // What Swift publishes around the browser sign-in.
        chatGpt.updateConnection("CONNECTING", null, null, false, null)
        token = "fresh-token"
        chatGpt.updateConnection("CONNECTED", "reader@example.com", "Reader", true, null)
        advanceUntilIdle()

        assertEquals(SharedChatGptAnswerCheckPhase.COMPLETED, controller.state.phase, controller.state.toString())
        assertEquals("ok", controller.state.text)
    }

    @Test
    fun failedSignInIsExplainedInTheSheet() = runTest {
        val chatGpt = connectedBridge { null to "sign_in_required" }
        chatGpt.updateConnection("DISCONNECTED", null, null, false, null)
        val controller = controller(this, FakeChatGptHttp(), chatGpt)
        controller.check(request(), force = false)
        advanceUntilIdle()

        controller.signIn()
        chatGpt.updateConnection("CONNECTING", null, null, false, null)
        chatGpt.updateConnection("DISCONNECTED", null, null, false, "invalid_grant")

        assertEquals(ChatGptFailureKind.SIGN_IN_REQUIRED, controller.state.failure?.kind)
        assertEquals("invalid_grant", controller.state.failure?.code)
    }

    @Test
    fun planLimitsAndStreamErrorsBecomeReadableFailures() = runTest {
        val http = FakeChatGptHttp(
            responsesStatus = 429,
            responsesBody = """{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}""".encodeToByteArray(),
        )
        val controller = controller(this, http, connectedBridge { "token" to null })
        controller.check(request(), force = false)
        advanceUntilIdle()
        assertEquals(ChatGptFailureKind.USAGE_LIMIT, controller.state.failure?.kind)
        assertEquals(429, controller.state.failure?.httpStatus)

        http.responsesStatus = 200
        http.responsesBody = sse(
            """{"type":"response.output_text.delta","delta":"partial"}""",
            """{"type":"error","code":"server_error","message":"boom"}""",
        )
        controller.retry()
        advanceUntilIdle()
        assertEquals(SharedChatGptAnswerCheckPhase.FAILED, controller.state.phase)
        assertEquals("partial", controller.state.text)
        assertEquals("server_error", controller.state.failure?.code)
    }

    @Test
    fun streamEndingWithoutCompletionIsMarkedTruncated() = runTest {
        val http = FakeChatGptHttp(responsesBody = sse(DELTA_OK))
        val controller = controller(this, http, connectedBridge { "token" to null })
        controller.check(request(), force = false)
        advanceUntilIdle()
        assertEquals(SharedChatGptAnswerCheckPhase.COMPLETED, controller.state.phase)
        assertTrue(controller.state.isTruncated)
    }

    @Test
    fun retiredModelClearsTheCatalogAndOfflineStopsEarly() = runTest {
        val http = FakeChatGptHttp(
            responsesStatus = 404,
            responsesBody = """{"error":{"code":"model_not_found"}}""".encodeToByteArray(),
        )
        val controller = controller(this, http, connectedBridge { "token" to null })
        controller.check(request(), force = false)
        advanceUntilIdle()
        assertEquals(ChatGptFailureKind.MODEL_UNAVAILABLE, controller.state.failure?.kind)
        assertTrue(controller.models.isEmpty())

        val offline = controller(this, http, connectedBridge { "token" to null }, online = false)
        offline.check(request(), force = false)
        advanceUntilIdle()
        assertEquals(ChatGptFailureKind.NETWORK, offline.state.failure?.kind)

        offline.dismiss()
        assertEquals(SharedChatGptAnswerCheckPhase.IDLE, offline.state.phase)
        assertNull(offline.state.failure)
    }

    private fun book(id: String) = BookItem(
        id = id,
        path = "/tmp/$id.pdf",
        type = FileType.PDF,
        displayName = "$id.pdf",
        timestamp = 0L,
        title = "Logic",
    )

    private fun request(
        annotations: List<SharedPdfAnnotation> = listOf(handwriting),
        book: BookItem = book("logic"),
        pdfPageIndex: Int = 2,
    ) = SharedPdfAnswerCheckRequest(
        book = book,
        pdfPageIndex = pdfPageIndex,
        pdfPageCount = 40,
        pageLabel = "${pdfPageIndex + 1}",
        password = null,
        annotations = annotations.map { it.copy(pageIndex = pdfPageIndex) },
    )

    private fun controller(
        scope: CoroutineScope,
        http: FakeChatGptHttp,
        chatGpt: IosChatGptBridge,
        online: Boolean = true,
        pageTexts: Map<Int, String> = emptyMap(),
        pictures: MutableList<IosAnswerCheckPicture> = mutableListOf(),
        defaults: NSUserDefaults = NSUserDefaults(suiteName = DEFAULTS_SUITE),
    ) = IosChatGptAnswerCheckController(
        chatGpt = chatGpt,
        scope = scope,
        isNetworkAvailable = { online },
        appLanguageTag = { "en" },
        defaults = defaults,
        http = http.transport,
        pageText = { _, pageIndex, _ -> pageTexts[pageIndex] ?: "1. Is the argument valid?" },
        pageJpeg = { picture ->
            pictures += picture
            byteArrayOf(1, 2, 3)
        },
    )

    private fun connectedBridge(
        onSignIn: () -> Unit = {},
        token: () -> Pair<String?, String?>,
    ): IosChatGptBridge = IosChatGptBridge().apply {
        setHandlers(
            signIn = onSignIn,
            signOut = {},
            accessToken = { completion ->
                val (value, errorCode) = token()
                completion(value, errorCode)
            },
        )
        updateConnection("CONNECTED", "reader@example.com", null, true, null)
    }

    private class FakeChatGptHttp(
        var responsesStatus: Int = 200,
        var responsesBody: ByteArray = ByteArray(0),
    ) {
        class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

        val calls = mutableListOf<Call>()

        fun lastResponsesBody(): String = calls.last { it.url.endsWith("/responses") }.body.orEmpty()

        val transport: IosChatGptHttpTransport = { method, url, headers, body ->
            calls += Call(method, url, headers, body)
            flow {
                if (url.endsWith("/models")) {
                    emit(IosChatGptHttpEvent.Status(200))
                    emit(IosChatGptHttpEvent.Body(MODEL_CATALOG.encodeToByteArray()))
                } else {
                    emit(IosChatGptHttpEvent.Status(responsesStatus))
                    // Two chunks, so events and characters can straddle a boundary.
                    val half = responsesBody.size / 2
                    emit(IosChatGptHttpEvent.Body(responsesBody.copyOfRange(0, half)))
                    emit(IosChatGptHttpEvent.Body(responsesBody.copyOfRange(half, responsesBody.size)))
                }
            }
        }
    }

    private fun sse(vararg events: String): ByteArray =
        events.joinToString("") { "data: $it\n\n" }.encodeToByteArray()

    private companion object {
        const val DEFAULTS_SUITE = "com.aryan.reader.tests.chatgpt"
        const val DELTA_OK = """{"type":"response.output_text.delta","delta":"ok"}"""
        const val COMPLETED = """{"type":"response.completed"}"""
        const val MODEL_CATALOG =
            """{"models":[{"slug":"gpt-a","display_name":"GPT A","visibility":"list"},""" +
                """{"slug":"gpt-b","display_name":"GPT B","visibility":"list","default_reasoning_level":"medium",""" +
                """"supported_reasoning_levels":[{"effort":"low","description":"Fast"},{"effort":"high","description":"Deep"}]}]}"""
    }
}
