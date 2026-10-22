package com.shilapi.xcertplay.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LrcParserTest {
    private fun parse(raw: String) = checkNotNull(LrcParser.parse(raw))

    @Test
    fun parsesTwoDigitAndThreeDigitFractions() {
        assertEquals(12_340L, parse("[00:12.34]a").lines.single().startMillis)
        assertEquals(12_345L, parse("[00:12.345]a").lines.single().startMillis)
    }

    @Test
    fun expandsMultipleTimestampsOnOneLine() {
        val lines = parse("[00:05.00][00:01.00]hi").lines
        assertEquals(listOf(1_000L, 5_000L), lines.map { it.startMillis })
        assertEquals(listOf("hi", "hi"), lines.map { it.text })
    }

    @Test
    fun appliesOffsetTag() {
        val lines = parse("[offset:+500]\n[00:10.00]a").lines
        assertEquals(9_500L, lines.single().startMillis)
        assertEquals(10_500L, parse("[offset:-500]\n[00:10.00]a").lines.single().startMillis)
    }

    @Test
    fun ignoresMetadataTags() {
        val lines = parse("[ar:Artist]\n[ti:Title]\n[00:01.00]a").lines
        assertEquals(listOf("a"), lines.map { it.text })
    }

    @Test
    fun keepsEmptyTimedLineAsGap() {
        val lines = parse("[00:01.00]a\n[00:30.00]").lines
        assertEquals(LyricLine(30_000L, ""), lines.last())
    }

    @Test
    fun handlesCrLfAndBom() {
        val lines = parse("﻿[00:01.00]a\r\n[00:02.00]b\r\n").lines
        assertEquals(listOf("a", "b"), lines.map { it.text })
    }

    @Test
    fun keepsEqualTimestampsInFileOrder() {
        val lines = parse("[00:01.00]first\n[00:01.00]second").lines
        assertEquals(listOf("first", "second"), lines.map { it.text })
    }

    @Test
    fun returnsNullWithoutTimedLines() {
        assertNull(LrcParser.parse("just words\nno timestamps"))
        assertNull(LrcParser.parse("[ar:x]"))
    }

    @Test
    fun indexAtBeforeFirstIsMinusOne() {
        assertEquals(-1, parse("[00:10.00]a").indexAt(9_999L))
    }

    @Test
    fun indexAtExactStartSelectsThatLine() {
        val lyrics = parse("[00:10.00]a\n[00:20.00]b")
        assertEquals(0, lyrics.indexAt(10_000L))
        assertEquals(1, lyrics.indexAt(20_000L))
        assertEquals(0, lyrics.indexAt(19_999L))
    }

    @Test
    fun indexAtAfterLastIsLastIndex() {
        assertEquals(1, parse("[00:10.00]a\n[00:20.00]b").indexAt(9_000_000L))
    }
}
