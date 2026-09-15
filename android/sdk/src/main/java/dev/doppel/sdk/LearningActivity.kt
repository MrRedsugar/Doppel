package dev.doppel.sdk

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

@Deprecated("Application learning is retired; this activity is not registered by Doppel.")
class LearningActivity : Activity() {
    private lateinit var learning: AppLearning
    private lateinit var content: LinearLayout
    private var exportName: String? = null
    private val io = Executors.newSingleThreadExecutor()
    private var generating = false
    private var firstResume = true
    private var restoredEditor: Bundle? = null
    private var editorSnapshot: (() -> JSONObject)? = null
    private var editingName: String? = null
    private var editingRevision: String? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); UiTheme.init(this); learning = AppLearning(this); exportName = state?.getString("export_name"); restoredEditor = state
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        UiTheme.bind(root) { root.setBackgroundColor(UiTheme.background) }; UiTheme.window(this, root)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(20), dp(8)) }
        header.addView(UiTheme.icon(this, UiIcons.back, "返回") { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(UiTheme.text(this, "应用学习", 21f, UiTheme.ink, true)); root.addView(header)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(28)) }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
    }
    override fun onResume() {
        super.onResume(); if (!firstResume) return; firstResume = false
        val restored = restoredEditor; restoredEditor = null
        val draft = restored?.getString("editor_json")?.let { runCatching { JSONObject(it) }.getOrNull() }
        val name = restored?.getString("editing_name")
        val evidence = if (name == null) learning.pendingEvidence() else learning.manual.read(name, inspect = true).optJSONObject("evidence")
        when {
            draft != null && evidence != null -> editDraft(draft, evidence, name, restored.getString("editing_revision"))
            intent.getBooleanExtra("review_demo", false) && learning.pendingEvidence() != null -> describeProcess(learning.pendingEvidence())
            else -> render()
        }
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("export_name", exportName)
        editorSnapshot?.let { out.putString("editor_json", it().toString()); out.putString("editing_name", editingName); out.putString("editing_revision", editingRevision) }
        super.onSaveInstanceState(out)
    }
    private fun render() {
        editorSnapshot = null; editingName = null; editingRevision = null
        content.removeAllViews()
        content.addView(UiTheme.text(this, "用过一次，下次更熟悉", 25f, UiTheme.ink, true).apply { setPadding(0, dp(10), 0, dp(12)) })
        content.addView(UiTheme.text(this, "把实际操作整理成本机 Skills，下次遇到相关任务时参考。每一步仍会观察当前界面。", 14f, UiTheme.muted))
        content.addView(UiTheme.row(this, "手动示范一次", "开始采集 → 亲自操作 → 结束后填写目标 → 复核 Skill", UiIcons.sparkles) { chooseApp() })
        content.addView(UiTheme.row(this, "用文字描述操作过程", "从目标、步骤和结果生成可编辑草稿", UiIcons.edit) { attempt { check(learning.pendingEvidence() == null) { "请先整理或丢弃已有草稿" }; describeProcess(null) } })
        learning.pendingEvidence()?.let { pending ->
            content.addView(UiTheme.row(this, "继续整理待保存的示范", "${pending.optJSONArray("screenshots")?.length() ?: 0} 张截图 · ${pending.optJSONArray("events")?.length() ?: 0} 条观察", UiIcons.sparkles) {
                pending.optJSONObject("generated_draft")?.let { editDraft(it, pending) } ?: describeProcess(pending)
            })
            content.addView(UiTheme.row(this, "丢弃待保存示范", "删除本机暂存截图与草稿", UiIcons.close) { learning.clearPendingEvidence(); render() })
        }
        if (DemonstrationSession.active) content.addView(UiTheme.row(this, "取消当前演示", "停止采集，不生成文档", UiIcons.pause) { DemonstrationSession.cancel(); render() })
        content.addView(UiTheme.text(this, "已积累的经验", 13f, UiTheme.muted).apply { setPadding(0, dp(24), 0, dp(10)) })
        attempt {
            val manualItems = learning.manual.list().getJSONArray("items")
            repeat(manualItems.length()) { index ->
                val item = manualItems.getJSONObject(index)
                content.addView(UiTheme.row(this, item.getString("title"), "${if (item.optBoolean("enabled", true)) "可供参考" else "已停用"} · ${item.getString("description")}", UiIcons.sparkles) { showManual(item.getString("name")) })
            }
            val result = learning.store.list(); val items = result.getJSONArray("items")
            if (items.length() == 0 && manualItems.length() == 0) content.addView(UiTheme.text(this, "亲自示范或描述流程，复核并保存后，这里会显示对应 Skill。", 14f, UiTheme.muted))
            repeat(items.length()) { index ->
                val item = items.getJSONObject(index)
                val app = item.getJSONObject("app"); val name = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(app.getString("package_name"), 0)).toString() }.getOrDefault(app.getString("package_name"))
                val availability = when (item.getString("availability")) { "disabled" -> "已停用"; "version_changed" -> "版本或环境变化 · 待重新学习"; else -> "可供参考" }
                content.addView(UiTheme.row(this, item.getString("title"), "$name · ${item.getInt("observations")} 次观察\n$availability", UiIcons.files) { show(item.getString("name")) })
            }
            if (result.getJSONArray("errors").length() > 0) content.addView(UiTheme.text(this, "部分学习文档损坏，未用于任务参考。", 13f, UiTheme.danger))
        }
        content.addView(UiTheme.text(this, "学习库保存在本机。生成草稿会把暂存的示范截图和观察发送给主模型，保存后按需读取 Skill。截图最多 12 张；连续手势、游戏画布与输入内容可能缺失，草稿会说明限制，保存不代表已验证。", 12f, UiTheme.muted).apply { setPadding(0, dp(28), 0, 0) })
    }
    private fun chooseApp() = attempt {
        FirstUseConsent.requireAccepted(this)
        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .distinctBy { it.activityInfo.packageName }.filter { it.activityInfo.packageName != packageName }.sortedBy { it.loadLabel(packageManager).toString() }
        check(learning.pendingEvidence() == null) { "请先整理或丢弃上次示范，避免覆盖" }
        UiDialog.Builder(this).setTitle("选择要教的应用").setMessage("开始后会显示采集提示并暂存截图。亲自操作，结束后填写完成的任务。生成草稿时会发送观察给主模型；取消将丢弃本次采集。")
            .setItems(apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()) { _, index -> attempt {
                val host = DoppelAccessibilityService.instance ?: error("请先开启无障碍服务")
                val target = apps[index].activityInfo.packageName
                val launch = packageManager.getLaunchIntentForPackage(target) ?: error("无法打开此应用")
                DemonstrationSession.start(host, target)
                try { startActivity(launch); finish() } catch (failure: Exception) { DemonstrationSession.cancel(); throw failure }
            } }.setNegativeButton("取消", null).show()
    }
    private fun describeProcess(recording: JSONObject?) {
        if (generating) return
        content.removeAllViews()
        content.addView(UiTheme.text(this, if (recording == null) "描述一段操作" else "刚才完成了什么任务？", 23f, UiTheme.ink, true))
        val goal = UiTheme.field(this, "例如：在设置中开启深色模式", recording?.optString("user_reported_goal").orEmpty())
        val process = UiTheme.field(this, if (recording == null) "描述前提、操作步骤、看到的结果" else "补充截图没记录到的步骤或结果（可选）", recording?.optString("user_description").orEmpty()).apply { minLines = 5 }
        content.addView(goal); content.addView(process)
        content.addView(UiTheme.text(this, "草稿生成后可逐步编辑，点击保存才会成为 Skill。模型不会替你操作手机。", 13f, UiTheme.muted))
        content.addView(UiTheme.command(this, "生成 Skill 草稿", true) { attempt {
            val objective = goal.text.toString().trim(); require(objective.isNotBlank()) { "请先填写任务目标" }
            val description = process.text.toString().trim(); require(description.length <= 12000) { "操作描述最多 12000 字" }
            if (recording == null) require(description.isNotBlank()) { "请填写操作过程" }
            val evidence = JSONObject(recording?.toString() ?: "{}").put("user_reported_goal", objective).put("user_description", description)
            if (!evidence.has("events")) evidence.put("events", JSONArray()).put("origin", "manual_description").put("source_id", "text-${UUID.randomUUID()}")
            if (description.isNotBlank()) {
                val events = evidence.getJSONArray("events")
                for (i in events.length() - 1 downTo 0) if (events.getJSONObject(i).optString("id") == "user-description") events.remove(i)
                events.put(JSONObject().put("id", "user-description").put("kind", "user_reported_unverified").put("description", description))
            }
            val generation = UUID.randomUUID().toString(); evidence.put("generation_id", generation)
            learning.savePendingEvidence(evidence); generating = true
            content.removeAllViews(); val progress = UiTheme.text(this, "主模型正在整理观察…", 18f); content.addView(progress)
            io.execute {
                val result = runCatching { SkillDraftGenerator(applicationContext).generate(objective, evidence) }
                runOnUiThread {
                    generating = false
                    result.fold({ draft ->
                        if (learning.pendingEvidence()?.optString("generation_id") != generation) return@fold
                        evidence.put("generated_draft", draft); learning.savePendingEvidence(evidence)
                        if (!isFinishing && !isDestroyed) editDraft(draft, evidence)
                    }, { failure -> if (!isFinishing && !isDestroyed) { render(); Toast.makeText(this, failure.message?.take(180) ?: "草稿生成失败，观察已保留", Toast.LENGTH_LONG).show() } })
                }
            }
        } })
        content.addView(UiTheme.command(this, "返回学习库") { render() })
    }
    private fun editDraft(draft: JSONObject, evidence: JSONObject, existing: String? = null, revision: String? = null) {
        content.removeAllViews()
        content.addView(UiTheme.text(this, "预览并编辑 Skill", 23f, UiTheme.ink, true))
        val title = UiTheme.field(this, "标题", draft.getString("title")); val description = UiTheme.field(this, "何时使用", draft.getString("description"))
        content.addView(title); content.addView(description)
        data class StepEditor(val source: JSONObject, val instruction: EditText, val expected: EditText,
                              val row: LinearLayout, val heading: android.widget.TextView, val remove: android.widget.Button)
        val editors = mutableListOf<StepEditor>()
        val stepArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; content.addView(stepArea)
        fun renumber() { editors.forEachIndexed { index, editor ->
            editor.heading.text = "第 ${index + 1} 步"; editor.remove.text = "删除第 ${index + 1} 步"
            editor.remove.contentDescription = "删除第 ${index + 1} 步"
            editor.instruction.contentDescription = "第 ${index + 1} 步操作与目标"
            editor.expected.contentDescription = "第 ${index + 1} 步核验结果"
        } }
        fun addStep(step: JSONObject) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(8)) }
            val heading = UiTheme.text(this, "", 14f, UiTheme.muted)
            val instruction = UiTheme.field(this, "操作与目标", step.optString("instruction")).apply { minLines = 2 }
            val expected = UiTheme.field(this, "如何核验结果", step.optString("expected"))
            val remove = UiTheme.command(this, "删除步骤") {}
            val editor = StepEditor(JSONObject(step.toString()), instruction, expected, row, heading, remove)
            remove.setOnClickListener { attempt {
                check(editors.size > 1) { "至少保留一个步骤" }
                editors.remove(editor); stepArea.removeView(row); renumber()
            } }
            row.addView(heading); row.addView(instruction); row.addView(expected); row.addView(remove)
            editors += editor; stepArea.addView(row); renumber()
        }
        val steps = draft.getJSONArray("steps")
        repeat(steps.length()) { addStep(steps.getJSONObject(it)) }
        content.addView(UiTheme.command(this, "添加步骤") { attempt {
            check(editors.size < 24) { "最多保留 24 个步骤，请拆分较长流程" }
            addStep(JSONObject().put("instruction", "").put("expected", "").put("evidence_ids", JSONArray().put("user-step-${UUID.randomUUID()}")))
            editors.last().instruction.requestFocus()
        } })
        val limits = draft.getJSONArray("limitations")
        val limitations = UiTheme.field(this, "观察限制（每行一项）", (0 until limits.length()).joinToString("\n") { limits.getString(it) }).apply { minLines = 3 }
        content.addView(limitations)
        editingName = existing; editingRevision = revision
        editorSnapshot = {
            JSONObject(draft.toString()).put("title", title.text.toString()).put("description", description.text.toString()).also { updated ->
                updated.put("steps", JSONArray(editors.map { fields -> JSONObject(fields.source.toString()).put("instruction", fields.instruction.text.toString()).put("expected", fields.expected.text.toString()) }))
                updated.put("limitations", JSONArray(limitations.text.toString().lines().filter { it.isNotBlank() }))
            }
        }
        content.addView(UiTheme.text(this, "这是待核验的语义操作建议；应用变化或证据不足时，应重新观察。它不会增加任何权限。", 13f, UiTheme.muted))
        content.addView(UiTheme.command(this, "保存 Skill", true) { attempt {
            val updated = requireNotNull(editorSnapshot).invoke()
            FirstUseConsent.requireAccepted(this)
            val updatedEvidence = JSONObject(evidence.toString())
            val events = updatedEvidence.getJSONArray("events")
            val known = SkillDraft.evidenceIds(updatedEvidence)
            val savedSteps = updated.getJSONArray("steps")
            repeat(savedSteps.length()) { index ->
                val step = savedSteps.getJSONObject(index); val ids = step.getJSONArray("evidence_ids")
                repeat(ids.length()) { sourceIndex ->
                    val id = ids.getString(sourceIndex)
                    if (id.startsWith("user-step-") && id !in known) events.put(JSONObject().put("id", id).put("kind", "user_added_step")
                        .put("instruction", step.getString("instruction")).put("expected", step.getString("expected")).put("verified", false))
                }
            }
            learning.manual.save(updated, updatedEvidence, existing, revision)
            if (existing == null) learning.clearPendingEvidence()
            Toast.makeText(this, "Skill 已保存", Toast.LENGTH_SHORT).show(); render()
        } })
        content.addView(UiTheme.command(this, "返回学习库") { render() })
    }
    private fun showManual(name: String) = attempt {
        val item = learning.manual.read(name, inspect = true); check(item.optBoolean("found")) { "Skill 已变化，请刷新" }
        UiDialog.Builder(this).setTitle(item.getString("title")).setMessage(item.getString("instructions"))
            .setItems(arrayOf("编辑", "导出 Skill ZIP", if (item.optBoolean("enabled", true)) "停用" else "启用", "删除")) { _, choice -> attempt {
                when (choice) {
                    0 -> editDraft(item.getJSONObject("draft"), item.getJSONObject("evidence"), name, item.getString("revision"))
                    1 -> { exportName = name; @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "$name.zip"), 115) }
                    2 -> { if (!item.optBoolean("enabled", true)) FirstUseConsent.requireAccepted(this); learning.manual.setEnabled(name, !item.optBoolean("enabled", true)); render() }
                    else -> UiDialog.Builder(this).setTitle("删除这份 Skill？").setNegativeButton("保留", null).setPositiveButton("删除") { _, _ -> attempt { learning.manual.delete(name); render() } }.show()
                }
            } }.setNegativeButton("关闭", null).show()
    }
    private fun show(name: String) = attempt {
        val item = learning.store.read(name, inspect = true); check(item.optBoolean("found")) { "学习文档已变化，请刷新" }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var sheet: UiDialog? = null
        body.addView(UiTheme.text(this, item.getString("instructions"), 14f).apply { setTextIsSelectable(true) })
        body.addView(UiTheme.row(this, if (item.getBoolean("enabled")) "停用此经验" else "启用此经验", "不改变原来的操作授权", UiIcons.pause) {
            attempt { learning.store.setEnabled(name, !item.getBoolean("enabled")); sheet?.dismiss(); render() }
        })
        body.addView(UiTheme.row(this, "导出为 Skill", "标准 SKILL.md 与来源资料 ZIP", UiIcons.files) {
            exportName = name
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "$name.zip"), 115)
        })
        sheet = UiDialog.Builder(this).setTitle(item.getString("title")).setView(body).setNegativeButton("关闭", null)
            .setNeutralButton("删除") { _, _ ->
                UiDialog.Builder(this).setTitle("删除这份经验？").setMessage("之后的任务将不再参考它。")
                    .setNegativeButton("保留", null).setPositiveButton("删除") { _, _ -> attempt { learning.store.delete(name); render() } }.show()
            }.show()
    }
    @Deprecated("Activity result bridge")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == 115) {
            val name = exportName; exportName = null
            if (result == RESULT_OK && name != null && data?.data != null) attempt {
                val bytes = if (name.startsWith("manual-")) learning.manual.export(name) else learning.store.export(name)
                requireNotNull(contentResolver.openOutputStream(data.data!!, "wt")).use { it.write(bytes) }
                Toast.makeText(this, "Skill 已导出", Toast.LENGTH_SHORT).show()
            }
        }
    }
    override fun onDestroy() { io.shutdown(); super.onDestroy() }
    private fun attempt(block: () -> Unit) { try { block() } catch (failure: Exception) { Toast.makeText(this, failure.message ?: "操作未完成，请重试", Toast.LENGTH_LONG).show() } }
    private fun dp(value: Int) = UiTheme.dp(this, value)
}
