package com.aryan.reader.shared.chatgpt

import com.aryan.reader.shared.pdf.PdfAnnotationKind
import com.aryan.reader.shared.pdf.PdfInkTool
import com.aryan.reader.shared.pdf.PdfPageBounds
import com.aryan.reader.shared.pdf.PdfPagePoint
import com.aryan.reader.shared.pdf.SharedPdfAnnotation
import com.aryan.reader.shared.pdf.SharedPdfAnnotationComment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatGptAnswerCheckPromptTest {
    private fun ink(id: String, tool: PdfInkTool = PdfInkTool.PEN) = SharedPdfAnnotation(
        id = id,
        pageIndex = 4,
        kind = PdfAnnotationKind.INK,
        tool = tool,
        points = listOf(PdfPagePoint(0.1f, 0.1f), PdfPagePoint(0.2f, 0.2f)),
        colorArgb = 0xFF1976D2.toInt(),
    )

    private fun textBox(id: String, text: String, bounds: PdfPageBounds) = SharedPdfAnnotation(
        id = id,
        pageIndex = 4,
        kind = PdfAnnotationKind.TEXT,
        tool = PdfInkTool.TEXT,
        bounds = bounds,
        text = text,
        colorArgb = 0xFF000000.toInt(),
    )

    @Test
    fun `page context collects every kind of student work`() {
        val context = buildChatGptAnswerCheckPageContext(
            bookTitle = " Logic 101 ",
            pageLabel = "12",
            pageText = "  1. Is the argument valid?  ",
            annotations = listOf(
                ink("a"),
                ink("b", PdfInkTool.FOUNTAIN_PEN),
                textBox("late", "Answer 2: invalid", PdfPageBounds(0.6f, 0.8f, 0.9f, 0.85f)),
                textBox("early", "Answer 1: valid", PdfPageBounds(0.05f, 0.1f, 0.3f, 0.15f)),
                textBox("blank", "   ", PdfPageBounds(0.4f, 0.4f, 0.5f, 0.5f)),
                SharedPdfAnnotation(
                    id = "h",
                    pageIndex = 4,
                    kind = PdfAnnotationKind.HIGHLIGHT,
                    text = "(c) All men are mortal",
                    note = "my choice",
                    comments = listOf(SharedPdfAnnotationComment(id = "c1", contents = "because of modus ponens")),
                    colorArgb = 0x66FFEB3B,
                ),
            ),
        )

        assertEquals("Logic 101", context.bookTitle)
        assertEquals("1. Is the argument valid?", context.pageText)
        assertEquals(2, context.inkStrokeCount)
        assertEquals(listOf("Answer 1: valid", "Answer 2: invalid"), context.typedNotes.map { it.text })
        assertEquals("top left (5% from left, 10% from top)", context.typedNotes.first().position)
        assertEquals(listOf("(c) All men are mortal"), context.highlightedPassages)
        assertEquals(listOf("my choice", "because of modus ponens"), context.attachedNotes)
        assertTrue(context.hasStudentWork)
    }

    @Test
    fun `erasers and empty pages are not student work`() {
        val context = buildChatGptAnswerCheckPageContext(
            bookTitle = "Logic",
            pageLabel = "1",
            pageText = "Exercises",
            annotations = listOf(ink("e", PdfInkTool.ERASER), ink("n", PdfInkTool.NONE)),
        )
        assertEquals(0, context.inkStrokeCount)
        assertFalse(context.hasStudentWork)
    }

    @Test
    fun `page text is capped`() {
        val context = buildChatGptAnswerCheckPageContext(
            bookTitle = "",
            pageLabel = "",
            pageText = "x".repeat(CHATGPT_ANSWER_CHECK_MAX_PAGE_TEXT_CHARS + 50),
            annotations = emptyList(),
        )
        assertEquals(CHATGPT_ANSWER_CHECK_MAX_PAGE_TEXT_CHARS, context.pageText.length)
    }

    @Test
    fun `positions read like a person would describe them`() {
        assertEquals("center (40% from left, 45% from top)", describePagePosition(PdfPageBounds(0.4f, 0.45f, 0.6f, 0.55f)))
        assertEquals("bottom right (80% from left, 90% from top)", describePagePosition(PdfPageBounds(0.8f, 0.9f, 0.95f, 0.95f)))
        assertEquals("position unknown", describePagePosition(null))
    }

    @Test
    fun `instructions name the reply language`() {
        assertTrue("Write the entire reply in Russian." in chatGptAnswerCheckInstructions("Russian"))
        assertTrue("Write the entire reply in English." in chatGptAnswerCheckInstructions("  "))
    }

    @Test
    fun `user message carries page text, notes and the picture hint`() {
        val context = ChatGptAnswerCheckPageContext(
            bookTitle = "Logic",
            pageLabel = "7",
            pageText = "1. Symbolize the sentence.",
            typedNotes = listOf(ChatGptTypedNote("P -> Q", "top left (5% from left, 10% from top)")),
            highlightedPassages = listOf("(b)"),
            attachedNotes = emptyList(),
            inkStrokeCount = 3,
        )
        val message = chatGptAnswerCheckUserMessage(context, hasPageImage = true)

        assertTrue("Book: Logic" in message)
        assertTrue("Page: 7" in message)
        assertTrue("\"\"\"\n1. Symbolize the sentence.\n\"\"\"" in message)
        assertTrue("- At the top left (5% from left, 10% from top): \"P -> Q\"" in message)
        assertTrue("- \"(b)\"" in message)
        assertTrue("Handwritten strokes on the page: 3." in message)
        assertFalse("Comments the student attached" in message)
    }

    @Test
    fun `user message without a text layer points at the picture`() {
        val context = ChatGptAnswerCheckPageContext("", "", "", emptyList(), emptyList(), emptyList(), 0)
        val withImage = chatGptAnswerCheckUserMessage(context, hasPageImage = true)
        assertTrue("The page has no text layer; read the exercises from the picture." in withImage)
        assertFalse("Book:" in withImage)
        val withoutImage = chatGptAnswerCheckUserMessage(context, hasPageImage = false)
        assertTrue("No picture of the page is attached" in withoutImage)
    }
}
