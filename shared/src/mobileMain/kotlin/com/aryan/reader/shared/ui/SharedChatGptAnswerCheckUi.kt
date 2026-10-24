package com.aryan.reader.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aryan.reader.shared.chatgpt.CHATGPT_CONTEXT_MAX_PAGES
import com.aryan.reader.shared.chatgpt.CHATGPT_CONTEXT_PREVIOUS_PAGE_CHOICES
import com.aryan.reader.shared.chatgpt.CHATGPT_FOLLOW_UP_MAX_CHARS
import com.aryan.reader.shared.chatgpt.CHATGPT_USAGE_SETTINGS_URL
import com.aryan.reader.shared.chatgpt.ChatGptAnswerCheckContextSetting
import com.aryan.reader.shared.chatgpt.ChatGptConnectionState
import com.aryan.reader.shared.chatgpt.ChatGptConnectionStatus
import com.aryan.reader.shared.chatgpt.ChatGptContextRangeInput
import com.aryan.reader.shared.chatgpt.ChatGptFailure
import com.aryan.reader.shared.chatgpt.ChatGptFailureKind
import com.aryan.reader.shared.chatgpt.ChatGptFollowUp
import com.aryan.reader.shared.chatgpt.ChatGptFollowUpStatus
import com.aryan.reader.shared.chatgpt.ChatGptModel
import com.aryan.reader.shared.chatgpt.SharedChatGptAnswerCheckPhase
import com.aryan.reader.shared.chatgpt.SharedPdfAnswerCheckHost
import com.aryan.reader.shared.chatgpt.chooseChatGptModel
import com.aryan.reader.shared.chatgpt.formatChatGptPageNumbers
import com.aryan.reader.shared.chatgpt.parseChatGptContextRange

/**
 * The answer-check popup, for screens too narrow for the panel beside the
 * PDF ([SharedPdfAnswerCheckPanel]): ChatGPT's feedback on the current page
 * streams in as Markdown, with the same read-aloud and copy actions as other
 * AI results. When there is no usable ChatGPT connection the sheet offers
 * sign-in in place, and the host resumes the check once the user is back.
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
                // The height range counts the padding, and the keyboard
                // (for a follow-up question) shrinks the content, not the sheet.
                .heightIn(min = 276.dp, max = 776.dp)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SharedChatGptAnswerCheckBody(
                host = host,
                bookTitle = bookTitle,
                isMainTtsActive = isMainTtsActive,
                fillHeight = false,
            )
            if (failure == null || !failure.needsSignIn) {
                SharedChatGptContextControls(
                    context = host.context,
                    pageCount = host.pageCount,
                    sentPageNumbers = state.contextPageNumbers,
                    isBusy = state.isBusy,
                    onContextChange = host.onContextChange,
                    onCheckAgain = host.onRetry,
                )
            }
            SharedChatGptPlanFooter(
                connection = host.connection,
                modelName = state.modelName,
                reasoningEffort = state.reasoningEffort,
            )
        }
    }
}

/**
 * The answer check in a panel beside the PDF, where it stays while the reader
 * keeps reading and writing: the check and the follow-up questions scroll in
 * the middle, the question field stays at the bottom, and the context pages
 * fold away under the header.
 */
@Composable
internal fun SharedPdfAnswerCheckPanel(
    host: SharedPdfAnswerCheckHost,
    bookTitle: String,
    isMainTtsActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val state = host.state
    val failure = state.failure
    val canChangeContext = failure == null || !failure.needsSignIn
    var showContext by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The bottom inset includes the keyboard, so the question
                // field rises above it.
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom
                    )
                )
                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    readerString("chatgpt_settings_title", "ChatGPT"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (canChangeContext) {
                    IconButton(onClick = { showContext = !showContext }) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = readerString("chatgpt_panel_context", "Context pages"),
                            tint = if (showContext) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                }
                IconButton(onClick = host.onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = readerString("chatgpt_panel_close", "Close ChatGPT"),
                    )
                }
            }
            if (showContext && canChangeContext) {
                SharedChatGptContextControls(
                    context = host.context,
                    pageCount = host.pageCount,
                    sentPageNumbers = state.contextPageNumbers,
                    isBusy = state.isBusy,
                    onContextChange = host.onContextChange,
                    onCheckAgain = host.onRetry,
                )
                HorizontalDivider()
            }
            SharedChatGptAnswerCheckBody(
                host = host,
                bookTitle = bookTitle,
                isMainTtsActive = isMainTtsActive,
                fillHeight = true,
            )
            SharedChatGptPlanFooter(
                connection = host.connection,
                modelName = state.modelName,
                reasoningEffort = state.reasoningEffort,
            )
        }
    }
}

/**
 * The check with its follow-up questions and the question field, or the
 * sign-in prompt or the failure in its place. With [fillHeight] the result
 * takes the rest of the column, which keeps the field at the bottom of the
 * panel; in the sheet the result only grows as tall as it needs.
 */
@Composable
private fun ColumnScope.SharedChatGptAnswerCheckBody(
    host: SharedPdfAnswerCheckHost,
    bookTitle: String,
    isMainTtsActive: Boolean,
    fillHeight: Boolean,
) {
    val state = host.state
    val failure = state.failure
    val title = readerString("chatgpt_answer_check_title", "Answer check · page %1\$s", state.pageLabel)
    when {
        failure != null && failure.needsSignIn -> {
            SharedChatGptSignInPrompt(
                title = title,
                failure = failure,
                connection = host.connection,
                onSignIn = host.onSignIn,
            )
            if (fillHeight) Spacer(Modifier.weight(1f))
        }
        failure != null && state.text.isBlank() -> {
            SharedChatGptAnswerCheckFailure(
                title = title,
                failure = failure,
                onRetry = host.onRetry,
            )
            if (fillHeight) Spacer(Modifier.weight(1f))
        }
        else -> {
            val isBusy = state.isBusy
            val followUps = state.followUps
            val resultScroll = rememberScrollState()
            // Show a new question and follow its answer while the
            // reader stays near the bottom.
            LaunchedEffect(followUps.size) {
                if (followUps.isNotEmpty()) {
                    withFrameNanos { }
                    resultScroll.animateScrollTo(resultScroll.maxValue)
                }
            }
            LaunchedEffect(followUps.lastOrNull()?.answer?.length) {
                if (resultScroll.maxValue - resultScroll.value < 600) {
                    resultScroll.scrollTo(resultScroll.maxValue)
                }
            }
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
                progressMessage = if (state.phase != SharedChatGptAnswerCheckPhase.PREPARING) {
                    readerString("chatgpt_answer_check_analyzing", "ChatGPT is checking your answers…")
                } else if (state.pagesToPrepare > 1) {
                    readerString(
                        "chatgpt_answer_check_preparing_pages",
                        "Preparing pages… %1\$d of %2\$d",
                        state.preparedPages.coerceAtMost(state.pagesToPrepare),
                        state.pagesToPrepare,
                    )
                } else {
                    readerString("chatgpt_answer_check_preparing", "Preparing the page…")
                },
                afterContent = if (followUps.isEmpty()) null else {
                    { SharedChatGptFollowUpList(followUps) }
                },
                scrollState = resultScroll,
                modifier = Modifier.weight(1f, fill = fillHeight),
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
            // Shown while the check runs too, so the question can be typed
            // ahead; it sends once the check is done.
            if (state.phase == SharedChatGptAnswerCheckPhase.COMPLETED || isBusy) {
                SharedChatGptFollowUpInput(
                    enabled = state.canAskFollowUp,
                    onAsk = host.onAskFollowUp,
                )
            }
        }
    }
}

/** The questions asked after the check and ChatGPT's answers, under the check itself. */
@Composable
private fun SharedChatGptFollowUpList(followUps: List<ChatGptFollowUp>) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        followUps.forEach { followUp ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(start = 48.dp),
                ) {
                    Text(
                        text = followUp.question,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            if (followUp.answer.isNotBlank()) {
                SharedMarkdownText(
                    markdown = followUp.answer,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (followUp.status == ChatGptFollowUpStatus.ANSWERING) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        readerString("chatgpt_follow_up_answering", "ChatGPT is answering…"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            followUp.failure?.let { failure ->
                SharedChatGptNote(chatGptFailureMessage(failure), isError = true)
            }
            if (followUp.status == ChatGptFollowUpStatus.ANSWERED && followUp.isTruncated) {
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
}

/** A question about the check. The next one can be typed while an answer is still coming in. */
@Composable
private fun SharedChatGptFollowUpInput(enabled: Boolean, onAsk: (String) -> Unit) {
    var question by remember { mutableStateOf("") }
    val send = {
        val text = question.trim()
        if (enabled && text.isNotEmpty()) {
            onAsk(text)
            question = ""
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = question,
            onValueChange = { question = it.take(CHATGPT_FOLLOW_UP_MAX_CHARS) },
            modifier = Modifier.weight(1f),
            placeholder = { Text(readerString("chatgpt_follow_up_hint", "Ask a follow-up question…")) },
            maxLines = 4,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        IconButton(onClick = send, enabled = enabled && question.isNotBlank()) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = readerString("chatgpt_follow_up_send", "Send"),
            )
        }
    }
}

/**
 * Which pages go with the check as context, saved for the book. A change
 * applies from the next check; nothing is sent until the reader asks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharedChatGptContextControls(
    context: ChatGptAnswerCheckContextSetting,
    pageCount: Int,
    sentPageNumbers: List<Int>,
    isBusy: Boolean,
    onContextChange: (ChatGptAnswerCheckContextSetting) -> Unit,
    onCheckAgain: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var editingRange by remember { mutableStateOf(false) }
    var changedSinceCheck by remember { mutableStateOf(false) }
    val savedRange = context.range
    var rangeFirst by remember(savedRange) { mutableStateOf(savedRange?.first?.toString().orEmpty()) }
    var rangeLast by remember(savedRange) { mutableStateOf(savedRange?.last?.toString().orEmpty()) }
    var rangeProblem by remember { mutableStateOf<ChatGptContextRangeInput?>(null) }

    fun change(setting: ChatGptAnswerCheckContextSetting) {
        if (setting == context) return
        onContextChange(setting)
        changedSinceCheck = true
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        SharedExposedDropdownMenuField(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth(),
            field = {
                OutlinedTextField(
                    value = if (savedRange != null) {
                        readerString("chatgpt_context_range_value", "Pages %1\$d–%2\$d", savedRange.first, savedRange.last)
                    } else {
                        chatGptPreviousPagesLabel(context.previousPages)
                    },
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(readerString("chatgpt_context_label", "Context")) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        ) {
            CHATGPT_CONTEXT_PREVIOUS_PAGE_CHOICES.forEach { count ->
                DropdownMenuItem(
                    text = { Text(chatGptPreviousPagesLabel(count)) },
                    onClick = {
                        expanded = false
                        editingRange = false
                        rangeProblem = null
                        change(context.copy(previousPages = count, range = null))
                    },
                    trailingIcon = if (savedRange == null && context.previousPages == count) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(readerString("chatgpt_context_range", "Page range…")) },
                onClick = {
                    expanded = false
                    editingRange = true
                },
                trailingIcon = if (savedRange != null) {
                    { Icon(Icons.Default.Check, contentDescription = null) }
                } else {
                    null
                },
            )
        }
        if (editingRange || savedRange != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = rangeFirst,
                    onValueChange = { rangeFirst = it.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    label = { Text(readerString("chatgpt_context_range_first", "First page")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = rangeLast,
                    onValueChange = { rangeLast = it.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    label = { Text(readerString("chatgpt_context_range_last", "Last page")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        when (val input = parseChatGptContextRange(rangeFirst, rangeLast, pageCount)) {
                            is ChatGptContextRangeInput.Valid -> {
                                rangeProblem = null
                                editingRange = false
                                change(context.copy(range = input.range))
                            }
                            else -> rangeProblem = input
                        }
                    },
                ) {
                    Text(readerString("chatgpt_context_range_apply", "Apply"))
                }
            }
            when (rangeProblem) {
                ChatGptContextRangeInput.OutOfBounds -> SharedChatGptNote(
                    readerString("chatgpt_context_range_invalid", "Enter pages from 1 to %1\$d.", pageCount),
                    isError = true,
                )
                ChatGptContextRangeInput.TooLong -> SharedChatGptNote(
                    readerString(
                        "chatgpt_context_range_too_long",
                        "A range can hold up to %1\$d pages.",
                        CHATGPT_CONTEXT_MAX_PAGES,
                    ),
                    isError = true,
                )
                else -> Unit
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                readerString(
                    "chatgpt_context_images",
                    "Also send pictures of context pages (formulas, diagrams)",
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Switch(
                checked = context.includeImages,
                onCheckedChange = { change(context.copy(includeImages = it)) },
            )
        }
        Text(
            if (sentPageNumbers.isNotEmpty()) {
                readerString(
                    "chatgpt_context_sent",
                    "Context sent: pages %1\$s. Pages without a text layer go as pictures.",
                    formatChatGptPageNumbers(sentPageNumbers),
                )
            } else {
                readerString(
                    "chatgpt_context_images_note",
                    "Pages without a text layer always go as pictures.",
                )
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (changedSinceCheck && !isBusy) {
            TextButton(
                onClick = {
                    changedSinceCheck = false
                    onCheckAgain()
                },
            ) {
                Text(readerString("chatgpt_context_check_again", "Check again with this context"))
            }
        }
    }
}

@Composable
private fun chatGptPreviousPagesLabel(count: Int): String = if (count <= 0) {
    readerString("chatgpt_context_current_only", "Current page only")
} else {
    readerString("chatgpt_context_previous", "Current page + %1\$d previous", count)
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
private fun SharedChatGptPlanFooter(connection: ChatGptConnectionState, modelName: String?, reasoningEffort: String?) {
    val account = connection.accountLabel
    if (!connection.isConnected || account == null) return
    val model = when {
        modelName == null -> null
        reasoningEffort != null -> "$modelName · ${chatGptReasoningEffortLabel(reasoningEffort)}"
        else -> modelName
    }
    val usage = if (model != null) {
        readerString("chatgpt_footer_with_model", "%1\$s · ChatGPT plan of %2\$s", model, account)
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
 * checks answers and how hard it reasons, and where plan usage is managed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedChatGptAccountSection(
    connection: ChatGptConnectionState,
    models: List<ChatGptModel>,
    selectedModelSlug: String?,
    isLoadingModels: Boolean,
    /** Reasoning effort saved per model slug. */
    reasoningEfforts: Map<String, String>,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSelectModel: (String) -> Unit,
    /** Saves an effort for a model slug; null returns it to the model's default. */
    onSelectReasoningEffort: (String, String?) -> Unit,
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
                    chooseChatGptModel(models, selectedModelSlug)?.let { model ->
                        SharedChatGptReasoningEffortPicker(
                            model = model,
                            savedEffort = reasoningEfforts[model.slug],
                            onSelect = { effort -> onSelectReasoningEffort(model.slug, effort) },
                        )
                    }
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

/**
 * Reasoning effort for the model that checks answers. Only the levels the
 * account's catalog lists for that model are offered; "Default" sends no
 * effort and lets the model use its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharedChatGptReasoningEffortPicker(
    model: ChatGptModel,
    savedEffort: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val available = model.reasoningEfforts
    // A level the catalog no longer lists is not sent, so show it as the default.
    val selected = savedEffort?.takeIf { it in available }
    val defaultLabel = model.defaultReasoningEffort?.let { effort ->
        readerString("chatgpt_reasoning_default_with_level", "Default (%1\$s)", chatGptReasoningEffortLabel(effort))
    } ?: readerString("chatgpt_reasoning_default", "Default")
    SharedExposedDropdownMenuField(
        expanded = expanded,
        onExpandedChange = { open -> expanded = open && available.isNotEmpty() },
        modifier = Modifier.fillMaxWidth(),
        field = {
            OutlinedTextField(
                value = selected?.let { chatGptReasoningEffortLabel(it) } ?: defaultLabel,
                onValueChange = {},
                readOnly = true,
                enabled = available.isNotEmpty(),
                singleLine = true,
                label = { Text(readerString("chatgpt_reasoning_label", "Reasoning effort")) },
                supportingText = if (available.isEmpty()) {
                    {
                        Text(
                            readerString(
                                "chatgpt_reasoning_unavailable",
                                "This model lists no adjustable levels, so it uses its default.",
                            )
                        )
                    }
                } else {
                    null
                },
                trailingIcon = if (available.isNotEmpty()) {
                    { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        DropdownMenuItem(
            text = { Text(defaultLabel) },
            onClick = {
                onSelect(null)
                expanded = false
            },
            trailingIcon = if (selected == null) {
                { Icon(Icons.Default.Check, contentDescription = null) }
            } else {
                null
            },
        )
        available.forEach { effort ->
            DropdownMenuItem(
                text = { Text(chatGptReasoningEffortLabel(effort)) },
                onClick = {
                    onSelect(effort)
                    expanded = false
                },
                trailingIcon = if (effort == selected) {
                    { Icon(Icons.Default.Check, contentDescription = null) }
                } else {
                    null
                },
            )
        }
    }
}

/** The name of a `reasoning.effort` value; levels this app does not know show as sent. */
@Composable
internal fun chatGptReasoningEffortLabel(effort: String): String = when (effort) {
    "none" -> readerString("chatgpt_reasoning_none", "None")
    "minimal" -> readerString("chatgpt_reasoning_minimal", "Minimal")
    "low" -> readerString("chatgpt_reasoning_low", "Low")
    "medium" -> readerString("chatgpt_reasoning_medium", "Medium")
    "high" -> readerString("chatgpt_reasoning_high", "High")
    "xhigh" -> readerString("chatgpt_reasoning_xhigh", "Extra high")
    "max" -> readerString("chatgpt_reasoning_max", "Maximum")
    else -> effort.replaceFirstChar { it.uppercaseChar() }
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
        ChatGptFailureKind.REASONING_EFFORT_REJECTED -> readerString(
            "chatgpt_error_reasoning_rejected",
            "ChatGPT did not accept the reasoning effort chosen for this model. Choose another level or Default in AI settings.",
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
