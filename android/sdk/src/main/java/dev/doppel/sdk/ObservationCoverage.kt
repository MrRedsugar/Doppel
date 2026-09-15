package dev.doppel.sdk
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Run-local coverage facts. Labels, models, available scroll actions and accepted gestures prove no boundary. */
internal object ObservationCoverage {
    private fun state(run:JSONObject)=run.optJSONObject("observation_coverage") ?: JSONObject()
        .put("viewports",JSONArray()).put("scroll_ids",JSONArray()).put("distinct_viewports",0).put("accepted_scrolls",0)
        .also { run.put("observation_coverage",it) }

    /** Adapt only metadata exported by the host's complete accessibility walk; never infer row indices from labels. */
    fun collectionEvidence(observation:JSONObject):JSONObject? {
        if(observation.opt("tree_complete") != true) return null
        val pkg = string(observation.opt("package_name"),255) ?: return null
        val screen = string(observation.opt("screen_id"),128) ?: return null
        val width = integer(observation.opt("width"),1,Int.MAX_VALUE) ?: return null
        val height = integer(observation.opt("height"),1,Int.MAX_VALUE) ?: return null
        val rawNodes = observation.optJSONArray("nodes") ?: return null
        if(rawNodes.length() !in 1..10000) return null
        var candidate:JSONObject? = null
        for(index in 0 until rawNodes.length()) {
            val node = rawNodes.optJSONObject(index) ?: return null
            if(node.has("collection_info") && !node.isNull("collection_info")) {
                if(candidate != null) return null // Nested/multiple collections have different index scopes.
                candidate = node
            }
        }
        val collectionNode = candidate ?: return null
        val nodes = linkedMapOf<String,JSONObject>()
        val parents = mutableMapOf<String,String?>()
        for(index in 0 until rawNodes.length()) {
            val node = rawNodes.optJSONObject(index) ?: return null
            val id = string(node.opt("id"),250) ?: return null
            if(nodes.put(id,node) != null) return null
            val rawParent = node.opt("parent_id")
            if(rawParent != null && rawParent != JSONObject.NULL && rawParent !is String) return null
            parents[id] = (rawParent as? String)?.takeIf { it.isNotBlank() }
        }
        if(parents.values.count { it == null } != 1) return null
        val ancestry = mutableMapOf<String,List<String>>()
        for(id in nodes.keys) {
            val path = mutableListOf<String>()
            val seen = mutableSetOf(id)
            var parent = parents[id]
            while(parent != null) {
                if(parent !in nodes || !seen.add(parent) || path.size >= 64) return null
                path.add(parent)
                parent = parents[parent]
            }
            ancestry[id] = path
        }
        val collectionId = collectionNode.getString("id")
        val info = collectionNode.optJSONObject("collection_info") ?: return null
        if(collectionNode.has("collection_item_info") && !collectionNode.isNull("collection_item_info")) return null
        val count = integer(info.opt("row_count"),1,10000) ?: return null
        if(integer(info.opt("column_count"),1,1) != 1 || info.opt("hierarchical") != false) return null
        val viewport = Box(0,0,width,height)
        val visible = mutableMapOf<String,Boolean>()
        fun fullyVisible(id:String):Boolean = visible.getOrPut(id) {
            val own = bounds(nodes.getValue(id))
            if(own == null || !viewport.contains(own)) false else {
                var child:Box = own
                ancestry.getValue(id).all { parent ->
                    val outer = bounds(nodes.getValue(parent))
                    val inside = outer != null && viewport.contains(outer) && outer.contains(child)
                    if(outer != null) child = outer
                    inside
                }
            }
        }
        if(!fullyVisible(collectionId)) return null
        val rowNodes = mutableMapOf<String,Int>()
        val indices = mutableSetOf<Int>()
        for((id,node) in nodes) {
            val ancestors = ancestry.getValue(id)
            if(collectionId !in ancestors || !node.has("collection_item_info") || node.isNull("collection_item_info")) continue
            val item = node.optJSONObject("collection_item_info") ?: return null
            if(ancestors.takeWhile { it != collectionId }.any { ancestor ->
                    nodes.getValue(ancestor).let { it.has("collection_item_info") && !it.isNull("collection_item_info") }
                }) return null
            val row = integer(item.opt("row_index"),0,count-1) ?: return null
            if(integer(item.opt("row_span"),1,1) != 1 || integer(item.opt("column_index"),0,0) != 0 ||
                integer(item.opt("column_span"),1,1) != 1 || !indices.add(row)) return null
            rowNodes[id] = row
        }
        val visibleRows = rowNodes.filterKeys { id ->
            fullyVisible(id) && nodes.keys.all { descendant -> id !in ancestry.getValue(descendant) || fullyVisible(descendant) }
        }.values.sorted()
        val resource = string(collectionNode.opt("resource_id"),255)
        val scopeId = resource?.takeIf { name -> nodes.values.count { it.opt("resource_id") == name } == 1 } ?: "node:$collectionId"
        return JSONObject().put("source","accessibility_collection_info").put("package_name",pkg).put("screen_id",screen)
            .put("collection_id",scopeId).put("row_count",count).put("column_count",1)
            .put("visible_rows",JSONArray(visibleRows)).put("structure_complete",true)
        // Android CollectionInfo has no dataset revision. A host/provider must supply one separately to observe().
    }

    /** trustedDatasetRevision is an explicit host/provider attestation, never a value copied from observation/model JSON. */
    fun observe(run:JSONObject,observation:JSONObject,evidenceId:String?,at:Long,trustedDatasetRevision:String?=null):JSONObject {
        val state=state(run)
        val previousScope=state.optString("current_scope")
        val previousKey=state.opt("dataset_key") as? String
        val previousCount=state.optInt("row_count")
        val previousRows=state.optJSONArray("covered_rows")
        val pkg=string(observation.opt("package_name"),255);val screen=string(observation.opt("screen_id"),128)
        state.remove("current_scope");state.put("all_proven",false)
        state.remove("current_proof")
        if(pkg==null || screen==null || evidenceId.isNullOrBlank()) {
            state.remove("current_package");state.remove("current_screen");state.remove("evidence_id");state.remove("observed_at")
            clearCollection(state)
            return JSONObject(state.toString())
        }
        state.put("current_package",pkg).put("current_screen",screen).put("evidence_id",evidenceId.take(128)).put("observed_at",at)
        val pages=state.getJSONArray("viewports");val key=fingerprint(observation)
        if((0 until pages.length()).none { pages.optString(it)==key } && pages.length()<128) pages.put(key)
        state.put("distinct_viewports",pages.length())
        val source=observation.optJSONObject("collection_evidence")
        val proof=source?.let { collection(it,observation) }
        if(source==null || proof==null) {
            clearCollection(state)
            return JSONObject(state.toString())
        }
        val scope="$pkg/${source.optString("collection_id")}"
        val revision=string(trustedDatasetRevision,160)
        val stableKey=revision?.let { JSONArray().put(scope).put(proof.first).put(it).toString() }
        val rows=proof.second.toMutableSet()
        if(stableKey!=null && previousKey==stableKey && previousScope==scope && previousCount==proof.first && previousRows!=null) {
            for(index in 0 until minOf(previousRows.length(),10000)) integer(previousRows.opt(index),0,proof.first-1)?.let(rows::add)
        }
        if(previousScope!=scope || previousKey!=stableKey || previousCount!=proof.first) {
            state.remove("seen_start");state.remove("seen_end")
        }
        state.put("dataset_key",stableKey ?: JSONObject.NULL).put("covered_rows",JSONArray(rows.sorted()))
            .put("current_scope",scope).put("row_count",proof.first).put("current_visible_rows",proof.second.size)
            .put("all_proven",rows.size==proof.first).put("stable_revision",stableKey!=null)
            .put("current_proof",proofFingerprint(observation,source,proof))
        return JSONObject(state.toString())
    }

    private fun clearCollection(state:JSONObject) {
        for(key in listOf("dataset_key","covered_rows","row_count","current_visible_rows","stable_revision","seen_start","seen_end")) state.remove(key)
    }

    fun recordAction(run:JSONObject,command:JSONObject,status:String,data:JSONObject) {
        val isScroll=command.optString("kind")=="scroll" || command.optString("kind")=="visual_gesture" && command.optJSONObject("gesture")?.optString("kind")=="swipe"
        if(!isScroll) return
        val id=command.optString("id");if(id.isBlank() || id.length>128) return
        val state=state(run);val ids=state.getJSONArray("scroll_ids")
        if(ids.length()>=256 || (0 until ids.length()).any { ids.optString(it)==id }) return
        ids.put(id)
        if(status=="ok" && data.optString("action_state")=="accepted" && !data.optBoolean("no_op")) state.put("accepted_scrolls",state.optInt("accepted_scrolls")+1)
        val boundary=data.optJSONObject("coverage_boundary") ?: return
        if(boundary.optString("source")!="host_verified_scroll_boundary" || boundary.optString("command_id")!=id ||
            boundary.optString("screen_id")!=state.optString("current_screen") || boundary.optString("package_name")!=state.optString("current_package") ||
            boundary.optString("scope")!=state.optString("current_scope") || state.optString("current_scope").isBlank()) return
        if(boundary.optString("edge") in setOf("start","end")) state.put("seen_${boundary.optString("edge")}",true)
        // Reaching both ends does not prove that no page or row was skipped.
    }

    fun canClaimAll(run:JSONObject,current:JSONObject?=null,collectionScope:String?=null):Boolean {
        val state=run.optJSONObject("observation_coverage") ?: return false
        if(current==null || !state.optBoolean("all_proven") || current.opt("tree_complete")!=true) return false
        if(current.optString("package_name")!=state.optString("current_package") || current.optString("screen_id")!=state.optString("current_screen")) return false
        val proof=current.optJSONObject("collection_evidence") ?: return false
        val parsed=collection(proof,current) ?: return false
        if(state.optString("current_proof")!=proofFingerprint(current,proof,parsed)) return false
        val scope="${current.optString("package_name")}/${proof.optString("collection_id")}"
        return state.optString("current_scope")==scope && (collectionScope==null || collectionScope==scope)
    }

    fun summary(run:JSONObject,current:JSONObject?=null):String {
        val state=run.optJSONObject("observation_coverage") ?: return "读取范围：当前可见范围，尚无完整列表证据。"
        val scope=state.optString("current_scope")
        val boundaries=listOf("start" to "起点","end" to "终点").filter { state.optBoolean("seen_${it.first}") }.joinToString("、") { it.second }
        val detail=if(scope.isBlank()) "未取得可信集合行索引" else "最近可见${state.optInt("current_visible_rows")}行，已核对${state.optJSONArray("covered_rows")?.length() ?: 0}/${state.optInt("row_count")}行；collection_scope=$scope${if(boundaries.isEmpty()) "" else "；已验证边界：$boundaries"}"
        return "读取范围：${if(canClaimAll(run,current)) "当前集合全部行已覆盖" else "当前可见范围，不能据此声称全部"}。已读${state.optInt("distinct_viewports")}个不同视口状态，系统接受${state.optInt("accepted_scrolls")}次滚动；$detail。".take(700)
    }

    private fun collection(value:JSONObject,screen:JSONObject):Pair<Int,Set<Int>>? {
        if(value.opt("source")!="accessibility_collection_info" || value.opt("screen_id")!=screen.opt("screen_id") ||
            value.opt("package_name")!=screen.opt("package_name") || screen.opt("tree_complete")!=true || value.opt("structure_complete")!=true ||
            string(value.opt("collection_id"),255)==null || integer(value.opt("column_count"),1,1)!=1) return null
        val count=integer(value.opt("row_count"),1,10000) ?: return null
        val values=value.optJSONArray("visible_rows") ?: return null
        if(values.length()>10000) return null
        val rows=mutableSetOf<Int>()
        for(index in 0 until values.length()) {
            val row=integer(values.opt(index),0,count-1) ?: return null
            rows.add(row)
        }
        return count to rows
    }

    private fun string(value:Any?,limit:Int):String? = (value as? String)?.takeIf { it.isNotBlank() && it.length<=limit }

    private fun integer(value:Any?,minimum:Int,maximum:Int):Int? {
        if(value !is Int && value !is Long) return null
        return (value as Number).toLong().takeIf { it in minimum.toLong()..maximum.toLong() }?.toInt()
    }

    private data class Box(val left:Int,val top:Int,val right:Int,val bottom:Int) {
        fun contains(other:Box) = left<=other.left && top<=other.top && right>=other.right && bottom>=other.bottom
    }

    private fun bounds(node:JSONObject):Box? {
        val values=node.optJSONArray("bounds") ?: return null
        if(values.length()!=4) return null
        val coordinates=(0..3).map { integer(values.opt(it),Int.MIN_VALUE,Int.MAX_VALUE) ?: return null }
        if(coordinates[0]>=coordinates[2] || coordinates[1]>=coordinates[3]) return null
        return Box(coordinates[0],coordinates[1],coordinates[2],coordinates[3])
    }

    private fun proofFingerprint(screen:JSONObject,source:JSONObject,proof:Pair<Int,Set<Int>>):String {
        val nodes=screen.optJSONArray("nodes") ?: JSONArray()
        val metadata=(0 until nodes.length()).map { index -> nodes.optJSONObject(index)?.let { node ->
            JSONArray().put(node.opt("id")).put(node.opt("parent_id")).put(node.opt("bounds")).put(node.opt("resource_id"))
                .put(node.opt("collection_info")).put(node.opt("collection_item_info")).toString()
        }.orEmpty() }.sorted()
        val bound=JSONArray().put(screen.opt("package_name")).put(screen.opt("screen_id")).put(source.opt("source"))
            .put(source.opt("collection_id")).put(proof.first).put(1).put(true).put(JSONArray(proof.second.sorted()))
            .put(source.opt("dataset_revision")).put(fingerprint(screen)).put(JSONArray(metadata))
        return digest(bound.toString())
    }

    private fun fingerprint(screen:JSONObject):String {
        val nodes=screen.optJSONArray("nodes") ?: JSONArray()
        val rows=(0 until minOf(nodes.length(),300)).mapNotNull { index -> nodes.optJSONObject(index)?.let { node ->
            val hidden=node.optBoolean("password") || node.optBoolean("editable")
            JSONArray().put(node.optString("role")).put(if(hidden) "" else node.optString("text").take(400))
                .put(if(hidden) "" else node.optString("description").take(400)).put(node.optJSONArray("bounds"))
                .put(node.optBoolean("selected")).put(node.optBoolean("checked")).toString()
        } }.sorted()
        val text=JSONArray().put(screen.optString("package_name")).put(screen.optInt("width")).put(screen.optInt("height")).put(JSONArray(rows)).toString()
        return digest(text)
    }

    private fun digest(text:String):String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
