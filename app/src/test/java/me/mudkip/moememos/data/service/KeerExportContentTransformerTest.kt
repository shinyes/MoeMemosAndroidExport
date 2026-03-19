package me.mudkip.moememos.data.service

import org.junit.Assert.assertEquals
import org.junit.Test

class KeerExportContentTransformerTest {
    @Test
    fun transformMemoForKeerExport_extractsFirstPureTagLineAndRemovesIt() {
        val input = "#a #b\nhello world"

        val result = transformMemoForKeerExport(
            content = input,
            originalTags = emptyList(),
        )

        assertEquals("hello world", result.content)
        assertEquals(listOf("a", "b"), result.tags)
    }

    @Test
    fun transformMemoForKeerExport_keepsContentWhenFirstLineIsNotPureTagLine() {
        val input = "#a hello\nworld"

        val result = transformMemoForKeerExport(
            content = input,
            originalTags = listOf("base"),
        )

        assertEquals(input, result.content)
        assertEquals(listOf("base"), result.tags)
    }

    @Test
    fun transformMemoForKeerExport_handlesTagOnlySingleLineMemo() {
        val input = "#single"

        val result = transformMemoForKeerExport(
            content = input,
            originalTags = emptyList(),
        )

        assertEquals("", result.content)
        assertEquals(listOf("single"), result.tags)
    }

    @Test
    fun transformMemoForKeerExport_mergesWithOriginalTagsAndDeduplicatesInOrder() {
        val input = "#b #c\nbody"

        val result = transformMemoForKeerExport(
            content = input,
            originalTags = listOf("a", "b", "a"),
        )

        assertEquals("body", result.content)
        assertEquals(listOf("a", "b", "c"), result.tags)
    }
}
