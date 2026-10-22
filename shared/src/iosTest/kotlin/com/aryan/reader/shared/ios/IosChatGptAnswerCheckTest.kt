package com.aryan.reader.shared.ios

import com.aryan.reader.shared.BookItem
import com.aryan.reader.shared.FileType
import com.aryan.reader.shared.chatgpt.ChatGptFailureKind
import com.aryan.reader.shared.chatgpt.SharedChatGptAnswerCheckPhase
import com.aryan.reader.shared.chatgpt.SharedPdfAnswerCheckRequest
import com.aryan.reader.shared.pdf.PdfAnnotationKind
import com.aryan.reader.shared.pdf.PdfInkTool
import com.aryan.reader.shared.pdf.PdfPagePoint
import com.aryan.reader.shared.pdf.SharedPdfAnnotation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private fun request(annotations: List<SharedPdfAnnotation> = listOf(handwriting)) = SharedPdfAnswerCheckRequest(
        book = BookItem(
            id = "logic",
            path = "/tmp/logic.pdf",
            type = FileType.PDF,
            displayName = "logic.pdf",
            timestamp = 0L,
            title = "Logic",
        ),
        pdfPageIndex = 2,
        pageLabel = "3",
        password = null,
        annotations = annotations,
    )

    private fun controller(
        scope: CoroutineScope,
        http: FakeChatGptHttp,
        chatGpt: IosChatGptBridge,
        online: Boolean = true,
    ) = IosChatGptAnswerCheckController(
        chatGpt = chatGpt,
        scope = scope,
        isNetworkAvailable = { online },
        appLanguageTag = { "en" },
        defaults = NSUserDefaults(suiteName = DEFAULTS_SUITE),
        http = http.transport,
        pageText = { "1. Is the argument valid?" },
        pageJpeg = { byteArrayOf(1, 2, 3) },
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
                """{"slug":"gpt-b","display_name":"GPT B","visibility":"list"}]}"""
    }
}
