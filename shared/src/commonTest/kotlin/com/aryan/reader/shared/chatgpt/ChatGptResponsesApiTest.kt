package com.aryan.reader.shared.chatgpt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatGptResponsesApiTest {
    @Test
    fun `request body stays inside the plan-usage subset`() {
        val body = Json.parseToJsonElement(
            buildChatGptAnswerCheckRequestBody(
                model = "gpt-test",
                instructions = "Check the answers.",
                content = listOf(
                    ChatGptInputPart.Text("Page 3"),
                    ChatGptInputPart.Image("data:image/jpeg;base64,AAAA"),
                ),
            )
        ).jsonObject

        assertEquals(setOf("model", "instructions", "input", "store", "stream"), body.keys)
        assertEquals("gpt-test", body.string("model"))
        assertEquals("Check the answers.", body.string("instructions"))
        assertFalse(body["store"]!!.jsonPrimitive.boolean)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        val input = body["input"]!!.jsonArray
        assertEquals(1, input.size)
        val message = input.single().jsonObject
        assertEquals("user", message.string("role"))
        val content = message["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("input_text", "input_image"), content.map { it.string("type") })
        assertEquals("Page 3", content[0].string("text"))
        assertEquals("data:image/jpeg;base64,AAAA", content[1].string("image_url"))
        assertEquals("high", content[1].string("detail"))
    }

    @Test
    fun `request body keeps the order of text and picture parts`() {
        val body = Json.parseToJsonElement(
            buildChatGptAnswerCheckRequestBody(
                "gpt-test",
                "i",
                listOf(
                    ChatGptInputPart.Text("context"),
                    ChatGptInputPart.Image("data:image/jpeg;base64,CTX"),
                    ChatGptInputPart.Text("page"),
                ),
            )
        ).jsonObject
        val content = body["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("input_text", "input_image", "input_text"), content.map { it.string("type") })
        assertEquals(listOf("context", null, "page"), content.map { it.string("text") })
    }

    @Test
    fun `reasoning effort is sent only when one is chosen`() {
        fun body(effort: String?) = Json.parseToJsonElement(
            buildChatGptAnswerCheckRequestBody("gpt-test", "i", listOf(ChatGptInputPart.Text("u")), reasoningEffort = effort)
        ).jsonObject

        assertFalse("reasoning" in body(null).keys)
        val high = body("high")
        assertEquals(setOf("model", "instructions", "input", "reasoning", "store", "stream"), high.keys)
        assertEquals("high", high["reasoning"]!!.jsonObject.string("effort"))
        val numeric = body("2048")["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive
        assertFalse(numeric.isString)
        assertEquals(2048, numeric.int)
    }

    @Test
    fun `catalog levels decide which efforts a model offers`() {
        val models = parseChatGptModelCatalog(
            """
            {"models":[
              {"slug":"gpt-a","display_name":"GPT A","visibility":"list","default_reasoning_level":"medium",
               "supported_reasoning_levels":[{"effort":"low","description":"Fast"},{"effort":"medium","description":"Balanced"},
                 {"effort":"high","description":"Deep"},{"effort":"low","description":"Duplicate"},{"effort":"Not Valid!"}]},
              {"slug":"gpt-b","display_name":"GPT B","visibility":"list","supported_reasoning_levels":["minimal","xhigh",7]},
              {"slug":"gpt-c","display_name":"GPT C","visibility":"list","supported_reasoning_levels":[]}
            ]}
            """.trimIndent()
        )!!

        assertEquals(listOf("low", "medium", "high"), models[0].reasoningEfforts)
        assertEquals("medium", models[0].defaultReasoningEffort)
        assertEquals(listOf("minimal", "xhigh", "7"), models[1].reasoningEfforts)
        assertNull(models[1].defaultReasoningEffort)
        assertEquals(emptyList(), models[2].reasoningEfforts)
    }

    @Test
    fun `only an effort the catalog still lists is sent`() {
        val model = ChatGptModel("gpt-a", "GPT A", reasoningEfforts = listOf("low", "high"))
        assertEquals("high", chooseChatGptReasoningEffort(model, "high"))
        assertNull(chooseChatGptReasoningEffort(model, "xhigh"))
        assertNull(chooseChatGptReasoningEffort(model, null))
        assertNull(chooseChatGptReasoningEffort(ChatGptModel("gpt-b", "GPT B"), "high"))
    }

    @Test
    fun `efforts per model survive storage and junk is dropped`() {
        val saved = mapOf("gpt-a" to "high", "gpt-b" to "minimal")
        assertEquals(saved, decodeChatGptReasoningEfforts(encodeChatGptReasoningEfforts(saved)))
        assertEquals(mapOf("gpt-a" to "low"), decodeChatGptReasoningEfforts("""{"gpt-a":"low","gpt-b":"Bad Value","gpt-c":{}}"""))
        assertEquals(emptyMap(), decodeChatGptReasoningEfforts(null))
        assertEquals(emptyMap(), decodeChatGptReasoningEfforts("not json"))
    }

    @Test
    fun `a rejected reasoning effort is told apart from other bad requests`() {
        val error = parseChatGptApiError(
            """{"error":{"message":"Unsupported value","type":"invalid_request_error","param":"reasoning.effort","code":"unsupported_value"}}"""
        )
        assertEquals("reasoning.effort", error.param)
        assertEquals(ChatGptFailureKind.REASONING_EFFORT_REJECTED, classifyChatGptFailure(400, error.code, error.param).kind)
        assertEquals(ChatGptFailureKind.UNSUPPORTED_REQUEST, classifyChatGptFailure(400, error.code, "input").kind)
        assertEquals(ChatGptFailureKind.RATE_LIMITED, classifyChatGptFailure(429, null, "reasoning.effort").kind)
    }

    @Test
    fun `stream parser joins text deltas and reports completion`() {
        val stream = """
            event: response.created
            data: {"type":"response.created","response":{"id":"resp_1"}}

            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"Hello"}

            : keep-alive comment

            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":", world"}

            event: response.completed
            data: {"type":"response.completed","response":{"status":"completed"}}

            data: [DONE]

        """.trimIndent() + "\n"
        val parser = ChatGptResponseStreamParser()
        val events = parser.feed(stream.encodeToByteArray()) + parser.finish()

        assertEquals(
            listOf(
                ChatGptStreamEvent.TextDelta("Hello"),
                ChatGptStreamEvent.TextDelta(", world"),
                ChatGptStreamEvent.Completed,
            ),
            events,
        )
    }

    @Test
    fun `stream parser survives chunks that split lines and multi-byte characters`() {
        val stream = "event: response.output_text.delta\r\n" +
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Привет, ответ ✅\"}\r\n\r\n" +
            "data: {\"type\":\"response.completed\"}\r\n\r\n"
        val bytes = stream.encodeToByteArray()
        val parser = ChatGptResponseStreamParser()
        val events = mutableListOf<ChatGptStreamEvent>()
        // One byte at a time cuts every Cyrillic letter and the emoji in half.
        bytes.forEach { byte -> events += parser.feed(byteArrayOf(byte)) }
        events += parser.finish()

        assertEquals(
            listOf(ChatGptStreamEvent.TextDelta("Привет, ответ ✅"), ChatGptStreamEvent.Completed),
            events,
        )
    }

    @Test
    fun `stream parser joins multi-line data fields`() {
        val parser = ChatGptResponseStreamParser()
        val events = parser.feed(
            "data: {\"type\":\"response.output_text.delta\",\ndata: \"delta\":\"x\"}\n\n".encodeToByteArray()
        )
        assertEquals(listOf(ChatGptStreamEvent.TextDelta("x")), events)
    }

    @Test
    fun `stream parser flushes a final event without a trailing blank line`() {
        val parser = ChatGptResponseStreamParser()
        val fed = parser.feed("data: {\"type\":\"response.completed\"}".encodeToByteArray())
        assertTrue(fed.isEmpty())
        assertEquals(listOf<ChatGptStreamEvent>(ChatGptStreamEvent.Completed), parser.finish())
    }

    @Test
    fun `stream parser reports failures, errors and incomplete responses`() {
        val parser = ChatGptResponseStreamParser()
        val events = parser.feed(
            (
                "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"server_error\",\"message\":\"boom\"}}}\n\n" +
                    "data: {\"type\":\"error\",\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"limit\"}\n\n" +
                    "data: {\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}\n\n" +
                    "data: {\"type\":\"response.refusal.delta\",\"delta\":\"I can't help with that.\"}\n\n"
                ).encodeToByteArray()
        )
        assertEquals(
            listOf(
                ChatGptStreamEvent.Failed("server_error", "boom"),
                ChatGptStreamEvent.Failed("subscription_sharing_usage_limit_exceeded", "limit"),
                ChatGptStreamEvent.Incomplete("max_output_tokens"),
                ChatGptStreamEvent.TextDelta("I can't help with that."),
            ),
            events,
        )
    }

    @Test
    fun `stream parser ignores events it does not use and malformed data`() {
        val parser = ChatGptResponseStreamParser()
        val events = parser.feed(
            (
                "data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"thinking\"}\n\n" +
                    "data: not json\n\n" +
                    "event: response.output_text.delta\ndata: {\"delta\":\"from event name\"}\n\n"
                ).encodeToByteArray()
        )
        assertEquals(listOf(ChatGptStreamEvent.TextDelta("from event name")), events)
    }

    @Test
    fun `error bodies yield their machine-readable code`() {
        assertEquals(
            "model_not_found",
            parseChatGptApiError("""{"error":{"message":"nope","type":"invalid_request_error","code":"model_not_found"}}""").code,
        )
        assertEquals("invalid_token", parseChatGptApiError("""{"error":"invalid_token"}""").code)
        assertEquals(
            "subscription_sharing_user_not_eligible",
            parseChatGptApiError("""{"detail":{"error":{"code":"subscription_sharing_user_not_eligible"}}}""").code,
        )
        assertEquals("invalid_request_error", parseChatGptApiError("""{"error":{"type":"invalid_request_error","code":null}}""").code)
        assertNull(parseChatGptApiError("<html>Bad gateway</html>").code)
        assertNull(parseChatGptApiError("""{"error":{"code":"has spaces and <tags>"}}""").code)
    }

    @Test
    fun `failures map to what the user can do about them`() {
        assertEquals(ChatGptFailureKind.USAGE_LIMIT, classifyChatGptFailure(429, "subscription_sharing_usage_limit_exceeded").kind)
        assertEquals(ChatGptFailureKind.NOT_ELIGIBLE, classifyChatGptFailure(403, "subscription_sharing_v2_user_not_eligible").kind)
        assertEquals(ChatGptFailureKind.APP_NOT_ENABLED, classifyChatGptFailure(403, "subscription_sharing_v2_client_not_enabled").kind)
        assertEquals(ChatGptFailureKind.PLAN_USAGE_NOT_ALLOWED, classifyChatGptFailure(403, "chatpass_v2_scope_not_authorized").kind)
        assertEquals(ChatGptFailureKind.UNSUPPORTED_REQUEST, classifyChatGptFailure(400, "subscription_sharing_unsupported_capability").kind)
        assertEquals(ChatGptFailureKind.MODEL_UNAVAILABLE, classifyChatGptFailure(404, "model_not_found").kind)
        assertEquals(ChatGptFailureKind.AUTH_REJECTED, classifyChatGptFailure(401, null).kind)
        assertEquals(ChatGptFailureKind.FORBIDDEN, classifyChatGptFailure(403, "something_new").kind)
        assertEquals(ChatGptFailureKind.RATE_LIMITED, classifyChatGptFailure(429, null).kind)
        assertEquals(ChatGptFailureKind.SERVICE_UNAVAILABLE, classifyChatGptFailure(503, null).kind)
        assertEquals(ChatGptFailureKind.UNKNOWN, classifyChatGptFailure(null, "mystery").kind)

        assertTrue(classifyChatGptFailure(401, null).needsSignIn)
        assertTrue(classifyChatGptFailure(403, "chatpass_v2_scope_not_authorized").needsSignIn)
        assertFalse(classifyChatGptFailure(429, null).needsSignIn)
    }

    @Test
    fun `plan model catalog keeps listed models in order`() {
        val models = parseChatGptModelCatalog(
            """
            {"models":[
              {"slug":"gpt-a","display_name":"GPT A","visibility":"list"},
              {"slug":"gpt-hidden","display_name":"Hidden","visibility":"hide"},
              {"slug":"gpt-b","display_name":"","visibility":"list"},
              {"slug":"gpt-a","display_name":"Duplicate","visibility":"list"},
              {"display_name":"No slug","visibility":"list"}
            ]}
            """.trimIndent()
        )
        assertEquals(listOf(ChatGptModel("gpt-a", "GPT A"), ChatGptModel("gpt-b", "gpt-b")), models)
        assertTrue(models!!.all { it.reasoningEfforts.isEmpty() && it.defaultReasoningEffort == null })
    }

    @Test
    fun `platform model catalog shape is accepted as a fallback`() {
        assertEquals(
            listOf(ChatGptModel("gpt-x", "gpt-x")),
            parseChatGptModelCatalog("""{"object":"list","data":[{"id":"gpt-x"}]}"""),
        )
        assertNull(parseChatGptModelCatalog("""{"unexpected":true}"""))
        assertNull(parseChatGptModelCatalog("not json"))
    }

    @Test
    fun `saved model wins while the account still offers it`() {
        val models = listOf(ChatGptModel("first", "First"), ChatGptModel("second", "Second"))
        assertEquals("second", chooseChatGptModel(models, "second")?.slug)
        assertEquals("first", chooseChatGptModel(models, "retired")?.slug)
        assertEquals("first", chooseChatGptModel(models, null)?.slug)
        assertNull(chooseChatGptModel(emptyList(), "second"))
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content
}
