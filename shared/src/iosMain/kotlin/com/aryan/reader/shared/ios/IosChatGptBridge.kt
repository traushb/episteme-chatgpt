package com.aryan.reader.shared.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aryan.reader.shared.chatgpt.ChatGptConnectionState
import com.aryan.reader.shared.chatgpt.ChatGptConnectionStatus
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Swift-facing half of "Sign in with ChatGPT" (`ChatGptAuthController.swift`).
 *
 * Swift owns the OAuth browser flow, the Keychain and token refresh. Kotlin
 * asks for an access token right before each request and renders the account
 * state Swift publishes, so credentials never sit in Compose state or logs.
 */
class IosChatGptBridge internal constructor() {
    internal var connection by mutableStateOf(ChatGptConnectionState())
        private set

    /** Sees every published transition, including ones that never reach a frame. */
    internal var connectionListener: ((previous: ChatGptConnectionState, current: ChatGptConnectionState) -> Unit)? = null

    private var signInHandler: (() -> Unit)? = null
    private var signOutHandler: (() -> Unit)? = null
    private var accessTokenHandler: (((String?, String?) -> Unit) -> Unit)? = null

    /**
     * Installed once by Swift. [accessToken] answers with either a token or a
     * machine-readable reason (`sign_in_required`, `reauth_required`,
     * `plan_usage_not_allowed`, `network_error`, or an OAuth error code).
     */
    fun setHandlers(
        signIn: () -> Unit,
        signOut: () -> Unit,
        accessToken: ((String?, String?) -> Unit) -> Unit,
    ) {
        signInHandler = signIn
        signOutHandler = signOut
        accessTokenHandler = accessToken
    }

    /** Swift publishes every connection change here. Never carries tokens. */
    fun updateConnection(
        status: String,
        email: String?,
        name: String?,
        planUsageAllowed: Boolean,
        errorCode: String?,
    ) {
        val previous = connection
        val current = ChatGptConnectionState(
            status = ChatGptConnectionStatus.fromWire(status),
            email = email?.takeIf(String::isNotBlank),
            name = name?.takeIf(String::isNotBlank),
            planUsageAllowed = planUsageAllowed,
            errorCode = errorCode?.takeIf(String::isNotBlank),
        )
        connection = current
        connectionListener?.invoke(previous, current)
    }

    internal fun requestSignIn() {
        signInHandler?.invoke()
    }

    internal fun requestSignOut() {
        signOutHandler?.invoke()
    }

    /** Call from the main thread; Swift refreshes the token first when it is about to expire. */
    internal suspend fun freshAccessToken(): IosChatGptAccessToken {
        val handler = accessTokenHandler ?: return IosChatGptAccessToken.Unavailable("sign_in_required")
        return suspendCancellableCoroutine { continuation ->
            handler { token: String?, errorCode: String? ->
                if (continuation.isActive) {
                    continuation.resume(
                        if (!token.isNullOrBlank()) {
                            IosChatGptAccessToken.Ready(token)
                        } else {
                            IosChatGptAccessToken.Unavailable(errorCode?.takeIf(String::isNotBlank) ?: "sign_in_required")
                        }
                    )
                }
            }
        }
    }
}

internal sealed interface IosChatGptAccessToken {
    data class Ready(val value: String) : IosChatGptAccessToken {
        override fun toString(): String = "Ready(<redacted>)"
    }

    data class Unavailable(val errorCode: String) : IosChatGptAccessToken
}
