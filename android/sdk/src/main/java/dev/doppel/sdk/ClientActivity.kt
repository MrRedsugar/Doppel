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
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var visible = false
    private var polling = false
    private var lastPending = ""
    private val poll = object : Runnable { override fun run() { if (visible) { refreshRun(); handler.postDelayed(this, 2500) } } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); gateway = Gateway(this)
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(247, 249, 250)); setPadding(dp(18), dp(12), dp(18), 0) }
        if (Build.VERSION.SDK_INT >= 35) container.setOnApplyWindowInsetsListener { view, insets -> val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()); view.setPadding(dp(18), bars.top + dp(12), dp(18), bars.bottom); insets }
        setContentView(container)
        container.addView(TextView(this).apply { text = clientTitle; textSize = 26f; setTextColor(Color.rgb(21, 59, 53)); setPadding(0, dp(12), 0, dp(12)) })
        status = TextView(this).apply { text = "未连接"; textSize = 14f; setTextColor(Color.DKGRAY); setPadding(0, 0, 0, dp(12)) }; container.addView(status)
        val tabs = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for (name in listOf("任务", "记录", "文件", "设置") + additionalSections) {
            tabs.addView(RadioButton(this).apply {
                text = name; textSize = 14f; id = View.generateViewId(); setButtonDrawable(android.R.color.transparent); gravity = android.view.Gravity.CENTER; isChecked = name == section
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_checked), android.graphics.drawable.ColorDrawable(Color.rgb(207, 232, 222)))
                    addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                }
                setOnClickListener { section = name; render() }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        container.addView(tabs)
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(16), 0, dp(24)) }
        container.addView(ScrollView(this).apply { isFillViewport = true; addView(page) }, LinearLayout.LayoutParams(-1, 0, 1f))
        render()
    }
    protected fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    protected fun label(text: String, size: Float = 16f): TextView = TextView(this).apply { this.text = text; textSize = size; setTextColor(Color.rgb(35, 43, 47)); setPadding(0, dp(8), 0, dp(8)); page.addView(this) }
    protected fun input(hint: String, initial: String = "", secret: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint; setText(initial); textSize = 16f; minHeight = dp(52)
        if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        page.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    protected fun button(text: String, action: () -> Unit): Button = Button(this).apply { this.text = text; isAllCaps = false; minHeight = dp(48); setOnClickListener { action() }; page.addView(this, LinearLayout.LayoutParams(-1, -2)) }
    protected fun async(work: () -> JSONObject, done: (JSONObject) -> Unit = {}) {
        status.text = "正在连接服务"
        io.execute {
            try { val value = work(); runOnUiThread { if (!isDestroyed) { status.text = DeviceWorkerService.state; done(value) } } }
            catch (e: Exception) { runOnUiThread { if (!isDestroyed) status.text = if (e is IllegalStateException || e is IllegalArgumentException) e.message ?: "请求失败" else "无法连接服务，请检查地址和网络" } }
        }
    }
    protected open fun customPage(section: String) {}
    private fun render() {
        page.removeAllViews(); goal = null; runView = null; runControls = null; lastPending = ""
        when(section) { "任务" -> tasks(); "设置" -> settings(); "记录" -> history(); "文件" -> documents(); else -> customPage(section) }
    }
    private fun tasks() {
        label("新任务", 21f)
        goal = input("今天需要我做什么？", gateway.prefs.getString("draft_goal", "").orEmpty()).apply { minLines = 3; gravity = android.view.Gravity.TOP }
        val modes = Spinner(this).apply { adapter = ArrayAdapter(this@ClientActivity, android.R.layout.simple_spinner_dropdown_item, listOf("请求批准", "帮我批准", "完全访问")); setSelection(gateway.prefs.getInt("mode_index", 1)) }; page.addView(modes)
        button("开始任务") {
            val value = goal?.text.toString().trim()
            val device = gateway.prefs.getString("device_id", "").orEmpty()
            if (value.isBlank()) { status.text = "请输入任务"; return@button }
            if (device.isBlank()) { status.text = "请在设置中连接并绑定设备"; return@button }
            if (DoppelAccessibilityService.instance == null) { status.text = "请先启用无障碍服务"; return@button }
            val selected = modes.selectedItemPosition
            gateway.prefs.edit().putInt("mode_index", selected).putString("draft_goal", value).apply()
            async({ gateway.request("POST", "/runs", JSONObject().put("device_id", device).put("goal", value).put("mode", listOf("ask", "assist", "full")[selected])) }) {
                gateway.prefs.edit().putString("active_run", it.getString("id")).apply(); startForegroundService(Intent(this, DeviceWorkerService::class.java)); displayRun(it)
            }
        }.apply { backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(27, 107, 81)); setTextColor(Color.WHITE) }
        val microphone = ImageButton(this).apply { setImageResource(android.R.drawable.ic_btn_speak_now); contentDescription = "语音输入"; setOnClickListener { startActivity(Intent(this@ClientActivity, VoiceActivity::class.java)) } }; page.addView(microphone, LinearLayout.LayoutParams(dp(52), dp(48)))
        label("当前任务", 21f)
        runView = label("暂无任务")
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; page.addView(actions)
        for ((text, operation) in listOf("暂停" to "pause", "继续" to "resume", "取消" to "cancel")) actions.addView(Button(this).apply { this.text = text; textSize = 14f; setOnClickListener {
            val run = gateway.prefs.getString("active_run", "").orEmpty(); if (run.isBlank()) return@setOnClickListener
            if (operation != "resume") DeviceWorkerService.instance?.cancel()
            async({ gateway.request("POST", "/runs/$run/$operation", JSONObject()) }) { displayRun(it); if (operation == "resume") startForegroundService(Intent(this@ClientActivity, DeviceWorkerService::class.java)) }
        } }, LinearLayout.LayoutParams(0, dp(52), 1f))
        runControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; page.addView(runControls)
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
        val state = run.optString("status")
        val translated = mapOf("queued" to "排队中", "running" to "执行中", "paused" to "已暂停", "awaiting_approval" to "等待批准", "awaiting_input" to "需要补充信息", "completed" to "已完成", "failed" to "失败", "cancelled" to "已取消")
        runView?.text = "${translated[state] ?: state}\n${run.optString("message")}"; status.text = DeviceWorkerService.state
        if (state == "paused") DeviceWorkerService.instance?.cancel()
        if (state in setOf("cancelled", "completed", "failed")) DoppelAccessibilityService.instance?.setTouchGuard(false)
        val pending = run.optJSONObject("pending_request")
        if (pending == null) { runControls?.removeAllViews(); lastPending = ""; return }
        if (pending.optString("id") == lastPending) return
        lastPending = pending.optString("id"); runControls?.removeAllViews()
        val controls = runControls ?: return
        controls.addView(TextView(this).apply { text = pending.optString("message"); textSize = 17f; setPadding(0, dp(12), 0, dp(8)) })
        val answer = EditText(this).apply { hint = "补充信息" }
        if (pending.optString("kind") == "input") controls.addView(answer)
        val choices = if (pending.optString("kind") == "approval") listOf("批准", "拒绝") else listOf("提交")
        for (choice in choices) controls.addView(Button(this).apply { text = choice; setOnClickListener {
            val body = JSONObject().put("request_id", pending.getString("id"))
            if (choice == "提交") body.put("text", answer.text.toString()) else body.put("approve", choice == "批准")
            async({ gateway.request("POST", "/runs/${run.getString("id")}/answer", body) }) { displayRun(it); if (it.optString("status") == "running") DeviceWorkerService.instance?.resume() }
        } })
    }
    private fun settings() {
        label("连接", 21f)
        val base = input("服务地址", gateway.prefs.getString("base_url", "http://10.0.2.2:8765").orEmpty())
        val token = if (showTokenSetting) input("网关令牌", gateway.prefs.getString("token", "").orEmpty(), true) else null
        button("保存连接") {
            DeviceWorkerService.instance?.cancel()
            val changed = gateway.prefs.getString("base_url", "http://10.0.2.2:8765") != base.text.toString().trim()
            val editor = gateway.prefs.edit().putString("base_url", base.text.toString().trim())
            if (changed) editor.remove("device_id").remove("active_run").remove("token")
            if (token != null) editor.putString("token", token.text.toString())
            editor.apply(); status.text = "连接已保存"
        }
        button("绑定本机") {
            var installation = gateway.prefs.getString("installation_id", null)
            if (installation == null) { installation = UUID.randomUUID().toString(); gateway.prefs.edit().putString("installation_id", installation).commit() }
            val identity = installation
            async({ gateway.request("POST", "/devices", JSONObject().put("installation_id", identity).put("name", "${Build.MANUFACTURER} ${Build.MODEL}")) }) { gateway.prefs.edit().putString("device_id", it.getString("id")).apply(); status.text = "本机已绑定" }
        }
        label("系统权限", 21f)
        page.addView(Switch(this).apply { text = "触屏暂停"; isChecked = gateway.prefs.getBoolean("touch_pause", true); setOnCheckedChangeListener { _, checked -> gateway.prefs.edit().putBoolean("touch_pause", checked).apply(); if (!checked) DoppelAccessibilityService.instance?.setTouchGuard(false) } })
        button(if (DoppelAccessibilityService.instance == null) "启用无障碍服务" else "无障碍服务已启用") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button(if (Settings.canDrawOverlays(this)) "悬浮窗已授权" else "授权悬浮窗") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        button("通知与麦克风权限") { requestPermissions((listOf(Manifest.permission.RECORD_AUDIO) + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()).toTypedArray(), 9) }
        button("语音识别与本地模型") { startActivity(Intent(this, SpeechSettingsActivity::class.java)) }
        button("启动悬浮服务") { startForegroundService(Intent(this, DeviceWorkerService::class.java)) }
        button("停止悬浮服务") { stopService(Intent(this, DeviceWorkerService::class.java)); status.text = "服务已停止" }
        button("扩展服务") { extensions() }
        button("数据保存设置") {
            async({ gateway.request("GET", "/data-retention") }) { config ->
                val days = listOf(1, 7, 30, 90, 0)
                AlertDialog.Builder(this).setTitle("截图保存时间").setSingleChoiceItems(arrayOf("1 天", "7 天", "30 天", "90 天", "长期保存"), days.indexOf(config.optInt("days", 7))) { dialog, which ->
                    async({ gateway.request("PATCH", "/data-retention", JSONObject().put("days", days[which])) }) { status.text = "保存时间已更新" }; dialog.dismiss()
                }.setNegativeButton("关闭", null).show()
            }
        }
        button("清理本机文档副本") { async({ gateway.clearDocumentCache(); JSONObject() }) { status.text = "本机文档副本已清理" } }
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
        label("工作文件", 21f)
        button("导入表格") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 40) }
        button("刷新文件") { page.removeAllViews(); documents() }
        async({ gateway.request("GET", "/documents") }) { response ->
            if (section != "文件") return@async
            val items = response.optJSONArray("items") ?: return@async
            if (items.length() == 0) label("暂无文件")
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                button("${item.optString("name")} · ${item.optLong("size") / 1024} KB") {
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
        label("任务记录", 21f)
        async({ gateway.request("GET", "/runs") }) { response ->
            if (section != "记录") return@async
            val items = response.optJSONArray("items") ?: return@async
            if (items.length() == 0) label("暂无记录")
            for (i in 0 until items.length()) { val item = items.getJSONObject(i); button(item.optString("goal")) { runHistoryMenu(item) } }
        }
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
    override fun onResume() { super.onResume(); visible = true; goal?.setText(gateway.prefs.getString("draft_goal", "")); handler.post(poll) }
    override fun onPause() { visible = false; handler.removeCallbacks(poll); goal?.let { gateway.prefs.edit().putString("draft_goal", it.text.toString()).apply() }; super.onPause() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); io.shutdown(); super.onDestroy() }
}
