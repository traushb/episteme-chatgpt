package com.aryan.reader.shared.chatgpt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Wire contract for "Sign in with ChatGPT" plan usage.
 *
 * A signed-in user's OAuth access token is a bearer credential for the public
 * Responses API; requests made with it count against that user's ChatGPT plan
 * instead of an API key. The plan-usage route is narrower than the platform
 * API: it requires `store: false` and `stream: true`, rejects explicit system
 * messages (guidance goes in `instructions`), and rejects sampling/limit
 * fields such as `temperature` or `max_output_tokens`. Everything built here
 * stays inside that subset.
 */
const val CHATGPT_API_BASE_URL = "https://api.openai.com/v1"
const val CHATGPT_RESPONSES_URL = "$CHATGPT_API_BASE_URL/responses"
const val CHATGPT_MODELS_URL = "$CHATGPT_API_BASE_URL/models"

/** Where users review and cap how much of their plan each connected app may use. */
const val CHATGPT_USAGE_SETTINGS_URL = "https://chatgpt.com/settings/usage"

private val ChatGptJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** One entry of the account-specific model catalog. Requests use [slug]. */
data class ChatGptModel(
    val slug: String,
    val displayName: String,
)

/**
 * Parses `GET /v1/models` for a ChatGPT-plan token.
 *
 * The plan route answers with `{"models": [{slug, display_name, visibility}]}`
 * and only `visibility == "list"` entries are meant to be offered. The
 * platform shape (`{"data": [{id}]}`) is accepted as a fallback so a catalog
 * change degrades to a plainer list instead of an empty picker. Returns null
 * when the body is not a catalog at all.
 */
fun parseChatGptModelCatalog(body: String): List<ChatGptModel>? {
    val root = runCatching { ChatGptJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    val planModels = root["models"]?.let { runCatching { it.jsonArray }.getOrNull() }
    if (planModels != null) {
        return planModels.mapNotNull { element ->
            val model = element.asObjectOrNull() ?: return@mapNotNull null
            val visibility = model.string("visibility")
            if (visibility != null && visibility != "list") return@mapNotNull null
            val slug = model.string("slug")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 }
                ?: return@mapNotNull null
            ChatGptModel(
                slug = slug,
                displayName = model.string("display_name")?.trim()?.takeIf(String::isNotEmpty) ?: slug,
            )
        }.distinctBy { it.slug }
    }
    val platformModels = root["data"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return null
    return platformModels.mapNotNull { element ->
        val id = element.asObjectOrNull()?.string("id")?.trim()?.takeIf(String::isNotEmpty)
            ?: return@mapNotNull null
        ChatGptModel(slug = id, displayName = id)
    }.distinctBy { it.slug }
}

/**
 * The saved model when the account still offers it, otherwise the first
 * catalog entry (the catalog is ordered by ChatGPT, best default first).
 */
fun chooseChatGptModel(models: List<ChatGptModel>, preferredSlug: String?): ChatGptModel? {
    val preferred = preferredSlug?.trim()?.takeIf(String::isNotEmpty)
    return models.firstOrNull { it.slug == preferred } ?: models.firstOrNull()
}

/**
 * Body for one answer-check request: a single user message carrying the
 * page description and, when available, the rendered page as a data URL.
 */
fun buildChatGptAnswerCheckRequestBody(
    model: String,
    instructions: String,
    userText: String,
    pageImageDataUrl: String?,
): String = buildJsonObject {
    put("model", JsonPrimitive(model))
    put("instructions", JsonPrimitive(instructions))
    put("input", buildJsonArray {
        add(buildJsonObject {
            put("role", JsonPrimitive("user"))
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("input_text"))
                    put("text", JsonPrimitive(userText))
                })
                if (pageImageDataUrl != null) {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("input_image"))
                        put("image_url", JsonPrimitive(pageImageDataUrl))
                        // Handwriting needs the high-resolution tiles.
                        put("detail", JsonPrimitive("high"))
                    })
                }
            })
        })
    })
    put("store", JsonPrimitive(false))
    put("stream", JsonPrimitive(true))
}.toString()

/** The events of a streamed Responses call that the answer check acts on. */
sealed interface ChatGptStreamEvent {
    data class TextDelta(val text: String) : ChatGptStreamEvent
    data object Completed : ChatGptStreamEvent
    data class Incomplete(val reason: String?) : ChatGptStreamEvent
    data class Failed(val code: String?, val message: String?) : ChatGptStreamEvent
}

/**
 * Incremental server-sent-events decoder for the Responses stream.
 *
 * Bytes arrive in arbitrary network chunks, so lines are cut at the byte
 * level (a newline byte never occurs inside a UTF-8 sequence) and decoded
 * only once complete; a multi-byte character split across two chunks is
 * therefore never mangled. Events are dispatched on the blank line that ends
 * them, as the SSE format requires.
 */
class ChatGptResponseStreamParser {
    private var pending = ByteArray(0)
    private var pendingSize = 0
    private val data = StringBuilder()
    private var eventName: String? = null
    private var hasData = false

    fun feed(chunk: ByteArray): List<ChatGptStreamEvent> {
        if (chunk.isEmpty()) return emptyList()
        append(chunk)
        val events = mutableListOf<ChatGptStreamEvent>()
        var lineStart = 0
        for (index in 0 until pendingSize) {
            if (pending[index] == NEWLINE) {
                var lineEnd = index
                if (lineEnd > lineStart && pending[lineEnd - 1] == CARRIAGE_RETURN) lineEnd--
                handleLine(pending.decodeToString(lineStart, lineEnd), events)
                lineStart = index + 1
            }
        }
        if (lineStart > 0) {
            pending.copyInto(pending, 0, lineStart, pendingSize)
            pendingSize -= lineStart
        }
        return events
    }

    /** Flushes a final event that the server ended without a trailing blank line. */
    fun finish(): List<ChatGptStreamEvent> {
        val events = mutableListOf<ChatGptStreamEvent>()
        if (pendingSize > 0) {
            var lineEnd = pendingSize
            if (pending[lineEnd - 1] == CARRIAGE_RETURN) lineEnd--
            handleLine(pending.decodeToString(0, lineEnd), events)
            pendingSize = 0
        }
        dispatch(events)
        return events
    }

    private fun append(chunk: ByteArray) {
        val required = pendingSize + chunk.size
        if (required > pending.size) {
            pending = pending.copyOf(maxOf(required, pending.size * 2, 1024))
        }
        chunk.copyInto(pending, pendingSize)
        pendingSize = required
    }

    private fun handleLine(line: String, events: MutableList<ChatGptStreamEvent>) {
        if (line.isEmpty()) {
            dispatch(events)
            return
        }
        if (line.startsWith(":")) return
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> eventName = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
    }

    private fun dispatch(events: MutableList<ChatGptStreamEvent>) {
        val payload = data.toString()
        val name = eventName
        data.clear()
        hasData = false
        eventName = null
        if (payload.isBlank() || payload == "[DONE]") return
        val json = runCatching { ChatGptJson.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return
        streamEventFor(json.string("type") ?: name, json)?.let(events::add)
    }

    private companion object {
        const val NEWLINE: Byte = 10
        const val CARRIAGE_RETURN: Byte = 13
    }
}

private fun streamEventFor(type: String?, json: JsonObject): ChatGptStreamEvent? = when (type) {
    // A refusal is the model's answer too; showing it beats an empty sheet.
    "response.output_text.delta",
    "response.refusal.delta" -> json.string("delta")?.takeIf(String::isNotEmpty)?.let(ChatGptStreamEvent::TextDelta)
    "response.completed" -> ChatGptStreamEvent.Completed
    "response.incomplete" -> ChatGptStreamEvent.Incomplete(
        json["response"]?.asObjectOrNull()?.get("incomplete_details")?.asObjectOrNull()?.string("reason"),
    )
    "response.failed" -> {
        val error = json["response"]?.asObjectOrNull()?.get("error")?.asObjectOrNull()
        ChatGptStreamEvent.Failed(error?.string("code"), error?.string("message"))
    }
    "error" -> {
        val nested = json["error"]?.asObjectOrNull()
        ChatGptStreamEvent.Failed(
            code = json.string("code") ?: nested?.string("code") ?: nested?.string("type"),
            message = json.string("message") ?: nested?.string("message"),
        )
    }
    else -> null
}

/** Machine-readable parts of an error body; [message] is for logs, not users. */
data class ChatGptApiErrorBody(
    val code: String?,
    val message: String?,
)

/**
 * Reads the error code out of the three shapes the plan route uses: Responses
 * errors (`{"error": {"code"}}`), OAuth errors (`{"error": "invalid_token"}`)
 * and admission errors wrapped in `detail`.
 */
fun parseChatGptApiError(body: String): ChatGptApiErrorBody {
    var node = runCatching { ChatGptJson.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: return ChatGptApiErrorBody(code = null, message = null)
    for (depth in 0 until 4) {
        node = node["error"]?.asObjectOrNull() ?: node["detail"]?.asObjectOrNull() ?: break
    }
    val oauthCode = (node["error"] as? JsonPrimitive)?.contentOrNull
    val code = (oauthCode ?: node.string("code") ?: node.string("type"))
        ?.takeIf { it.length <= 100 && ChatGptErrorCodePattern.matches(it) }
    return ChatGptApiErrorBody(
        code = code,
        message = node.string("message") ?: node.string("error_description"),
    )
}

private val ChatGptErrorCodePattern = Regex("[A-Za-z0-9_.:-]+")

enum class ChatGptFailureKind {
    /** No usable ChatGPT connection on this device. */
    SIGN_IN_REQUIRED,
    /** Signed in, but the user did not allow the app to use their plan. */
    PLAN_USAGE_NOT_ALLOWED,
    /** The account or workspace cannot share its plan (for example a free plan). */
    NOT_ELIGIBLE,
    /** The plan limit, or the user's cap for this app, is used up. */
    USAGE_LIMIT,
    RATE_LIMITED,
    /** The route rejected something in the request (model, input type, field). */
    UNSUPPORTED_REQUEST,
    MODEL_UNAVAILABLE,
    /** The credential was rejected; signing in again is the fix. */
    AUTH_REJECTED,
    /** OpenAI has not enabled this app for plan usage. */
    APP_NOT_ENABLED,
    /** A policy or region restriction blocked the request. */
    FORBIDDEN,
    SERVICE_UNAVAILABLE,
    NETWORK,
    /** The page has no ink, typed notes or highlights to check. */
    NO_ANSWERS_ON_PAGE,
    PAGE_RENDER_FAILED,
    EMPTY_RESPONSE,
    UNKNOWN,
}

data class ChatGptFailure(
    val kind: ChatGptFailureKind,
    val code: String? = null,
    val httpStatus: Int? = null,
) {
    /** Whether "Sign in with ChatGPT" is the action that can fix this failure. */
    val needsSignIn: Boolean
        get() = kind == ChatGptFailureKind.SIGN_IN_REQUIRED ||
            kind == ChatGptFailureKind.PLAN_USAGE_NOT_ALLOWED ||
            kind == ChatGptFailureKind.AUTH_REJECTED
}

/** Maps an HTTP status and error code from the plan route to what the user can do about it. */
fun classifyChatGptFailure(httpStatus: Int?, code: String?): ChatGptFailure {
    // Early preview responses used a `_v2_` infix; treat them as the same codes.
    val normalized = code?.replace("subscription_sharing_v2_", "subscription_sharing_")
    val kind = when (normalized) {
        "subscription_sharing_user_not_eligible" -> ChatGptFailureKind.NOT_ELIGIBLE
        "subscription_sharing_usage_limit_exceeded" -> ChatGptFailureKind.USAGE_LIMIT
        "subscription_sharing_usage_unavailable",
        "subscription_sharing_user_unavailable" -> ChatGptFailureKind.SERVICE_UNAVAILABLE
        "subscription_sharing_unsupported_capability",
        "subscription_sharing_route_not_supported" -> ChatGptFailureKind.UNSUPPORTED_REQUEST
        "subscription_sharing_client_not_enabled" -> ChatGptFailureKind.APP_NOT_ENABLED
        "chatpass_v2_scope_not_authorized",
        "chatpass_v2_invalid_authorization_context" -> ChatGptFailureKind.PLAN_USAGE_NOT_ALLOWED
        "subscription_sharing_invalid_user",
        "invalid_token",
        "invalid_api_key",
        "token_expired",
        "invalid_grant",
        "invalid_refresh_token",
        "refresh_token_expired",
        "refresh_token_invalidated",
        "refresh_token_reused" -> ChatGptFailureKind.AUTH_REJECTED
        "model_not_found" -> ChatGptFailureKind.MODEL_UNAVAILABLE
        "rate_limit_exceeded" -> ChatGptFailureKind.RATE_LIMITED
        else -> when {
            httpStatus == 401 -> ChatGptFailureKind.AUTH_REJECTED
            httpStatus == 403 -> ChatGptFailureKind.FORBIDDEN
            httpStatus == 429 -> ChatGptFailureKind.RATE_LIMITED
            httpStatus == 400 || httpStatus == 422 -> ChatGptFailureKind.UNSUPPORTED_REQUEST
            httpStatus != null && httpStatus >= 500 -> ChatGptFailureKind.SERVICE_UNAVAILABLE
            else -> ChatGptFailureKind.UNKNOWN
        }
    }
    return ChatGptFailure(kind = kind, code = code, httpStatus = httpStatus)
}

private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
