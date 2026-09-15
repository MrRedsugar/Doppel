package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class ChineseSpeechTextTest {
    private val lexicon = mapOf("你" to intArrayOf(30), "好" to intArrayOf(31), "你好" to intArrayOf(40, 41), "一" to intArrayOf(50))

    @Test fun prefersPhrasePronunciationAndAddsModelBoundaryTokens() {
        val chunks = ChineseSpeechText(lexicon).chunks("你好。")
        assertEquals(1, chunks.size)
        assertArrayEquals(longArrayOf(0, 40, 41, 4, 0), chunks[0])
    }

    @Test fun chunksLongTextWithoutDroppingAnyPronouncedCharacter() {
        val chunks = ChineseSpeechText(lexicon, 8).chunks("好".repeat(25))
        assertEquals(4, chunks.size)
        assertTrue(chunks.all { it.size <= 10 && it.first() == 0L && it.last() == 0L })
        assertEquals(25, chunks.sumOf { chunk -> chunk.count { it == 31L } })
    }

    @Test fun punctuationEndsSentenceAndUnsupportedSymbolsDoNotBecomeWords() {
        val chunks = ChineseSpeechText(lexicon).chunks("你好！好？")
        assertEquals(2, chunks.size)
        assertArrayEquals(longArrayOf(0, 40, 41, 5, 0), chunks[0])
        assertArrayEquals(longArrayOf(0, 31, 6, 0), chunks[1])
        assertTrue(ChineseSpeechText(lexicon).chunks("***").isEmpty())
    }

    @Test fun digitSpellingPreservesLeadingZerosAndFractions() {
        assertEquals("零零一点零二", ChineseSpeechText.digits("001.02"))
    }
}
