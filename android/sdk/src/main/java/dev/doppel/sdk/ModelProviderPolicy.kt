package dev.doppel.sdk

import org.json.JSONObject
import java.net.URI
import java.util.Locale

enum class ModelVision(val label: String) { VERIFIED("已验证视觉"), UNSUPPORTED("不支持图片"), UNKNOWN("视觉待验证") }

data class ModelProvider(val id: String, val name: String, val baseUrl: String, val preset: String = "custom") {
    companion object {
        fun qwen() = ModelProvider("qwen-cn", "阿里云百炼 · 中国内地", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen")
        fun deepseek() = ModelProvider("deepseek", "DeepSeek", "https://api.deepseek.com/beta", "deepseek")
        fun presets() = listOf(qwen(), deepseek(),
            ModelProvider("qwen-intl", "阿里云百炼 · 国际", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", "qwen"),
            ModelProvider("openai", "OpenAI", "https://api.openai.com/v1"),
            ModelProvider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1"),
            ModelProvider("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1"),
            ModelProvider("mimo", "小米 MiMo", "https://api.xiaomimimo.com/v1", "mimo"))
    }
}
data class ModelSelection(val providerId: String, val model: String)
data class ModelRouting(val primary: ModelSelection, val enhancementEnabled: Boolean, val enhancement: ModelSelection) {
    fun select(role: String): ModelSelection {
        require(role in setOf("primary", "grounding")) { "模型请求角色无效" }
        return if (role == "grounding" && enhancementEnabled) enhancement else primary
    }
    companion object {
        fun defaults() = ModelRouting(ModelSelection("qwen-cn", "qwen3.8-flash"), true, ModelSelection("qwen-cn", "qwen3.8-max"))
    }
}

object ModelEndpoint {
    fun authenticationMustChange(previous: String, next: String): Boolean {
        fun origin(value: String): String {
            val uri = URI(normalize(value))
            val port = if (uri.port == -1) if (uri.scheme == "https") 443 else 80 else uri.port
            return "${uri.scheme}://${uri.host.lowercase(Locale.ROOT)}:$port"
        }
        return origin(previous) != origin(next)
    }
    fun normalize(value: String): String {
        val input = value.trim().trimEnd('/')
        val uri = try { URI(input) } catch (_: Exception) { throw IllegalArgumentException("请输入有效的 API 地址") }
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val local = host in setOf("localhost", "127.0.0.1", "[::1]", "::1")
        require(host.isNotBlank() && (uri.scheme == "https" || uri.scheme == "http" && local) &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.port in -1..65535 && uri.port != 0 && !input.contains('\\')) { "远程 API 必须使用 HTTPS；地址不能含账号、查询参数或片段" }
        var path = uri.rawPath.orEmpty().trimEnd('/')
        if (path.endsWith("/chat/completions")) path = path.removeSuffix("/chat/completions")
        else if (path.endsWith("/models")) path = path.removeSuffix("/models")
        require(!path.contains("..") && !Regex("%2f|%5c|%2e", RegexOption.IGNORE_CASE).containsMatchIn(path)) { "API 地址路径无效" }
        return "${uri.scheme}://${uri.rawAuthority}$path"
    }
}

object ModelHeaders {
    private val reserved = setOf("host", "content-length", "content-type", "connection", "transfer-encoding", "cookie", "proxy-authorization", "accept-encoding")
    fun parse(value: String): Map<String, String> {
        if (value.isBlank()) return emptyMap()
        require(value.length <= 16384) { "附加请求头过长" }
        val json = try { JSONObject(value) } catch (_: Exception) { throw IllegalArgumentException("附加请求头需要 JSON 对象，例如 {\"X-Tenant\":\"名称\"}") }
        require(json.length() <= 24) { "附加请求头过多" }
        return json.keys().asSequence().associateWith { name ->
            require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,80}")) && name.lowercase(Locale.ROOT) !in reserved) { "附加请求头名称无效或由连接管理" }
            val text = json.opt(name)
            require(text is String && text.length in 1..4096 && text.all { it.code in 32..126 }) { "附加请求头的值必须是单行文本" }
            text
        }
    }
}

object ModelWirePolicy {
    fun prepare(payload: JSONObject, provider: ModelProvider, model: String, role: String): JSONObject {
        require(role in setOf("primary", "grounding")) { "模型请求角色无效" }
        val body = JSONObject(payload.toString())
        body.keys().asSequence().filter { it.startsWith("_doppel_") }.toList().forEach(body::remove)
        listOf("thinking", "enable_thinking", "reasoning_effort").forEach(body::remove)
        body.put("model", model).put("stream", false)
        val host = URI(provider.baseUrl).host?.lowercase(Locale.ROOT)
        val schema = body.optJSONObject("response_format")
        if (schema?.optString("type") == "json_schema") {
            require(schema.optJSONObject("json_schema")?.optBoolean("strict") == true) { "任务输出必须使用严格结构化模式" }
            // Do not retry without response_format: a gateway can reject unsupported schemas explicitly.
            // Models known not to offer schema mode fail here instead of losing field constraints silently.
            if (provider.preset == "qwen" && isOfficialQwenHost(host)) {
                require(Regex("qwen3\\.(8-(flash|max)|7-(flash|max|plus))(?:[-:].*)?", RegexOption.IGNORE_CASE).matches(model)) {
                    "所选千问模型未列入严格结构化输出支持范围，请使用 Qwen3.8-Flash 或 Qwen3.8-Max"
                }
            }
        }
        if (provider.preset == "qwen" && isOfficialQwenHost(host) && model.startsWith("qwen")) {
            body.put("enable_thinking", false)
        } else if (provider.preset == "mimo" && host == "api.xiaomimimo.com" && model.startsWith("mimo")) {
            body.put("thinking", JSONObject().put("type", if (role == "primary") "enabled" else "disabled"))
        } else if (isOfficialDeepSeek(provider) && model.startsWith("deepseek-")) {
            // A plans with low reasoning; visual grounding stays non-thinking.
            body.put("thinking", JSONObject().put("type", if (role == "primary") "enabled" else "disabled"))
            if (role == "primary") body.put("reasoning_effort", "low")
            if (body.has("max_completion_tokens")) {
                val limit = body.remove("max_completion_tokens")
                require(!body.has("max_tokens") || body.opt("max_tokens") == limit) { "模型输出上限参数冲突" }
                body.put("max_tokens", limit)
            }
            if (schema?.optString("type") == "json_schema") {
                require(URI(provider.baseUrl).path.trimEnd('/') == "/beta") {
                    "DeepSeek 严格任务输出需要将 API 地址设置为 https://api.deepseek.com/beta"
                }
                DeepSeekStructuredOutput.prepare(body)
            }
        }
        return body
    }

    internal fun isOfficialDeepSeek(provider: ModelProvider): Boolean {
        val uri = URI(provider.baseUrl)
        return uri.scheme == "https" && uri.host?.lowercase(Locale.ROOT) == "api.deepseek.com" && uri.port in setOf(-1, 443)
    }

    private fun isOfficialQwenHost(host: String?) = host in setOf("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com", "dashscope-us.aliyuncs.com") ||
        host?.endsWith(".maas.aliyuncs.com") == true
}

object ModelVisionProbe {
    fun classify(status: Int, response: String, expected: List<String>): ModelVision {
        if (status in 200..299) {
            val colors = Regex("[A-Za-z]+").findAll(response).map { it.value.uppercase(Locale.ROOT) }.toList()
            return if (colors == expected) ModelVision.VERIFIED else ModelVision.UNKNOWN
        }
        val text = response.lowercase(Locale.ROOT)
        val unsupported = listOf("does not support image", "doesn't support image", "image inputs are not supported", "image input is not supported",
            "image_url is not supported", "unsupported content type: image", "not a multimodal model", "does not support multimodal", "不支持图像", "不支持图片")
        return if (status in setOf(400, 422) && unsupported.any(text::contains)) ModelVision.UNSUPPORTED else ModelVision.UNKNOWN
    }
}

internal class ModelSecret(val apiKey: String, val headers: Map<String, String>) {
    override fun toString() = "ModelSecret([encrypted credentials])"
}
