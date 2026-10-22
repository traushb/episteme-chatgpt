package com.aryan.reader.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aryan.reader.shared.chatgpt.CHATGPT_USAGE_SETTINGS_URL
import com.aryan.reader.shared.chatgpt.ChatGptConnectionState
import com.aryan.reader.shared.chatgpt.ChatGptConnectionStatus
import com.aryan.reader.shared.chatgpt.ChatGptFailure
import com.aryan.reader.shared.chatgpt.ChatGptFailureKind
import com.aryan.reader.shared.chatgpt.ChatGptModel
import com.aryan.reader.shared.chatgpt.SharedChatGptAnswerCheckPhase
import com.aryan.reader.shared.chatgpt.SharedPdfAnswerCheckHost

/**
 * The answer-check popup: ChatGPT's feedback on the current page streams in
 * as Markdown, with the same read-aloud and copy actions as other AI results.
 * When there is no usable ChatGPT connection the sheet offers sign-in in
 * place, and the host resumes the check once the user is back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedPdfAnswerCheckSheet(
    host: SharedPdfAnswerCheckHost,
    bookTitle: String,
    isMainTtsActive: Boolean,
) {
    val state = host.state
    val failure = state.failure
    val title = readerString("chatgpt_answer_check_title", "Answer check · page %1\$s", state.pageLabel)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = host.onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentWindowInsets = { WindowInsets.navigationBars },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .heightIn(min = 260.dp, max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                failure != null && failure.needsSignIn -> SharedChatGptSignInPrompt(
                    title = title,
                    failure = failure,
                    connection = host.connection,
                    onSignIn = host.onSignIn,
                )
                failure != null && state.text.isBlank() -> SharedChatGptAnswerCheckFailure(
                    title = title,
                    failure = failure,
                    onRetry = host.onRetry,
                )
                else -> {
                    val isBusy = state.isBusy
                    SharedMobileAiResultContent(
                        title = title,
                        markdownText = state.text,
                        errorText = null,
                        isLoading = isBusy,
                        cost = null,
                        freeRemaining = null,
                        isCacheHit = false,
                        onRegenerate = if (isBusy) null else host.onRetry,
                        onClear = null,
                        onOpenExternalDictionary = null,
                        isMainTtsActive = isMainTtsActive,
                        ttsBookTitle = bookTitle,
                        ttsChapterTitle = title,
                        showUsageBadge = false,
                        progressMessage = if (state.phase == SharedChatGptAnswerCheckPhase.PREPARING) {
                            readerString("chatgpt_answer_check_preparing", "Preparing the page…")
                        } else {
                            readerString("chatgpt_answer_check_analyzing", "ChatGPT is checking your answers…")
                        },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (failure != null) {
                        SharedChatGptNote(chatGptFailureMessage(failure), isError = true)
                    } else if (state.phase == SharedChatGptAnswerCheckPhase.COMPLETED && state.isTruncated) {
                        SharedChatGptNote(
                            readerString(
                                "chatgpt_answer_check_truncated",
                                "ChatGPT stopped before finishing; the feedback may be incomplete.",
                            ),
                            isError = false,
                        )
                    }
                }
            }
            SharedChatGptPlanFooter(connection = host.connection, modelName = state.modelName)
        }
    }
}

@Composable
private fun SharedChatGptSignInPrompt(
    title: String,
    failure: ChatGptFailure,
    connection: ChatGptConnectionState,
    onSignIn: () -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(
        chatGptFailureMessage(failure),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        readerString(
            "chatgpt_sign_in_explainer",
            "Your answers are checked with your own ChatGPT plan (Plus or Pro). No API key is needed, and the usage counts against your ChatGPT limits.",
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    connection.errorCode?.let { code ->
        SharedChatGptNote(chatGptSignInErrorMessage(code), isError = true)
    }
    SharedChatGptSignInButton(
        label = if (failure.kind == ChatGptFailureKind.PLAN_USAGE_NOT_ALLOWED) {
            readerString("chatgpt_allow_plan_usage", "Allow ChatGPT plan usage")
        } else {
            readerString("chatgpt_sign_in", "Sign in with ChatGPT")
        },
        isConnecting = connection.status == ChatGptConnectionStatus.CONNECTING,
        onClick = onSignIn,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}

@Composable
private fun SharedChatGptAnswerCheckFailure(
    title: String,
    failure: ChatGptFailure,
    onRetry: () -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = if (failure.kind == ChatGptFailureKind.NO_ANSWERS_ON_PAGE) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.padding(top = 2.dp).size(20.dp),
        )
        Text(chatGptFailureMessage(failure), style = MaterialTheme.typography.bodyLarge)
    }
    if (failure.kind == ChatGptFailureKind.USAGE_LIMIT || failure.kind == ChatGptFailureKind.NOT_ELIGIBLE) {
        TextButton(onClick = { openSharedMobileExternalUrl(CHATGPT_USAGE_SETTINGS_URL) }) {
            Text(readerString("chatgpt_manage_usage", "Manage usage in ChatGPT"))
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = onRetry) {
            Text(
                if (failure.kind == ChatGptFailureKind.NO_ANSWERS_ON_PAGE) {
                    readerString("chatgpt_check_anyway", "Check anyway")
                } else {
                    readerString("chatgpt_try_again", "Try again")
                }
            )
        }
    }
}

@Composable
private fun SharedChatGptNote(text: String, isError: Boolean) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SharedChatGptPlanFooter(connection: ChatGptConnectionState, modelName: String?) {
    val account = connection.accountLabel
    if (!connection.isConnected || account == null) return
    val usage = if (modelName != null) {
        readerString("chatgpt_footer_with_model", "%1\$s · ChatGPT plan of %2\$s", modelName, account)
    } else {
        readerString("chatgpt_footer", "ChatGPT plan of %1\$s", account)
    }
    Text(
        usage,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * "Sign in with ChatGPT" as a high-contrast pill, the way OpenAI's
 * guidelines present it. Text only: OpenAI's marks are not bundled here.
 */
@Composable
fun SharedChatGptSignInButton(
    label: String,
    isConnecting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.surface.isDarkSurface()
    Button(
        onClick = onClick,
        enabled = !isConnecting,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (dark) Color.White else Color.Black,
            contentColor = if (dark) Color.Black else Color.White,
        ),
        modifier = modifier,
    ) {
        if (isConnecting) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                readerString("chatgpt_signing_in", "Waiting for ChatGPT…"),
                modifier = Modifier.padding(start = 8.dp),
            )
        } else {
            Text(label, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun Color.isDarkSurface(): Boolean = (red * 0.299f + green * 0.587f + blue * 0.114f) < 0.5f

/**
 * The ChatGPT block of the AI settings screen: sign-in state, which model
 * checks answers, and where plan usage is managed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedChatGptAccountSection(
    connection: ChatGptConnectionState,
    models: List<ChatGptModel>,
    selectedModelSlug: String?,
    isLoadingModels: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSelectModel: (String) -> Unit,
    onLoadModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            readerString("chatgpt_settings_title", "ChatGPT"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            readerString(
                "chatgpt_settings_description",
                "Check the answers you write in PDF books. Open a page with your answers and tap the check button in the toolbar. Runs on your ChatGPT plan, no API key needed.",
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (connection.status) {
            ChatGptConnectionStatus.CONNECTED -> {
                connection.accountLabel?.let { account ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            readerString("chatgpt_signed_in_as", "Signed in as %1\$s", account),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                if (connection.planUsageAllowed) {
                    SharedChatGptModelPicker(
                        models = models,
                        selectedModelSlug = selectedModelSlug,
                        isLoading = isLoadingModels,
                        onSelectModel = onSelectModel,
                        onLoadModels = onLoadModels,
                    )
                } else {
                    SharedChatGptNote(
                        readerString(
                            "chatgpt_plan_usage_off",
                            "Plan usage is not allowed for this app yet, so answers cannot be checked.",
                        ),
                        isError = true,
                    )
                    SharedChatGptSignInButton(
                        label = readerString("chatgpt_allow_plan_usage", "Allow ChatGPT plan usage"),
                        isConnecting = false,
                        onClick = onSignIn,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { openSharedMobileExternalUrl(CHATGPT_USAGE_SETTINGS_URL) }) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(readerString("chatgpt_manage_usage", "Manage usage in ChatGPT"), modifier = Modifier.padding(start = 6.dp))
                    }
                    TextButton(onClick = onSignOut) {
                        Text(readerString("chatgpt_sign_out", "Sign out"), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            ChatGptConnectionStatus.REAUTH_REQUIRED,
            ChatGptConnectionStatus.DISCONNECTED,
            ChatGptConnectionStatus.CONNECTING -> {
                if (connection.status == ChatGptConnectionStatus.REAUTH_REQUIRED) {
                    SharedChatGptNote(
                        readerString(
                            "chatgpt_error_session_expired",
                            "Your ChatGPT sign-in has expired. Sign in again to continue.",
                        ),
                        isError = true,
                    )
                }
                connection.errorCode?.let { code -> SharedChatGptNote(chatGptSignInErrorMessage(code), isError = true) }
                SharedChatGptSignInButton(
                    label = readerString("chatgpt_sign_in", "Sign in with ChatGPT"),
                    isConnecting = connection.status == ChatGptConnectionStatus.CONNECTING,
                    onClick = onSignIn,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (connection.status == ChatGptConnectionStatus.REAUTH_REQUIRED) {
                    TextButton(onClick = onSignOut) {
                        Text(readerString("chatgpt_forget_account", "Forget this account"))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharedChatGptModelPicker(
    models: List<ChatGptModel>,
    selectedModelSlug: String?,
    isLoading: Boolean,
    onSelectModel: (String) -> Unit,
    onLoadModels: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = models.firstOrNull { it.slug == selectedModelSlug } ?: models.firstOrNull()
    SharedExposedDropdownMenuField(
        expanded = expanded,
        onExpandedChange = { open ->
            if (open && models.isEmpty()) onLoadModels()
            expanded = open && models.isNotEmpty()
        },
        modifier = Modifier.fillMaxWidth(),
        field = {
            OutlinedTextField(
                value = selected?.displayName ?: selectedModelSlug.orEmpty(),
                onValueChange = {},
                readOnly = true,
                label = { Text(readerString("chatgpt_model_label", "Model for answer checks")) },
                placeholder = {
                    Text(readerString("chatgpt_model_default", "Default (first available)"))
                },
                trailingIcon = {
                    if (isLoading) {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        models.forEach { model ->
            DropdownMenuItem(
                text = { Text(model.displayName) },
                onClick = {
                    onSelectModel(model.slug)
                    expanded = false
                },
                trailingIcon = if (model.slug == selected?.slug) {
                    { Icon(Icons.Default.Check, contentDescription = null) }
                } else {
                    null
                },
            )
        }
    }
    if (models.isEmpty() && !isLoading) {
        OutlinedButton(onClick = onLoadModels) {
            Text(readerString("chatgpt_load_models", "Load available models"))
        }
    }
}

@Composable
internal fun chatGptFailureMessage(failure: ChatGptFailure): String {
    val message = when (failure.kind) {
        ChatGptFailureKind.SIGN_IN_REQUIRED -> if (failure.code == "reauth_required") {
            readerString("chatgpt_error_session_expired", "Your ChatGPT sign-in has expired. Sign in again to continue.")
        } else {
            readerString("chatgpt_error_sign_in_required", "Sign in with ChatGPT to check your answers.")
        }
        ChatGptFailureKind.PLAN_USAGE_NOT_ALLOWED -> readerString(
            "chatgpt_error_plan_usage_not_allowed",
            "Allow Episteme to use your ChatGPT plan to check answers.",
        )
        ChatGptFailureKind.NOT_ELIGIBLE -> readerString(
            "chatgpt_error_not_eligible",
            "This ChatGPT account or workspace cannot share its plan with apps. A ChatGPT Plus or Pro plan is required.",
        )
        ChatGptFailureKind.USAGE_LIMIT -> readerString(
            "chatgpt_error_usage_limit",
            "You have reached your ChatGPT usage limit for this app. Check your limits in ChatGPT settings.",
        )
        ChatGptFailureKind.RATE_LIMITED -> readerString(
            "chatgpt_error_rate_limited",
            "Too many requests right now. Wait a moment and try again.",
        )
        ChatGptFailureKind.UNSUPPORTED_REQUEST -> readerString(
            "chatgpt_error_unsupported",
            "ChatGPT could not process this request. Try another model in AI settings.",
        )
        ChatGptFailureKind.MODEL_UNAVAILABLE -> readerString(
            "chatgpt_error_model_unavailable",
            "The selected model is not available for your ChatGPT account. Choose another model in AI settings.",
        )
        ChatGptFailureKind.AUTH_REJECTED -> readerString(
            "chatgpt_error_auth_rejected",
            "ChatGPT did not accept the saved sign-in. Sign in again.",
        )
        ChatGptFailureKind.APP_NOT_ENABLED -> readerString(
            "chatgpt_error_app_not_enabled",
            "OpenAI has not enabled this app for ChatGPT plan usage.",
        )
        ChatGptFailureKind.FORBIDDEN -> readerString(
            "chatgpt_error_forbidden",
            "ChatGPT blocked this request because of a permission or region restriction.",
        )
        ChatGptFailureKind.SERVICE_UNAVAILABLE -> readerString(
            "chatgpt_error_service_unavailable",
            "ChatGPT is temporarily unavailable. Try again shortly.",
        )
        ChatGptFailureKind.NETWORK -> readerString(
            "chatgpt_error_network",
            "Could not reach ChatGPT. Check your connection and try again.",
        )
        ChatGptFailureKind.NO_ANSWERS_ON_PAGE -> readerString(
            "chatgpt_error_no_answers",
            "There are no answers on this page yet. Write them with the pen or a text box, then check again.",
        )
        ChatGptFailureKind.PAGE_RENDER_FAILED -> readerString(
            "chatgpt_error_page_render",
            "This page could not be prepared for checking.",
        )
        ChatGptFailureKind.EMPTY_RESPONSE -> readerString(
            "chatgpt_error_empty",
            "ChatGPT returned an empty answer. Try again.",
        )
        ChatGptFailureKind.UNKNOWN -> readerString(
            "chatgpt_error_unknown",
            "Something went wrong while checking your answers.",
        )
    }
    val diagnostic = listOfNotNull(failure.code, failure.httpStatus?.let { "HTTP $it" }).joinToString(", ")
    val showsDiagnostic = failure.kind == ChatGptFailureKind.UNKNOWN ||
        failure.kind == ChatGptFailureKind.UNSUPPORTED_REQUEST ||
        failure.kind == ChatGptFailureKind.FORBIDDEN
    return if (showsDiagnostic && diagnostic.isNotEmpty()) "$message ($diagnostic)" else message
}

@Composable
private fun chatGptSignInErrorMessage(code: String): String = when (code) {
    "network_error" -> readerString("chatgpt_error_network", "Could not reach ChatGPT. Check your connection and try again.")
    "browser_unavailable", "browser_failed" -> readerString(
        "chatgpt_sign_in_error_browser",
        "The ChatGPT sign-in page could not be opened. Try again.",
    )
    "callback_failed", "callback_timeout", "callback_port_unavailable" -> readerString(
        "chatgpt_sign_in_error_interrupted",
        "Sign-in was interrupted. Stay in the app until it finishes, then try again.",
    )
    else -> readerString("chatgpt_sign_in_error", "Sign in with ChatGPT did not finish (%1\$s). Try again.", code)
}
