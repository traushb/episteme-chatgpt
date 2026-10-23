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
    fun `instructions keep context pages for conditions and forbid guessing`() {
        val instructions = chatGptAnswerCheckInstructions("English")
        assertTrue("Check only the answers on the page to check." in instructions)
        assertTrue("Never check, grade or comment on anything written on a context page." in instructions)
        assertTrue("Do not guess or invent missing conditions" in instructions)
        assertTrue("❓ when the given material is not enough to judge it" in instructions)
    }

    private val pageSeven = ChatGptAnswerCheckPageContext(
        bookTitle = "Logic",
        pageLabel = "7",
        pageText = "1. Symbolize the sentence.",
        typedNotes = listOf(ChatGptTypedNote("P -> Q", "top left (5% from left, 10% from top)")),
        highlightedPassages = listOf("(b)"),
        attachedNotes = emptyList(),
        inkStrokeCount = 3,
    )

    @Test
    fun `message lists labelled context pages first and the page to check last`() {
        val parts = chatGptAnswerCheckUserContent(
            current = pageSeven,
            currentPageImageDataUrl = "data:image/jpeg;base64,PAGE",
            contextPages = listOf(
                ChatGptContextPage(pageNumber = 5, text = "Rule 5: modus ponens.", imageDataUrl = "data:image/jpeg;base64,P5"),
                ChatGptContextPage(pageNumber = 6, text = "Exercise statements", isTextShortened = true),
            ),
        )

        assertEquals(
            listOf("text", "text", "image", "text", "text", "image", "text"),
            parts.map { if (it is ChatGptInputPart.Image) "image" else "text" },
        )
        val texts = parts.filterIsInstance<ChatGptInputPart.Text>().map { it.text }
        assertTrue("Book: Logic" in texts[0])
        assertTrue("Page to check: 7 (it comes last)." in texts[0])
        assertTrue("Context pages (statements and rules only, not to be checked): 5–6." in texts[0])
        assertTrue(texts[1].startsWith("=== Context page 5: statements and rules only, do not check ==="))
        assertTrue("\"\"\"\nRule 5: modus ponens.\n\"\"\"" in texts[1])
        assertTrue(texts[1].endsWith("Picture of page 5:"))
        assertTrue("Printed text of page 6, shortened:" in texts[2])
        assertFalse("Picture of page 6" in texts[2])
        assertTrue(texts[3].startsWith("=== Page 7: the page to check ==="))
        assertTrue("\"\"\"\n1. Symbolize the sentence.\n\"\"\"" in texts[3])
        assertTrue("- At the top left (5% from left, 10% from top): \"P -> Q\"" in texts[3])
        assertTrue("- \"(b)\"" in texts[3])
        assertTrue("Handwritten strokes on the page: 3. The next picture shows this page" in texts[3])
        assertFalse("Comments the student attached" in texts[3])
        assertEquals("Check only the student's answers on page 7.", texts[4])
        assertEquals(
            listOf("data:image/jpeg;base64,P5", "data:image/jpeg;base64,PAGE"),
            parts.filterIsInstance<ChatGptInputPart.Image>().map { it.dataUrl },
        )
    }

    @Test
    fun `pages without a text layer point at their pictures`() {
        val bare = ChatGptAnswerCheckPageContext("", "", "", emptyList(), emptyList(), emptyList(), 0)
        val parts = chatGptAnswerCheckUserContent(
            current = bare,
            currentPageImageDataUrl = null,
            contextPages = listOf(ChatGptContextPage(pageNumber = 2, text = "", imageDataUrl = "data:image/jpeg;base64,P2")),
        )
        val texts = parts.filterIsInstance<ChatGptInputPart.Text>().map { it.text }
        assertFalse("Book:" in texts[0])
        assertTrue("Page 2 has no text layer; read it from its picture." in texts[1])
        assertTrue("The page has no text layer; read the exercises from the picture." in texts[2])
        assertTrue("No picture of this page is attached" in texts[2])

        val alone = chatGptAnswerCheckUserContent(pageSeven, "data:image/jpeg;base64,PAGE", emptyList())
        assertTrue("No context pages are included." in (alone.first() as ChatGptInputPart.Text).text)
    }

    @Test
    fun `text layer and context text budget`() {
        assertFalse(chatGptPageHasTextLayer("  12  "))
        assertFalse(chatGptPageHasTextLayer(""))
        assertTrue(chatGptPageHasTextLayer("1. Symbolize: if P then Q."))
        assertEquals(8_000, chatGptContextPageTextLimit(0))
        assertEquals(8_000, chatGptContextPageTextLimit(5))
        assertEquals(6_000, chatGptContextPageTextLimit(10))
        assertEquals(2_000, chatGptContextPageTextLimit(30))
        assertEquals(1_500, chatGptContextPageTextLimit(100))
    }
}
