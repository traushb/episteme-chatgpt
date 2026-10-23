package com.aryan.reader.shared.chatgpt

import kotlin.math.abs

/** Previous-page counts the context picker offers. */
val CHATGPT_CONTEXT_PREVIOUS_PAGE_CHOICES: List<Int> = listOf(0, 2, 5, 10)
const val CHATGPT_CONTEXT_DEFAULT_PREVIOUS_PAGES = 5

/** Most context pages one check sends; a manual range may not be longer. */
const val CHATGPT_CONTEXT_MAX_PAGES = 30

/**
 * Which pages an answer check sends besides the one it checks, saved per
 * book. Context pages carry exercise statements and rules; only the current
 * page is checked.
 */
data class ChatGptAnswerCheckContextSetting(
    /** How many pages right before the current one; unused while [range] is set. */
    val previousPages: Int = CHATGPT_CONTEXT_DEFAULT_PREVIOUS_PAGES,
    /** A manual inclusive range of 1-based page numbers, or null. */
    val range: IntRange? = null,
    /** Also send pictures of context pages that have text, for formulas and diagrams. */
    val includeImages: Boolean = false,
) {
    /** Storage form, read back by [decodeChatGptAnswerCheckContextSetting]. */
    fun encode(): String = buildString {
        append("v1")
        if (range != null) append(";range=${range.first}-${range.last}") else append(";previous=$previousPages")
        append(";images=").append(if (includeImages) 1 else 0)
    }

    /**
     * The 0-based context pages for a check of [currentPageIndex]: ascending,
     * inside the document and without the current page. A range longer than
     * [CHATGPT_CONTEXT_MAX_PAGES] keeps the pages nearest the current one.
     */
    fun contextPageIndices(currentPageIndex: Int, pageCount: Int): List<Int> {
        if (pageCount <= 0) return emptyList()
        val candidates = if (range != null) {
            ((range.first - 1).coerceAtLeast(0)..(range.last - 1).coerceAtMost(pageCount - 1)).toList()
        } else {
            val count = previousPages.coerceIn(0, CHATGPT_CONTEXT_MAX_PAGES)
            ((currentPageIndex - count).coerceAtLeast(0) until currentPageIndex.coerceAtMost(pageCount)).toList()
        }
        return candidates
            .filter { it != currentPageIndex }
            .sortedBy { abs(it - currentPageIndex) }
            .take(CHATGPT_CONTEXT_MAX_PAGES)
            .sorted()
    }
}

/** The saved setting, or the default (5 previous pages, no pictures) for anything unreadable. */
fun decodeChatGptAnswerCheckContextSetting(value: String?): ChatGptAnswerCheckContextSetting {
    val fields = value?.split(';').orEmpty()
    if (fields.firstOrNull() != "v1") return ChatGptAnswerCheckContextSetting()
    var setting = ChatGptAnswerCheckContextSetting()
    fields.drop(1).forEach { field ->
        val raw = field.substringAfter('=', "")
        when (field.substringBefore('=')) {
            "previous" -> raw.toIntOrNull()
                ?.takeIf { it in CHATGPT_CONTEXT_PREVIOUS_PAGE_CHOICES }
                ?.let { setting = setting.copy(previousPages = it) }
            "range" -> {
                val first = raw.substringBefore('-').toIntOrNull()
                val last = raw.substringAfter('-', "").toIntOrNull()
                if (first != null && last != null && first in 1..last) setting = setting.copy(range = first..last)
            }
            "images" -> setting = setting.copy(includeImages = raw == "1")
        }
    }
    return setting
}

/** What the manual range fields hold. */
sealed interface ChatGptContextRangeInput {
    data class Valid(val range: IntRange) : ChatGptContextRangeInput

    /** Not page numbers from 1 to the page count, or the first after the last. */
    data object OutOfBounds : ChatGptContextRangeInput

    /** More than [CHATGPT_CONTEXT_MAX_PAGES] pages. */
    data object TooLong : ChatGptContextRangeInput
}

/** Reads the range fields; an empty last field means a single page. */
fun parseChatGptContextRange(first: String, last: String, pageCount: Int): ChatGptContextRangeInput {
    val from = first.trim().toIntOrNull()
    val to = last.trim().ifEmpty { first.trim() }.toIntOrNull()
    if (from == null || to == null || from < 1 || from > to || to > pageCount.coerceAtLeast(1)) {
        return ChatGptContextRangeInput.OutOfBounds
    }
    if (to - from + 1 > CHATGPT_CONTEXT_MAX_PAGES) return ChatGptContextRangeInput.TooLong
    return ChatGptContextRangeInput.Valid(from..to)
}

/** "3–7, 9, 12" for 1-based page numbers. */
fun formatChatGptPageNumbers(pages: List<Int>): String {
    val sorted = pages.distinct().sorted()
    if (sorted.isEmpty()) return ""
    val runs = mutableListOf<IntRange>()
    var start = sorted.first()
    var end = start
    sorted.drop(1).forEach { page ->
        if (page == end + 1) {
            end = page
        } else {
            runs += start..end
            start = page
            end = page
        }
    }
    runs += start..end
    return runs.joinToString(", ") { run -> if (run.first == run.last) "${run.first}" else "${run.first}–${run.last}" }
}
