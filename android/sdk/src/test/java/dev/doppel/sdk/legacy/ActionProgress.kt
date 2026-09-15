package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Host observations explain effects; neither a changed page nor an accepted action proves task success. */
internal object ActionProgress {
    private const val MAX_RECEIPTS = 10
    private val inputKinds = setOf("type", "login_phone", "login_code", "login_password", "ime_action")
    private val kinds = setOf("tap", "long_press", "scroll", "visual_gesture", "launch", "back", "home", "recents",
        "notifications", "quick_settings", "split_screen") + inputKinds

    /** Sanitized transient page. Node IDs locate current geometry; they are excluded from content identity. */
    fun page(observation: JSONObject?, frame: VisualFrame?): JSONObject {
        val result = JSONObject().put("semantic_reliable", false).put("targets", JSONObject())
        if (observation == null) return result
        val width = observation.optInt("width")
        val height = observation.optInt("height")
        val pkg = observation.optString("package_name").take(255)
        if (pkg.isBlank() || width !in 1..16384 || height !in 1..16384) return result
        result.put("package_name", pkg).put("width", width).put("height", height)
        val nodes = observation.optJSONArray("nodes") ?: JSONArray()
        val states = mutableListOf<String>()
        var semantic = false
        var opaque = observation.optBoolean("assistant_surface") || nodes.length() >= 300 ||
            observation.has("tree_complete") && !observation.optBoolean("tree_complete") || observation.optBoolean("truncated")
        val targets = result.getJSONObject("targets")
        for (index in 0 until minOf(nodes.length(), 300)) {
            val node = nodes.optJSONObject(index) ?: continue
            if (node.has("visible") && !node.optBoolean("visible")) continue
            val hidden = node.optBoolean("password") || node.optBoolean("editable")
            val role = node.optString("role").take(160)
            val bounds = bounds(node.optJSONArray("bounds"), width, height)
            val labels = if (hidden) emptyList() else listOf("text", "description", "state_description")
                .map { node.optString(it).trim().take(400) }
            val hasLabel = labels.any { it.isNotBlank() }
            if (hasLabel) semantic = true
            val largeUnnamed = !hasLabel && bounds != null &&
                (bounds[2] - bounds[0]).toLong() * (bounds[3] - bounds[1]) > width.toLong() * height / 4 &&
                (node.optBoolean("clickable") || role == "button")
            if (hidden || largeUnnamed || Regex("webview|surfaceview|textureview|canvas", RegexOption.IGNORE_CASE).containsMatchIn(role)) opaque = true
            // Never hash protected/input values: even hashes of short secrets can disclose them.
            val identity = JSONArray().put(role).put(if (hidden) "" else node.optString("resource_id").take(255))
                .put(JSONArray(bounds ?: emptyList<Int>())).put(JSONArray(labels))
            val state = JSONArray().put(identity).put(JSONArray(listOf("enabled", "clickable", "long_clickable", "scrollable", "checkable",
                "checked", "selected", "focused").map { node.opt(it) as? Boolean ?: false }))
            states += state.toString()
            val id = node.optString("id")
            if (id.length <= 120 && id.matches(Regex("n[0-9]+(?:_[0-9]+)*")) && bounds != null) {
                targets.put(id, JSONObject().put("key", digest(identity.toString())).put("bounds", JSONArray(bounds))
                    .put("input", hidden).put("semantic", hasLabel && !hidden))
            }
        }
        result.put("semantic_reliable", semantic && !opaque)
            .put("content_fingerprint", digest(JSONArray().put(pkg).put(width).put(height).put(JSONArray(states.sorted())).toString()))
        if (frame != null && frame.screenId == observation.optString("screen_id") && frame.packageName == pkg &&
            frame.displayWidth == width && frame.displayHeight == height) {
            result.put("image_sha256", frame.sha256.take(128)).put("capture_id", frame.captureId.take(128)).put("rotation", frame.rotation)
        }
        return result
    }

    /** Called only for an accepted host action. Copies an allowlist, never arguments, values, labels or permits. */
    fun record(run: JSONObject, sent: JSONObject, before: JSONObject, after: JSONObject, at: Long) {
        val kind = sent.optString("kind")
        if (kind !in kinds) return
        val progress = run.optJSONObject("action_progress") ?: JSONObject().put("receipts", JSONArray()).also { run.put("action_progress", it) }
        val receipts = progress.optJSONArray("receipts") ?: JSONArray().also { progress.put("receipts", it) }
        while (receipts.length() >= MAX_RECEIPTS) receipts.remove(0)
        val target = target(sent, before)
        // A visual decision may choose a native node. Its rendered result still needs pixel evidence.
        val requiresPixels = kind == "visual_gesture" || before.optBoolean("requires_pixels") || before.optString("image_sha256").isNotBlank()
        val source = state(before).put("requires_pixels", requiresPixels)
        val result = state(after).put("requires_pixels", requiresPixels)
        val receipt = JSONObject().put("command_id", sent.optString("id").take(128)).put("kind", kind).put("accepted_at", at)
            .put("before", source).put("after", result).put("effect", if (kind in inputKinds) "unknown" else effect(source, result))
            .put("proves_business_success", false)
        if (target != null && kind !in inputKinds) receipt.put("target", target)
        receipts.put(receipt)
    }

    /** Only the host's immediate deferred read may fill the newest command's missing result image. */
    fun completeResultFrame(run: JSONObject, commandId: String?, after: JSONObject) {
        if (commandId.isNullOrBlank()) return
        val receipts = run.optJSONObject("action_progress")?.optJSONArray("receipts") ?: return
        val receipt = receipts.optJSONObject(receipts.length() - 1) ?: return
        if (receipt.optString("command_id") != commandId) return
        val previous = receipt.optJSONObject("after") ?: return
        val before = receipt.optJSONObject("before") ?: return
        if (previous.optString("image_sha256").isNotBlank() || after.optString("image_sha256").isBlank() ||
            after.optString("capture_id").isBlank()) return
        // Existing post-action evidence wins. If the whole observation was absent, retain the source geometry/app boundary.
        for (key in listOf("package_name", "width", "height")) {
            val expected = if (previous.has(key)) previous.opt(key) else before.opt(key)
            if (expected == null || expected == JSONObject.NULL || after.opt(key) != expected) return
        }
        if (after.optString("package_name").isBlank() || after.optInt("width") <= 0 || after.optInt("height") <= 0) return
        for (key in listOf("content_fingerprint", "rotation")) {
            if (previous.has(key) && previous.opt(key) != after.opt(key)) return
        }
        val requiresPixels = before.optBoolean("requires_pixels") || previous.optBoolean("requires_pixels") ||
            before.optString("image_sha256").isNotBlank() || receipt.optString("kind") == "visual_gesture"
        val result = state(after).put("requires_pixels", requiresPixels)
        receipt.put("after", result).put("effect", if (receipt.optString("kind") in inputKinds) "unknown" else effect(before, result))
    }

    /** A proposal may be stopped, but this component never dispatches a recovery or restores a command. */
    fun repetition(run: JSONObject, action: JSONObject, current: JSONObject): JSONObject? {
        pathRepetition(run, action, current)?.let { return it }
        val target = target(action, current) ?: return null
        if (target.optString("kind") !in setOf("tap", "long_press")) return null
        unverifiedVisualRepetition(run, action, current, target)?.let { return it }
        val currentState = state(current).put("requires_pixels", action.optString("kind") == "visual_gesture" ||
            current.optBoolean("requires_pixels") || current.optString("image_sha256").isNotBlank())
        val receipts = run.optJSONObject("action_progress")?.optJSONArray("receipts") ?: return null
        if (receipts.length() < 2) return null
        for (index in receipts.length() - 2 until receipts.length()) {
            val receipt = receipts.optJSONObject(index) ?: return null
            if (receipt.optString("effect") != "unchanged" || receipt.optJSONObject("target")?.optString("key") != target.optString("key") ||
                effect(receipt.optJSONObject("after") ?: return null, currentState) != "unchanged") return null
        }
        return JSONObject().put("code", "repeated_unchanged_target").put("action_executed", false).put("unchanged_attempts", 2)
            .put("message", "同一目标的前两次操作均未观察到变化，本次未执行。请根据当前画面核对目标、遮挡或编辑状态，选择有依据的其他操作；不要重复旧目标。")
            .put("target", target).put("proves_business_success", false)
    }

    /** Two accepted inputs without verified progress require review; unknown never means unchanged. */
    private fun unverifiedVisualRepetition(run: JSONObject, action: JSONObject, current: JSONObject, proposed: JSONObject): JSONObject? {
        if (action.optString("kind") != "visual_gesture") return null
        val receipts = run.optJSONObject("action_progress")?.optJSONArray("receipts") ?: return null
        if (receipts.length() < 2) return null
        val width = current.optInt("width"); val height = current.optInt("height")
        val pkg = current.optString("package_name")
        val rotation = current.optInt("rotation", -1)
        if (pkg.isBlank() || width <= 0 || height <= 0 || rotation !in 0..3) return null
        // A few pixels of localization noise are one region, including points on opposite grid edges.
        val tolerance = maxOf(2.0, minOf(width, height) * 0.003).coerceAtMost(8.0)
        val x = coordinate(proposed.opt("x")) ?: return null
        val y = coordinate(proposed.opt("y")) ?: return null
        fun sameRegion(previous: JSONObject): Boolean {
            if (previous.optString("source") != "visual" || previous.optString("kind") != proposed.optString("kind")) return false
            val px = coordinate(previous.opt("x")) ?: return false
            val py = coordinate(previous.opt("y")) ?: return false
            val dx = (px - x) * width; val dy = (py - y) * height
            return dx * dx + dy * dy <= tolerance * tolerance
        }
        val pages = arrayListOf<JSONObject>()
        for (index in receipts.length() - 2 until receipts.length()) {
            val receipt = receipts.optJSONObject(index) ?: return null
            if (receipt.optString("kind") != "visual_gesture" || receipt.optString("effect") != "unknown" ||
                !sameRegion(receipt.optJSONObject("target") ?: return null)) return null
            pages += receipt.optJSONObject("before") ?: return null
            pages += receipt.optJSONObject("after") ?: return null
        }
        pages += current
        for (page in pages) {
            if (page.optString("package_name") != pkg || page.optInt("width") != width || page.optInt("height") != height ||
                page.optInt("rotation", -1) != rotation || page.optString("image_sha256").isBlank() || page.optString("capture_id").isBlank()) return null
        }
        // A proven semantic change is an available progress signal. Equal sparse trees prove nothing.
        val reliable = pages.filter { it.optBoolean("semantic_reliable") && it.optString("content_fingerprint").isNotBlank() }
        if (reliable.map { it.optString("content_fingerprint") }.distinct().size > 1) return null
        return JSONObject().put("code", "repeated_unverified_target").put("action_executed", false).put("unverified_attempts", 2)
            .put("message", "同一区域的前两次操作均已接受，但阶段进展仍未证实，本次未执行。先根据当前画面核对所在页面、阶段目标和预期结果；可选择有当前证据的不同目标或操作，不用重复点击代替核对。")
            .put("target", proposed).put("proves_business_success", false)
    }

    /** Detect repeated navigation cycles using authoritative semantic pages, not animation hashes. */
    private fun pathRepetition(run: JSONObject, action: JSONObject, current: JSONObject): JSONObject? {
        if (!current.optBoolean("semantic_reliable")) return null
        val proposedKind = action.optString("kind")
        if (proposedKind !in setOf("tap", "long_press", "back")) return null
        val receipts = run.optJSONObject("action_progress")?.optJSONArray("receipts") ?: return null
        fun signature(receipt: JSONObject): String? {
            if (receipt.optString("kind") !in setOf("tap", "long_press", "back")) return null
            val before = receipt.optJSONObject("before") ?: return null
            val after = receipt.optJSONObject("after") ?: return null
            if (!before.optBoolean("semantic_reliable") || !after.optBoolean("semantic_reliable")) return null
            if (before.optString("content_fingerprint").isBlank() || after.optString("content_fingerprint").isBlank() ||
                before.optString("content_fingerprint") == after.optString("content_fingerprint")) return null
            return listOf(before.optString("package_name"),before.optString("content_fingerprint"),
                receipt.optString("kind"),receipt.optJSONObject("target")?.optString("key").orEmpty(),
                after.optString("package_name"),after.optString("content_fingerprint")).joinToString("|")
        }
        for (length in 2..4) {
            if (receipts.length() < length * 2) continue
            val start = receipts.length() - length * 2
            val segment = (start until receipts.length()).map { receipts.optJSONObject(it) ?: return null }
            val signatures = segment.map { signature(it) ?: return null }
            if ((0 until length).any { signatures[it] != signatures[it + length] }) continue
            val first = segment.first()
            val before = first.getJSONObject("before")
            val end = segment.last().getJSONObject("after")
            if (before.optString("package_name") != current.optString("package_name") ||
                before.optString("content_fingerprint") != current.optString("content_fingerprint") ||
                end.optString("content_fingerprint") != before.optString("content_fingerprint") || first.optString("kind") != proposedKind) continue
            if (proposedKind != "back" && first.optJSONObject("target")?.optString("key") != target(action,current)?.optString("key")) continue
            return JSONObject().put("code","repeated_navigation_cycle").put("action_executed",false).put("cycle_length",length)
                .put("message","已沿相同路径往返两次且回到同一页面，本次未再次执行。检查入口是否为导航、步骤前提及真正业务按钮；调整阶段计划或获取当前图像重新定位。")
        }
        return null
    }

    private fun effect(before: JSONObject, after: JSONObject): String {
        if (before.optString("package_name").isBlank() || after.optString("package_name").isBlank()) return "unknown"
        if (before.optString("package_name") != after.optString("package_name") || before.optInt("width") != after.optInt("width") ||
            before.optInt("height") != after.optInt("height")) return "changed"
        if (before.has("rotation") && after.has("rotation") && before.optInt("rotation") != after.optInt("rotation")) return "changed"
        val beforeHash = before.optString("content_fingerprint")
        val afterHash = after.optString("content_fingerprint")
        if (before.optBoolean("semantic_reliable") && after.optBoolean("semantic_reliable") && beforeHash.isNotBlank() && afterHash.isNotBlank()) {
            if (beforeHash != afterHash) return "changed"
            if (!before.optBoolean("requires_pixels") && !after.optBoolean("requires_pixels")) return "unchanged"
        }
        val beforeImage = before.optString("image_sha256")
        val afterImage = after.optString("image_sha256")
        // Image difference on an opaque canvas can be only a clock or animation; do not infer success or progress.
        return if (beforeImage.isNotBlank() && beforeImage == afterImage) "unchanged" else "unknown"
    }

    private fun state(page: JSONObject) = JSONObject().apply {
        for (key in listOf("package_name", "width", "height", "rotation", "semantic_reliable", "content_fingerprint", "image_sha256", "capture_id", "requires_pixels"))
            if (page.has(key)) put(key, page.get(key))
    }

    private fun target(action: JSONObject, page: JSONObject): JSONObject? {
        val width = page.optInt("width"); val height = page.optInt("height")
        val pkg = page.optString("package_name")
        if (width <= 0 || height <= 0 || pkg.isBlank()) return null
        val kind = action.optString("kind")
        val target = JSONObject()
        val identity: String
        val actualKind: String
        if (kind == "visual_gesture") {
            val gesture = action.optJSONObject("gesture") ?: return null
            actualKind = gesture.optString("kind").takeIf { it in setOf("tap", "long_press", "swipe") } ?: return null
            val x = coordinate(gesture.opt("x")) ?: return null
            val y = coordinate(gesture.opt("y")) ?: return null
            target.put("source", "visual").put("x", x).put("y", y).put("device_x", x * width).put("device_y", y * height)
            if (actualKind == "swipe") {
                target.put("end_x", coordinate(gesture.opt("end_x")) ?: return null).put("end_y", coordinate(gesture.opt("end_y")) ?: return null)
            }
            identity = target.toString()
        } else {
            actualKind = kind.takeIf { it in setOf("tap", "long_press", "scroll") } ?: return null
            val node = page.optJSONObject("targets")?.optJSONObject(action.optString("target")) ?: return null
            if (node.optBoolean("input")) return null
            val bounds = bounds(node.optJSONArray("bounds"), width, height) ?: return null
            val x = (bounds[0].toDouble() + bounds[2]) / 2
            val y = (bounds[1].toDouble() + bounds[3]) / 2
            target.put("source", "node").put("bounds", JSONArray(bounds)).put("x", x / width).put("y", y / height)
                .put("device_x", x).put("device_y", y)
            identity = node.optString("key").takeIf { it.isNotBlank() } ?: return null
        }
        return target.put("kind", actualKind).put("key", digest(JSONArray().put(pkg).put(width).put(height).put(actualKind).put(identity).toString()))
    }

    private fun coordinate(value: Any?): Double? = (value as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 && it < 1 }
    private fun bounds(value: JSONArray?, width: Int, height: Int): List<Int>? {
        if (value == null || value.length() != 4) return null
        val values = (0..3).map { index ->
            val number = value.opt(index) as? Number ?: return null
            val double = number.toDouble()
            if (!double.isFinite() || double != number.toInt().toDouble()) return null
            number.toInt()
        }
        return values.takeIf { it[0] >= 0 && it[1] >= 0 && it[2] <= width && it[3] <= height && it[0] < it[2] && it[1] < it[3] }
    }
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
