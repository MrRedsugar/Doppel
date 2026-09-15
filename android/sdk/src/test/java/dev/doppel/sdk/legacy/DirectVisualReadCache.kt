package dev.doppel.sdk

import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest

/** One short-lived interpretation, never a gesture, execution permit or persisted memory. */
internal class DirectVisualReadCache {
    private data class Entry(val key: String, val text: String, val at: Long)
    private var entry: Entry? = null
    fun clear() { entry = null }
    fun read(key: String?, now: Long): String? {
        val stored = entry ?: return null
        if (now < stored.at || now - stored.at > 30000) { clear(); return null }
        return stored.text.takeIf { key != null && key == stored.key }
    }
    fun remember(key: String?, text: String, now: Long) {
        entry = if (key != null && text.isNotBlank() && text.length <= 4000) Entry(key, text, now) else null
    }
    fun key(run: JSONObject, generation: Long, question: String, image: String?, frame: VisualFrame?, observation: JSONObject?, environment: JSONObject): String? {
        if (image.isNullOrBlank() || frame == null || observation == null || question.isBlank()) return null
        if (frame.screenId != observation.optString("screen_id") || frame.packageName != observation.optString("package_name") ||
            frame.displayWidth != observation.optInt("width") || frame.displayHeight != observation.optInt("height")) return null
        // Exclude only the observation timestamp. All semantic state and the actual image must still match.
        val state = JSONObject(observation.toString()).apply { remove("captured_at") }
        val identity = JSONObject().put("run", run.getString("id")).put("generation", generation).put("question", question)
            .put("goal", run.getString("goal")).put("model", DirectPayload.VISION).put("screen", state)
            .put("image_width", frame.imageWidth).put("image_height", frame.imageHeight).put("rotation", frame.rotation)
            .put("reported_digest", frame.sha256).put("environment", environment)
            .put("knowledge", run.optJSONArray("knowledge"))
            .put("receipts", run.optJSONObject("execution_context")?.optJSONArray("receipts") ?: JSONArray())
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(identity.toString().toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(image.toByteArray(Charsets.US_ASCII))
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
