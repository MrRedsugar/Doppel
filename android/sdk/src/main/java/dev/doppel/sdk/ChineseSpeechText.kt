package dev.doppel.sdk

import java.text.Normalizer

/** Phrase lookup uses the model's published pronunciations, including phrase tone changes. */
internal class ChineseSpeechText(private val lexicon: Map<String, IntArray>, private val limit: Int = 96) {
    private val longest = lexicon.keys.maxOfOrNull { it.length } ?: 1
    private val punctuation = mapOf('，' to 3, ',' to 3, '。' to 4, '.' to 4, '！' to 5, '!' to 5,
        '？' to 6, '?' to 6, '；' to 1, ';' to 1, '：' to 2, ':' to 2, '、' to 3, '\n' to 4)

    fun chunks(input: String): List<LongArray> {
        require(limit in 8..508)
        val text = Normalizer.normalize(input, Normalizer.Form.NFKC)
        require(text.length <= 10000)
        val chunks = mutableListOf<LongArray>()
        val phones = mutableListOf<Long>()
        fun flush() {
            if (phones.isEmpty()) return
            chunks.add(longArrayOf(0) + phones.toLongArray() + longArrayOf(0)); phones.clear()
        }
        var cursor = 0
        while (cursor < text.length) {
            val mark = punctuation[text[cursor]]
            if (mark != null) {
                if (phones.isNotEmpty()) {
                    if (phones.size == limit) flush()
                    else {
                        phones.add(mark.toLong())
                        if (mark in setOf(1, 4, 5, 6)) flush()
                    }
                }
                cursor++; continue
            }
            var found: IntArray? = null
            var length = minOf(longest, text.length - cursor)
            while (length > 0) {
                found = lexicon[text.substring(cursor, cursor + length)]
                if (found != null) break
                length--
            }
            if (found == null) { cursor++; continue }
            for (phone in found) {
                if (phones.size == limit) flush()
                phones.add(phone.toLong())
            }
            cursor += length
        }
        flush()
        return chunks
    }

    companion object {
        fun digits(value: String): String = buildString {
            for (character in value) append(when (character) {
                in '0'..'9' -> "零一二三四五六七八九"[character - '0']
                '.' -> '点'
                else -> character
            })
        }
    }
}
