package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/** org.json accepts JavaScript-like syntax on Android; model commands require actual JSON. */
internal object StrictModelJson {
    fun objectValue(text: String): JSONObject {
        Reader(text).validateObject()
        return JSONObject(text)
    }

    /** Bounded structural evidence only: never retain scalar values or arbitrary prose used as a key. */
    fun shape(value: JSONObject?): JSONObject {
        if (value == null) return JSONObject()
        var remaining = 128
        fun describe(item: Any?, depth: Int): JSONObject {
            val type = when (item) {
                null, JSONObject.NULL -> "null"
                is JSONObject -> "object"
                is JSONArray -> "array"
                is String -> "string"
                is Boolean -> "boolean"
                is Number -> "number"
                else -> "unknown"
            }
            val result = JSONObject().put("type", type)
            if (--remaining <= 0 || depth >= 6) return result.put("truncated", true)
            if (item is JSONObject) {
                val fields = JSONObject()
                val keys = item.keys().asSequence().toList().sorted()
                for ((index, key) in keys.take(32).withIndex()) {
                    if (remaining <= 0) break
                    fields.put(diagnosticKey(key, index), describe(item.opt(key), depth + 1))
                }
                result.put("fields", fields)
                if (keys.size > fields.length()) result.put("truncated", true)
            } else if (item is JSONArray) {
                val items = JSONArray()
                for (index in 0 until minOf(item.length(), 4)) {
                    if (remaining <= 0) break
                    items.put(describe(item.opt(index), depth + 1))
                }
                result.put("items", items)
                if (item.length() > items.length()) result.put("truncated", true)
            }
            return result
        }
        return describe(value, 0)
    }

    internal fun diagnosticKey(key: String, index: Int): String =
        if (key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}"))) key else "<redacted_field_$index>"

    private class Reader(private val source: String) {
        private var cursor = 0
        private fun invalid(): Nothing = throw IllegalArgumentException("模型输出不是严格 JSON（位置 $cursor）")
        private fun whitespace() { while (cursor < source.length && source[cursor] in " \t\r\n") cursor++ }
        private fun take(char: Char): Boolean {
            whitespace()
            return if (cursor < source.length && source[cursor] == char) { cursor++; true } else false
        }
        private fun need(char: Char) { if (!take(char)) invalid() }

        fun validateObject() {
            whitespace()
            if (cursor >= source.length || source[cursor] != '{') invalid()
            value(0)
            whitespace()
            if (cursor != source.length) invalid()
        }

        private fun value(depth: Int) {
            if (depth > 24) invalid()
            whitespace()
            if (cursor >= source.length) invalid()
            when (source[cursor]) {
                '{' -> {
                    cursor++
                    val keys = HashSet<String>()
                    if (take('}')) return
                    while (true) {
                        val key = string()
                        if (!keys.add(key)) invalid()
                        need(':'); value(depth + 1)
                        if (take('}')) break
                        need(',')
                    }
                }
                '[' -> {
                    cursor++
                    if (take(']')) return
                    while (true) {
                        value(depth + 1)
                        if (take(']')) break
                        need(',')
                    }
                }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> number()
                else -> invalid()
            }
        }

        private fun string(): String {
            whitespace()
            if (cursor >= source.length || source[cursor++] != '"') invalid()
            val decoded = StringBuilder()
            while (cursor < source.length) {
                val char = source[cursor++]
                if (char == '"') return decoded.toString()
                if (char.code < 0x20) invalid()
                if (char != '\\') { decoded.append(char); continue }
                if (cursor >= source.length) invalid()
                when (val escape = source[cursor++]) {
                    '"', '\\', '/' -> decoded.append(escape)
                    'b' -> decoded.append('\b')
                    'f' -> decoded.append('\u000C')
                    'n' -> decoded.append('\n')
                    'r' -> decoded.append('\r')
                    't' -> decoded.append('\t')
                    'u' -> {
                        if (cursor + 4 > source.length) invalid()
                        val digits = source.substring(cursor, cursor + 4)
                        if (digits.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) invalid()
                        decoded.append(digits.toInt(16).toChar()); cursor += 4
                    }
                    else -> invalid()
                }
            }
            invalid()
        }

        private fun literal(expected: String) {
            if (!source.startsWith(expected, cursor)) invalid()
            cursor += expected.length
        }

        private fun number() {
            if (source[cursor] == '-') cursor++
            if (cursor >= source.length) invalid()
            if (source[cursor] == '0') cursor++
            else {
                if (source[cursor] !in '1'..'9') invalid()
                while (cursor < source.length && source[cursor] in '0'..'9') cursor++
            }
            if (cursor < source.length && source[cursor] == '.') { cursor++; digits() }
            if (cursor < source.length && source[cursor] in "eE") {
                cursor++
                if (cursor < source.length && source[cursor] in "+-") cursor++
                digits()
            }
        }
        private fun digits() {
            val start = cursor
            while (cursor < source.length && source[cursor] in '0'..'9') cursor++
            if (cursor == start) invalid()
        }
    }
}
