package com.aryan.reader.shared.chatgpt

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatGptAnswerCheckContextTest {
    @Test
    fun `default context is five previous pages without pictures`() {
        val setting = ChatGptAnswerCheckContextSetting()
        assertEquals(5, setting.previousPages)
        assertEquals(null, setting.range)
        assertEquals(false, setting.includeImages)
        assertEquals(setting, decodeChatGptAnswerCheckContextSetting(null))
        assertEquals(setting, decodeChatGptAnswerCheckContextSetting("garbage"))
    }

    @Test
    fun `settings survive storage`() {
        listOf(
            ChatGptAnswerCheckContextSetting(previousPages = 0),
            ChatGptAnswerCheckContextSetting(previousPages = 10, includeImages = true),
            ChatGptAnswerCheckContextSetting(range = 12..15, includeImages = true),
        ).forEach { setting ->
            assertEquals(setting, decodeChatGptAnswerCheckContextSetting(setting.encode()))
        }
        assertEquals("v1;previous=2;images=0", ChatGptAnswerCheckContextSetting(previousPages = 2).encode())
        assertEquals("v1;range=3-4;images=1", ChatGptAnswerCheckContextSetting(range = 3..4, includeImages = true).encode())
    }

    @Test
    fun `unreadable fields fall back field by field`() {
        assertEquals(
            ChatGptAnswerCheckContextSetting(previousPages = 5, includeImages = true),
            decodeChatGptAnswerCheckContextSetting("v1;previous=7;range=9-2;images=1"),
        )
    }

    @Test
    fun `previous pages stop at the start of the book`() {
        assertEquals(listOf(5, 6, 7, 8, 9), ChatGptAnswerCheckContextSetting().contextPageIndices(10, 100))
        assertEquals(listOf(0, 1), ChatGptAnswerCheckContextSetting().contextPageIndices(2, 100))
        assertEquals(emptyList(), ChatGptAnswerCheckContextSetting().contextPageIndices(0, 100))
        assertEquals(emptyList(), ChatGptAnswerCheckContextSetting(previousPages = 0).contextPageIndices(10, 100))
        assertEquals(emptyList(), ChatGptAnswerCheckContextSetting().contextPageIndices(3, 0))
    }

    @Test
    fun `a range skips the current page and stays inside the book`() {
        assertEquals(listOf(2, 3, 5), ChatGptAnswerCheckContextSetting(range = 3..6).contextPageIndices(4, 100))
        assertEquals(listOf(8, 9), ChatGptAnswerCheckContextSetting(range = 9..40).contextPageIndices(0, 10))
        assertEquals(emptyList(), ChatGptAnswerCheckContextSetting(range = 50..60).contextPageIndices(0, 10))
    }

    @Test
    fun `an oversized saved range keeps the pages nearest the current one`() {
        val pages = ChatGptAnswerCheckContextSetting(range = 1..100).contextPageIndices(80, 200)
        assertEquals(CHATGPT_CONTEXT_MAX_PAGES, pages.size)
        // Fifteen pages on each side of page index 80.
        assertEquals((65..95).filter { it != 80 }, pages)
    }

    @Test
    fun `range fields are validated against the book`() {
        assertEquals(ChatGptContextRangeInput.Valid(3..7), parseChatGptContextRange(" 3", "7 ", 100))
        assertEquals(ChatGptContextRangeInput.Valid(4..4), parseChatGptContextRange("4", "", 100))
        assertEquals(ChatGptContextRangeInput.OutOfBounds, parseChatGptContextRange("7", "3", 100))
        assertEquals(ChatGptContextRangeInput.OutOfBounds, parseChatGptContextRange("0", "3", 100))
        assertEquals(ChatGptContextRangeInput.OutOfBounds, parseChatGptContextRange("90", "101", 100))
        assertEquals(ChatGptContextRangeInput.OutOfBounds, parseChatGptContextRange("x", "3", 100))
        assertEquals(ChatGptContextRangeInput.TooLong, parseChatGptContextRange("1", "31", 100))
        assertEquals(ChatGptContextRangeInput.Valid(1..30), parseChatGptContextRange("1", "30", 100))
    }

    @Test
    fun `page numbers collapse into runs`() {
        assertEquals("3–5, 7, 9–10", formatChatGptPageNumbers(listOf(10, 3, 4, 5, 7, 9, 4)))
        assertEquals("1", formatChatGptPageNumbers(listOf(1)))
        assertEquals("", formatChatGptPageNumbers(emptyList()))
    }
}
