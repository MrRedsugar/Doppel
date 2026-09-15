package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject

/** Operates on the already-read SystemUI tree, never on the entire notification shade as one text. */
internal object SystemUiNotificationPrivacy {
    data class Result(val nodeIds: Set<String>, val bounds: List<List<Int>>)
    fun find(nodes: JSONArray, protectedBounds: List<List<Int>> = emptyList()): Result {
        val records = linkedMapOf<String, JSONObject>()
        repeat(minOf(nodes.length(), 300)) { index -> nodes.optJSONObject(index)?.let { node ->
            node.optString("id").takeIf { it.isNotBlank() }?.let { records[it] = node }
        } }
        val children = records.keys.groupBy { records.getValue(it).optString("parent_id") }
        fun subtree(root: String): Set<String> {
            val result = linkedSetOf<String>(); val pending = ArrayDeque<String>(); pending.add(root)
            while (pending.isNotEmpty()) {
                val id = pending.removeFirst()
                if (result.add(id)) children[id]?.forEach(pending::addLast)
            }
            return result
        }
        fun kind(node: JSONObject): Int {
            val type = node.optString("class_name", node.optString("role"))
            if (type == "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow") return 0
            return when (node.optString("resource_id")) {
                "com.android.systemui:id/notification_row", "com.android.systemui:id/expandableNotificationRow" -> 0
                "android:id/status_bar_latest_event_content" -> 1
                "android:id/notification_main_column" -> 2
                else -> -1
            }
        }
        val candidates = records.filterValues { kind(it) >= 0 }
        val descendants = candidates.keys.associateWith(::subtree)
        val cards = mutableListOf<String>()
        for (rank in 0..2) for ((id, node) in candidates) {
            if (kind(node) != rank) continue
            val family = descendants.getValue(id)
            // A grouped row must never combine a title from one notification with another's numbers.
            if (candidates.any { (child, other) -> child != id && child in family && kind(other) <= rank }) continue
            if (candidates.count { (child, other) -> child != id && child in family && kind(other) == rank + 1 } > 1) continue
            if (cards.any { id in descendants.getValue(it) }) continue
            cards += id
        }
        val hidden = linkedSetOf<String>(); val bounds = linkedSetOf<List<Int>>()
        for (id in cards) {
            val family = descendants.getValue(id)
            val text = family.joinToString("\n") { child -> records.getValue(child).let { node ->
                listOf("text", "description", "hint", "state_description").joinToString(" ") { node.optString(it) }
            } }
            val alreadyPrivate = family.any { child -> records.getValue(child).optJSONArray("bounds")?.let { rect ->
                rect.length() == 4 && (0..3).map(rect::optInt) in protectedBounds
            } == true }
            if (!alreadyPrivate && !DeviceReadPrivacy.codeNotification("", text)) continue
            val rect = records.getValue(id).optJSONArray("bounds") ?: continue
            if (rect.length() != 4) continue
            val box = (0..3).map { rect.optInt(it) }
            if (box[2] <= box[0] || box[3] <= box[1]) continue
            hidden.addAll(family); bounds.add(box)
        }
        return Result(hidden, bounds.toList())
    }
}
