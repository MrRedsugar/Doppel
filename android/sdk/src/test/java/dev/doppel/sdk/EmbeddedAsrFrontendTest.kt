package dev.doppel.sdk

import org.junit.Assert.*
import org.junit.Test

class EmbeddedAsrFrontendTest {
    @Test fun featuresMatchIndependentKaldiNativeFbankGolden() {
        val samples = ShortArray(800) { ((it * 73 % 20000) - 10000).toShort() }
        val actual = EmbeddedAsrFrontend.filterbank(samples)
        val expected = javaClass.getResourceAsStream("/paraformer-fbank-golden.txt")!!
            .bufferedReader().use { it.readText() }.trim().split(Regex("\\s+")).map { it.toFloat() }
        assertEquals(expected.size, actual.size)
        for (i in actual.indices) assertEquals("Feature $i", expected[i], actual[i], 0.0005f)
    }

    @Test fun silenceStaysFiniteAndTooShortDoesNotCreateFrames() {
        assertTrue(EmbeddedAsrFrontend.filterbank(ShortArray(399)).isEmpty())
        val features = EmbeddedAsrFrontend.filterbank(ShortArray(16000))
        assertEquals(98 * 80, features.size)
        assertTrue(features.all { it.isFinite() && it < -10 })
    }

    @Test fun lowFrameRateUsesSevenFramesEverySixFramesWithoutInventingTail() {
        val source = FloatArray(20 * 80) { it.toFloat() }
        val mean = FloatArray(560) { -1f }
        val inverseStd = FloatArray(560) { 0.5f }
        val result = EmbeddedAsrFrontend.stackAndNormalize(source, mean, inverseStd)
        assertEquals(3 * 560, result.size)
        assertEquals(-0.5f, result[0], 0f)
        assertEquals((6 * 80 - 1) * 0.5f, result[560], 0f)
        assertEquals((12 * 80 + 559 - 1) * 0.5f, result.last(), 0f)
    }

    @Test fun subwordsPreserveChineseAndEnglishAndNeverCollapseRepeatedTokens() {
        assertEquals("打开 WPS 创建文档", EmbeddedAsrFrontend.text(listOf("打开", "W@@", "P@@", "S", "创建", "文档")))
        assertEquals("零零零", EmbeddedAsrFrontend.text(listOf("<blank>", "零", "零", "零", "</s>")))
        assertEquals("<OOV>", EmbeddedAsrFrontend.text(listOf("<OOV>")))
    }
}
