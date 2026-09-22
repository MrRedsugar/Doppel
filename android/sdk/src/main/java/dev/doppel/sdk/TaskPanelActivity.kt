package dev.doppel.sdk

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.json.JSONObject
import java.util.concurrent.Executors

/** Task controls over the user's previous app, sharing the existing broker contract. */
class TaskPanelActivity : Activity() {
    companion object {
        const val EXTRA_PAUSE_ON_OPEN = "pause_on_open"
        @Volatile var isVisible = false; private set
    }
    private lateinit var gateway: Gateway
    private lateinit var title: TextView
    private lateinit var summary: TextView
    private lateinit var goal: TextView
    private lateinit var processSummary: TextView
    private lateinit var input: EditText
    private lateinit var pending: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var progress: UiActivitySignal
    private lateinit var taskProgress: TaskProgressView
    private lateinit var resume: Button
    private lateinit var pause: Button
    private lateinit var stop: Button
    private lateinit var newTask: Button
    private lateinit var details: ImageButton
    private lateinit var headingTools: LinearLayout
    private lateinit var headingIcon: ImageView
    private lateinit var close: ImageButton
    private lateinit var handle: View
    private var pauseLayout = false
    private var expandedControls: Boolean? = null
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var visible = false
    private var polling = false
    private var busy = false
    private var current: JSONObject? = null
    private var requestId = ""
    private var runId = ""
    private var initialText = ""
    private var events: org.json.JSONArray? = null
    private val eventFeed = TaskEventFeed()
    private val poll = object : Runnable { override fun run() { if (visible) { refresh(); handler.postDelayed(this, 1500) } } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gateway = Gateway(this)
        if (!FirstUseConsent.allowEntry(this)) return
        runId = savedInstanceState?.getString("run_id") ?: intent.getStringExtra("run_id")
            ?: gateway.prefs.getString("active_run", "").orEmpty().ifBlank {
                runCatching { JSONObject(gateway.prefs.getString("last_result", "{}").orEmpty()).optString("id") }.getOrDefault("")
            }
        if (intent.getStringExtra("pending_input").isNullOrBlank() && DeviceWorkerService.instance?.revealPauseControls(runId) == true) {
            closePanel(); return
        }
        initialText = savedInstanceState?.getString("pending_input") ?: intent.getStringExtra("pending_input") ?: gateway.prefs.getString("companion_draft_$runId", "").orEmpty()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = UiTheme.glass(this@TaskPanelActivity, 28); clipToOutline = true }
        UiTheme.window(this, root)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(12), dp(22), 0) }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, -2, 1f))
        setContentView(root); UiTheme.styleSheet(window)
        setFinishOnTouchOutside(false)
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(-1, -2)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        handle = View(this).apply { background = UiTheme.surface(this@TaskPanelActivity, UiTheme.line) }
        content.addView(handle, LinearLayout.LayoutParams(dp(32), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        headingIcon = ImageView(this).apply {
            setImageResource(UiIcons.sparkles)
            UiTheme.bind(this) { imageTintList = android.content.res.ColorStateList.valueOf(UiTheme.ink) }
        }
        heading.addView(headingIcon, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
        title = UiTheme.text(this, "Doppel", 18f, UiTheme.ink, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        heading.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        headingTools = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(headingTools)
        close = UiTheme.icon(this, UiIcons.close, "收起任务面板") { closePanel() }
        heading.addView(close, LinearLayout.LayoutParams(dp(44), dp(44)))
        content.addView(heading)
        goal = UiTheme.text(this, "", 16f, UiTheme.ink, true).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(12), 0, dp(10)) }
        content.addView(goal)
        taskProgress = TaskProgressView(this)
        content.addView(taskProgress, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        summary = UiTheme.text(this, "正在连接", 15f, UiTheme.ink).apply { maxLines = 8; ellipsize = android.text.TextUtils.TruncateAt.END; setTextIsSelectable(true); setLineSpacing(dp(4).toFloat(), 1f) }
        content.addView(summary)
        processSummary = UiTheme.text(this, "", 13f, UiTheme.muted).apply { visibility = View.GONE; maxLines = 8; setLineSpacing(dp(4).toFloat(), 1f); setPadding(0, dp(16), 0, dp(4)) }
        content.addView(processSummary)
        progress = UiActivitySignal(this)
        heading.addView(progress, 2, LinearLayout.LayoutParams(dp(18), dp(18)))
        pending = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; content.addView(pending)
        input = UiTheme.field(this, "补充信息", initialText).apply {
            minLines = 2; maxLines = 4; visibility = View.GONE
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        content.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(22), dp(14), dp(22), dp(20)) }
        newTask = UiTheme.command(this, "新任务") { openNewTask() }.apply { contentDescription = "新任务" }
        details = UiTheme.icon(this, UiIcons.history, "查看任务详情") {
            saveInput(); startActivity(packageManager.getLaunchIntentForPackage(packageName)); closePanel()
        }
        pause = UiTheme.command(this, "暂停执行", true) { control("pause") }.apply { contentDescription = "暂停任务"; tooltipText = "暂停任务"; visibility = View.GONE }
        resume = UiTheme.command(this, "继续执行", true) { control("resume") }.apply { contentDescription = "继续任务"; tooltipText = "继续执行"; visibility = View.GONE }
        stop = UiTheme.command(this, "停止任务") { control("cancel") }.apply { contentDescription = "停止任务"; tooltipText = "停止任务"; visibility = View.GONE }
        layoutTaskControls(resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
        root.addView(controls, LinearLayout.LayoutParams(-1, -2))
        var compactEditor = false
        root.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val compact = input.hasFocus() && bottom - top - root.paddingTop - root.paddingBottom < dp(180)
            val viewport = Rect().also { root.getWindowVisibleDisplayFrame(it) }
            val expanded = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                viewport.width() >= dp(320) && viewport.height() >= dp(360) &&
                !(input.hasFocus() && bottom - top - root.paddingTop - root.paddingBottom < dp(300))
            layoutTaskControls(expanded)
            if (compact != compactEditor) {
                compactEditor = compact
                controls.setPadding(dp(22), dp(if (compact) 4 else 14), dp(22), dp(if (compact) 4 else 20))
                input.minLines = if (compact) 1 else 2
                input.maxLines = if (compact) 1 else 4
                input.minHeight = dp(if (compact) 28 else 48)
                input.setPadding(dp(12), dp(if (compact) 4 else 12), dp(12), dp(if (compact) 4 else 12))
                input.setHorizontallyScrolling(compact)
                input.post { if (input.hasFocus()) input.requestRectangleOnScreen(Rect(0, 0, input.width, input.height), true) }
            }
        }
        if (ValueAnimator.areAnimatorsEnabled()) { root.alpha = 0f; root.translationY = dp(14).toFloat(); root.animate().alpha(1f).translationY(0f).setDuration(180).start() }
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_PAUSE_ON_OPEN, false) && runId.isNotBlank()) control("pause")
    }

    private fun layoutTaskControls(expanded: Boolean) {
        if (expandedControls == expanded) return
        expandedControls = expanded
        controls.removeAllViews(); headingTools.removeAllViews()
        if (pauseLayout) {
            stop.text = "结束"; resume.text = "继续"
            listOf(stop, resume).forEach { button ->
                button.setCompoundDrawablesRelative(null, null, null, null)
                controls.addView(button, LinearLayout.LayoutParams(0, dp(52), 1f).apply { if (button === resume) marginStart = dp(10) })
            }
            return
        }
        headingTools.addView(details, LinearLayout.LayoutParams(dp(44), dp(44)))
        listOf(Triple(pause, UiIcons.pause, "暂停执行"), Triple(resume, UiIcons.play, "继续执行"), Triple(stop, UiIcons.close, "停止任务")).forEach { (button, icon, label) ->
            val drawable = getDrawable(icon)!!.mutate().apply { setBounds(0, 0, dp(20), dp(20)); setTint(if (button === stop) UiTheme.ink else android.graphics.Color.WHITE) }
            button.text = label
            button.setCompoundDrawablesRelative(if (expanded && resources.displayMetrics.widthPixels >= dp(420)) drawable else null, null, null, null)
            button.compoundDrawablePadding = dp(6)
            button.gravity = Gravity.CENTER
            button.setPadding(dp(8), 0, dp(8), 0)
            controls.addView(button, LinearLayout.LayoutParams(0, dp(if (expanded) 52 else 48), 1f).apply { if (button === stop) marginStart = dp(8) })
        }
        newTask.setPadding(dp(8), 0, dp(8), 0)
        controls.addView(newTask, LinearLayout.LayoutParams(0, dp(if (expanded) 52 else 48), 1f).apply { marginStart = dp(8) })
    }

    private fun refresh() {
        if (polling || busy) return
        if (runId.isBlank()) { showIdle(); return }
        polling = true
        val requestedRun = runId
        io.execute {
            var run: JSONObject? = null
            var error: String? = null
            try { run = gateway.request("GET", "/runs/$requestedRun") } catch (failure: Exception) { error = failure.message }
            val recent = if (run != null) eventFeed.refresh(requestedRun) { after -> gateway.request("GET", "/runs/$requestedRun/events?after=$after").optJSONArray("items") } else null
            runOnUiThread {
                polling = false
                if (!visible || busy || runId != requestedRun) return@runOnUiThread
                if (run != null) { if (recent != null) events = recent; render(run!!) } else {
                    val saved = PauseDetails.cached(this, requestedRun)
                    if (saved != null) {
                        current?.optString("goal")?.let { saved.put("goal", it) }
                        render(saved)
                        summary.append("\n\n暂时无法读取任务服务，以上为本机保存的暂停原因。")
                    } else { progress.visibility = View.INVISIBLE; summary.text = error ?: "连接中断" }
                    resume.isEnabled = false
                }
            }
        }
    }

    private fun showIdle() {
        title.text = "Doppel"; goal.text = "现在想做什么？"; summary.text = ""; taskProgress.display(null)
        progress.visibility = View.GONE; processSummary.visibility = View.GONE; pending.removeAllViews()
        pause.visibility = View.GONE; resume.visibility = View.GONE; stop.visibility = View.GONE
        if (initialText.isNotBlank()) { input.visibility = View.VISIBLE; summary.text = "补充草稿已保留" }
    }

    private fun render(run: JSONObject) {
        current = run
        val state = run.optString("status")
        title.text = TaskPresentation.withSourceLabel(run, TaskPresentation.title(state))
        goal.text = run.optString("goal")
        val ownerPaused = gateway.prefs.getString("active_run", "") == runId && DeviceWorkerService.instance?.isPaused != false
        val display = PauseDetails.resolve(this, run, ownerPaused)
        val locallyStopped = state == "running" && ownerPaused
        taskProgress.display(run, locallyStopped)
        val pauseInfo = PausePresentation.from(display) ?: if (locallyStopped) PausePresentation.from(JSONObject(display.toString()).put("status", "paused").put("message", "已暂停")) else null
        val pausedSurface = pauseInfo != null
        if (pauseLayout != pausedSurface) { pauseLayout = pausedSurface; expandedControls = null; layoutTaskControls(resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) }
        headingIcon.setImageResource(if (pausedSurface) UiIcons.pause else UiIcons.sparkles)
        close.visibility = if (pausedSurface) View.GONE else View.VISIBLE
        handle.visibility = if (pausedSurface) View.GONE else View.VISIBLE
        summary.maxLines = if (display.optString("status") == "paused") Int.MAX_VALUE else 8
        summary.text = pauseInfo?.surfaceDetail ?: TaskPresentation.detail(display, false)
        val process = TaskPresentation.process(events, run.optString("message"))
        processSummary.text = if (process.isEmpty()) "" else "执行过程\n" + process.joinToString("\n") { "· $it" }
        processSummary.visibility = if (process.isEmpty() || pausedSurface) View.GONE else View.VISIBLE
        title.setTextColor(if (state == "failed") UiTheme.danger else UiTheme.ink)
        progress.visibility = if (state == "running") View.VISIBLE else View.INVISIBLE
        val active = state !in setOf("completed", "failed", "cancelled")
        newTask.text = if (active) "说件新事" else "继续说"
        val localPaused = gateway.prefs.getString("active_run", "") == runId && DeviceWorkerService.instance?.isPaused != false
        progress.active = state == "running" && !localPaused
        if (localPaused) progress.visibility = View.INVISIBLE
        if (pauseInfo != null) title.text = TaskPresentation.withSourceLabel(run, if (pauseInfo.userInitiated) "任务待续" else pauseInfo.category)
        pause.visibility = if (state == "running" && !localPaused) View.VISIBLE else View.GONE
        resume.visibility = if (state == "paused" || state == "running" && localPaused) View.VISIBLE else View.GONE
        stop.visibility = if (active) View.VISIBLE else View.GONE
        stop.text = if (state == "queued") "取消排队" else "停止任务"
        for (button in listOf(pause, resume, stop, newTask)) button.isEnabled = !busy
        val request = display.optJSONObject("pending_request")
        val identity = display.optString("status") + ":" + request?.toString().orEmpty()
        if (identity == requestId) return
        requestId = identity; pending.removeAllViews()
        val editable = request?.optString("kind") == "input" && !request.optBoolean("manual_only") && display.optString("status") == "awaiting_input"
        input.visibility = if (editable || input.text.isNotBlank()) View.VISIBLE else View.GONE
        if (request != null) {
            val explanation = request.optString("message")
            if (explanation.isNotBlank() && !summary.text.toString().contains(explanation)) pending.addView(UiTheme.text(this, explanation, 14f).apply { setPadding(0, dp(12), 0, dp(8)) })
            if (pauseInfo?.showLoginSettings == true) pending.addView(UiTheme.command(this, "设置登录方式") {
                if (busy || gateway.prefs.getString("active_run", "") != runId) return@command
                val latest = current ?: return@command
                val stopped = PauseDetails.resolve(this, latest, DeviceWorkerService.instance?.isPaused != false)
                if (PausePresentation.from(stopped)?.showLoginSettings != true) return@command
                saveInput()
                val open = Intent(this, LoginSettingsActivity::class.java)
                stopped.optJSONObject("pending_request")?.optString("package_name")
                    ?.takeIf { it.isNotBlank() && it != packageName }?.let { open.putExtra("package_name", it) }
                startActivity(open)
            }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
            if (display.optString("status") == "awaiting_approval") {
                for ((name, allow) in listOf("批准" to true, "拒绝" to false)) pending.addView(UiTheme.command(this, name, allow) {
                    control("answer", JSONObject().put("request_id", request.getString("id")).put("approve", allow))
                }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
            } else if (display.optString("status") == "awaiting_input") {
                val manual = request.optBoolean("manual_only")
                pending.addView(UiTheme.command(this, if (manual) "已手动处理，继续" else "提交补充", true) {
                    val answer = if (manual) "用户确认已在手机上手动处理，请重新观察当前页面" else input.text.toString().trim()
                    if (answer.isBlank()) { input.error = "请输入补充信息"; return@command }
                    control("answer", JSONObject().put("request_id", request.getString("id")).put("text", answer))
                }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
            }
        }
        if (!editable && input.text.isNotBlank()) pending.addView(UiTheme.text(this, "补充草稿已保留，尚未发送", 12f, UiTheme.muted).apply { setPadding(0, dp(10), 0, 0) })
    }

    private fun openNewTask() {
        saveInput()
        val draft = gateway.prefs.getString("draft_goal", "").orEmpty()
        NewTaskEntry.open(this, gateway, draft) {
            if (!visible || isFinishing || isDestroyed) return@open
            initialText = ""; input.setText("")
            gateway.prefs.edit().remove("companion_draft_$runId").apply()
            startActivity(Intent(this, VoiceActivity::class.java).putExtra(VoiceActivity.EXTRA_OPEN_KEYBOARD, true)
                .putExtra("initial_text", draft).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            closePanel()
        }
    }

    private fun control(action: String, body: JSONObject = JSONObject()) {
        if (action !in setOf("pause", "cancel") && !FirstUseConsent.allowEntry(this)) return
        if (busy || runId.isBlank()) return
        busy = true
        controls.isEnabled = false
        for (button in listOf(pause, resume, stop, newTask)) button.isEnabled = false
        for (i in 0 until pending.childCount) pending.getChildAt(i).isEnabled = false
        summary.text = when (action) { "cancel" -> "正在停止"; "pause" -> "正在暂停"; else -> "正在确认" }
        TaskControl.request(this, runId, action, body) { run, error ->
            busy = false
            if (!visible) return@request
            controls.isEnabled = true; requestId = ""
            if (run != null) {
                if (action == "answer") { input.setText(""); gateway.prefs.edit().remove("companion_draft_$runId").apply() }
                if (action == "cancel" && TaskPresentation.terminal(run.optString("status"))) DeviceWorkerService.instance?.acceptEndedRun(run)
                render(run)
                if (action == "cancel" && TaskPresentation.terminal(run.optString("status"))) closePanel()
                if (run.optString("status") == "running" && action in setOf("resume", "answer")) {
                    startWorker()
                }
            } else { current?.let { render(it) }; summary.text = error ?: "请求未确认，执行保持暂停" }
        }
    }

    private fun startWorker() {
        if (!visible || isFinishing || isDestroyed || runId.isBlank()) return
        try { if (TaskControl.startWorker(this)) closePanel() }
        catch (_: Exception) { summary.text = "执行服务未启动，请重新检查权限" }
    }

    private fun saveInput() { if (::input.isInitialized) gateway.prefs.edit().putString("companion_draft_$runId", input.text.toString()).apply() }
    private fun closePanel() { if (isTaskRoot) finishAndRemoveTask() else finish() }
    private fun dp(value: Int) = UiTheme.dp(this, value)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (!FirstUseConsent.allowEntry(this)) return
        val active = intent.getStringExtra("run_id") ?: gateway.prefs.getString("active_run", "").orEmpty().ifBlank {
            runCatching { JSONObject(gateway.prefs.getString("last_result", "{}").orEmpty()).optString("id") }.getOrDefault("")
        }
        if (intent.getStringExtra("pending_input").isNullOrBlank() && DeviceWorkerService.instance?.revealPauseControls(active) == true) { closePanel(); return }
        if (active != runId) {
            saveInput(); runId = active; input.setText(gateway.prefs.getString("companion_draft_$runId", "").orEmpty()); current = null; events = null; requestId = ""; pending.removeAllViews()
        }
        intent.getStringExtra("pending_input")?.let {
            initialText = it; input.setText(it); saveInput()
            if (it.isNotBlank()) input.visibility = View.VISIBLE
        }
        if (intent.getBooleanExtra(EXTRA_PAUSE_ON_OPEN, false) && runId.isNotBlank()) control("pause")
    }
    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        if (!FirstUseConsent.allowEntry(this)) return
        visible = true; isVisible = true; busy = false; handler.post(poll)
    }
    override fun onPause() { visible = false; isVisible = false; handler.removeCallbacks(poll); saveInput(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("run_id", runId); outState.putString("pending_input", if (::input.isInitialized) input.text.toString() else initialText); super.onSaveInstanceState(outState) }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); io.shutdown(); super.onDestroy() }
}
