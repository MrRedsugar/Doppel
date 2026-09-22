package dev.doppel.sdk

import android.Manifest
import android.app.Activity
import android.content.DialogInterface
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
import org.json.JSONArray
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

open class ClientActivity : Activity() {
    companion object {
        private val taskCreation = TaskSubmissionGate.creating
        private val pendingWorkerRun = java.util.concurrent.atomic.AtomicReference<String?>(null)
        private val pendingWorkerGeneration = java.util.concurrent.atomic.AtomicLong()
        private val submittedDraft = java.util.concurrent.atomic.AtomicReference<Pair<String, String>?>(null)
        private val repliedDraft = java.util.concurrent.atomic.AtomicReference<Pair<String, String>?>(null)
        private val draftLock = Any()
    }
    protected lateinit var gateway: Gateway
    protected lateinit var page: LinearLayout
    protected lateinit var status: TextView
    protected open val clientTitle = "Doppel 开发者"
    protected open val additionalSections: List<String> = emptyList()
    protected open val showTokenSetting = true
    private lateinit var container: LinearLayout
    private lateinit var composerDock: LinearLayout
    private lateinit var conversationScroll: ScrollView
    private var emptyConversation: View? = null
    private var conversationContent: LinearLayout? = null
    private var conversationRows: LinearLayout? = null
    /** Ordinary chat turns are kept locally and are never submitted as device commands. */
    private var chatHistory: LinearLayout? = null
    private var chatHeading: TextView? = null
    private var renderedConversation = ""
    private var navigationDialog: android.app.Dialog? = null
    private var section = "任务"
    private var goal: EditText? = null
    private var runView: TextView? = null
    private var runProcess: TextView? = null
    private var runEvents: org.json.JSONArray? = null
    private var eventsRunId = ""
    private val eventFeed = TaskEventFeed()
    private var freshConversation = false
    private var runControls: LinearLayout? = null
    private var runTitle: TextView? = null
    private var runGoal: TextView? = null
    private var runAttachments: LinearLayout? = null
    private var runVersions: LinearLayout? = null
    private var editBanner: LinearLayout? = null
    private var displayedVersionGroups = JSONArray()
    private var runProgress: UiActivitySignal? = null
    private var taskProgress: TaskProgressView? = null
    private var taskActions: LinearLayout? = null
    private var sendButton: ImageButton? = null
    private var classifying = false
    private lateinit var attachments: ChatAttachmentsUi
    private val creating get() = taskCreation.get()
    private var latestRun: JSONObject? = null
    private var queueView: LinearLayout? = null
    private var queueSnapshot = JSONArray()
    private var queueScope = ""
    private val cancellingQueued = mutableSetOf<String>()
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var visible = false
    private var polling = false
    private var lastPending = ""
    private var onboardingOpen = false
    private var trustedInstallation = true
    private val poll = object : Runnable { override fun run() { if (visible) { attachments.refresh(); refreshRun(); handler.postDelayed(this, 2500) } } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); UiTheme.init(this)
        TaskCompletionDelivery.registerChannels(this)
        trustedInstallation = ReleaseIntegrity.isTrusted(this)
        if (!trustedInstallation) {
            val error = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)) }
            UiTheme.bind(error) { error.setBackgroundColor(UiTheme.background) }; UiTheme.window(this, error)
            error.addView(UiTheme.text(this, "安装包签名校验失败", 20f, UiTheme.ink, true))
            error.addView(UiTheme.command(this, "关闭") { finishAndRemoveTask() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(24) })
            setContentView(error); return
        }
        gateway = Gateway(this)
        attachments = ChatAttachmentsUi(this, gateway, io, { creating || classifying }) { message ->
            sendButton?.isEnabled = !creating && !classifying && !attachments.processing()
            if (message != null) { status.text = message; Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }.also { it.restore(savedInstanceState) }
        onboardingOpen = savedInstanceState?.getBoolean("onboarding_open") ?: false
        section = savedInstanceState?.getString("section") ?: "任务"
        freshConversation = gateway.selectedConversationRun() == "" || (savedInstanceState?.getBoolean("fresh_conversation") ?: false)
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(UiTheme.background) }
        UiTheme.bind(container) { container.setBackgroundColor(UiTheme.background) }
        UiTheme.window(this, container)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(container)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        header.addView(UiTheme.icon(this, UiIcons.menu, "导航菜单") { openNavigation() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val identity = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        identity.addView(UiTheme.text(this, clientTitle, 18f, UiTheme.ink, true))
        status = UiTheme.text(this, connectionSummary(), 10f, UiTheme.muted).apply { setPadding(0, dp(3), 0, 0); maxLines = 2 }; identity.addView(status)
        header.addView(identity, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(UiTheme.icon(this, UiIcons.newChat, "新任务") { newConversation() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        container.addView(header)
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(24)) }
        conversationScroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; addView(page) }
        container.addView(conversationScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        composerDock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(12)) }
        container.addView(composerDock)
        if (FirstUseConsent.isAccepted(this) && !FirstUseConsent.needsGuide(this)) render() else openFirstUse()
    }
    private fun openNavigation() {
        navigationDialog?.dismiss()
        val dialog = android.app.Dialog(this, R.style.DoppelGlassDialog)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = UiTheme.glass(this@ClientActivity); setPadding(dp(18), dp(20), dp(18), dp(20)) }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(UiTheme.text(this, clientTitle, 20f, UiTheme.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(UiTheme.icon(this, UiIcons.panelLeft, "关闭导航") { dialog.dismiss() }, LinearLayout.LayoutParams(dp(44), dp(44))); panel.addView(heading)
        panel.addView(UiTheme.command(this, "新任务", true) { dialog.dismiss(); newConversation() }.apply {
            val symbol = getDrawable(UiIcons.newChat)!!.mutate().apply { setBounds(0, 0, dp(20), dp(20)); setTint(UiTheme.onPrimary) }
            setCompoundDrawablesRelative(symbol, null, null, null); compoundDrawablePadding = dp(10)
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(20); bottomMargin = dp(18) })
        val links = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sections = listOf("任务", "定时任务", "记录", "设置") + additionalSections
        for (name in sections) {
            val icon = when(name) { "任务" -> UiIcons.play; "定时任务", "记录" -> UiIcons.history; "设置" -> UiIcons.settings; else -> UiIcons.account }
            val row = UiTheme.navigationRow(this, if (name == "任务") "当前任务" else name, icon, name == section) {
                dialog.dismiss()
                if (name == "定时任务") startActivity(Intent(this, ScheduleActivity::class.java)) else selectSection(name)
            }
            links.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
        panel.addView(ScrollView(this).apply { addView(links) }, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(UiTheme.text(this, connectionSummary(), 12f, UiTheme.muted).apply { setPadding(dp(6), dp(18), dp(6), 0) })
        dialog.setContentView(panel); dialog.window?.let { UiTheme.styleSheet(it); it.setGravity(Gravity.START); it.setLayout(minOf(dp(310), resources.displayMetrics.widthPixels - dp(36)), -1) }
        dialog.show(); dialog.window?.setLayout(minOf(dp(310), resources.displayMetrics.widthPixels - dp(36)), -1); navigationDialog = dialog
    }
    private fun saveDraft() { synchronized(draftLock) { reconcileSubmittedDraft(); reconcileRepliedDraft(); goal?.let { gateway.prefs.edit().putString("draft_goal", it.text.toString()).apply() } } }
    private fun newConversation() {
        android.util.Log.i("DoppelEntry", "new_conversation requested")
        saveDraft()
        if (creating || classifying || attachments.processing()) { status.text = "正在处理消息或附件，请稍候"; return }
        fun clear() {
            gateway.prefs.edit().remove("conversation_edit_${gateway.conversationKey()}").commit()
            attachments.clearDraft()
            gateway.startNewConversation()
            android.util.Log.i("DoppelEntry", "new_conversation selected")
            latestRun = null; runEvents = null; eventsRunId = ""; freshConversation = true
            gateway.prefs.edit().putString("draft_goal", "").apply(); goal = null; selectSection("任务")
            val editor = goal ?: return
            editor.requestFocus()
            editor.post {
                if (visible && goal === editor && editor.hasWindowFocus()) getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        fun confirmClear() {
            if (gateway.prefs.getString("draft_goal", "").isNullOrBlank() && !attachments.hasDraft()) clear()
            else UiDialog.Builder(this).setTitle("开始新任务？").setMessage("当前草稿将被清除，之前的任务保留在记录中。").setNegativeButton("保留草稿", null).setPositiveButton("新任务") { _, _ -> clear() }.show()
        }
        confirmClear()
    }
    protected fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    protected fun label(text: String, size: Float = 15f): TextView = UiTheme.text(this, text, size, UiTheme.ink, size >= 18f).apply { setPadding(0, dp(8), 0, dp(12)); page.addView(this) }
    protected fun input(hint: String, initial: String = "", secret: Boolean = false): EditText = UiTheme.field(this, hint, initial, secret).apply { page.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }
    protected fun button(text: String, action: () -> Unit): Button = UiTheme.command(this, text, action = action).apply { page.addView(this, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6); bottomMargin = dp(6) }) }
    protected fun sectionLabel(text: String) { page.addView(UiTheme.text(this, text, 12f, UiTheme.muted, true).apply { setPadding(0, dp(22), 0, dp(6)) }) }
    protected fun settingsRow(title: String, detail: String, icon: Int = android.R.drawable.ic_menu_manage, action: () -> Unit) { page.addView(UiTheme.row(this, title, detail, icon, action)); page.addView(UiTheme.divider(this)) }
    protected fun selectSection(name: String) {
        if (creating && name in additionalSections) { status.text = "任务创建中，暂不可更换账号"; return }
        if (name == "任务") workerStartDeferred = false
        saveDraft(); section = name; render(); conversationScroll.scrollTo(0, 0)
    }
    protected fun connectionSummary(): String = when {
        !gateway.isConnected() -> "尚未连接"
        gateway.prefs.getString("device_id", "").isNullOrBlank() -> "等待绑定设备"
        DeviceWorkerService.instance == null -> "设备已绑定 · 服务未启动"
        else -> DeviceWorkerService.state
    }
    protected fun pointsPanel(developer: Boolean = false) {
        var balanceInput: EditText? = null
        var savingBalance = false
        fun readUsage() = gateway.readUsage(fallbackPoints = true)
        val amount = label("正在读取用量", 24f).apply { setTextColor(UiTheme.green) }
        val summary = UiTheme.text(this, "Token 用量按模型平台实际账单计算", 13f, UiTheme.muted).also { page.addView(it) }
        val chart = UsageChartView(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        page.addView(chart, LinearLayout.LayoutParams(-1, dp(132)).apply { topMargin = dp(12); bottomMargin = dp(4) })
        page.addView(UiTheme.text(this, "输入 Token  ·  输出 Token", 11f, UiTheme.muted).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(8)) })
        val numbers = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(22), 0, dp(20)) }
        val values = listOf("输入 Token", "输出 Token", "请求次数", "截图数量").map { title ->
            val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val value = UiTheme.text(this, "-", 18f, UiTheme.ink, true).apply { maxLines = 1; setAutoSizeTextTypeUniformWithConfiguration(10, 18, 1, android.util.TypedValue.COMPLEX_UNIT_SP) }; group.addView(value, LinearLayout.LayoutParams(-1, dp(26)))
            group.addView(UiTheme.text(this, title, 11f, UiTheme.muted).apply { setPadding(0, dp(7), 0, 0) })
            numbers.addView(group, LinearLayout.LayoutParams(0, -2, 1f)); value
        }
        page.addView(numbers); page.addView(UiTheme.divider(this))
        async({
            readUsage()
        }) { points ->
            val data = points
            val inTokens = UsageBalance.total(data, "input_tokens")
            val outTokens = UsageBalance.total(data, "output_tokens")
            val reqs = if (data.has("requests")) UsageBalance.total(data, "requests") else points.optLong("calls")
            val shots = UsageBalance.total(data, "screenshots")
            amount.text = "${java.text.NumberFormat.getIntegerInstance().format(inTokens + outTokens)} Token"
            summary.text = if (data.optString("source") == "local_model_ledger") "本机请求用量累计 · 旧用量仅含升级时保留的任务" else "当前可用记录的用量 · 费用由模型平台账户结算"
            fun number(value: Long) = java.text.NumberFormat.getIntegerInstance().format(value.coerceAtLeast(0))
            values[0].text = number(inTokens); values[1].text = number(outTokens); values[2].text = number(reqs); values[3].text = number(shots)
            val daily = UsageBalance.daily(data)
            if (daily.length() > 0) {
                val ins = LongArray(daily.length()) { daily.optJSONObject(it)?.optLong("input_tokens") ?: 0L }
                val outs = LongArray(daily.length()) { daily.optJSONObject(it)?.optLong("output_tokens") ?: 0L }
                val dates = List(daily.length()) { daily.optJSONObject(it)?.optString("date").orEmpty().ifBlank { "日期未知" } }
                chart.setSeries(ins, outs, dates)
            } else chart.setSeries(longArrayOf(inTokens), longArrayOf(outTokens), listOf("累计"))
            gateway.updateUsageBalance(data, "balance_usage_baseline") { previous, snapshot, editor ->
                val balance = gateway.prefs.getString("balance_amount", "").orEmpty()
                val cost = gateway.prefs.getString("cost_per_million", "").orEmpty()
                val threshold = gateway.prefs.getString("balance_alert_threshold", "").orEmpty()
                val price = cost.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                if (balance.toDoubleOrNull()?.isFinite() == true && price != null) {
                    val remaining = balance.toDouble() - UsageBalance.cost(previous, snapshot, price, price)
                    val remainingText = if (remaining == balance.toDouble()) balance else String.format(Locale.US, "%.6f", remaining)
                    editor.putString("balance_amount", remainingText)
                    balanceInput?.takeIf { it.text.toString() == balance }?.setText(remainingText)
                    amount.text = "余额 ¥${String.format(java.util.Locale.US, "%.2f", remaining)}"
                    if (threshold.toDoubleOrNull() != null && remaining <= threshold.toDouble()) summary.text = "余额即将用尽，请及时充值（剩余 ¥${String.format(java.util.Locale.US, "%.2f", remaining)}）"
                }
            }
        }
        sectionLabel("余额监控（可选）")
        page.addView(UiTheme.text(this, "设置当前余额后，仅按新增的已记录 Token 估算。旧网关没有累计账单时仅能估算保留日期内的增量，实际余额请以平台为准。", 12f, UiTheme.muted).apply { setPadding(0, 0, 0, dp(8)) })
        val balance = UiTheme.field(this, "当前余额（元）", gateway.prefs.getString("balance_amount", "").orEmpty()).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val cost = UiTheme.field(this, "每百万 Token 费用（元）", gateway.prefs.getString("cost_per_million", "").orEmpty()).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val threshold = UiTheme.field(this, "余额提醒阈值（元，可选）", gateway.prefs.getString("balance_alert_threshold", "").orEmpty()).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        balanceInput = balance
        page.addView(balance); page.addView(cost); page.addView(threshold)
        val balanceActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun saveBalance(add: Boolean) {
            if (savingBalance) return
            val value = balance.text.toString().trim(); val price = cost.text.toString().trim(); val alert = threshold.text.toString().trim()
            val parsed = listOf(value.toDoubleOrNull(), price.toDoubleOrNull(), alert.ifBlank { "0" }.toDoubleOrNull())
            if (parsed.any { it == null || !it.isFinite() || it < 0 }) { balance.error = "请输入有效的非负金额和价格"; return }
            savingBalance = true; status.text = "正在更新余额基线"
            io.execute {
                val result = runCatching {
                    val response = readUsage()
                    gateway.updateUsageBalance(response, "balance_usage_baseline") { previous, snapshot, editor ->
                        val oldPrice = gateway.prefs.getString("cost_per_million", "0")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: 0.0
                        val oldBalance = gateway.prefs.getString("balance_amount", "0")?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: 0.0
                        val next = if (add) oldBalance - UsageBalance.cost(previous, snapshot, oldPrice, oldPrice) + value.toDouble() else value.toDouble()
                        check(next.isFinite()) { "金额过大" }
                        editor.putString("balance_amount", if (add) String.format(Locale.US, "%.6f", next) else value)
                            .putString("cost_per_million", price).putString("balance_alert_threshold", alert)
                    }
                }
                runOnUiThread {
                    savingBalance = false
                    if (!isDestroyed) {
                        if (result.isSuccess) { render(); status.text = if (add) "已加到账户余额" else "余额已重新设置" }
                        else status.text = result.exceptionOrNull()?.message ?: "无法读取用量，余额未更改"
                    }
                }
            }
        }
        balanceActions.addView(UiTheme.command(this, "重新设置余额", true) { saveBalance(false) }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        balanceActions.addView(UiTheme.command(this, "充值金额相加") { saveBalance(true) }, LinearLayout.LayoutParams(0, dp(44), 1f))
        page.addView(balanceActions)
        page.addView(UiTheme.text(this, "余额仅保存在本机，用于估算和提醒，不会修改平台账户。", 11f, UiTheme.muted).apply { setPadding(0, dp(8), 0, 0) })
    }
    protected fun async(work: () -> JSONObject, done: (JSONObject) -> Unit = {}) {
        if (!FirstUseConsent.isAccepted(this)) return
        status.text = "正在连接服务"
        io.execute {
            try { val value = work(); runOnUiThread { if (!isDestroyed) {
                try { status.text = connectionSummary(); done(value) }
                catch (error: Exception) { status.text = error.message ?: "页面数据处理失败，请重试" }
            } } }
            catch (e: Exception) { runOnUiThread { if (!isDestroyed) status.text = if (e is IllegalStateException || e is IllegalArgumentException) e.message ?: "请求失败" else "无法连接服务，请检查地址和网络" } }
        }
    }
    protected open fun customPage(section: String) {}
    private fun render() {
        if (!FirstUseConsent.isAccepted(this)) return
        attachments.detachDraftView(); runAttachments = null
        page.removeAllViews(); goal = null; runView = null; runProcess = null; runControls = null; runTitle = null; runGoal = null; runProgress = null; taskProgress = null; taskActions = null; sendButton = null; lastPending = ""
        composerDock.removeAllViews(); composerDock.visibility = if (section == "任务") View.VISIBLE else View.GONE
        emptyConversation = null; conversationContent = null; chatHistory = null; chatHeading = null; queueView = null
        when(section) { "任务" -> tasks(); "设置" -> settings(); "记录" -> history(); else -> customPage(section) }
    }
    private fun tasks() {
        queueView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { page.addView(it) }
        renderQueue()
        val empty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(12), dp(24), dp(12), dp(36)) }
        empty.addView(ImageView(this).apply { setImageResource(UiIcons.sparkles); UiTheme.bind(this) { imageTintList = ColorStateList.valueOf(UiTheme.ink) }; background = UiTheme.glass(this@ClientActivity, 20); setPadding(dp(15), dp(15), dp(15), dp(15)) }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(24) })
        val existingTitle = gateway.conversationTitle().trim()
        chatHeading = UiTheme.text(this, existingTitle.ifBlank { "今天想和 Doppel 做什么？" }, 23f, UiTheme.ink, true).apply { gravity = Gravity.CENTER }
        empty.addView(chatHeading, LinearLayout.LayoutParams(-1, -2))
        empty.addView(UiTheme.text(this, "可以直接下达手机任务，也可以先聊聊之前的执行记录。", 13f, UiTheme.muted).apply {
            gravity = Gravity.CENTER; setPadding(dp(16), dp(10), dp(16), dp(4))
        }, LinearLayout.LayoutParams(-1, -2))
        chatHistory = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(18), 0, dp(4)) }
        val recent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = UiTheme.glass(this@ClientActivity, 18); setPadding(dp(14), dp(10), dp(14), dp(8))
        }
        recent.addView(UiTheme.text(this, "最近执行", 12f, UiTheme.muted, true))
        recent.addView(UiTheme.text(this, "正在读取任务记录…", 13f, UiTheme.muted).apply { tag = "recent_placeholder" })
        empty.addView(recent, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(28); bottomMargin = dp(8) })
        async({ gateway.request("GET", "/runs") }) { response ->
            if (section != "任务" || conversationContent?.visibility == View.VISIBLE) return@async
            val items = response.optJSONArray("items")
            recent.removeAllViews()
            recent.addView(UiTheme.text(this, "最近执行", 12f, UiTheme.muted, true))
            if (items == null || items.length() == 0) {
                recent.addView(UiTheme.text(this, "完成第一个任务后，可以在这里查看记录并继续聊天。", 13f, UiTheme.muted).apply { setPadding(0, dp(8), 0, dp(4)) })
            } else {
                for (i in 0 until minOf(3, items.length())) {
                    val item = items.optJSONObject(i) ?: continue
                    val displayTitle = item.optString("title").ifBlank { item.optString("goal") }.ifBlank { "未命名任务" }
                    val row = UiTheme.row(this, displayTitle.take(42), TaskPresentation.withSourceLabel(item, TaskPresentation.title(item.optString("status"))), android.R.drawable.ic_menu_recent_history) { openTaskChat(item) }
                    recent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
                }
                recent.addView(UiTheme.command(this, "查看全部记录") { selectSection("记录") }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(6) })
            }
        }
        page.addView(empty, LinearLayout.LayoutParams(-1, 0, 1f)); emptyConversation = empty
        val messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        page.addView(messages, LinearLayout.LayoutParams(-1, -2)); conversationContent = messages
        page.addView(chatHistory, LinearLayout.LayoutParams(-1, -2))
        renderLocalChat()
        conversationRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { messages.addView(it) }
        renderedConversation = ""
        val userRow = LinearLayout(this).apply { gravity = Gravity.END; setPadding(dp(32), dp(8), 0, dp(26)) }
        runGoal = UiTheme.text(this, "", 15f).apply { background = UiTheme.glass(this@ClientActivity, 20); setPadding(dp(16), dp(13), dp(16), dp(13)) }
        userRow.addView(runGoal, LinearLayout.LayoutParams(-2, -2)); messages.addView(userRow)
        runVersions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { messages.addView(it) }
        runAttachments = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { messages.addView(it) }
        val assistantHeading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        assistantHeading.addView(ImageView(this).apply { setImageResource(UiIcons.sparkles); UiTheme.bind(this) { imageTintList = ColorStateList.valueOf(UiTheme.ink) } }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
        runTitle = UiTheme.text(this, "", 14f, UiTheme.ink, true); assistantHeading.addView(runTitle, LinearLayout.LayoutParams(0, -2, 1f))
        runProgress = UiActivitySignal(this).apply { visibility = View.GONE }
        assistantHeading.addView(runProgress, LinearLayout.LayoutParams(dp(18), dp(18))); messages.addView(assistantHeading)
        taskProgress = TaskProgressView(this).also { messages.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(4) }) }
        runView = UiTheme.text(this, "", 16f).apply { setLineSpacing(dp(4).toFloat(), 1f); setPadding(0, dp(14), 0, dp(8)) }; messages.addView(runView)
        runProcess = UiTheme.text(this, "", 13f, UiTheme.muted).apply { visibility = View.GONE; setLineSpacing(dp(5).toFloat(), 1f); setTextIsSelectable(true); setPadding(dp(14), dp(12), dp(14), dp(12)); background = UiTheme.glass(this@ClientActivity, 16) }
        messages.addView(runProcess, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) })
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = UiTheme.glass(this@ClientActivity)
            elevation = dp(5).toFloat(); outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            if (Build.VERSION.SDK_INT >= 28) { outlineAmbientShadowColor = Color.argb(65, 98, 108, 130); outlineSpotShadowColor = Color.argb(55, 98, 108, 130) }
            setPadding(dp(14), dp(8), dp(8), dp(8))
        }
        goal = UiTheme.field(this, "输入消息或任务", gateway.prefs.getString("draft_goal", "").orEmpty()).apply { contentDescription = "任务输入"; minLines = 1; maxLines = 4; gravity = Gravity.TOP; background = null; setPadding(dp(4), dp(10), dp(4), dp(8)) }
        goal?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { synchronized(draftLock) { gateway.prefs.edit().putString("draft_goal", s?.toString().orEmpty()).apply() } }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        editBanner = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }.also { composer.addView(it) }
        refreshEditBanner()
        val attachmentRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        composer.addView(attachmentRows, LinearLayout.LayoutParams(-1, -2)); attachments.attachDraftView(attachmentRows)
        composer.addView(goal, LinearLayout.LayoutParams(-1, -2))
        val composerTools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val modeNames = listOf("请求批准", "帮我批准", "完全访问")
        val mode = UiTheme.text(this, modeNames[gateway.prefs.getInt("mode_index", 1).coerceIn(0, 2)], 12f, UiTheme.muted).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0); minHeight = dp(44); isFocusable = true; contentDescription = "执行模式"; tooltipText = "执行模式"
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, UiIcons.chevronDown, 0); compoundDrawablePadding = dp(5)
            setOnClickListener {
                UiDialog.Builder(this@ClientActivity).setTitle("执行模式").setSingleChoiceItems(modeNames.toTypedArray(), gateway.prefs.getInt("mode_index", 1).coerceIn(0, 2)) { dialog, which ->
                    gateway.prefs.edit().putInt("mode_index", which).apply(); text = modeNames[which]; dialog.dismiss()
                }.setNegativeButton("关闭", null).show()
            }
        }
        composerTools.addView(mode, LinearLayout.LayoutParams(0, dp(44), 1f))
        composerTools.addView(UiTheme.icon(this, UiIcons.plus, "添加图片或文件") { attachments.choose() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        composerTools.addView(UiTheme.icon(this, android.R.drawable.ic_btn_speak_now, "语音输入") { startActivity(Intent(this, VoiceActivity::class.java)) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        sendButton = UiTheme.icon(this, UiIcons.arrowUp, "开始任务", true) {
            android.util.Log.i("DoppelEntry", "composer submit requested")
            val capturedAttachments = attachments.draft()
            val typed = goal?.text.toString().trim()
            if (typed.isBlank() && capturedAttachments.length() == 0) { goal?.error = "请输入消息或添加附件"; return@icon }
            if (creating || classifying || attachments.processing()) return@icon
            val value = typed.ifBlank { "请查看这些附件。" }
            val edit = pendingMessageEdit()?.optJSONObject("target")
            classifying = true
            sendButton?.isEnabled = false
            status.text = if (capturedAttachments.length() > 0) "正在处理消息，AI 可按需读取附件" else "正在处理消息"
            saveDraft()
            val selected = gateway.prefs.getInt("mode_index", 1).coerceIn(0, 2)
            val (conversation, afterRun) = synchronized(gateway.prefs) { gateway.conversationKey() to gateway.selectedConversationRun().orEmpty() }
            // Classification is model based. There is intentionally no local
            // keyword trigger, so a question that happens to mention "click"
            // remains a conversation when the model says so.
            io.execute {
                try {
                    check(conversation == gateway.conversationKey() && afterRun == gateway.selectedConversationRun().orEmpty()) { "对话或关联任务已变化，请重新发送" }
                    val device = gateway.prepareUserConnection()
                    check(conversation == gateway.conversationKey() && afterRun == gateway.selectedConversationRun().orEmpty()) { "连接或对话已变化，请重新发送" }
                    val connection = gateway.captureReviewConnection()
                    check(connection.direct || capturedAttachments.length() == 0) { "附件需要使用本机模型连接，请在模型设置中启用" }
                    val revision = edit?.let { gateway.prepareConversationRevision(it, value, capturedAttachments, connection) }
                    val requestId = revision?.getString("request_id") ?: gateway.conversationRequestId(conversation, afterRun, value, capturedAttachments)
                    val request = JSONObject().put("message", value)
                        .put("history", revision?.getJSONArray("history") ?: gateway.conversationContext(conversation, afterRun, connection)).put("device_available", device.isNotBlank())
                    if (capturedAttachments.length() > 0) request.put("attachments", capturedAttachments)
                    // Older remote gateways reject unknown fields; the local runtime owns local chat memory.
                    if (connection.direct) request.put("conversation_id", conversation).put("request_id", requestId).put("run_id", afterRun)
                    val intent = connection.request("POST", "/conversation/intent", request).put("request_id", requestId)
                    val savedReply = persistConversationReply(intent, value, conversation, afterRun, capturedAttachments, revision)
                    runOnUiThread {
                        classifying = false
                        if (isDestroyed) return@runOnUiThread
                        if (conversation != gateway.conversationKey() || (afterRun != gateway.selectedConversationRun().orEmpty() && !(savedReply && revision != null))) { sendButton?.isEnabled = true; status.text = "对话或关联任务已变化，请在当前对话重新发送"; return@runOnUiThread }
                        try {
                            if (savedReply) {
                                reconcileRepliedDraft(); attachments.refresh(); refreshEditBanner(); renderLocalChat(true); sendButton?.isEnabled = true; status.text = connectionSummary()
                                if (revision != null) showSelectedConversation()
                            } else {
                                handleMessageIntent(intent, value, device, selected, capturedAttachments, revision)
                                gateway.finishConversationRequest(conversation, intent.optString("request_id"))
                            }
                        }
                        catch (error: Exception) { sendButton?.isEnabled = true; status.text = ConversationIntent.failureMessage(error) }
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        classifying = false
                        if (!isDestroyed) {
                            sendButton?.isEnabled = true
                            status.text = ConversationIntent.failureMessage(error)
                        }
                    }
                }
            }
        }
        sendButton?.isEnabled = !creating && !classifying && !attachments.processing()
        composerTools.addView(sendButton, LinearLayout.LayoutParams(dp(44), dp(44))); composer.addView(composerTools); composerDock.addView(composer)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START; visibility = View.GONE }; messages.addView(actions, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) }); taskActions = actions
        for ((text, operation) in listOf("暂停执行" to "pause", "继续执行" to "resume", "停止任务" to "cancel")) actions.addView(UiTheme.command(this, text) {
            val shown = latestRun ?: return@command
            val run = shown.optString("id"); if (run.isBlank()) return@command
            TaskControl.request(this, run, operation) { response, error ->
                if (!visible || isDestroyed || latestRun?.optString("id") != run) return@request
                if (response == null) status.text = error ?: "操作尚未确认"
                else { displayRun(response); if (operation == "resume" && response.optString("status") == "running") TaskControl.startWorker(this); refreshRun() }
            }
        }.apply { setPadding(dp(8), 0, dp(8), 0) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { if (operation != "pause") marginStart = dp(8) })
        runControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; messages.addView(runControls, messages.indexOfChild(actions))
        val selectedRun = if (freshConversation) "" else gateway.selectedConversationRun().orEmpty()
        latestRun?.takeIf { it.optString("id") == selectedRun }?.let { displayRun(it) }
        if (selectedRun.isNotBlank() && latestRun?.optString("id") != selectedRun) {
            empty.visibility = View.GONE; messages.visibility = View.VISIBLE; runTitle?.text = "正在读取任务"
        }
        refreshRun()
    }

    /** Complete only chat/clarification on IO; a dead screen must never start a device task. */
    private fun persistConversationReply(intent: JSONObject, value: String, key: String, afterRun: String, refs: JSONArray, revision: JSONObject? = null): Boolean {
        val rawKind = intent.optString("intent", intent.optString("type")).lowercase(Locale.ROOT)
        val parsed = if (rawKind.isNotBlank()) ConversationIntent.parse(intent.toString()) else null
        val kind = parsed?.kind?.name?.lowercase(Locale.ROOT) ?: rawKind
        val reply = when (kind) {
            "chat", "conversation", "dialogue" -> intent.optString("reply", intent.optString("message")).trim()
            "task" -> if (parsed?.canStartTask() == true) return false else parsed?.question?.ifBlank { null }
                ?: "请说明你希望我在手机上完成什么，或告诉我想讨论的内容。"
            "uncertain" -> parsed?.question.orEmpty().ifBlank { "你希望我直接操作手机，还是只回答这个问题？" }
            else -> return false
        }
        synchronized(draftLock) {
            val memory = intent.optJSONObject("memory_result")
            val note = when {
                memory?.has("error") == true -> "长期记忆未更新：${memory.optString("error")}"
                memory != null && memory.optInt("applied") > 0 -> "已更新长期记忆，可在设置中编辑或删除。"
                else -> ""
            }
            val answer = if (note.isBlank()) reply else "$reply\n\n$note"
            if (revision == null) gateway.appendCapturedConversationReply(key, afterRun, value,
                answer, parsed?.title ?: intent.optString("title"), intent.optString("request_id"), refs)
            else gateway.commitConversationRevision(revision, answer, parsed?.title ?: intent.optString("title"))
            if (key == gateway.conversationKey()) {
                repliedDraft.set(key to value)
            }
        }
        return true
    }

    private fun handleMessageIntent(intent: JSONObject, value: String, device: String, selected: Int, refs: JSONArray, revision: JSONObject? = null) {
        val rawKind = intent.optString("intent", intent.optString("type")).lowercase(Locale.ROOT)
        val parsed = if (rawKind.isNotBlank()) ConversationIntent.parse(intent.toString()) else null
        val kind = parsed?.kind?.name?.lowercase(Locale.ROOT) ?: rawKind
        when (kind) {
            "task" -> if (parsed?.canStartTask() == true)
                submitClassifiedTask(value, device, selected, parsed.title.ifBlank { null }, parsed.goal, refs, revision)
            "run", "device_task" -> submitClassifiedTask(value, device, selected, intent.optString("title").trim().ifBlank { null }, refs = refs, revision = revision)
            else -> {
                // Older gateways answer an unknown route with their generic JSON
                // envelope. Keep those installations usable while new gateways
                // return an explicit `uncertain` and fail closed.
                if (!intent.has("intent") && !intent.has("type"))
                    submitClassifiedTask(value, device, selected, null, refs = refs, revision = revision)
                else { sendButton?.isEnabled = true; status.text = "模型没有明确判断这是对话还是手机任务，请换一种说法" }
            }
        }
    }

    private fun reconcileRepliedDraft() = synchronized(draftLock) {
        val submitted = repliedDraft.get() ?: return@synchronized
        if (submitted.first == gateway.conversationKey() && gateway.prefs.getString("draft_goal", "").orEmpty().let { it.isBlank() || it.trim() == submitted.second }) {
            if (goal?.text?.toString()?.trim() == submitted.second) goal?.setText("")
        }
        repliedDraft.compareAndSet(submitted, null)
    }

    private fun clearSubmittedChatDraft(submitted: String) = synchronized(draftLock) {
        if (gateway.prefs.getString("draft_goal", "").orEmpty().trim() == submitted) {
            check(gateway.prefs.edit().putString("draft_goal", "").commit()) { "回复已保存，输入框状态尚未保存" }
            if (goal?.text?.toString()?.trim() == submitted) goal?.setText("")
        }
    }

    private fun pendingMessageEdit(): JSONObject? = gateway.prefs.getString("conversation_edit_${gateway.conversationKey()}", null)
        ?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun focusComposer() {
        val editor = goal ?: return
        editor.requestFocus(); editor.setSelection(editor.text.length)
        editor.post { if (!isDestroyed && editor.hasWindowFocus()) getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            .showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
        conversationScroll.post { conversationScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refreshEditBanner() {
        val banner = editBanner ?: return
        banner.removeAllViews()
        banner.visibility = if (pendingMessageEdit() == null) View.GONE else View.VISIBLE
        if (banner.visibility == View.GONE) return
        banner.addView(UiTheme.text(this, "正在修改上一条消息", 12f, UiTheme.muted), LinearLayout.LayoutParams(0, -2, 1f))
        banner.addView(UiTheme.icon(this, UiIcons.close, "取消修改") {
            if (creating || classifying || attachments.processing()) return@icon
            val pending = pendingMessageEdit() ?: return@icon
            runCatching {
                attachments.replaceDraft(pending.optJSONArray("previous_attachments") ?: JSONArray())
                check(gateway.prefs.edit().remove("conversation_edit_${gateway.conversationKey()}")
                    .putString("draft_goal", pending.optString("previous_text")).commit()) { "草稿未恢复" }
                goal?.setText(pending.optString("previous_text")); refreshEditBanner()
            }.onFailure { status.text = it.message ?: "无法取消修改" }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
    }

    private fun editMessage(kind: String, turnId: String) {
        if (creating || classifying || attachments.processing()) return
        val key = gateway.conversationKey()
        val connection = gateway.captureReviewConnection()
        async({ gateway.revisionTarget(connection) ?: JSONObject() }) { target ->
            if (key != gateway.conversationKey()) return@async
            if (target.optString("kind") != kind || target.optString("turn_id") != turnId) {
                status.text = "目前可以修改当前对话的最后一个问题；执行中的任务请先结束"; return@async
            }
            fun begin() {
                if (creating || classifying || attachments.processing() || key != gateway.conversationKey()) return
                val previousEdit = gateway.prefs.getString("conversation_edit_$key", null)
                runCatching {
                    val old = pendingMessageEdit()
                    val pending = JSONObject().put("target", target)
                        .put("previous_text", old?.optString("previous_text") ?: goal?.text?.toString().orEmpty())
                        .put("previous_attachments", old?.optJSONArray("previous_attachments") ?: attachments.draft())
                    check(gateway.prefs.edit().putString("conversation_edit_$key", pending.toString()).commit()) { "修改草稿未保存" }
                    attachments.replaceDraft(target.optJSONArray("attachments") ?: JSONArray())
                    goal?.setText(target.optString("text")); refreshEditBanner(); focusComposer()
                }.onFailure {
                    gateway.prefs.edit().apply { if (previousEdit == null) remove("conversation_edit_$key") else putString("conversation_edit_$key", previousEdit) }.commit()
                    refreshEditBanner(); status.text = it.message ?: "无法修改消息"
                }
            }
            if (pendingMessageEdit() == null && (!goal?.text.isNullOrBlank() || attachments.hasDraft()))
                UiDialog.Builder(this).setTitle("修改上一条消息？").setMessage("当前草稿会暂存，取消修改后恢复。").setNegativeButton("返回", null)
                    .setPositiveButton("修改") { _, _ -> begin() }.show()
            else begin()
        }
    }

    private fun bindMessageMenu(view: TextView, kind: String = "", turnId: String = "", user: Boolean = false) {
        view.setTextIsSelectable(false)
        view.setOnLongClickListener {
            val text = view.text.toString()
            PopupMenu(this, view).apply {
                menu.add(0, 1, 0, "复制")
                if (user) menu.add(0, 2, 1, "修改").isEnabled = !creating && !classifying && !attachments.processing()
                menu.add(0, 3, 2, "追问").isEnabled = !creating && !classifying && !attachments.processing()
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> { getSystemService(android.content.ClipboardManager::class.java)
                            .setPrimaryClip(android.content.ClipData.newPlainText("消息", text))
                            if (Build.VERSION.SDK_INT < 33) Toast.makeText(this@ClientActivity, "已复制", Toast.LENGTH_SHORT).show() }
                        2 -> editMessage(kind, turnId)
                        3 -> {
                            if (pendingMessageEdit() != null) status.text = "请先完成或取消当前修改"
                            else {
                                val quote = text.take(1200) + if (text.length > 1200) "…（节选）" else ""
                                goal?.setText("关于这条消息：\n「$quote」\n\n${goal?.text?.toString().orEmpty()}")
                                focusComposer()
                            }
                        }
                    }
                    true
                }
                show()
            }
            true
        }
    }

    private fun showSelectedConversation() {
        latestRun = null; runEvents = null; eventsRunId = ""
        freshConversation = gateway.selectedConversationRun().isNullOrBlank()
        render()
        conversationScroll.post { conversationScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendVersionPicker(parent: LinearLayout, kind: String, turnId: String) {
        val groups = displayedVersionGroups
        val group = (0 until groups.length()).map { groups.getJSONObject(it) }.firstOrNull {
            it.getJSONObject("anchor").optString("kind") == kind && it.getJSONObject("anchor").optString("turn_id") == turnId
        } ?: return
        val items = group.getJSONArray("items"); if (items.length() < 2) return
        val index = group.getInt("index")
        val row = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        fun button(step: Int) = UiTheme.icon(this, if (step < 0) UiIcons.back else UiIcons.next, if (step < 0) "上一个版本" else "下一个版本") {
            if (creating || classifying || attachments.processing()) return@icon
            if (pendingMessageEdit() != null) { status.text = "请先完成或取消当前修改"; return@icon }
            runCatching {
                saveDraft(); gateway.selectConversationVersion(group, items.getJSONObject(index + step).getString("id"))
                showSelectedConversation()
            }.onFailure { status.text = it.message ?: "版本未能打开" }
        }.apply { isEnabled = index + step in 0 until items.length(); alpha = if (isEnabled) 1f else 0.3f }
        row.addView(button(-1), LinearLayout.LayoutParams(dp(44), dp(44)))
        row.addView(UiTheme.text(this, "${index + 1} / ${items.length()}", 12f, UiTheme.muted).apply { contentDescription = "消息版本 ${index + 1} / ${items.length()}" })
        row.addView(button(1), LinearLayout.LayoutParams(dp(44), dp(44)))
        parent.addView(row, LinearLayout.LayoutParams(-1, dp(44)))
    }

    private fun renderLocalChat(scrollToReply: Boolean = false) {
        val target = chatHistory ?: return
        chatHeading?.text = gateway.conversationTitle().ifBlank { "今天想和 Doppel 做什么？" }
        val messages = gateway.conversationMessages()
        displayedVersionGroups = gateway.conversationVersionGroups()
        val displayed = latestRun?.optString("id").takeIf { conversationContent?.visibility == View.VISIBLE }
        emptyConversation?.visibility = if (messages.length() == 0 && displayed == null) View.VISIBLE else View.GONE
        val stamp = messages.toString() + displayed.orEmpty()
        if (target.tag == stamp) {
            latestRun?.takeIf { displayed != null }?.let { renderConversation(it) }
            if (scrollToReply) conversationScroll.post { if (!isDestroyed) conversationScroll.fullScroll(View.FOCUS_DOWN) }
            return
        }
        target.tag = stamp; target.removeAllViews()
        for (i in 0 until messages.length()) {
            val item = messages.optJSONObject(i) ?: continue
            if (displayed != null && item.optString("after_run_id") != displayed) continue
            val role = item.optString("role", "assistant")
            val text = item.optString("content").trim()
            if (text.isBlank()) continue
            val bubble = UiTheme.text(this, text, 15f, if (role == "user") UiTheme.ink else UiTheme.muted).apply {
                setLineSpacing(dp(3).toFloat(), 1f); setPadding(dp(15), dp(11), dp(15), dp(11))
                if (role == "user") background = UiTheme.glass(this@ClientActivity, 18)
            }
            val turnId = item.optString("turn_id").ifBlank { item.optString("request_id") }
            val deleted = item.optBoolean("deleted")
            if (!deleted) bindMessageMenu(bubble, "chat", turnId, role == "user")
            target.addView(bubble, LinearLayout.LayoutParams(if (role == "user") -2 else -1, -2).apply {
                gravity = if (role == "user") Gravity.END else Gravity.START
                marginStart = if (role == "user") dp(34) else 0; marginEnd = if (role == "user") 0 else dp(18)
                topMargin = dp(8); bottomMargin = dp(4)
            })
            if (role == "user" || deleted) appendVersionPicker(target, if (deleted) "task" else "chat", turnId)
            attachments.append(target, item.optJSONArray("attachments"))
        }
        latestRun?.takeIf { displayed != null }?.let { renderConversation(it) }
        if (scrollToReply) conversationScroll.post { if (!isDestroyed) conversationScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun submitClassifiedTask(value: String, device: String, selected: Int, title: String?, contextGoal: String? = null, refs: JSONArray = JSONArray(), revision: JSONObject? = null) {
        if (device.isBlank()) { sendButton?.isEnabled = true; status.text = "请先连接并绑定设备"; selectSection("设置"); return }
        if (DoppelAccessibilityService.instance == null) { sendButton?.isEnabled = true; status.text = "请先启用无障碍服务"; selectSection("设置"); return }
        if (!taskCreation.compareAndSet(false, true)) { sendButton?.isEnabled = true; return }
        val creationGeneration = TaskControl.currentGeneration()
        val connection = gateway.captureReviewConnection()
        io.execute {
            try {
                // Older gateways reject unknown fields. Original user text remains authoritative on both paths.
                val body = ConversationIntent.taskRequest(value, device, listOf("ask", "assist", "full")[selected], title,
                    contextGoal.takeIf { connection.direct })
                if (refs.length() > 0) body.put("attachments", refs)
                val run = if (revision == null) gateway.createConversationRun(body, connection)
                    else gateway.createConversationRevisionRun(revision, body, connection)
                synchronized(draftLock) {
                    if (connection.scope == gateway.reviewScope()) {
                        if (gateway.prefs.getString("draft_goal", "").orEmpty().trim() == value)
                            gateway.prefs.edit().putString("draft_goal", "").commit()
                        submittedDraft.set(run.getString("id") to value)
                    }
                }
                TaskControl.reconcileCreatedRun(this, run, creationGeneration, connection) { reconciled, error ->
                    taskCreation.set(false)
                    if (!isDestroyed) sendButton?.isEnabled = true
                    if (connection.scope != gateway.reviewScope()) return@reconcileCreatedRun
                    if (reconciled != null) {
                        runCatching { TaskControl.wakeQueue(this) }
                        if (visible && !isFinishing && !isDestroyed && gateway.selectedConversationRun() == reconciled.optString("id")) {
                            reconcileSubmittedDraft(); displayRun(reconciled); refreshRun()
                            if (reconciled.optString("status") == "running") moveTaskToBack(true)
                        }
                    } else if (!isDestroyed) status.text = error ?: "任务已创建，请查看任务队列"
                }

            } catch (error: Exception) {
                taskCreation.set(false)
                runOnUiThread { if (!isDestroyed) {
                    sendButton?.isEnabled = true
                    if (connection.scope == gateway.reviewScope()) status.text = error.message ?: "任务创建失败"
                } }
            }
        }
    }
    private fun renderQueue() {
        val target = queueView ?: return
        target.removeAllViews()
        if (queueScope != gateway.reviewScope()) queueSnapshot = JSONArray()
        val dispatchPaused = gateway.prefs.getBoolean("queue_dispatch_paused", false)
        if (queueSnapshot.length() == 0 && !dispatchPaused) { target.visibility = View.GONE; return }
        target.visibility = View.VISIBLE
        target.addView(UiTheme.text(this, "任务队列 · ${queueSnapshot.length()}", 13f, UiTheme.muted, true))
        if (dispatchPaused) target.addView(UiTheme.command(this, "恢复队列调度") {
            try {
                check(gateway.prefs.edit().putBoolean("queue_dispatch_paused", false).commit())
                check(TaskControl.wakeQueue(this))
                status.text = "队列已恢复；暂停中的任务需单独继续"
                renderQueue(); refreshRun()
            } catch (_: Exception) { status.text = "队列尚未启动，请检查执行权限后重试" }
        })
        for (i in 0 until queueSnapshot.length()) {
            val run = queueSnapshot.optJSONObject(i) ?: continue
            val id = run.optString("id")
            val queued = run.optString("status") == "queued"
            val state = if (queued) TaskPresentation.queueLabel(run) else TaskPresentation.title(run.optString("status"))
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(UiTheme.row(this, run.optString("title").ifBlank { run.optString("goal") },
                TaskPresentation.withSourceLabel(run, state), UiIcons.play) {
                startActivity(Intent(this, TaskPanelActivity::class.java).putExtra("run_id", id))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(UiTheme.command(this, if (queued) "取消排队" else "结束任务") {
                val scope = queueScope
                if (scope != gateway.reviewScope() || !cancellingQueued.add(id)) return@command
                renderQueue()
                TaskControl.request(this, id, "cancel") { response, error ->
                    cancellingQueued.remove(id)
                    if (!isDestroyed && visible && scope == gateway.reviewScope()) {
                        if (response == null) status.text = error ?: "取消尚未确认"
                        else {
                            DeviceWorkerService.instance?.acceptEndedRun(response)
                            if (latestRun?.optString("id") == id) displayRun(response)
                        }
                        renderQueue(); refreshRun()
                    }
                }
            }.apply { isEnabled = id !in cancellingQueued }, LinearLayout.LayoutParams(-2, dp(44)))
            target.addView(row)
        }
        target.addView(UiTheme.divider(this))
    }
    private fun refreshRun() {
        if (!FirstUseConsent.isAccepted(this) || section != "任务" || polling) return
        reconcileSubmittedDraft()
        sendButton?.isEnabled = !creating && !classifying && !attachments.processing()
        val id = if (freshConversation) "" else gateway.selectedConversationRun().orEmpty()
        val connection = gateway.captureReviewConnection()
        val conversation = gateway.conversationKey()
        polling = true
        io.execute {
            try {
                val queue = if (connection.deviceId.isBlank()) JSONArray() else
                    connection.request("GET", "/devices/${connection.deviceId}/queue").optJSONArray("items") ?: JSONArray()
                val run = if (id.isBlank()) null else connection.request("GET", "/runs/$id").also { value ->
                    check(value.optString("id") == id) { "Task response does not match requested run" }
                    runCatching { connection.request("GET", "/runs/$id/conversation").optJSONArray("items") }.getOrNull()?.let { value.put("conversation_items", it) }
                }
                val recent = if (run == null) null else eventFeed.refresh(id) { after -> connection.request("GET", "/runs/$id/events?after=$after").optJSONArray("items") }
                runOnUiThread {
                    if (!visible || isFinishing || isDestroyed || connection.scope != gateway.reviewScope() || conversation != gateway.conversationKey()) return@runOnUiThread
                    queueScope = connection.scope; queueSnapshot = queue; renderQueue()
                    if (run != null && !freshConversation && gateway.selectedConversationRun() == id) {
                        runEvents = recent; eventsRunId = id; displayRun(run); startCreatedWorker(run)
                    }
                }
            } catch (_: Exception) { runOnUiThread { if (!isDestroyed && visible) status.text = "任务状态暂不可用" } }
            finally { polling = false }
        }
    }
    private var workerStartDeferred = false
    private fun startCreatedWorker(run: JSONObject) {
        val id = run.getString("id")
        val pending = pendingWorkerRun.get()
        val voicePending = gateway.prefs.getString("voice_pending_worker_run", "") == id
        if (pending != id && !voicePending) return
        val generation = if (voicePending) gateway.prefs.getLong("voice_pending_worker_generation", TaskControl.currentGeneration()) else pendingWorkerGeneration.get()
        if (!TaskControl.isCurrent(generation)) {
            if (pending == id) pendingWorkerRun.compareAndSet(pending, null)
            if (voicePending) gateway.prefs.edit().remove("voice_pending_worker_run").remove("voice_pending_worker_generation").apply()
            return
        }
        if (run.optString("status") in setOf("completed", "failed", "cancelled")) {
            if (pending == id) pendingWorkerRun.compareAndSet(pending, null)
            if (voicePending) gateway.prefs.edit().remove("voice_pending_worker_run").remove("voice_pending_worker_generation").apply()
            return
        }
        if (voicePending && run.optString("status") != "running") return
        if (!visible || isFinishing || isDestroyed || workerStartDeferred || gateway.prefs.getString("active_run", "") != id) return
        try {
            if (!TaskControl.wakeQueue(this, generation)) return
            if (pending == id) pendingWorkerRun.compareAndSet(pending, null)
            if (voicePending) gateway.prefs.edit().remove("voice_pending_worker_run").remove("voice_pending_worker_generation").apply()
        } catch (_: IllegalStateException) { workerStartDeferred = true; status.text = "服务未启动，请返回任务页重试" }
        catch (_: SecurityException) { workerStartDeferred = true; status.text = "服务权限不可用，请检查设置" }
    }
    private fun reconcileSubmittedDraft() {
        val submitted = submittedDraft.get() ?: return
        val field = goal ?: return
        synchronized(draftLock) {
            if (gateway.prefs.getString("draft_goal", "").isNullOrBlank() && field.text.toString().trim() == submitted.second) field.setText("")
        }
        submittedDraft.compareAndSet(submitted, null)
        attachments.refresh()
        refreshEditBanner()
    }
    private fun displayRun(run: JSONObject) {
        latestRun = run
        freshConversation = false
        emptyConversation?.visibility = View.GONE; conversationContent?.visibility = View.VISIBLE
        renderLocalChat()
        val state = run.optString("status")
        runTitle?.text = "Doppel · ${TaskPresentation.withSourceLabel(run, TaskPresentation.title(state))}"
        runTitle?.setTextColor(when (state) { "failed" -> UiTheme.danger; "running", "completed" -> UiTheme.green; else -> UiTheme.ink })
        runGoal?.text = run.optString("goal")
        runGoal?.let { bindMessageMenu(it, "task", run.optString("id"), true) }
        runView?.let { bindMessageMenu(it) }
        runVersions?.let { versions ->
            val stamp = run.optString("id") + displayedVersionGroups.toString()
            if (versions.tag != stamp) { versions.tag = stamp; versions.removeAllViews(); appendVersionPicker(versions, "task", run.optString("id")) }
        }
        runAttachments?.let { view ->
            val refs = run.optJSONArray("attachments")
            if (view.tag != refs?.toString().orEmpty()) {
                view.tag = refs?.toString().orEmpty(); view.removeAllViews(); attachments.append(view, refs)
            }
        }
        taskProgress?.display(run, state == "running" && gateway.prefs.getString("active_run", "") == run.optString("id") && DeviceWorkerService.instance?.isPaused != false)
        runView?.apply {
            val locallyPaused = gateway.prefs.getString("active_run", "") == run.optString("id") && DeviceWorkerService.instance?.isPaused != false
            text = TaskPresentation.detail(PauseDetails.resolve(this@ClientActivity, run, locallyPaused), locallyPaused)
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        val process = TaskPresentation.process(runEvents.takeIf { eventsRunId == run.optString("id") }, run.optString("message"))
        runProcess?.apply { text = "执行过程\n" + process.joinToString("\n") { "· $it" }; visibility = if (process.isEmpty()) View.GONE else View.VISIBLE }
        status.text = connectionSummary()
        runProgress?.visibility = if (state == "running") View.VISIBLE else View.GONE
        taskActions?.visibility = if (state in setOf("running", "queued", "paused", "awaiting_input", "awaiting_approval")) View.VISIBLE else View.GONE
        val owner = gateway.prefs.getString("active_run", "") == run.optString("id")
        val localPaused = owner && DeviceWorkerService.instance?.isPaused != false
        runProgress?.active = state == "running" && !localPaused
        if (state == "running" && localPaused) runProgress?.visibility = View.GONE
        taskActions?.let { actions ->
            actions.getChildAt(0).visibility = if (state == "running" && !localPaused) View.VISIBLE else View.GONE
            actions.getChildAt(1).visibility = if (state == "paused" || state == "running" && localPaused) View.VISIBLE else View.GONE
            (actions.getChildAt(2) as? Button)?.text = if (state == "queued") "取消排队" else "停止任务"
        }
        if (state == "running" && localPaused) runTitle?.text = "Doppel · ${TaskPresentation.withSourceLabel(run, "已暂停")}"
        val display = PauseDetails.resolve(this, run, localPaused)
        val pending = display.optJSONObject("pending_request")
        if (pending == null) { runControls?.removeAllViews(); lastPending = ""; return }
        val pendingIdentity = display.optString("status") + ":" + pending.toString()
        if (pendingIdentity == lastPending) return
        lastPending = pendingIdentity; runControls?.removeAllViews()
        val controls = runControls ?: return
        controls.addView(UiTheme.text(this, pending.optString("message"), 15f).apply { setPadding(0, dp(16), 0, dp(12)) })
        if (PausePresentation.from(display)?.showLoginSettings == true) controls.addView(UiTheme.command(this, "设置登录方式") {
            val current = latestRun?.takeIf { it.optString("id") == run.optString("id") } ?: return@command
            if (gateway.prefs.getString("active_run", "") != current.optString("id")) return@command
            val stopped = PauseDetails.resolve(this, current, DeviceWorkerService.instance?.isPaused != false)
            if (PausePresentation.from(stopped)?.showLoginSettings != true) return@command
            val open = Intent(this, LoginSettingsActivity::class.java)
            stopped.optJSONObject("pending_request")?.optString("package_name")
                ?.takeIf { it.isNotBlank() && it != packageName }?.let { open.putExtra("package_name", it) }
            startActivity(open)
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
        if (display.optString("status") == "paused") return
        val answer = UiTheme.field(this, "补充信息")
        if (pending.optString("kind") == "input") controls.addView(answer)
        val choices = if (pending.optString("kind") == "approval") listOf("批准", "拒绝") else listOf("提交")
        for (choice in choices) controls.addView(UiTheme.command(this, choice, choice != "拒绝") {
            val body = JSONObject().put("request_id", pending.getString("id"))
            if (choice == "提交") body.put("text", answer.text.toString()) else body.put("approve", choice == "批准")
            TaskControl.request(this, run.getString("id"), "answer", body) { response, error ->
                if (!visible || isDestroyed || gateway.prefs.getString("active_run", "") != run.optString("id")) return@request
                if (response == null) status.text = error ?: "回复尚未确认"
                else { displayRun(response); if (response.optString("status") == "running") TaskControl.startWorker(this) }
            }
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
    }
    private fun renderConversation(run: JSONObject) {
        val rows = conversationRows ?: return
        val items = run.optJSONArray("conversation_items") ?: JSONArray().put(run)
        val chats = gateway.conversationMessages()
        val key = items.toString() + chats.toString()
        if (key == renderedConversation) return
        renderedConversation = key; rows.removeAllViews()
        fun discussion(after: String) {
            for (i in 0 until chats.length()) {
                val item = chats.optJSONObject(i) ?: continue
                if (item.optString("after_run_id") != after) continue
                val text = item.optString("content").trim(); if (text.isBlank()) continue
                val user = item.optString("role") == "user"
                val turnId = item.optString("turn_id").ifBlank { item.optString("request_id") }
                val deleted = item.optBoolean("deleted")
                rows.addView(UiTheme.text(this, text, 15f, if (user) UiTheme.ink else UiTheme.muted).apply {
                    setLineSpacing(dp(3).toFloat(), 1f); setPadding(dp(15), dp(11), dp(15), dp(11))
                    if (user) background = UiTheme.glass(this@ClientActivity, 18)
                    if (!deleted) bindMessageMenu(this, "chat", turnId, user)
                }, LinearLayout.LayoutParams(if (user) -2 else -1, -2).apply {
                    gravity = if (user) Gravity.END else Gravity.START
                    marginStart = if (user) dp(34) else 0; marginEnd = if (user) 0 else dp(18); topMargin = dp(8); bottomMargin = dp(4)
                })
                if (user || deleted) appendVersionPicker(rows, if (deleted) "task" else "chat", turnId)
                attachments.append(rows, item.optJSONArray("attachments"))
            }
        }
        discussion("")
        for (i in 0 until items.length()) {
            val entry = items.getJSONObject(i)
            if (entry.optString("id") == run.optString("id")) continue
            val bubble = UiTheme.text(this, entry.optString("goal"), 16f).apply {
                background = UiTheme.glass(this@ClientActivity, 18); setPadding(dp(16), dp(12), dp(16), dp(12))
                bindMessageMenu(this, "task", entry.optString("id"), true)
            }
            rows.addView(bubble, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; marginStart = dp(32); topMargin = dp(12) })
            appendVersionPicker(rows, "task", entry.optString("id"))
            attachments.append(rows, entry.optJSONArray("attachments"))
            rows.addView(UiTheme.text(this, entry.optString("message"), 16f).apply { bindMessageMenu(this) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16); bottomMargin = dp(24) })
            discussion(entry.optString("id"))
            rows.addView(UiTheme.divider(this))
        }
    }
    private fun settings() {
        label("设置", 24f)
        sectionLabel("外观")
        settingsRow("显示模式", ThemeController.mode(this).label, UiIcons.device) { startActivity(Intent(this, AppearanceActivity::class.java)) }
        sectionLabel("设备连接")
        if (DirectMode.isDeveloperBuild(this)) settingsRow("模型设置", "主模型、视觉增强与供应商", UiIcons.connection) {
            startActivity(Intent(this, ModelSettingsActivity::class.java))
        }
        settingsRow("网关连接", if (DirectMode.isEnabled(this)) "当前使用手机直连" else gateway.prefs.getString("base_url", "http://10.0.2.2:8765").orEmpty(), UiIcons.connection) {
            if (DirectMode.isEnabled(this)) startActivity(Intent(this, ModelSettingsActivity::class.java)) else connectionDialog()
        }
        settingsRow("绑定本机", if (gateway.prefs.getString("device_id", "").isNullOrBlank()) "尚未绑定" else "当前设备已绑定", android.R.drawable.ic_menu_mylocation) {
            if (creating) { status.text = "任务创建中，暂不可重新绑定"; return@settingsRow }
            var installation = gateway.prefs.getString("installation_id", null)
            if (installation == null) { installation = UUID.randomUUID().toString(); gateway.prefs.edit().putString("installation_id", installation).commit() }
            val identity = installation
            async({ gateway.request("POST", "/devices", JSONObject().put("installation_id", identity).put("name", "${Build.MANUFACTURER} ${Build.MODEL}")) }) { gateway.prefs.edit().putString("device_id", it.getString("id")).apply(); status.text = "本机已绑定"; if (section == "设置") render() }
        }
        val worker = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, dp(4)) }
        worker.addView(UiTheme.command(this, "启动悬浮服务", true) { if (TaskControl.startWorker(this)) status.text = "服务已启动" }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        worker.addView(UiTheme.command(this, "停止悬浮服务") { stopService(Intent(this, DeviceWorkerService::class.java)); status.text = "服务已停止" }, LinearLayout.LayoutParams(0, dp(44), 1f)); page.addView(worker)
        sectionLabel("系统权限")
        settingsRow("自动设置权限", "一次开启屏幕操作、悬浮窗、麦克风、发送和读取通知、读取日历；首次无障碍需手动启用", android.R.drawable.ic_menu_manage) {
            startActivity(Intent(this, PermissionSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        permissionShortcut("无障碍服务", if (DoppelAccessibilityService.instance == null) "未启用 · 用于读取控件和执行操作" else "已启用", android.R.drawable.ic_menu_view) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        if (Build.VERSION.SDK_INT < 30) permissionShortcut("屏幕识别", if (LegacyScreenCaptureService.isReady) "已授权 · 可随时停止" else "授权后可识别游戏与图片界面", UiIcons.scan) {
            startActivity(Intent(this, LegacyScreenCaptureActivity::class.java))
        }
        permissionShortcut("悬浮窗", if (Settings.canDrawOverlays(this)) "已授权" else "未授权 · 用于显示执行状态", android.R.drawable.ic_menu_crop) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        permissionShortcut(BackgroundActivitySettings.title, BackgroundActivitySettings.detail, UiIcons.device) { BackgroundActivitySettings.open(this) }
        val microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val notifications = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        settingsRow("通知与麦克风权限", "通知${if (notifications) "已授权" else "未授权"} · 麦克风${if (microphone) "已授权" else "未授权"}", android.R.drawable.ic_btn_speak_now) { requestPermissions((listOf(Manifest.permission.RECORD_AUDIO) + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()).toTypedArray(), 9) }
        permissionShortcut("读取通知", if (PermissionSetupTargets.notificationAccess(this)) "已授权 · 用于读取通知与登录验证码" else "未授权 · 包含其他应用的通知内容", android.R.drawable.ic_dialog_email) {
            runCatching { startActivity(PermissionSetupActivity.notificationAccessIntent(this)) }
                .onFailure { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        settingsRow("读取日历", if (checkSelfPermission(Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED) "已授权" else "未授权 · 用于读取日程", android.R.drawable.ic_menu_my_calendar) {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 10)
        }
        sectionLabel("执行偏好")
        settingsSwitch("触屏暂停", "touch_pause") { checked -> if (!checked) DoppelAccessibilityService.instance?.setTouchGuard(false) }
        settingsSwitch("自动播报结果", "completion_speech") {}
        settingsRow("离线中文语音识别", "内置中文模型 · 无需联网", android.R.drawable.ic_btn_speak_now) { startActivity(Intent(this, SpeechSettingsActivity::class.java)) }
        settingsRow("长期记忆", "聊天中的纠错与偏好 · 可编辑和删除", UiIcons.edit) { startActivity(Intent(this, LongTermMemoryActivity::class.java)) }
        settingsRow("登录设置", "账号密码或短信验证码 · 4 位 PIN 保护", UiIcons.lock) { startActivity(Intent(this, LoginSettingsActivity::class.java)) }
        settingsRow("视觉增强", "在模型设置中选择独立视觉模型", UiIcons.scan) { startActivity(Intent(this, ModelSettingsActivity::class.java)) }
        settingsRow("支付授权", if (PaymentConsent(this).isEnabledForSettings()) "允许代为支付" else "未开启，付款由你确认", android.R.drawable.ic_lock_lock) { startActivity(Intent(this, PaymentSettingsActivity::class.java)) }
        sectionLabel("扩展与数据")
        settingsRow("账号与服务器", "登录账号、管理服务器连接与密码", UiIcons.account) {
            startActivity(Intent(this, CloudAccountActivity::class.java))
        }
        settingsRow("PC 连接", "在同一网络中连接电脑，由手机完成操作", UiIcons.connection) {
            startActivity(Intent(this, PcConnectionActivity::class.java))
        }
            settingsRow("扩展服务", "服务连接与工具权限", UiIcons.connection) { startActivity(Intent(this, ExtensionSettingsActivity::class.java)) }
            settingsRow("自动触发", "出现指定控件时执行任务或动作", UiIcons.scan) { startActivity(Intent(this, AutoTriggerSettingsActivity::class.java)) }
        settingsRow("定时任务", "安排稍后执行的手机任务", UiIcons.history) { startActivity(Intent(this, ScheduleActivity::class.java)) }
        settingsRow("数据保存设置", "任务截图保留时间", android.R.drawable.ic_menu_recent_history) {
            async({ gateway.request("GET", "/data-retention") }) { config ->
                val days = listOf(1, 7, 30, 90, 0)
                UiDialog.Builder(this).setTitle("截图保存时间").setSingleChoiceItems(arrayOf("1 天", "7 天", "30 天", "90 天", "长期保存"), days.indexOf(config.optInt("days", 7))) { dialog, which ->
                    async({ gateway.request("PATCH", "/data-retention", JSONObject().put("days", days[which])) }) { status.text = "保存时间已更新" }; dialog.dismiss()
                }.setNegativeButton("关闭", null).show()
            }
        }
        settingsRow("清理本机文档副本", "清理已下载的本地副本", android.R.drawable.ic_menu_delete) { async({ gateway.clearDocumentCache(); JSONObject() }) { status.text = "本机文档副本已清理" } }
        sectionLabel("帮助与隐私")
        settingsRow("新手教程", "连接、权限与悬浮入口练习", UiIcons.play) { startActivity(Intent(this, OnboardingActivity::class.java)) }
        settingsRow("使用条款", FirstUseConsent.TERMS_VERSION, UiIcons.files) { startActivity(Intent(this, LegalActivity::class.java)) }
        settingsRow("隐私说明", "数据处理与本机同意记录", UiIcons.lock) { startActivity(Intent(this, LegalActivity::class.java).putExtra("privacy", true)) }
    }
    private fun settingsSwitch(title: String, key: String, changed: (Boolean) -> Unit) {
        page.addView(UiTheme.toggle(this, title, gateway.prefs.getBoolean(key, true)) { checked ->
            gateway.prefs.edit().putBoolean(key, checked).apply(); changed(checked)
        }); page.addView(UiTheme.divider(this))
    }
    private fun connectionDialog() {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(8)) }
        val base = UiTheme.field(this, "服务地址", gateway.prefs.getString("base_url", "http://10.0.2.2:8765").orEmpty()).apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI; setSingleLine() }; fields.addView(base)
        val token = if (showTokenSetting) UiTheme.field(this, "网关令牌", gateway.prefs.getString("token", "").orEmpty(), true).also { fields.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }) } else null
        val dialog = UiDialog.Builder(this).setTitle("网关连接").setView(fields).setNegativeButton("取消", null).setPositiveButton("保存连接", null).create()
        dialog.setOnShowListener { dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            if (creating) { status.text = "任务创建中，暂不可更换连接"; return@setOnClickListener }
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
    @Deprecated("Platform callback") override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (!trustedInstallation) return
        if (attachments.result(requestCode, resultCode, data)) return
        if (requestCode == 70) {
            onboardingOpen = false
            if (resultCode != RESULT_OK || !FirstUseConsent.isAccepted(this)) { finishAndRemoveTask(); return }
            render(); return
        }
    }

    private fun history() {
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(UiTheme.text(this, "记录", 24f, UiTheme.ink, true), LinearLayout.LayoutParams(0, dp(48), 1f))
        toolbar.addView(UiTheme.icon(this, android.R.drawable.ic_popup_sync, "刷新记录") { page.removeAllViews(); history() }, LinearLayout.LayoutParams(dp(44), dp(44))); page.addView(toolbar)
        val chats = gateway.savedConversations()
        if (chats.length() > 0) {
            sectionLabel("对话")
            repeat(chats.length()) { index ->
                val chat = chats.getJSONObject(index)
                settingsRow(chat.optString("title").ifBlank { "未命名对话" }, "继续对话", UiIcons.account) {
                    if (creating || classifying || attachments.processing()) { status.text = "正在处理消息或附件，请稍候"; return@settingsRow }
                    val active = gateway.prefs.getString("active_run", "").orEmpty()
                    if (active.isNotBlank() && active != chat.optString("tail")) { status.text = "请先结束当前任务，再切换对话"; return@settingsRow }
                    gateway.selectConversation(chat.getString("key"))
                    latestRun = null; runEvents = null; eventsRunId = ""; freshConversation = chat.optString("tail").isBlank()
                    selectSection("任务")
                }
            }
        }
        sectionLabel("最近任务")
        async({ gateway.request("GET", "/runs") }) { response ->
            if (section != "记录") return@async
            val items = response.optJSONArray("items") ?: return@async
            if (items.length() == 0) emptyState("暂无任务记录", android.R.drawable.ic_menu_recent_history)
            val states = mapOf("queued" to "排队中", "running" to "执行中", "paused" to "已暂停", "awaiting_approval" to "等待批准", "awaiting_input" to "等待补充", "completed" to "已完成", "failed" to "未完成", "cancelled" to "已取消")
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val title = item.optString("title").ifBlank { item.optString("goal") }.ifBlank { "未命名任务" }
                settingsRow(title, TaskPresentation.withSourceLabel(item, states[item.optString("status")] ?: item.optString("status")), android.R.drawable.ic_menu_recent_history) { runHistoryMenu(item) }
            }
        }
    }
    private fun emptyState(title: String, icon: Int) {
        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(20), dp(56), dp(20), dp(56))
            addView(ImageView(this@ClientActivity).apply { setImageResource(UiIcons.resolve(icon)); UiTheme.bind(this) { imageTintList = ColorStateList.valueOf(UiTheme.ink) } }, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(UiTheme.text(this@ClientActivity, title, 14f, UiTheme.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0) })
        }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun runHistoryMenu(run: JSONObject) {
        val id = run.getString("id")
        val displayTitle = run.optString("title").ifBlank { run.optString("goal") }.ifBlank { "未命名任务" }
        UiDialog.Builder(this).setTitle(displayTitle).setItems(arrayOf("任务过程", "打开对话", "截图", "删除任务")) { _, choice ->
            when(choice) {
                0 -> async({ JSONObject().put("run", gateway.request("GET", "/runs/$id")).put("items", TaskEventFeed().refresh(id) { after -> gateway.request("GET", "/runs/$id/events?after=$after").optJSONArray("items") }) }) { events ->
                    val list = events.optJSONArray("items")
                    val history = TaskPresentation.process(list)
                    val text = if (history.isEmpty()) "还没有可展示的执行过程" else history.joinToString("\n\n") { "· $it" }
                    val content = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TaskProgressView(this@ClientActivity).apply { display(events.optJSONObject("run") ?: run, true) })
                        addView(UiTheme.text(this@ClientActivity, text, 14f).apply { setPadding(0, dp(14), 0, 0); setTextIsSelectable(true) })
                    }
                    UiDialog.Builder(this).setTitle("任务过程").setView(ScrollView(this).apply { addView(content) }).setPositiveButton("关闭", null).show()
                }
                1 -> openTaskChat(run)
                2 -> screenshotList(id)
                3 -> UiDialog.Builder(this).setTitle("删除任务和截图？").setMessage(run.optString("goal")).setNegativeButton("返回", null).setPositiveButton("删除") { _, _ ->
                    async({ val result = gateway.request("DELETE", "/runs/$id"); ResultStore(this).use { it.erase(id) }; PauseDetails.clear(this, id); gateway.clearDocumentCache(); result }) {
                        synchronized(gateway.prefs) {
                            val edit = gateway.prefs.edit()
                            if (gateway.prefs.getString("active_run", "") == id) edit.remove("active_run")
                            val receipt = runCatching { JSONObject(gateway.prefs.getString("last_result", "{}").orEmpty()).optString("id") }.getOrDefault("")
                            if (receipt == id) edit.remove("last_result")
                            edit.apply()
                        }
                        getSystemService(android.app.NotificationManager::class.java).cancel(id.hashCode())
                        status.text = "任务已删除"; if (section == "记录") { page.removeAllViews(); history() }
                    }
                }.show()
            }
        }.setNegativeButton("关闭", null).show()
    }
    private fun permissionShortcut(title: String, detail: String, icon: Int, action: () -> Unit) {
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(7), 0, dp(7)) }
        block.addView(UiTheme.row(this, title, detail, icon, action))
        block.addView(UiTheme.command(this, "立即打开系统设置", false, action).apply {
            contentDescription = "打开${title}系统设置"
        }, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(4) })
        page.addView(block); page.addView(UiTheme.divider(this))
    }

    private fun openTaskChat(run: JSONObject) {
        runCatching {
            saveDraft()
            gateway.selectTaskConversation(run)
            freshConversation = false
            latestRun = null
            selectSection("任务")
            goal?.requestFocus()
        }.onFailure { status.text = it.message ?: "对话未能打开" }
    }

    private fun screenshotList(run: String) {
        async({ gateway.request("GET", "/runs/$run/screenshots") }) { response ->
            val items = response.optJSONArray("items")
            if (items == null || items.length() == 0) {
                UiDialog.Builder(this).setTitle("任务截图").setMessage("这个任务还没有保存的截图。截图会在任务读取屏幕时产生，可在这里查看和删除。").setPositiveButton("知道了", null).show(); return@async
            }
            UiDialog.Builder(this).setTitle("任务截图 · ${items.length()} 张").setItems(Array(items.length()) {
                val item = items.getJSONObject(it)
                val dimensions = if (item.optInt("width") > 0 && item.optInt("height") > 0) "${item.optInt("width")} × ${item.optInt("height")}" else if (item.optInt("size") > 0) "${item.optInt("size") / 1024} KB" else "查看画面"
                "截图 ${it + 1} · $dimensions"
            }) { _, index ->
                val item = items.getJSONObject(index); val imageId = item.getString("id")
                val path = "/runs/$run/screenshots/$imageId"
                status.text = "正在读取截图"
                io.execute {
                    try {
                        val bytes = gateway.image(item.optString("url").ifBlank { path })
                        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        check(options.outWidth > 0 && options.outHeight > 0) { "Invalid image" }
                        options.inJustDecodeBounds = false; options.inSampleSize = 1
                        while (options.outWidth.toLong() * options.outHeight / options.inSampleSize / options.inSampleSize > 4_000_000) options.inSampleSize *= 2
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Invalid image")
                        runOnUiThread {
                            if (!visible || isFinishing || isDestroyed) { bitmap.recycle(); return@runOnUiThread }
                            val view = ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
                            val fitViewport = android.view.ViewTreeObserver.OnGlobalLayoutListener {
                                val viewport = (view.parent as? View)?.parent as? ScrollView
                                val maximum = viewport?.height?.minus(dp(24)) ?: 0
                                if (maximum > 0 && view.width > 0) {
                                    val fittedHeight = minOf(maximum, (view.width.toLong() * bitmap.height / bitmap.width).toInt()).coerceAtLeast(1)
                                    if (view.layoutParams.height != fittedHeight) view.layoutParams = view.layoutParams.apply { height = fittedHeight }
                                }
                            }
                            val dialog = UiDialog.Builder(this).setTitle("截图 ${index + 1} / ${items.length()}").setView(view).setPositiveButton("关闭", null).setNegativeButton("删除") { _, _ ->
                                async({ val result = gateway.request("DELETE", path); ResultStore(this).use { it.erase(run, setOf(item.optString("command_id", imageId))) }; result }) { status.text = "截图已删除" }
                            }.create()
                            dialog.setOnDismissListener {
                                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(fitViewport)
                                view.setImageDrawable(null); bitmap.recycle()
                            }
                            view.viewTreeObserver.addOnGlobalLayoutListener(fitViewport); dialog.show()
                        }
                    } catch (_: Exception) { runOnUiThread { if (visible && !isFinishing && !isDestroyed) UiDialog.Builder(this).setTitle("截图暂不可用").setMessage("画面可能已被清理，或服务暂时无法连接。返回后可重新打开截图列表。").setPositiveButton("返回", null).show() } }
                }
            }.setNegativeButton("关闭", null).show()
        }
    }
    private fun openFirstUse() {
        if (onboardingOpen || isFinishing) return
        onboardingOpen = true
        startActivityForResult(Intent(this, OnboardingActivity::class.java), 70)
    }
    override fun onResume() {
        super.onResume(); if (!trustedInstallation) return
        ThemeController.refreshSystem(this)
        if (!FirstUseConsent.isAccepted(this) || FirstUseConsent.needsGuide(this)) { visible = false; openFirstUse(); return }
        visible = true; workerStartDeferred = false; goal?.setText(gateway.prefs.getString("draft_goal", ""))
        attachments.refresh()
        if (section == "设置") render()
        status.text = connectionSummary(); handler.post(poll)
    }
    @Deprecated("Platform callback") override fun onBackPressed() { if (section != "任务") selectSection("任务") else super.onBackPressed() }
    override fun onSaveInstanceState(outState: Bundle) {
        if (trustedInstallation) { saveDraft(); attachments.save(outState); outState.putString("section", section); outState.putBoolean("onboarding_open", onboardingOpen); outState.putBoolean("fresh_conversation", freshConversation) }
        super.onSaveInstanceState(outState)
    }
    override fun onPause() { visible = false; handler.removeCallbacks(poll); if (trustedInstallation) saveDraft(); super.onPause() }
    override fun onDestroy() { navigationDialog?.dismiss(); handler.removeCallbacksAndMessages(null); io.shutdown(); super.onDestroy() }
}
