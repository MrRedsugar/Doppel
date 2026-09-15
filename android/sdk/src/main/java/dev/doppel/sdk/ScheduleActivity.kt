package dev.doppel.sdk

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/** Calendar and recurrence editor using the SDK's themed surfaces and native time pickers. */
class ScheduleActivity : Activity() {
    companion object { @Volatile var isVisible = false; private set }
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var gateway: Gateway
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private var submitting = false
    private var viewRevision = 0L
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); UiTheme.init(this); gateway = Gateway(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        UiTheme.bind(root) { root.setBackgroundColor(UiTheme.background) }; UiTheme.window(this, root)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(16), dp(8)) }
        header.addView(UiTheme.icon(this, UiIcons.back, "返回") { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(UiTheme.text(this, "定时任务", 18f, UiTheme.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(UiTheme.icon(this, UiIcons.plus, "新建定时任务") { editor() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(header)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(32)) }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }
    override fun onResume() { super.onResume(); isVisible = true; reload() }
    override fun onPause() { isVisible = false; super.onPause() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 8342) reload()
    }
    override fun onDestroy() { io.shutdown(); super.onDestroy() }
    private fun dp(value: Int) = UiTheme.dp(this, value)
    private fun space(parent: LinearLayout, view: View, margin: Int = 12) {
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(margin) })
    }
    private fun execute(work: () -> Unit) {
        if (submitting) return
        submitting = true
        io.execute {
            val error = runCatching(work).exceptionOrNull()
            runOnUiThread {
                submitting = false
                if (!isFinishing && !isDestroyed) {
                    if (error == null) reload()
                    else status.text = error.message?.take(160) ?: "定时任务暂时不可用，请检查连接"
                }
            }
        }
    }
    private fun reload() {
        if (!::content.isInitialized) return
        val revision = ++viewRevision
        content.removeAllViews()
        space(content, UiTheme.text(this, "让事情按时发生", 26f, UiTheme.ink, true))
        space(content, UiTheme.text(this, "安排一次、每天，或按自己的节奏重复。已有任务优先，错过的任务不会密集补做。", 14f, UiTheme.muted), 22)
        space(content, AutomaticTaskConflict.notificationSettingsView(this))
        space(content, UiTheme.text(this, "请保留系统锁屏密码。正在使用手机时会提前 15 秒提醒，可执行、推迟或跳过。锁屏时默认等待你解锁，也可单独开启本机自动解锁。自动解锁后执行完会重新锁屏；期间长按接管或停止 3 秒，再输入系统密码。超过 5 分钟未就绪会跳过并记录原因。", 13f, UiTheme.muted))
        space(content, UiTheme.command(this, "自动解锁设置") { startActivity(android.content.Intent(this, AutomaticUnlockSettingsActivity::class.java)) })
        space(content, UiTheme.command(this, "打开系统显示设置") { startActivity(android.content.Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)) })
        status = UiTheme.text(this, "正在读取计划…", 13f, UiTheme.muted)
        space(content, status)
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(UiTheme.command(this, "新建计划", true) { editor() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        actions.addView(UiTheme.command(this, "开启执行会话") {
            try {
                check(FirstUseConsent.isAccepted(this)) { "请先完成使用同意" }
                check(gateway.prefs.getString("active_run", "").isNullOrBlank()) { "请先结束已有任务；定时任务不会抢占它" }
                check(TaskControl.startWorker(this)) { "会话已被其他操作暂停" }
                status.text = "执行会话已开启；锁屏时按自动解锁设置处理，未开启时等待你手动解锁"
            } catch (error: Exception) { status.text = error.message ?: "暂时无法开启执行会话" }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        space(content, actions, 22)
        io.execute {
            val result = runCatching { gateway.request("GET", "/schedules") }
            runOnUiThread {
                if (isFinishing || isDestroyed || viewRevision != revision) return@runOnUiThread
                result.fold({ response ->
                    val items = response.getJSONArray("items")
                    status.text = if (items.length() == 0) "还没有计划。新建后会显示下一次时间与执行记录。" else "${items.length()} 个计划 · 当前设备"
                    if (response.optJSONObject("background_wakeup")?.optString("status") == "waiting") {
                        status.text = "计划已保存，后台唤醒暂时不可用。无需重复新建；可开启执行会话，或重试后台唤醒。"
                        space(content, UiTheme.command(this@ScheduleActivity, "重试后台唤醒") {
                            execute { ScheduleManager.get(this@ScheduleActivity).arm() }
                        })
                    }
                    for (i in 0 until items.length()) card(items.getJSONObject(i))
                }, { status.text = it.message?.take(160) ?: "读取失败，请检查连接" })
            }
        }
    }
    private fun time(stamp: Long, zone: String) = if (stamp <= 0) "未安排" else
        runCatching { dateFormat.format(Instant.ofEpochMilli(stamp).atZone(ZoneId.of(zone))) }.getOrDefault("时间不可用")
    private fun card(job: JSONObject) {
        val id = job.getString("id")
        val rule = job.getJSONObject("rule")
        val zone = rule.optString("timezone", "UTC")
        val enabled = job.optBoolean("enabled")
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = UiTheme.glass(this@ScheduleActivity, 24); setPadding(dp(18), dp(18), dp(18), dp(16)) }
        space(card, UiTheme.text(this, job.getString("goal"), 17f, UiTheme.ink, true))
        val zoneName = TimeZone.getTimeZone(zone).getDisplayName(false, TimeZone.LONG, Locale.SIMPLIFIED_CHINESE)
        space(card, UiTheme.text(this, if (enabled) "下次 ${time(job.optLong("next_due_ms"), zone)}\n$zoneName" else "已停用 · 执行记录保留", 13f, UiTheme.muted))
        job.optString("waiting_reason").takeIf { it.isNotBlank() && it != "null" }?.let {
            space(card, UiTheme.text(this, reason(it), 13f, UiTheme.spectrumBlue))
        }
        val history = job.optJSONArray("history")
        if (enabled && !job.isNull("next_due_ms") && job.optLong("next_due_ms") <= System.currentTimeMillis()) {
            val choices = LinearLayout(this)
            for ((label, action) in listOf("执行" to "execute", "推迟 10 分钟" to "postpone", "跳过" to "skip")) choices.addView(UiTheme.command(this, label) {
                ScheduleManager.get(this).control(id, action)
                status.text = "正在处理选择…"
                content.postDelayed({ if (!isDestroyed && !isFinishing) reload() }, 500)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            space(card, choices)
        }
        if (history != null && history.length() > 0) {
            val last = history.getJSONObject(history.length() - 1)
            val label = when (last.optString("status")) { "started" -> "已创建任务"; "completed" -> "已完成"; "cancelled" -> "已取消"; "missed" -> "错过，已跳过"; "uncertain" -> "结果待核对"; "failed" -> "执行失败"; "skipped" -> "已跳过"; "unavailable" -> "任务记录不可用"; else -> "记录待更新" }
            space(card, UiTheme.text(this, "最近一次 · $label\n${time(last.optLong("at_ms"), zone)}", 12f, UiTheme.muted))
            last.optString("reason").takeIf { it.isNotBlank() }?.let { space(card, UiTheme.text(this, reason(it), 12f, UiTheme.muted)) }
            last.optString("run_id").takeIf { it.isNotBlank() && it != "null" }?.let { runId ->
                space(card, UiTheme.text(this, "任务 $runId", 11f, UiTheme.muted).apply { setTextIsSelectable(true) })
            }
        }
        val row = LinearLayout(this)
        row.addView(UiTheme.command(this, if (enabled) "停用" else "启用") {
            execute { gateway.request("PATCH", "/schedules/$id", JSONObject().put("enabled", !enabled)) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(UiTheme.icon(this, UiIcons.edit, "编辑计划") { editor(job) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        row.addView(UiTheme.icon(this, UiIcons.delete, "删除计划") {
            UiDialog.Builder(this).setTitle("删除这个计划？").setMessage("后续不再执行。已经创建的任务会保留，可在任务记录中单独取消。")
                .setNegativeButton("保留", null).setPositiveButton("删除") { _, _ -> execute { gateway.request("DELETE", "/schedules/$id") } }.show()
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        card.addView(row); space(content, card, 14)
    }
    private fun editor(existing: JSONObject? = null) {
        viewRevision++
        content.removeAllViews()
        space(content, UiTheme.text(this, if (existing == null) "安排一件事" else "编辑计划", 26f, UiTheme.ink, true), 22)
        status = UiTheme.text(this, "计划保存后会检查设备是否就绪。支付和其他确认沿用每次任务的权限控制。", 13f, UiTheme.muted)
        space(content, status)
        val goal = UiTheme.field(this, "希望替你完成什么？", existing?.optString("goal").orEmpty()).apply { minLines = 2; maxLines = 5 }
        space(content, goal)
        val form = ScheduleEditorForm(existing, System.currentTimeMillis(), ZoneId.systemDefault())
        val originalValues = form.values
        val recurrence = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun showRecurrence() {
            recurrence.removeAllViews()
            val values = form.values
            val kind = values.frequency
            if (kind == ScheduleEditorForm.Frequency.LEGACY) {
                space(recurrence, UiTheme.text(this, "旧版自定义周期 · 保留原来的重复安排\n下次 ${time(existing?.optLong("next_due_ms") ?: 0, values.zone.id)}\n可以直接修改任务内容。要修改执行时间，请先选择新的执行频率。", 13f, UiTheme.muted))
                return
            }
            if (kind == ScheduleEditorForm.Frequency.ONCE) {
                val date = values.localTime.toLocalDate()
                space(recurrence, UiTheme.command(this, "日期 · ${date.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))}") {
                    DatePickerDialog(this, { _, year, month, day ->
                        form.values = form.values.copy(localTime = form.values.localTime.withDayOfMonth(1).withYear(year).withMonth(month + 1).withDayOfMonth(day))
                        showRecurrence()
                    }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                })
            }
            if (kind != ScheduleEditorForm.Frequency.INTERVAL) {
                val local = values.localTime
                space(recurrence, UiTheme.command(this, "时间 · ${local.format(DateTimeFormatter.ofPattern("HH:mm"))}") {
                    TimePickerDialog(this, { _, hour, minute ->
                        form.values = form.values.copy(localTime = form.values.localTime.withHour(hour).withMinute(minute))
                        showRecurrence()
                    }, local.hour, local.minute, true).show()
                })
            }
            if (kind == ScheduleEditorForm.Frequency.WEEKLY) {
                space(recurrence, UiTheme.text(this, "选择每周的执行日期", 13f, UiTheme.muted), 4)
                val labels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
                for (days in (1..7).toList().chunked(4)) {
                    val row = LinearLayout(this)
                    for (day in days) row.addView(UiTheme.check(this, labels[day - 1], day in values.weekdays).apply {
                        minWidth = 0
                        setOnCheckedChangeListener { _, checked ->
                            form.values = form.values.copy(weekdays = if (checked) form.values.weekdays + day else form.values.weekdays - day)
                        }
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    space(recurrence, row, 4)
                }
            }
            if (kind == ScheduleEditorForm.Frequency.INTERVAL) {
                val amount = UiTheme.field(this, "每隔多少时间", values.intervalAmount.toString()).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                            form.values = form.values.copy(intervalAmount = text?.toString()?.toLongOrNull() ?: 0)
                        }
                        override fun afterTextChanged(text: Editable?) {}
                    })
                }
                space(recurrence, amount)
                val units = ScheduleEditorForm.IntervalUnit.entries
                space(recurrence, UiTheme.selector(this, "间隔单位", units.map { it.label }, units.indexOf(values.intervalUnit)) { selected ->
                    form.values = form.values.copy(intervalUnit = units[selected])
                })
                space(recurrence, UiTheme.text(this, "保存新的间隔后开始计时；只修改任务内容不会重新计时。最长 365 天。", 12f, UiTheme.muted))
            }
        }
        val frequencies = mutableListOf("仅一次", "每天", "每周", "固定间隔")
        if (form.values.frequency == ScheduleEditorForm.Frequency.LEGACY) frequencies += "保留旧版自定义周期"
        space(content, UiTheme.selector(this, "执行频率", frequencies, form.values.frequency.ordinal) { selected ->
            val frequency = ScheduleEditorForm.Frequency.entries[selected]
            form.values = if (frequency == ScheduleEditorForm.Frequency.LEGACY) originalValues else form.values.copy(frequency = frequency)
            showRecurrence()
        })
        showRecurrence(); space(content, recurrence)
        val zoneName = TimeZone.getTimeZone(form.values.zone).getDisplayName(false, TimeZone.LONG, Locale.SIMPLIFIED_CHINESE)
        space(content, UiTheme.text(this, "执行时间按$zoneName。${if (existing == null) "使用当前设备时区。" else "保留这个计划原来的时区。"}", 12f, UiTheme.muted))
        var mode = listOf("ask", "assist", "full").indexOf(existing?.optString("mode") ?: "ask").coerceAtLeast(0)
        space(content, UiTheme.selector(this, "任务权限", listOf("请求批准", "帮我批准", "完全访问"), mode) { mode = it })
        space(content, UiTheme.command(this, "保存计划", true) {
            try {
                val body = form.saveFields(goal.text.toString(), listOf("ask", "assist", "full")[mode], System.currentTimeMillis())
                    .put("device_id", gateway.prefs.getString("device_id", ""))
                existing?.optJSONArray("allowed_packages")?.let { body.put("allowed_packages", it) }
                execute { gateway.request(if (existing == null) "POST" else "PATCH", "/schedules" + (existing?.let { "/${it.getString("id")}" } ?: ""), body) }
            } catch (error: Exception) { status.text = error.message?.take(160) ?: "请检查时间与计划内容" }
        })
        space(content, UiTheme.command(this, "返回计划列表") { reload() })
    }
    private fun reason(value: String) = when (value) {
        "run_missing" -> "原任务记录不存在或不可访问，执行结果未知"
        "device_busy" -> "等待当前任务结束，暂停任务也会保留"
        "device_locked" -> "等待解锁并亮屏"
        "device_unlocking" -> "正在本机解锁，成功后开始任务"
        "user_active" -> "你正在使用手机，正在显示 15 秒执行预告，可推迟或跳过"
        "schedule_countdown" -> "正在显示 15 秒执行预告，可执行、推迟或跳过"
        "user_postponed" -> "已按你的选择推迟"
        "user_skipped" -> "你跳过了这一次"
        "host_unavailable_at_due_time" -> "到时间时未能启动检查，已跳过"
        "device_offline" -> "等待设备连接"
        "accessibility_unavailable" -> "等待无障碍权限"
        "session_unavailable" -> "请开启执行会话，系统后台限制可能延迟执行"
        "consent_required" -> "请先完成使用同意"
        "connection_changed" -> "连接已改变，请检查计划绑定的设备"
        "unsupported_direct_scope" -> "本机直连不支持该计划的应用范围或目标长度，请编辑计划或改用网关"
        "review_uncertain_dispatch" -> "上次创建结果不明确，已停用以避免重复执行"
        else -> "等待设备就绪"
    }
}
