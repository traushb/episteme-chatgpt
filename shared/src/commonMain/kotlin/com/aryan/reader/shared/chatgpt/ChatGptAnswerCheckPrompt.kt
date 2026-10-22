package com.aryan.reader.shared.chatgpt

import com.aryan.reader.shared.pdf.PdfAnnotationKind
import com.aryan.reader.shared.pdf.PdfInkTool
import com.aryan.reader.shared.pdf.PdfPageBounds
import com.aryan.reader.shared.pdf.SharedPdfAnnotation
import kotlin.math.roundToInt

/** Printed page text is the exercise statement; past this size it only costs plan usage. */
const val CHATGPT_ANSWER_CHECK_MAX_PAGE_TEXT_CHARS = 12_000
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

/**
 * The tutor instructions. [responseLanguage] is an English language name
 * ("Russian"); the reply is written in it whatever language the book uses.
 */
fun chatGptAnswerCheckInstructions(responseLanguage: String): String {
    val language = responseLanguage.trim().ifEmpty { "English" }
    return """
        You are a patient, rigorous tutor checking a student's work in a textbook.

        You receive a picture of one textbook page. The student answered the exercises directly on the page: handwriting is drawn as coloured ink strokes over the printed page, highlighted passages appear as translucent colour, and typed notes are listed in the message with their position. The printed text of the page is included as extracted text; use it to read the exercises exactly, and use the picture for the student's handwriting, diagrams and symbols.

        How to check:
        1. Find every exercise or question on the page that the student attempted. Treat a highlighted or circled option as the student's choice in multiple-choice tasks.
        2. For each attempted exercise, give its number or a short paraphrase, transcribe the student's answer as you read it, and mark it ✅ correct, ⚠️ partially correct, or ❌ incorrect.
        3. Explain each mistake briefly and show the correct reasoning and answer. Work carefully and step by step on formal tasks such as logic (validity, truth tables, derivations, symbolization, fallacies) and mathematics before you judge.
        4. If handwriting is illegible, say which part you cannot read instead of guessing.
        5. Do not grade exercises the student left blank; list them in one line at the end.

        Format the reply in Markdown: start with one line stating how many attempted answers are correct, then one "###" heading per exercise with short bullet points, and finish with a short overall comment and one concrete tip. Do not copy the whole page text.

        Write the entire reply in $language. Keep formulas, symbols and quoted terms exactly as written on the page.
    """.trimIndent()
}

/** The text part of the user message; the page picture travels next to it. */
fun chatGptAnswerCheckUserMessage(context: ChatGptAnswerCheckPageContext, hasPageImage: Boolean): String = buildString {
    if (context.bookTitle.isNotEmpty()) appendLine("Book: ${context.bookTitle}")
    if (context.pageLabel.isNotEmpty()) appendLine("Page: ${context.pageLabel}")
    appendLine()
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
        appendLine("Handwritten strokes on the page: ${context.inkStrokeCount}. The attached picture shows the page with the student's work.")
    } else {
        appendLine("No picture of the page is attached; check the answers from the text above.")
    }
    append("Please check the student's answers on this page.")
}
