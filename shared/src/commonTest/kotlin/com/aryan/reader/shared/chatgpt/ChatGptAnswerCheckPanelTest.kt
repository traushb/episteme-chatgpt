package com.aryan.reader.shared.chatgpt

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatGptAnswerCheckPanelTest {
    @Test
    fun `a saved panel width is kept`() {
        assertEquals(0.42f, sanitizeChatGptPanelWidthFraction(0.42f))
    }

    @Test
    fun `no saved width or an unusable one gives the default`() {
        listOf(null, 0f, 1f, -0.3f, 1.7f, Float.NaN, Float.POSITIVE_INFINITY).forEach { value ->
            assertEquals(CHATGPT_PANEL_DEFAULT_WIDTH_FRACTION, sanitizeChatGptPanelWidthFraction(value), "for $value")
        }
    }

    @Test
    fun `extreme widths are pulled back to what a screen can show`() {
        assertEquals(0.15f, sanitizeChatGptPanelWidthFraction(0.02f))
        assertEquals(0.85f, sanitizeChatGptPanelWidthFraction(0.99f))
    }
}
