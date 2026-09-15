package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/** Checks the offered JSON subset before a model claim can advance a stage. Never repairs arguments. */
internal object FeedbackToolContract {
    data class Rejection(val field: String, val code: String)

    fun check(arguments: JSONObject, schema: JSONObject): Rejection? = checkValue(arguments, schema, "$", 0)

    private fun checkValue(value: Any?, schema: JSONObject, path: String, depth: Int): Rejection? {
        fun reject(code: String) = Rejection(path.take(120), code)
        if (depth > 10) return reject("nesting_limit")
        val valid = when (schema.optString("type")) {
            "object" -> value is JSONObject
            "array" -> value is JSONArray
            "string" -> value is String
            "boolean" -> value is Boolean
            "integer" -> value is Int || value is Long
            "number" -> value is Number && value.toDouble().isFinite()
            else -> false
        }
        if (!valid) return reject("wrong_type")
        schema.optJSONArray("enum")?.let { allowed ->
            if ((0 until allowed.length()).none { allowed.opt(it) == value }) return reject("not_offered")
        }
        when (value) {
            is JSONObject -> {
                val properties = schema.optJSONObject("properties") ?: return reject("unsupported_schema")
                val required = schema.optJSONArray("required") ?: JSONArray()
                for (i in 0 until required.length()) {
                    val key = required.getString(i)
                    if (!value.has(key)) return Rejection("$path.$key", "missing_required")
                }
                for (key in value.keys()) {
                    // Error paths use only host schema names, never arbitrary model text.
                    val child = properties.optJSONObject(key)
                    if (child == null) {
                        if (schema.opt("additionalProperties") == false) return reject("unknown_field")
                    } else checkValue(value.opt(key), child, "$path.$key", depth + 1)?.let { return it }
                }
            }
            is JSONArray -> {
                if (schema.has("minItems") && value.length() < schema.getInt("minItems") ||
                    schema.has("maxItems") && value.length() > schema.getInt("maxItems")) return reject("length_range")
                val items = schema.optJSONObject("items") ?: return reject("unsupported_schema")
                if (value.length() > 300) return reject("length_range")
                for (i in 0 until value.length()) checkValue(value.opt(i), items, "$path[$i]", depth + 1)?.let { return it }
            }
            is String -> if (schema.has("minLength") && value.length < schema.getInt("minLength") ||
                schema.has("maxLength") && value.length > schema.getInt("maxLength")) return reject("length_range")
            is Number -> if (schema.has("minimum") && value.toDouble() < schema.getDouble("minimum") ||
                schema.has("maximum") && value.toDouble() > schema.getDouble("maximum")) return reject("number_range")
        }
        return null
    }
}
