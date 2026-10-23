package com.aryan.reader.shared.chatgpt

import com.aryan.reader.shared.pdf.PdfAnnotationKind
import com.aryan.reader.shared.pdf.PdfInkTool
import com.aryan.reader.shared.pdf.PdfPageBounds
import com.aryan.reader.shared.pdf.SharedPdfAnnotation
import kotlin.math.roundToInt

/** Printed page text is the exercise statement; past this size it only costs plan usage. */
const val CHATGPT_ANSWER_CHECK_MAX_PAGE_TEXT_CHARS = 12_000

/** Text budget shared by all context pages of one check. */
const val CHATGPT_CONTEXT_MAX_TEXT_CHARS = 60_000

/** Below this many non-blank characters a page counts as having no text layer. */
const val CHATGPT_MIN_TEXT_LAYER_CHARS = 16
private const val MAX_NOTE_CHARS = 1_000
private const val MAX_NOTES = 40

/** A text-box answer the student typed onto the page, with where it sits. */
data class ChatGptTypedNote(
    val text: String,
    val position: String,
)

/** Everything about one page the answer check sends besides the page image. */
data class ChatGptAnswerCheckPageContext(
    val bookTitle: String,
    val pageLabel: String,
    val pageText: String,
    val typedNotes: List<ChatGptTypedNote>,
    val highlightedPassages: List<String>,
    val attachedNotes: List<String>,
    val inkStrokeCount: Int,
) {
    /** False when the student has not written, typed or highlighted anything on the page. */
    val hasStudentWork: Boolean
        get() = inkStrokeCount > 0 ||
            typedNotes.isNotEmpty() ||
            highlightedPassages.isNotEmpty() ||
            attachedNotes.isNotEmpty()
}

/**
 * Collects the student's work on one PDF page from the reader's annotations.
 *
 * Ink is only counted here: the strokes themselves travel in the page image,
 * where the model reads the handwriting in place. Typed text boxes and
 * highlights are sent as text so they arrive verbatim.
 */
fun buildChatGptAnswerCheckPageContext(
    bookTitle: String,
    pageLabel: String,
    pageText: String,
    annotations: List<SharedPdfAnnotation>,
): ChatGptAnswerCheckPageContext {
    val inkStrokeCount = annotations.count {
        it.kind == PdfAnnotationKind.INK &&
            it.points.isNotEmpty() &&
            it.tool != PdfInkTool.NONE &&
            it.tool != PdfInkTool.SELECT &&
            it.tool != PdfInkTool.ERASER
    }
    val typedNotes = annotations
        .filter { it.kind == PdfAnnotationKind.TEXT && it.text.isNotBlank() }
        .sortedWith(compareBy({ it.bounds?.top ?: 0f }, { it.bounds?.left ?: 0f }))
        .take(MAX_NOTES)
        .map { ChatGptTypedNote(text = it.text.trim().take(MAX_NOTE_CHARS), position = describePagePosition(it.bounds)) }
    val highlights = annotations.filter { it.kind == PdfAnnotationKind.HIGHLIGHT }
    val highlightedPassages = highlights
        .mapNotNull { it.text.trim().takeIf(String::isNotEmpty)?.take(MAX_NOTE_CHARS) }
        .distinct()
        .take(MAX_NOTES)
    val attachedNotes = annotations
        .flatMap { annotation ->
            listOfNotNull(annotation.note) + annotation.comments.map { it.contents }
        }
        .mapNotNull { it.trim().takeIf(String::isNotEmpty)?.take(MAX_NOTE_CHARS) }
        .distinct()
        .take(MAX_NOTES)
    return ChatGptAnswerCheckPageContext(
        bookTitle = bookTitle.trim(),
        pageLabel = pageLabel.trim(),
        pageText = pageText.trim().take(CHATGPT_ANSWER_CHECK_MAX_PAGE_TEXT_CHARS),
        typedNotes = typedNotes,
        highlightedPassages = highlightedPassages,
        attachedNotes = attachedNotes,
        inkStrokeCount = inkStrokeCount,
    )
}

/** "top left (12% from left, 8% from top)" for a normalized page rect. */
internal fun describePagePosition(bounds: PdfPageBounds?): String {
    if (bounds == null) return "position unknown"
    val centerX = ((bounds.left + bounds.right) / 2f).coerceIn(0f, 1f)
    val centerY = ((bounds.top + bounds.bottom) / 2f).coerceIn(0f, 1f)
    val vertical = when {
        centerY < 1f / 3f -> "top"
        centerY < 2f / 3f -> "middle"
        else -> "bottom"
    }
    val horizontal = when {
        centerX < 1f / 3f -> "left"
        centerX < 2f / 3f -> "center"
        else -> "right"
    }
    val area = if (vertical == "middle" && horizontal == "center") "center" else "$vertical $horizontal"
    val leftPercent = (bounds.left.coerceIn(0f, 1f) * 100f).roundToInt()
    val topPercent = (bounds.top.coerceIn(0f, 1f) * 100f).roundToInt()
    return "$area ($leftPercent% from left, $topPercent% from top)"
}

/** Whether [text] is a real text layer rather than nothing or a stray page number. */
fun chatGptPageHasTextLayer(text: String): Boolean = text.count { !it.isWhitespace() } >= CHATGPT_MIN_TEXT_LAYER_CHARS

/** Characters of text each of [contextPageCount] context pages may send. */
fun chatGptContextPageTextLimit(contextPageCount: Int): Int =
    (CHATGPT_CONTEXT_MAX_TEXT_CHARS / contextPageCount.coerceAtLeast(1)).coerceIn(1_500, 8_000)

/** A page sent only for its exercise statements and rules; nothing on it is checked. */
data class ChatGptContextPage(
    /** 1-based page number in the PDF. */
    val pageNumber: Int,
    /** The page's text layer, possibly shortened; empty when it has none. */
    val text: String,
    val isTextShortened: Boolean = false,
    /** The page picture as a data URL, when one is sent. */
    val imageDataUrl: String? = null,
)

/**
 * The tutor instructions. [responseLanguage] is an English language name
 * ("Russian"); the reply is written in it whatever language the book uses.
 */
fun chatGptAnswerCheckInstructions(responseLanguage: String): String {
    val language = responseLanguage.trim().ifEmpty { "English" }
    return """
        You are a patient, rigorous tutor checking a student's work in a textbook.

        What you receive, each part labelled with its page number:
        - The page to check: a picture of the textbook page with the student's work on it (handwriting drawn as coloured ink strokes, highlighted passages as translucent colour), the printed text extracted from the page, and the notes the student typed onto it with their position. Use the extracted text to read the exercises exactly, and the picture for the handwriting, diagrams and symbols.
        - Possibly context pages, with their text and sometimes their picture. Context pages are only the source of the exercise statements, conditions, definitions, rules and examples that the page to check relies on.

        Rules:
        - Check only the answers on the page to check. Never check, grade or comment on anything written on a context page.
        - Use the context pages to understand the exercises and the rules they apply, and name the page when you rely on one.
        - If an exercise depends on a statement, table, rule or text that is neither on the page to check nor on a context page, say so for that exercise and name what is missing. Do not guess or invent missing conditions; check only what the given material allows. The student can include more pages or a page range in the app.

        How to check:
        1. Find every exercise or question on the page to check that the student attempted. Treat a highlighted or circled option as the student's choice in multiple-choice tasks.
        2. For each attempted exercise, give its number or a short paraphrase, transcribe the student's answer as you read it, and mark it ✅ correct, ⚠️ partially correct, ❌ incorrect, or ❓ when the given material is not enough to judge it.
        3. Explain each mistake briefly and show the correct reasoning and answer. Work carefully and step by step on formal tasks such as logic (validity, truth tables, derivations, symbolization, fallacies) and mathematics before you judge.
        4. If handwriting is illegible, say which part you cannot read instead of guessing.
        5. Do not grade exercises the student left blank; list them in one line at the end.

        Format the reply in Markdown: start with one line stating how many attempted answers are correct, then one "###" heading per exercise with short bullet points, and finish with a short overall comment and one concrete tip. Do not copy page texts.

        Write the entire reply in $language. Keep formulas, symbols and quoted terms exactly as written on the page.
    """.trimIndent()
}

/**
 * The user message, in reading order: an overview, each context page (text,
 * then its picture when sent) and last the page to check with its picture.
 */
fun chatGptAnswerCheckUserContent(
    current: ChatGptAnswerCheckPageContext,
    currentPageImageDataUrl: String?,
    contextPages: List<ChatGptContextPage>,
): List<ChatGptInputPart> = buildList {
    val pageToCheck = current.pageLabel.ifEmpty { "?" }
    add(
        ChatGptInputPart.Text(
            buildString {
                if (current.bookTitle.isNotEmpty()) appendLine("Book: ${current.bookTitle}")
                appendLine("Page to check: $pageToCheck (it comes last).")
                if (contextPages.isEmpty()) {
                    append("No context pages are included.")
                } else {
                    append("Context pages (statements and rules only, not to be checked): ")
                    append(formatChatGptPageNumbers(contextPages.map { it.pageNumber }))
                    append('.')
                }
            }
        )
    )
    contextPages.forEach { page ->
        add(
            ChatGptInputPart.Text(
                buildString {
                    appendLine("=== Context page ${page.pageNumber}: statements and rules only, do not check ===")
                    if (page.text.isNotEmpty()) {
                        val shortened = if (page.isTextShortened) ", shortened" else ""
                        appendLine("Printed text of page ${page.pageNumber}$shortened:")
                        appendLine("\"\"\"")
                        appendLine(page.text)
                        append("\"\"\"")
                    } else {
                        append("Page ${page.pageNumber} has no text layer; read it from its picture.")
                    }
                    if (page.imageDataUrl != null) {
                        appendLine()
                        append("Picture of page ${page.pageNumber}:")
                    }
                }
            )
        )
        page.imageDataUrl?.let { add(ChatGptInputPart.Image(it)) }
    }
    add(ChatGptInputPart.Text(chatGptPageToCheckText(current, hasPageImage = currentPageImageDataUrl != null)))
    currentPageImageDataUrl?.let { add(ChatGptInputPart.Image(it)) }
    add(ChatGptInputPart.Text("Check only the student's answers on page $pageToCheck."))
}

/** The page to check as text; its picture follows it. */
private fun chatGptPageToCheckText(context: ChatGptAnswerCheckPageContext, hasPageImage: Boolean): String = buildString {
    appendLine("=== Page ${context.pageLabel.ifEmpty { "?" }}: the page to check ===")
    if (context.pageText.isNotEmpty()) {
        appendLine("Printed text extracted from the page (it may be incomplete or out of reading order):")
        appendLine("\"\"\"")
        appendLine(context.pageText)
        appendLine("\"\"\"")
    } else {
        appendLine("The page has no text layer; read the exercises from the picture.")
    }
    appendLine()
    if (context.typedNotes.isNotEmpty()) {
        appendLine("Notes the student typed onto the page:")
        context.typedNotes.forEach { note -> appendLine("- At the ${note.position}: \"${note.text}\"") }
        appendLine()
    }
    if (context.highlightedPassages.isNotEmpty()) {
        appendLine("Passages the student highlighted:")
        context.highlightedPassages.forEach { passage -> appendLine("- \"$passage\"") }
        appendLine()
    }
    if (context.attachedNotes.isNotEmpty()) {
        appendLine("Comments the student attached to annotations:")
        context.attachedNotes.forEach { note -> appendLine("- \"$note\"") }
        appendLine()
    }
    if (hasPageImage) {
        append("Handwritten strokes on the page: ${context.inkStrokeCount}. The next picture shows this page with the student's work.")
    } else {
        append("No picture of this page is attached; check the answers from the text above.")
    }
}
