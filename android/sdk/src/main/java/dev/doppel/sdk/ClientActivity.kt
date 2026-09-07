package dev.doppel.sdk

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.View
import android.view.Gravity
import android.content.res.ColorStateList
import android.widget.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

open class ClientActivity : Activity() {
    protected lateinit var gateway: Gateway
    protected lateinit var page: LinearLayout
    protected lateinit var status: TextView
    protected open val clientTitle = "Doppel 开发者"
    protected open val additionalSections: List<String> = emptyList()
    protected open val showTokenSetting = true
    private lateinit var container: LinearLayout
    private var section = "任务"
    private var goal: EditText? = null
    private var runView: TextView? = null
    private var runControls: LinearLayout? = null
    private var runTitle: TextView? = null
    private var runGoal: TextView? = null
    private var runProgress: ProgressBar? = null
    private var taskActions: LinearLayout? = null
    private var sendButton: ImageButton? = null
    private var creating = false
    private var latestRun: JSONObject? = null
    private val navigation = mutableMapOf<String, LinearLayout>()
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var visible = false
    private var polling = false
    private var lastPending = ""
    private val poll = object : Runnable { override fun run() { if (visible) { refreshRun(); handler.postDelayed(this, 2500) } } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); gateway = Gateway(this)
        section = savedInstanceState?.getString("section") ?: "任务"
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        UiTheme.window(this, container)
        setContentView(container)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(14), dp(20), dp(14)) }
        header.addView(UiTheme.text(this, "D", 22f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = UiTheme.surface(this@ClientActivity, UiTheme.green) }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
        val identity = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        identity.addView(UiTheme.text(this, clientTitle, 19f, UiTheme.ink, true))
        status = UiTheme.text(this, connectionSummary(), 11f, UiTheme.muted).apply { setPadding(0, dp(4), 0, 0); maxLines = 2 }; identity.addView(status)
        header.addView(identity, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(UiTheme.icon(this, android.R.drawable.ic_btn_speak_now, "语音输入") { startActivity(Intent(this, VoiceActivity::class.java)) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        container.addView(header); container.addView(UiTheme.divider(this))
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(24)) }
        container.addView(ScrollView(this).apply { isFillViewport = true; clipToPadding = false; setBackgroundColor(UiTheme.background); addView(page) }, LinearLayout.LayoutParams(-1, 0, 1f))
        container.addView(UiTheme.divider(this))
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(6)) }
        for (name in listOf("任务", "记录", "文件", "设置") + additionalSections) {
            val icon = when(name) { "任务" -> android.R.drawable.ic_menu_edit; "记录" -> android.R.drawable.ic_menu_recent_history; "文件" -> android.R.drawable.ic_menu_agenda; "设置" -> android.R.drawable.ic_menu_manage; else -> android.R.drawable.ic_menu_myplaces }
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; contentDescription = name; tooltipText = name; isFocusable = true
                addView(ImageView(this@ClientActivity).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(23), dp(23)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                addView(UiTheme.text(this@ClientActivity, name, 11f).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
                setOnClickListener { selectSection(name) }
            }
            navigation[name] = tab; tabs.addView(tab, LinearLayout.LayoutParams(0, dp(54), 1f))
        }
        container.addView(tabs)
        render()
    }
    protected fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    protected fun label(text: String, size: Float = 15f): TextView = UiTheme.text(this, text, size, UiTheme.ink, size >= 18f).apply { setPadding(0, dp(8), 0, dp(12)); page.addView(this) }
    protected fun input(hint: String, initial: String = "", secret: Boolean = false): EditText = UiTheme.field(this, hint, initial, secret).apply { page.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }
    protected fun button(text: String, action: () -> Unit): Button = UiTheme.command(this, text, action = action).apply { page.addView(this, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6); bottomMargin = dp(6) }) }
    protected fun sectionLabel(text: String) { page.addView(UiTheme.text(this, text, 12f, UiTheme.muted, true).apply { setPadding(0, dp(22), 0, dp(6)) }) }
    protected fun settingsRow(title: String, detail: String, icon: Int = android.R.drawable.ic_menu_manage, action: () -> Unit) { page.addView(UiTheme.row(this, title, detail, icon, action)); page.addView(UiTheme.divider(this)) }
    protected fun selectSection(name: String) { goal?.let { gateway.prefs.edit().putString("draft_goal", it.text.toString()).apply() }; section = name; render() }
    protected fun connectionSummary(): String = when {
        gateway.prefs.getString("token", "").isNullOrBlank() -> "尚未连接"
        gateway.prefs.getString("device_id", "").isNullOrBlank() -> "等待绑定设备"
        DeviceWorkerService.instance == null -> "设备已绑定 · 服务未启动"
        else -> DeviceWorkerService.state
    }
    protected fun pointsPanel(developer: Boolean = false) {
        val amount = label("正在读取额度", 24f).apply { setTextColor(UiTheme.green) }
        val summary = UiTheme.text(this, "", 13f, UiTheme.muted).also { page.addView(it) }
        val numbers = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(22), 0, dp(20)) }
        val values = listOf("已用积分", "输入 Token", "输出 Token").map { title ->
            val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val value = UiTheme.text(this, "-", 18f, UiTheme.ink, true).apply { maxLines = 1; setAutoSizeTextTypeUniformWithConfiguration(10, 18, 1, android.util.TypedValue.COMPLEX_UNIT_SP) }; group.addView(value, LinearLayout.LayoutParams(-1, dp(26)))
            group.addView(UiTheme.text(this, title, 11f, UiTheme.muted).apply { setPadding(0, dp(7), 0, 0) })
            numbers.addView(group, LinearLayout.LayoutParams(0, -2, 1f)); value
        }
        page.addView(numbers); page.addView(UiTheme.divider(this))
        async({
            try { gateway.request("GET", "/points") }
            catch (error: Exception) {
                runOnUiThread { if (!isDestroyed) { amount.text = "额度暂不可用"; amount.setTextColor(UiTheme.muted) } }
                throw error
            }
        }) { points ->
            amount.text = if (points.optBoolean("unlimited")) "无限测试额度" else "${points.optLong("remaining")} 积分可用"
            summary.text = when {
                developer -> "保留记录累计 · 积分为换算值"
                points.optBoolean("unlimited") -> "今日实际用量 · 已购 ${points.optInt("purchased")}"
                else -> "今日额度 ${points.optInt("daily_limit")} · 已购 ${points.optInt("purchased")}"
            }
            fun number(key: String) = if (points.has(key) && !points.isNull(key)) java.text.NumberFormat.getIntegerInstance().format(points.getLong(key)) else "-"
            values[0].text = number("used_points"); values[1].text = number("input_tokens"); values[2].text = number("output_tokens")
        }
    }
    protected fun async(work: () -> JSONObject, done: (JSONObject) -> Unit = {}) {
        status.text = "正在连接服务"
        io.execute {
            try { val value = work(); runOnUiThread { if (!isDestroyed) { status.text = connectionSummary(); done(value) } } }
            catch (e: Exception) { runOnUiThread { if (!isDestroyed) status.text = if (e is IllegalStateException || e is IllegalArgumentException) e.message ?: "请求失败" else "无法连接服务，请检查地址和网络" } }
        }
    }
    protected open fun customPage(section: String) {}
    private fun render() {
        page.removeAllViews(); goal = null; runView = null; runControls = null; runTitle = null; runGoal = null; runProgress = null; taskActions = null; sendButton = null; lastPending = ""
        navigation.forEach { (name, tab) ->
            val selected = name == section; tab.isSelected = selected; tab.background = UiTheme.surface(this, if (selected) UiTheme.pale else Color.TRANSPARENT)
            (tab.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(if (selected) UiTheme.green else UiTheme.muted)
            (tab.getChildAt(1) as TextView).setTextColor(if (selected) UiTheme.green else UiTheme.muted)
        }
        when(section) { "任务" -> tasks(); "设置" -> settings(); "记录" -> history(); "文件" -> documents(); else -> customPage(section) }
    }
    private fun tasks() {
        label("任务", 24f)
        val composer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = UiTheme.surface(this@ClientActivity, Color.WHITE, true); setPadding(dp(14), dp(14), dp(10), dp(10)) }
        goal = UiTheme.field(this, "输入这次要完成的任务", gateway.prefs.getString("draft_goal", "").orEmpty()).apply { minLines = 4; maxLines = 8; gravity = Gravity.TOP; background = null; setPadding(dp(2), dp(2), dp(2), dp(12)) }
        composer.addView(goal, LinearLayout.LayoutParams(-1, -2))
        val composerTools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        composerTools.addView(UiTheme.icon(this, android.R.drawable.ic_btn_speak_now, "语音输入") { startActivity(Intent(this, VoiceActivity::class.java)) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        composerTools.addView(UiTheme.text(this, "新任务", 12f, UiTheme.muted), LinearLayout.LayoutParams(0, -2, 1f))
        sendButton = UiTheme.icon(this, android.R.drawable.ic_menu_send, "开始任务", true) {
            val value = goal?.text.toString().trim()
            val device = gateway.prefs.getString("device_id", "").orEmpty()
            if (value.isBlank()) { goal?.error = "请输入任务"; return@icon }
            if (device.isBlank()) { status.text = "请先连接并绑定设备"; selectSection("设置"); return@icon }
            if (DoppelAccessibilityService.instance == null) { status.text = "请先启用无障碍服务"; selectSection("设置"); return@icon }
            if (creating) return@icon
            creating = true; sendButton?.isEnabled = false
            val selected = gateway.prefs.getInt("mode_index", 1).coerceIn(0, 2)
            gateway.prefs.edit().putString("draft_goal", value).apply()
            async({ try { gateway.request("POST", "/runs", JSONObject().put("device_id", device).put("goal", value).put("mode", listOf("ask", "assist", "full")[selected])) } finally { runOnUiThread { creating = false; sendButton?.isEnabled = true } } }) {
                gateway.prefs.edit().putString("active_run", it.getString("id")).putString("draft_goal", "").apply(); goal?.setText(""); startForegroundService(Intent(this, DeviceWorkerService::class.java)); displayRun(it)
            }
        }
        composerTools.addView(sendButton, LinearLayout.LayoutParams(dp(44), dp(44))); composer.addView(composerTools); page.addView(composer)
        sectionLabel("执行模式")
        val modes = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL; background = UiTheme.surface(this@ClientActivity, UiTheme.line); setPadding(dp(3), dp(3), dp(3), dp(3)) }
        listOf("请求批准", "帮我批准", "完全访问").forEachIndexed { index, name ->
            modes.addView(RadioButton(this).apply {
                id = View.generateViewId(); text = name; textSize = 12f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 0); buttonDrawable = null; isChecked = gateway.prefs.getInt("mode_index", 1) == index
                fun style() { background = UiTheme.surface(this@ClientActivity, if (isChecked) Color.WHITE else Color.TRANSPARENT); setTextColor(if (isChecked) UiTheme.green else UiTheme.muted) }
                style(); setOnCheckedChangeListener { _, checked -> style(); if (checked) gateway.prefs.edit().putInt("mode_index", index).apply() }
            }, LinearLayout.LayoutParams(0, dp(38), 1f))
        }; page.addView(modes)
        sectionLabel("当前进度")
        runTitle = UiTheme.text(this, "暂无进行中的任务", 18f, UiTheme.ink, true).also { page.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }) }
        runGoal = UiTheme.text(this, "", 14f, UiTheme.muted).also { page.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
        runProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(UiTheme.green); visibility = View.GONE }.also { page.addView(it, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(14); bottomMargin = dp(10) }) }
        runView = UiTheme.text(this, connectionSummary(), 14f, UiTheme.muted).also { page.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }) }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; visibility = View.GONE }; page.addView(actions, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) }); taskActions = actions
        for ((text, operation, icon) in listOf(Triple("暂停", "pause", android.R.drawable.ic_media_pause), Triple("继续", "resume", android.R.drawable.ic_media_play), Triple("取消", "cancel", android.R.drawable.ic_menu_close_clear_cancel))) actions.addView(UiTheme.icon(this, icon, text) {
            val run = gateway.prefs.getString("active_run", "").orEmpty(); if (run.isBlank()) return@icon
            if (operation != "resume") DeviceWorkerService.instance?.cancel()
            async({ gateway.request("POST", "/runs/$run/$operation", JSONObject()) }) { displayRun(it); if (operation == "resume") startForegroundService(Intent(this@ClientActivity, DeviceWorkerService::class.java)) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        runControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; page.addView(runControls)
        latestRun?.let { displayRun(it) }
        refreshRun()
    }
    private fun refreshRun() {
        if (section != "任务" || polling) return
        val id = gateway.prefs.getString("active_run", "").orEmpty(); if (id.isEmpty()) return
        polling = true
        io.execute {
            try { val run = gateway.request("GET", "/runs/$id"); runOnUiThread { if (!isDestroyed && section == "任务") displayRun(run) } }
            catch (_: Exception) { runOnUiThread { if (!isDestroyed) status.text = "任务状态暂不可用" } }
            finally { polling = false }
        }
    }
    private fun displayRun(run: JSONObject) {
        latestRun = run
        val state = run.optString("status")
        val translated = mapOf("queued" to "排队中", "running" to "执行中", "paused" to "已暂停", "awaiting_approval" to "等待批准", "awaiting_input" to "需要补充信息", "completed" to "已完成", "failed" to "失败", "cancelled" to "已取消")
        runTitle?.text = translated[state] ?: state
        runTitle?.setTextColor(when (state) { "failed" -> UiTheme.danger; "running", "completed" -> UiTheme.green; else -> UiTheme.ink })
        runGoal?.text = run.optString("goal")
        runView?.text = run.optString("message").ifBlank { if (state == "running") "正在处理任务" else "" }; status.text = connectionSummary()
        runProgress?.visibility = if (state in setOf("running", "queued")) View.VISIBLE else View.GONE
        taskActions?.visibility = if (state in setOf("running", "queued", "paused", "awaiting_input", "awaiting_approval")) View.VISIBLE else View.GONE
        taskActions?.let { actions -> actions.getChildAt(0).isEnabled = state == "running"; actions.getChildAt(1).isEnabled = state == "paused" }
        if (state == "paused") DeviceWorkerService.instance?.cancel()
        if (state in setOf("cancelled", "completed", "failed")) DoppelAccessibilityService.instance?.setTouchGuard(false)
        val pending = run.optJSONObject("pending_request")
        if (pending == null) { runControls?.removeAllViews(); lastPending = ""; return }
        if (pending.optString("id") == lastPending) return
        lastPending = pending.optString("id"); runControls?.removeAllViews()
        val controls = runControls ?: return
        controls.addView(UiTheme.text(this, pending.optString("message"), 15f).apply { setPadding(0, dp(16), 0, dp(12)) })
        if (state == "paused" && pending.optBoolean("manual_only")) return
        val answer = UiTheme.field(this, "补充信息")
        if (pending.optString("kind") == "input") controls.addView(answer)
        val choices = if (pending.optString("kind") == "approval") listOf("批准", "拒绝") else listOf("提交")
        for (choice in choices) controls.addView(UiTheme.command(this, choice, choice != "拒绝") {
            val body = JSONObject().put("request_id", pending.getString("id"))
            if (choice == "提交") body.put("text", answer.text.toString()) else body.put("approve", choice == "批准")
            async({ gateway.request("POST", "/runs/${run.getString("id")}/answer", body) }) { displayRun(it); if (it.optString("status") == "running") DeviceWorkerService.instance?.resume() }
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
    }
    private fun settings() {
        label("设置", 24f)
        sectionLabel("设备连接")
        settingsRow("网关连接", gateway.prefs.getString("base_url", "http://10.0.2.2:8765").orEmpty(), android.R.drawable.ic_menu_share) { connectionDialog() }
        settingsRow("绑定本机", if (gateway.prefs.getString("device_id", "").isNullOrBlank()) "尚未绑定" else "当前设备已绑定", android.R.drawable.ic_menu_mylocation) {
            var installation = gateway.prefs.getString("installation_id", null)
            if (installation == null) { installation = UUID.randomUUID().toString(); gateway.prefs.edit().putString("installation_id", installation).commit() }
            val identity = installation
            async({ gateway.request("POST", "/devices", JSONObject().put("installation_id", identity).put("name", "${Build.MANUFACTURER} ${Build.MODEL}")) }) { gateway.prefs.edit().putString("device_id", it.getString("id")).apply(); status.text = "本机已绑定"; if (section == "设置") render() }
        }
        val worker = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, dp(4)) }
        worker.addView(UiTheme.command(this, "启动悬浮服务", true) { startForegroundService(Intent(this, DeviceWorkerService::class.java)); status.text = "服务已启动" }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        worker.addView(UiTheme.command(this, "停止悬浮服务") { stopService(Intent(this, DeviceWorkerService::class.java)); status.text = "服务已停止" }, LinearLayout.LayoutParams(0, dp(44), 1f)); page.addView(worker)
        sectionLabel("系统权限")
        settingsRow("无障碍服务", if (DoppelAccessibilityService.instance == null) "未启用" else "已启用", android.R.drawable.ic_menu_view) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        settingsRow("悬浮窗", if (Settings.canDrawOverlays(this)) "已授权" else "未授权", android.R.drawable.ic_menu_crop) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        val microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val notifications = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        settingsRow("通知与麦克风权限", "通知${if (notifications) "已授权" else "未授权"} · 麦克风${if (microphone) "已授权" else "未授权"}", android.R.drawable.ic_btn_speak_now) { requestPermissions((listOf(Manifest.permission.RECORD_AUDIO) + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()).toTypedArray(), 9) }
        sectionLabel("执行偏好")
        settingsSwitch("触屏暂停", "touch_pause") { checked -> if (!checked) DoppelAccessibilityService.instance?.setTouchGuard(false) }
        settingsSwitch("动作反馈", "action_feedback") {}
        settingsRow("语音识别与本地模型", if (SpeechModels.installed(this)) "中文模型已就绪" else "中文模型未安装", android.R.drawable.ic_btn_speak_now) { startActivity(Intent(this, SpeechSettingsActivity::class.java)) }
        settingsRow("登录设置", "登录资料与验证码", android.R.drawable.ic_lock_lock) { startActivity(Intent(this, LoginSettingsActivity::class.java)) }
        sectionLabel("扩展与数据")
        settingsRow("扩展服务", "连接与工具权限", android.R.drawable.ic_menu_add) { extensions() }
        settingsRow("数据保存设置", "任务截图保留时间", android.R.drawable.ic_menu_recent_history) {
            async({ gateway.request("GET", "/data-retention") }) { config ->
                val days = listOf(1, 7, 30, 90, 0)
                AlertDialog.Builder(this).setTitle("截图保存时间").setSingleChoiceItems(arrayOf("1 天", "7 天", "30 天", "90 天", "长期保存"), days.indexOf(config.optInt("days", 7))) { dialog, which ->
                    async({ gateway.request("PATCH", "/data-retention", JSONObject().put("days", days[which])) }) { status.text = "保存时间已更新" }; dialog.dismiss()
                }.setNegativeButton("关闭", null).show()
            }
        }
        settingsRow("清理本机文档副本", "清理已下载的本地副本", android.R.drawable.ic_menu_delete) { async({ gateway.clearDocumentCache(); JSONObject() }) { status.text = "本机文档副本已清理" } }
    }
    private fun settingsSwitch(title: String, key: String, changed: (Boolean) -> Unit) {
        page.addView(Switch(this).apply {
            text = title; textSize = 15f; setTextColor(UiTheme.ink); minHeight = dp(54); thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(UiTheme.green, UiTheme.muted))
            isChecked = gateway.prefs.getBoolean(key, true); setOnCheckedChangeListener { _, checked -> gateway.prefs.edit().putBoolean(key, checked).apply(); changed(checked) }
        }); page.addView(UiTheme.divider(this))
    }
    private fun connectionDialog() {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(8)) }
        val base = UiTheme.field(this, "服务地址", gateway.prefs.getString("base_url", "http://10.0.2.2:8765").orEmpty()).apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI; setSingleLine() }; fields.addView(base)
        val token = if (showTokenSetting) UiTheme.field(this, "网关令牌", gateway.prefs.getString("token", "").orEmpty(), true).also { fields.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }) } else null
        val dialog = AlertDialog.Builder(this).setTitle("网关连接").setView(fields).setNegativeButton("取消", null).setPositiveButton("保存连接", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = base.text.toString().trim().trimEnd('/')
            val uri = try { java.net.URI(value) } catch (_: Exception) { null }
            if (uri == null || uri.scheme !in setOf("http", "https") || uri.host == null || uri.userInfo != null) { base.error = "请输入有效的服务地址"; return@setOnClickListener }
            DeviceWorkerService.instance?.cancel()
            val changed = gateway.prefs.getString("base_url", "http://10.0.2.2:8765") != value
            val editor = gateway.prefs.edit().putString("base_url", value)
            if (changed) { editor.remove("device_id").remove("active_run").remove("token"); latestRun = null }
            if (token != null) editor.putString("token", token.text.toString())
            editor.apply(); status.text = "连接已保存"; dialog.dismiss(); if (section == "设置") render()
        } }; dialog.show()
    }
    private fun extensions() {
        async({ gateway.request("GET", "/extensions") }) { response ->
            val items = response.optJSONArray("items") ?: org.json.JSONArray()
            val names = Array(items.length()) { items.getJSONObject(it).optString("name") }
            AlertDialog.Builder(this).setTitle("扩展服务").setItems(names) { _, selected ->
                val config = items.getJSONObject(selected)
                AlertDialog.Builder(this).setTitle(config.getString("name")).setItems(arrayOf("工具权限", "删除服务")) { _, action ->
                    if (action == 0) extensionTools(config)
                    else AlertDialog.Builder(this).setTitle("删除扩展服务？").setNegativeButton("返回", null).setPositiveButton("删除") { _, _ -> async({ gateway.request("DELETE", "/extensions/" + config.getString("name")) }) { status.text = "扩展已删除" } }.show()
                }.setNegativeButton("关闭", null).show()
            }.setPositiveButton("添加服务") { _, _ ->
                val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
                val name = EditText(this).apply { hint = "服务名称" }; fields.addView(name)
                val url = EditText(this).apply { hint = "服务地址 https://"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }; fields.addView(url)
                AlertDialog.Builder(this).setTitle("添加扩展服务").setView(fields).setNegativeButton("取消", null).setPositiveButton("连接") { _, _ ->
                    val config = JSONObject().put("name", name.text.toString().trim()).put("url", url.text.toString().trim()).put("allowed_tools", org.json.JSONArray()).put("read_only_tools", org.json.JSONArray())
                    async({ gateway.request("POST", "/extensions", config) }) { extensionTools(config) }
                }.show()
            }.setNegativeButton("关闭", null).show()
        }
    }
    private fun extensionTools(config: JSONObject) {
        val name = config.getString("name")
        async({ gateway.request("GET", "/extensions/$name/tools") }) { response ->
            val tools = response.optJSONArray("items") ?: response.optJSONArray("tools") ?: org.json.JSONArray()
            val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
            val enabled = mutableMapOf<String, CheckBox>(); val readOnly = mutableMapOf<String, Switch>()
            val allowed = config.optJSONArray("allowed_tools") ?: org.json.JSONArray()
            val safe = config.optJSONArray("read_only_tools") ?: org.json.JSONArray()
            for (i in 0 until tools.length()) {
                val tool = tools.getJSONObject(i); val toolName = tool.getString("name")
                enabled[toolName] = CheckBox(this).apply { text = toolName; isChecked = (0 until allowed.length()).any { allowed.optString(it) == toolName }; content.addView(this) }
                content.addView(TextView(this).apply { text = tool.optString("description").take(250); textSize = 14f })
                readOnly[toolName] = Switch(this).apply { text = "只读授权"; isChecked = (0 until safe.length()).any { safe.optString(it) == toolName }; content.addView(this) }
            }
            AlertDialog.Builder(this).setTitle("工具权限 · $name").setView(ScrollView(this).apply { addView(content) }).setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
                val selected = enabled.filterValues { it.isChecked }.keys
                val body = JSONObject(config.toString()).put("allowed_tools", org.json.JSONArray(selected.toList())).put("read_only_tools", org.json.JSONArray(selected.filter { readOnly[it]?.isChecked == true }))
                async({ gateway.request("PUT", "/extensions/$name", body) }) { status.text = "工具权限已保存" }
            }.show()
        }
    }
    private fun documents() {
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(UiTheme.text(this, "文件", 24f, UiTheme.ink, true), LinearLayout.LayoutParams(0, dp(48), 1f))
        toolbar.addView(UiTheme.icon(this, android.R.drawable.ic_popup_sync, "刷新文件") { page.removeAllViews(); documents() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        toolbar.addView(UiTheme.icon(this, android.R.drawable.ic_menu_add, "导入表格", true) { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 40) }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
        page.addView(toolbar); sectionLabel("工作文件")
        async({ gateway.request("GET", "/documents") }) { response ->
            if (section != "文件") return@async
            val items = response.optJSONArray("items") ?: return@async
            if (items.length() == 0) emptyState("暂无文件", android.R.drawable.ic_menu_agenda)
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                settingsRow(item.optString("name"), "XLSX · ${item.optLong("size") / 1024} KB", android.R.drawable.ic_menu_agenda) {
                    AlertDialog.Builder(this).setTitle(item.optString("name")).setItems(arrayOf("打开副本", "删除文件")) { _, choice ->
                        if (choice == 0) async({ gateway.openDocument(item.getString("download_uri")); JSONObject() }) { status.text = "已打开文件副本" }
                        else AlertDialog.Builder(this).setTitle("删除文件？").setMessage(item.optString("name")).setNegativeButton("返回", null).setPositiveButton("删除") { _, _ ->
                            async({ val deleted = gateway.request("DELETE", "/documents/" + java.net.URLEncoder.encode(item.getString("name"), "UTF-8").replace("+", "%20")); gateway.clearDocumentCache(item.getString("name")); deleted }) { page.removeAllViews(); documents() }
                        }.show()
                    }.setNegativeButton("关闭", null).show()
                }
            }
        }
    }
    @Deprecated("Platform callback") override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 40 && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            var name = uri.lastPathSegment ?: "workbook.xlsx"
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) }
            val filename = name
            async({ gateway.uploadDocument(uri, filename) }) { status.text = "文件副本已导入"; if (section == "文件") { page.removeAllViews(); documents() } }
        }
    }
    private fun history() {
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(UiTheme.text(this, "记录", 24f, UiTheme.ink, true), LinearLayout.LayoutParams(0, dp(48), 1f))
        toolbar.addView(UiTheme.icon(this, android.R.drawable.ic_popup_sync, "刷新记录") { page.removeAllViews(); history() }, LinearLayout.LayoutParams(dp(44), dp(44))); page.addView(toolbar)
        sectionLabel("最近任务")
        async({ gateway.request("GET", "/runs") }) { response ->
            if (section != "记录") return@async
            val items = response.optJSONArray("items") ?: return@async
            if (items.length() == 0) emptyState("暂无任务记录", android.R.drawable.ic_menu_recent_history)
            val states = mapOf("queued" to "排队中", "running" to "执行中", "paused" to "已暂停", "awaiting_approval" to "等待批准", "awaiting_input" to "等待补充", "completed" to "已完成", "failed" to "未完成", "cancelled" to "已取消")
            for (i in 0 until items.length()) { val item = items.getJSONObject(i); settingsRow(item.optString("goal"), states[item.optString("status")] ?: item.optString("status"), android.R.drawable.ic_menu_recent_history) { runHistoryMenu(item) } }
        }
    }
    private fun emptyState(title: String, icon: Int) {
        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(20), dp(56), dp(20), dp(56))
            addView(ImageView(this@ClientActivity).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(UiTheme.muted) }, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(UiTheme.text(this@ClientActivity, title, 14f, UiTheme.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0) })
        }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun runHistoryMenu(run: JSONObject) {
        val id = run.getString("id")
        AlertDialog.Builder(this).setTitle(run.optString("goal")).setItems(arrayOf("任务过程", "截图", "删除任务")) { _, choice ->
            when(choice) {
                0 -> async({ gateway.request("GET", "/runs/$id/events?after=0") }) { events ->
                    val list = events.optJSONArray("items")
                    val text = if (list == null) "暂无事件" else (0 until list.length()).joinToString("\n\n") { list.getJSONObject(it).optString("message") }
                    AlertDialog.Builder(this).setTitle("任务过程").setMessage(text).setPositiveButton("关闭", null).show()
                }
                1 -> screenshotList(id)
                2 -> AlertDialog.Builder(this).setTitle("删除任务和截图？").setMessage(run.optString("goal")).setNegativeButton("返回", null).setPositiveButton("删除") { _, _ ->
                    async({ val result = gateway.request("DELETE", "/runs/$id"); ResultStore(this).use { it.erase(id) }; gateway.clearDocumentCache(); result }) {
                        if (gateway.prefs.getString("active_run", "") == id) gateway.prefs.edit().remove("active_run").apply()
                        status.text = "任务已删除"; if (section == "记录") { page.removeAllViews(); history() }
                    }
                }.show()
            }
        }.setNegativeButton("关闭", null).show()
    }
    private fun screenshotList(run: String) {
        async({ gateway.request("GET", "/runs/$run/screenshots") }) { response ->
            val items = response.optJSONArray("items") ?: return@async
            if (items.length() == 0) { status.text = "暂无截图"; return@async }
            AlertDialog.Builder(this).setTitle("任务截图").setItems(Array(items.length()) { "截图 ${it + 1} · ${items.getJSONObject(it).optInt("size") / 1024} KB" }) { _, index ->
                val item = items.getJSONObject(index); val imageId = item.getString("id")
                val path = "/runs/$run/screenshots/$imageId"
                status.text = "正在读取截图"
                io.execute {
                    try {
                        val bytes = gateway.image(path)
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid image")
                        runOnUiThread {
                            if (isDestroyed) { bitmap.recycle(); return@runOnUiThread }
                            val view = ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
                            val dialog = AlertDialog.Builder(this).setTitle("任务截图").setView(view).setPositiveButton("关闭", null).setNegativeButton("删除") { _, _ ->
                                async({ val result = gateway.request("DELETE", path); ResultStore(this).use { it.erase(run, setOf(item.optString("command_id", imageId))) }; result }) { status.text = "截图已删除" }
                            }.create()
                            dialog.setOnDismissListener { view.setImageDrawable(null); bitmap.recycle() }; dialog.show()
                        }
                    } catch (_: Exception) { runOnUiThread { if (!isDestroyed) status.text = "截图不可读取" } }
                }
            }.setNegativeButton("关闭", null).show()
        }
    }
    override fun onResume() { super.onResume(); visible = true; goal?.setText(gateway.prefs.getString("draft_goal", "")); if (section == "设置") render(); status.text = connectionSummary(); handler.post(poll) }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("section", section); super.onSaveInstanceState(outState) }
    override fun onPause() { visible = false; handler.removeCallbacks(poll); goal?.let { gateway.prefs.edit().putString("draft_goal", it.text.toString()).apply() }; super.onPause() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); io.shutdown(); super.onDestroy() }
}
