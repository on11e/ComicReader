package com.example.comicreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun normalizeTags_trimsSplitsDeduplicatesAndDropsBlankTags() {
        val tags = normalizeTags("  热血, 冒险;热血,, ;  科幻  ")

        assertEquals(listOf("热血", "冒险", "科幻"), tags)
    }

    @Test
    fun normalizeTags_preservesCaseSensitiveDistinctTags() {
        val tags = normalizeTags("Action, action, ACTION")

        assertEquals(listOf("Action", "action", "ACTION"), tags)
    }

    @Test
    fun normalizeTags_returnsEmptyListForOnlySeparatorsAndWhitespace() {
        val tags = normalizeTags(" , ;  ,, ; ")

        assertTrue(tags.isEmpty())
    }

    @Test
    fun isValidExternalUrl_acceptsOnlyHttpAndHttpsPrefixes() {
        assertTrue(isValidExternalUrl("http://example.com"))
        assertTrue(isValidExternalUrl("https://example.com"))
        assertFalse(isValidExternalUrl("ftp://example.com"))
        assertFalse(isValidExternalUrl(" example.com"))
        assertFalse(isValidExternalUrl("HTTPS://example.com"))
        assertFalse(isValidExternalUrl(""))
    }

    @Test
    fun isExternalBookId_matchesOnlyExternalBookPrefix() {
        assertTrue(isExternalBookId("${EXTERNAL_BOOK_ID_PREFIX}abc"))
        assertFalse(isExternalBookId("local::$EXTERNAL_BOOK_ID_PREFIX"))
        assertFalse(isExternalBookId(""))
    }
}
