package dev.doppel.sdk

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** Local rule editor; the accessibility picker can prefill the selected package and ID. */
class AutoTriggerSettingsActivity : Activity() {
    private lateinit var content: LinearLayout
    private val store by lazy { AutoTriggerStore(this) }
    private fun dp(value: Int) = UiTheme.dp(this, value)

    override fun onCreate(state: Bundle?) { super.onCreate(state); UiTheme.init(this); render() }
    /**
     * The picker launches this activity with CLEAR_TOP.  When an editor is
     * already on the back stack Android reuses that instance and delivers the
     * selected package/id through onNewIntent; keep the new intent so onResume
     * can consume it and prefill the editor.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) setIntent(intent)
    }
    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) render()
        val pickedId = intent.getStringExtra("picker_resource_id").orEmpty()
        if (pickedId.isNotBlank()) {
            val pkg = intent.getStringExtra("picker_package").orEmpty()
            val text = intent.getStringExtra("picker_text").orEmpty()
            intent.removeExtra("picker_resource_id"); intent.removeExtra("picker_package"); intent.removeExtra("picker_text")
            edit(null, pkg, pickedId, text)
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 8342) render()
    }

    private fun render() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        UiTheme.bind(root) { root.setBackgroundColor(UiTheme.background) }; UiTheme.window(this, root)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(18), dp(6)) }
        header.addView(UiTheme.icon(this, UiIcons.back, "返回") { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(UiTheme.text(this, "自动触发", 21f, UiTheme.ink, true)); root.addView(header)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), dp(28)) }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        content.addView(UiTheme.text(this, "当指定应用出现目标控件时自动执行一次动作。跳过后，要等控件消失再出现才会重新触发；默认 15 秒内触发 3 次，会暂停该规则 60 秒并提醒。规则只读取无障碍节点，不上传截图；其他任务执行中不会自动操作。", 13f, UiTheme.muted))
        content.addView(AutomaticTaskConflict.notificationSettingsView(this))
        content.addView(UiTheme.command(this, "自动解锁设置") { startActivity(Intent(this, AutomaticUnlockSettingsActivity::class.java)) })
        content.addView(UiTheme.command(this, "创建规则", true) { edit(null) }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(16) })
        content.addView(UiTheme.command(this, "从当前应用选择控件", false) {
            DoppelAccessibilityService.instance?.let { AccessibilityControlPicker.begin(it) }
                ?: toast(IllegalStateException("请先启用无障碍服务"))
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
        val rules = store.list()
        if (rules.isNotEmpty()) content.addView(UiTheme.text(this, "已保存规则", 13f, UiTheme.muted, true).apply { setPadding(0, dp(26), 0, dp(10)) })
        rules.forEach { rule ->
            content.addView(UiTheme.row(this, "${rule.controlLabel(this)} → ${rule.actionLabel()}",
                if (rule.enabled) "已启用" else "已停用", UiIcons.scan) { edit(rule) })
            content.addView(UiTheme.divider(this))
        }
    }

    private fun edit(existing: AutoTriggerRule?, pickedPackage: String = "", pickedId: String = "", pickedText: String = "") {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pkg = UiTheme.field(this, "应用包名，例如 com.example.app", existing?.packageName ?: pickedPackage)
        val matchId = UiTheme.field(this, "触发控件 resource-id（必须填写）", existing?.matchResourceId ?: pickedId)
        val targetText = UiTheme.field(this, "要点击的文字（可留空则点触发控件）", existing?.targetText.orEmpty())
        val targetId = UiTheme.field(this, "要点击的 resource-id（可选）", existing?.targetResourceId.orEmpty())
        val taskGoal = UiTheme.field(this, "触发后执行的任务内容", existing?.taskGoal.orEmpty())
        val actions = listOf("执行任务", "点击", "关闭弹窗", "返回")
        var actionIndex = when (existing?.action) {
            "dismiss" -> 2; "back" -> 3; "task" -> 0
            else -> if (existing == null && pickedId.isNotBlank()) 0 else 1
        }
        fun showActionFields() {
            taskGoal.visibility = if (actionIndex == 0) View.VISIBLE else View.GONE
            targetText.visibility = if (actionIndex in 1..2) View.VISIBLE else View.GONE
            targetId.visibility = if (actionIndex in 1..2) View.VISIBLE else View.GONE
        }
        val action = UiTheme.selector(this, "动作", actions, actionIndex) { actionIndex = it; showActionFields() }
        val enabled = UiTheme.toggle(this, "启用规则", existing?.enabled ?: true)
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pkg); addView(matchId)
            visibility = if (existing == null && pickedId.isBlank()) View.VISIBLE else View.GONE
        }
        val selected = existing ?: pickedPackage.takeIf { it.isNotBlank() }?.let {
            AutoTriggerRule(packageName = it, matchResourceId = pickedId, matchText = pickedText)
        }
        selected?.let { box.addView(UiTheme.text(this, it.controlLabel(this), 16f, UiTheme.ink, true)) }
        box.addView(UiTheme.text(this, "仅支持带控件 ID 的控件；没有 ID 的控件不会触发。", 12f, UiTheme.muted))
        box.addView(UiTheme.command(this, "控件技术信息") { details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
        listOf(details, action, taskGoal, targetText, targetId, enabled).forEach { box.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
        showActionFields()
        val dialog = UiDialog.Builder(this).setTitle(if (existing == null) "创建自动触发" else "编辑自动触发")
            .setView(ScrollView(this).apply { addView(box) }).setNegativeButton("取消", null).setPositiveButton("保存", null)
        if (existing != null) dialog.setNeutralButton("删除") { _, _ -> runCatching { store.remove(existing.id); render() }.onFailure { toast(it) } }
        val shown = dialog.show()
        shown.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            runCatching {
                val resource = matchId.text.toString().trim()
                require(resource.isNotBlank()) { "触发控件没有 resource-id，无法创建规则" }
                val goal = taskGoal.text.toString().trim()
                val selectedAction = listOf("task", "click", "dismiss", "back")[actionIndex]
                require(selectedAction != "task" || goal.isNotBlank()) { "请填写触发后要执行的任务内容" }
                val packageName = pkg.text.toString().trim()
                val controlText = if (packageName == (existing?.packageName ?: pickedPackage) && resource == (existing?.matchResourceId ?: pickedId))
                    existing?.matchText ?: pickedText else ""
                val rule = AutoTriggerRule(id = existing?.id ?: java.util.UUID.randomUUID().toString(), packageName = packageName,
                    matchResourceId = resource, matchText = controlText, taskGoal = if (selectedAction == "task") goal else "",
                    targetResourceId = if (actionIndex in 1..2) targetId.text.toString().trim() else "",
                    targetText = if (actionIndex in 1..2) targetText.text.toString().trim() else "",
                    cooldownMs = existing?.cooldownMs ?: 3000L, burstLimit = existing?.burstLimit ?: 3,
                    burstWindowMs = existing?.burstWindowMs ?: 15000L,
                    action = selectedAction, enabled = enabled.isChecked)
                store.save(rule); shown.dismiss(); render()
            }.onFailure { toast(it) }
        }
    }
    private fun toast(error: Throwable) = Toast.makeText(this, error.message ?: "保存失败", Toast.LENGTH_LONG).show()
}
