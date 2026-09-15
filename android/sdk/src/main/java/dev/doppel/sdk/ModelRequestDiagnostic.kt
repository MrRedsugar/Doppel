package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/** Small payload-derived metadata only; never a request body, image archive or execution authority. */
internal object ModelRequestDiagnostic {
    /** Numeric costs only. Does not decode images, copy text, or retain URLs. */
    fun payloadSizeMetadata(payload: JSONObject): JSONObject {
        val messages = payload.optJSONArray("messages") ?: JSONArray()
        var textChars = 0L
        var imageChars = 0L
        var imageCount = 0
        var truncated = messages.length() > MAX_MESSAGES
        repeat(minOf(messages.length(), MAX_MESSAGES)) { index ->
            when (val content = messages.optJSONObject(index)?.opt("content")) {
                is String -> textChars += content.length
                is JSONArray -> {
                    if (content.length() > MAX_PARTS) truncated = true
                    repeat(minOf(content.length(), MAX_PARTS)) { partIndex ->
                        val part = content.optJSONObject(partIndex)
                        when (part?.optString("type")) {
                            "text", "input_text" -> textChars += (part.opt("text") as? String)?.length ?: 0
                            "image_url", "input_image" -> {
                                imageCount++
                                val image = part.opt("image_url")
                                val url = if (image is JSONObject) image.opt("url") as? String else image as? String
                                if (url?.startsWith("data:image/") == true) imageChars += url.length
                            }
                        }
                    }
                }
            }
        }
        return JSONObject().put("message_count", messages.length()).put("image_count", imageCount)
            .put("text_chars", textChars).put("inline_image_chars", imageChars)
            .put("schema_chars", (payload.optJSONObject("response_format")?.toString()?.length ?: 0) +
                (payload.optJSONArray("tools")?.toString()?.length ?: 0))
            .put("size_counts_truncated", truncated)
    }

    /** Metadata from the final wire body, without traversing messages or image bytes. */
    fun wireMetadata(payload: JSONObject): JSONObject = JSONObject().apply {
        (payload.opt("model") as? String)?.takeIf { it.length in 1..128 && it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/-]*")) }
            ?.let { put("model", it) }
        (payload.opt("enable_thinking") as? Boolean)?.let { put("enable_thinking", it) }
        (payload.optJSONObject("thinking")?.opt("type") as? String)?.takeIf { it in setOf("enabled", "disabled") }
            ?.let { put("thinking", it) }
        (payload.opt("reasoning_effort") as? String)?.takeIf { it in setOf("low", "high", "max") }
            ?.let { put("reasoning_effort", it) }
        (payload.opt("max_tokens") as? Int)?.takeIf { it in 1..393216 }?.let { put("max_tokens", it) }
        payload.optJSONObject("response_format")?.let { format ->
            val type = format.optString("type")
            if (type in setOf("json_object", "json_schema")) put("response_format", type)
            if (type == "json_schema") format.optJSONObject("json_schema")?.let { contract ->
                (contract.opt("strict") as? Boolean)?.let { put("schema_strict", it) }
                contract.optString("name").takeIf { it.matches(Regex("doppel_[ab]_[a-z_]+_v[0-9]+")) }?.let { put("schema_name", it) }
            }
        }
        val tools = payload.optJSONArray("tools")
        if (tools?.length() == 1) tools.optJSONObject(0)?.optJSONObject("function")?.let { function ->
            val name = function.optString("name")
            val forced = payload.optJSONObject("tool_choice")?.let {
                it.optString("type") == "function" && it.optJSONObject("function")?.optString("name") == name
            } == true
            val thinkingAuto = payload.opt("tool_choice") == "auto" && payload.optJSONObject("thinking")?.optString("type") == "enabled"
            if (function.optBoolean("strict") && name.matches(Regex("doppel_[ab]_[a-z_]+_v[0-9]+")) && (forced || thinkingAuto)) {
                put("response_format", "strict_tool").put("schema_strict", true).put("schema_name", name)
                put("client_length_validation", true).put("tool_choice", if (thinkingAuto) "auto" else "forced")
            }
        }
    }
    private const val MAX_MESSAGES = 32
    private const val MAX_PARTS = 32
    private const val MAX_IMAGES = 3
    private const val MAX_TOOLS = 64
    private const val MAX_ENCODED = 6 * 1024 * 1024
    private const val PNG_PREFIX = "data:image/png;base64,"
    private val pngSignature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    // Only host vocabulary is persisted; arbitrary unknown names may themselves contain private data.
    private val toolNames = setOf("action", "navigate", "launch", "search_web", "read_web", "list_skills",
        "load_skill", "read_skill_resource", "visual_action", "inspect_screen", "ask_user", "finish",
        "observe_screen", "locate_ui", "execute_plan", "execute_visual_plan", "plan_task", "set_task_plan",
        "propose_tap", "propose_long_press", "propose_swipe", "cannot_ground")

    fun from(payload: JSONObject, visualSource: JSONObject?, call: Int, preparedAt: Long): JSONObject {
        val model = (payload.opt("model") as? String)?.takeIf {
            it.length in 1..128 && it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/-]*"))
        } ?: "unknown"
        val result = JSONObject().put("model", model).put("prepared_at", preparedAt.coerceAtLeast(0))
            .put("state", "prepared").put("call", call.coerceAtLeast(0)).put("current_source", source(visualSource))
        (payload.optJSONObject("thinking")?.opt("type") as? String)?.takeIf { it in setOf("enabled", "disabled") }
            ?.let { result.put("thinking", it) }
        wireMetadata(payload).let { metadata -> metadata.keys().forEach { result.put(it, metadata.get(it)) } }
        (payload.opt("max_completion_tokens") as? Int)?.takeIf { it in 1..131072 }
            ?.let { result.put("max_completion_tokens", it) }
        val images = JSONArray()
        val messages = payload.optJSONArray("messages") ?: JSONArray()
        var truncated = messages.length() > MAX_MESSAGES
        val tools = payload.optJSONArray("tools") ?: JSONArray()
        val offered = JSONArray()
        var launchPackageCount: Any = if (tools.length() > MAX_TOOLS) JSONObject.NULL else 0
        if (tools.length() > MAX_TOOLS || payload.has("tools") && payload.opt("tools") !is JSONArray) truncated = true
        for (index in 0 until minOf(tools.length(), MAX_TOOLS)) {
            val tool = tools.optJSONObject(index)
            val function = tool?.optJSONObject("function")
            val name = function?.opt("name") as? String
            if (tool?.opt("type") != "function" || name !in toolNames) { truncated = true; continue }
            offered.put(name)
            if (name == "launch") {
                // Count the actual schema enum without retaining or traversing package names.
                launchPackageCount = function?.optJSONObject("parameters")?.optJSONObject("properties")
                    ?.optJSONObject("package_name")?.optJSONArray("enum")?.length() ?: JSONObject.NULL
            }
        }
        result.put("offered_tool_names", offered).put("launch_package_count", launchPackageCount)
        var textChars = 0L
        var imageCount = 0
        var encodedRemaining = MAX_ENCODED
        for (index in 0 until minOf(messages.length(), MAX_MESSAGES)) {
            val content = messages.optJSONObject(index)?.opt("content")
            if (content is String) { textChars += content.length; continue }
            if (content !is JSONArray) continue
            if (content.length() > MAX_PARTS) truncated = true
            for (partIndex in 0 until minOf(content.length(), MAX_PARTS)) {
                val part = content.optJSONObject(partIndex) ?: continue
                when (part.opt("type")) {
                    "text", "input_text" -> textChars += (part.opt("text") as? String)?.length ?: 0
                    "image_url", "input_image" -> {
                        imageCount++
                        if (images.length() >= MAX_IMAGES) { truncated = true; continue }
                        val imageUrl = part.opt("image_url")
                        val url = if (imageUrl is JSONObject) imageUrl.opt("url") as? String else imageUrl as? String
                        val record = JSONObject().put("invalid", true)
                        images.put(record)
                        if (url == null || !url.startsWith(PNG_PREFIX)) continue
                        val size = url.length - PNG_PREFIX.length
                        if (size > encodedRemaining) { truncated = true; continue }
                        encodedRemaining -= size
                        // Invalid images must not interrupt planning or put exception text in persisted diagnostics.
                        try {
                            val bytes = Base64.getDecoder().decode(url.substring(PNG_PREFIX.length))
                            if (!png(bytes)) continue
                            record.remove("invalid")
                            record.put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes)
                                .joinToString("") { "%02x".format(it.toInt() and 255) }).put("bytes", bytes.size)
                        } catch (_: Exception) { /* Only the existing invalid marker is retained. */ }
                    }
                }
            }
        }
        return result.put("images", images).put("text_chars", textChars).put("message_count", messages.length())
            .put("image_count", imageCount).put("truncated", truncated)
    }

    private fun source(value: JSONObject?): JSONObject = JSONObject().apply {
        if (value == null) return@apply
        for ((key, limit) in listOf("screen_id" to 128, "package_name" to 255, "capture_id" to 128, "evidence_id" to 128)) {
            val text = value.opt(key) as? String ?: continue
            if (text.isNotBlank() && text.none { it.isISOControl() }) put(key, text.take(limit))
        }
        (value.opt("sha256") as? String)?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?.let { put("sha256", it.lowercase()) }
    }

    /** Recognize a bounded PNG container without decoding or retaining its pixel data. */
    private fun png(bytes: ByteArray): Boolean {
        if (bytes.size < 45 || pngSignature.indices.any { bytes[it] != pngSignature[it] }) return false
        val input = java.nio.ByteBuffer.wrap(bytes)
        if (input.getInt(8) != 13 || input.getInt(12) != 0x49484452 || input.getInt(16) <= 0 || input.getInt(20) <= 0) return false
        return input.getInt(bytes.size - 12) == 0 && input.getInt(bytes.size - 8) == 0x49454e44
    }
}
