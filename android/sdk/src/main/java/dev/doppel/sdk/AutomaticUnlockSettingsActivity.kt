package dev.doppel.sdk

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** Saved credentials are never displayed or sent through task/model state. */
class AutomaticUnlockSettingsActivity : Activity() {
    private var input: EditText? = null
    private var firstEntry: AutomaticUnlockCredentials.Credential? = null
    private var pending: AutomaticUnlockCredentials.Credential? = null
    private var kind = AutomaticUnlockCredentials.Kind.PIN
    private var editing = false
    private var authentication: CancellationSignal? = null
    private var resumed = false
    private var redrawOnResume = false
    private val main = Handler(Looper.getMainLooper())
    private val timeout = Runnable { cancelAuthentication(); toast("验证超时，设置未修改"); render() }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        UiTheme.init(this)
        editing = !AutomaticUnlockCredentials.hasSaved(this)
        render()
    }

    override fun onResume() {
        super.onResume(); resumed = true
        DirectMode.enterSettings(this); DeviceWorkerService.instance?.suspendLocally()
        if (redrawOnResume && authentication == null) { redrawOnResume = false; render() }
    }
    override fun onPause() {
        resumed = false; wipeDraft(); redrawOnResume = true
        if (authentication == null) DirectMode.leaveSettings(this)
        super.onPause()
    }
    override fun onDestroy() { wipeDraft(); cancelAuthentication(); DirectMode.leaveSettings(this); super.onDestroy() }

    private fun render() {
        if (isFinishing || isDestroyed) return
        input?.text?.clear(); input = null
        val saved = AutomaticUnlockCredentials.hasSaved(this)
        val enabled = AutomaticUnlockCredentials.isEnabled(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(UiTheme.background) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(24), dp(6)) }
        header.addView(UiTheme.icon(this, UiIcons.back, "返回") {
            wipeDraft()
            if (editing && saved) { editing = false; render() } else finish()
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(UiTheme.text(this, "自动任务解锁", 18f, UiTheme.ink, true)); root.addView(header)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(32)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        UiTheme.window(this, root); setContentView(root)
        fun command(label: String, primary: Boolean = false, action: () -> Unit) {
            body.addView(UiTheme.command(this, label, primary) { if (authentication == null) action() }.apply {
                filterTouchesWhenObscured = true
            }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
        }
        note(body, when { enabled -> "已开启 · 更新密码必须重新验证设备身份"; saved -> "已停用，等待用户检查"; else -> "未设置 · 默认关闭 · 不需要关闭系统锁屏密码" })
        if (saved && !enabled) note(body, "密码仍在本机加密保留。请先手动解锁手机，确认系统密码是否变更，再确认原密码重新启用，或更新保存的密码。停用期间不会自动尝试解锁。")
        if (!editing) {
            if (enabled) command("停用自动解锁") {
                runCatching { AutomaticUnlockCredentials.suspend(this) }.onSuccess { render() }.onFailure { toast("停用失败，请重试") }
            }
            if (saved && !enabled) command("确认原密码并重新启用", true) { authenticate(null) }
            command(if (saved) "更新密码" else "设置锁屏密码", !saved) { wipeDraft(); editing = true; render() }
            if (saved) command("删除保存的密码") {
                runCatching { AutomaticUnlockCredentials.clear(this) }.onSuccess { toast("已删除保存的密码"); render() }.onFailure { toast("删除失败，请重试") }
            }
        }
        if (editing) {
            when {
                Build.VERSION.SDK_INT < 30 -> note(body, "此功能需要 Android 11 或以上。", true)
                getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true ->
                    note(body, "当前设备未设置安全锁屏，请先在系统设置中设置数字 PIN 或密码；图案锁暂不支持。", true)
                else -> renderEntry(body, saved)
            }
        }
        if (!editing) {
            val lanEnabled = AutomaticUnlockCredentials.isLanHandoffEnabled(this)
            body.addView(UiTheme.toggle(this, "同一局域网免密码接管", lanEnabled) { checked ->
                // Redraw before authentication: cancelling must leave the saved switch unchanged.
                render()
                if (authentication != null) return@toggle
                if (checked) authenticate(null, enableLanHandoff = true)
                else runCatching { AutomaticUnlockCredentials.setLanHandoffEnabled(this, false) }
                    .onSuccess { render() }.onFailure { toast("设置未保存，请重试"); render() }
            }.apply { isEnabled = enabled || lanEnabled; filterTouchesWhenObscured = true })
            note(body, "默认关闭。仅在本应用自动解锁后，已配对电脑通过局域网加密连接确认在附近时，长按接管 3 秒可免密码；停止任务仍需验证。请保持手机 PC 连接服务与电脑版运行，云端在线不代表处于同一局域网。断线证明最迟 12 秒后失效，恢复密码验证。")
            note(body, "开启后，电脑在附近时，任何拿到手机的人都可能接管自动任务。只应在可信环境使用。", true)
            command("管理已配对电脑") { startActivity(android.content.Intent(this, PcConnectionActivity::class.java)) }
        }
        note(body, "高风险：开启后，自动任务会使用保存的密码实际解锁手机。全屏悬浮窗只能阻挡普通触摸，不能替代系统锁屏；执行手势时拦截会短暂放行。系统界面、强停应用、服务中断等仍可能让他人使用手机。手机遗失、被借用或无人看管时存在盗用风险。", true)
        note(body, "锁屏密码仅在本机加密保存、不备份、不发送给 AI。为能在锁屏时解锁，Doppel 在后台可读取该密码。停用会保留加密密码，只有主动删除才会删除密码。请勿在共享设备或无法接受风险时开启。")
        note(body, "系统身份验证只证明是你在设置，不能证明保存或录入的密码与系统密码一致。自动解锁失败后会停用并保留密码，等待你检查，不会反复试错。")
        note(body, "任务期间长按接管或停止 3 秒，按上述设置决定是否验证密码；不会先重新锁屏。连续输错 3 次或 5 秒没有操作会继续原任务；每次验证最长 30 秒，返回后 10 秒内不能重复唤起。此验证不具有系统锁屏的安全等级。")
    }

    private fun renderEntry(body: LinearLayout, saved: Boolean) {
        val confirming = firstEntry != null
        val numeric = kind == AutomaticUnlockCredentials.Kind.PIN
        if (!confirming) body.addView(UiTheme.selector(this, "系统锁屏类型", listOf("数字 PIN", "英文、数字与符号密码"), kind.ordinal) {
            wipeDraft(); kind = AutomaticUnlockCredentials.Kind.entries[it]; render()
        })
        note(body, if (confirming) "第 2 步 · 再次输入${if (numeric) "当前系统 PIN" else "当前系统密码"}" else "第 1 步 · 输入${if (numeric) "当前系统 PIN" else "当前系统密码"}")
        val field = secretField().also { input = it }
        body.addView(field, LinearLayout.LayoutParams(-1, dp(54)))
        body.addView(keyboard(field, numeric), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val accepted = if (confirming) UiTheme.check(this, "我已了解手机会实际解锁及盗用风险，仍选择开启", false).apply {
            filterTouchesWhenObscured = true; body.addView(this)
        } else null
        body.addView(UiTheme.command(this, if (!confirming) "下一步" else if (saved) "验证身份并更新" else "验证身份并开启", true) {
            if (authentication != null || input !== field) return@command
            if (confirming && accepted?.isChecked != true) { toast("请先阅读并确认风险"); return@command }
            val value = CharArray(field.text.length) { field.text[it] }
            if (!AutomaticUnlockCredentials.valid(kind, value)) { value.fill('\u0000'); toast("PIN 需 4–16 位数字；密码需 4–64 位英文、数字或符号"); return@command }
            field.text.clear()
            if (!confirming) {
                firstEntry = AutomaticUnlockCredentials.Credential(kind, value); render()
            } else {
                val first = firstEntry
                val matches = first != null && first.kind == kind && first.value.contentEquals(value)
                value.fill('\u0000')
                if (!matches) { toast("两次输入不一致，请重新输入第二遍"); return@command }
                firstEntry = null
                authenticate(requireNotNull(first))
            }
        }.apply { filterTouchesWhenObscured = true }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
        body.addView(UiTheme.command(this, if (confirming) "重新输入" else "取消设置") {
            wipeDraft(); if (!confirming) editing = false; render()
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
    }

    /** Local keys for both supported credential types; the editable buffer never opens an IME. */
    private fun keyboard(field: EditText, numeric: Boolean): LinearLayout {
        val keys = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "automatic_unlock_keyboard" }
        var upper = false; var symbols = false
        fun draw() {
            keys.removeAllViews()
            val rows = if (numeric) listOf("123", "456", "789", "0") else if (symbols)
                listOf("1234567890", "!@#\$%^&*()", "-_=+[]{}\\|", ";:'\",.<>/?`~")
            else listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm").map { if (upper) it.uppercase(java.util.Locale.ROOT) else it }
            fun row(labels: List<String>, onKey: (String) -> Unit) {
                val line = LinearLayout(this)
                labels.forEach { label -> line.addView(UiTheme.command(this, label) { if (input === field) onKey(label) }.apply {
                    textSize = if (label.length == 1) 16f else 13f; setPadding(0, 0, 0, 0)
                    filterTouchesWhenObscured = true; tag = "automatic_unlock_key_$label"
                }, LinearLayout.LayoutParams(0, dp(46), 1f)) }
                keys.addView(line)
            }
            rows.forEach { row(it.map(Char::toString)) { field.text.append(it) } }
            row(if (numeric) listOf("退格", "清空") else listOf(if (symbols) "字母" else "符号", if (upper) "小写" else "大写", "空格", "退格", "清空")) {
                when (it) {
                    "退格" -> if (field.text.isNotEmpty()) field.text.delete(field.text.length - 1, field.text.length)
                    "清空" -> field.text.clear()
                    "空格" -> field.text.append(' ')
                    "字母", "符号" -> { symbols = !symbols; draw() }
                    "大写", "小写" -> { upper = !upper; symbols = false; draw() }
                }
            }
        }
        draw(); return keys
    }

    @android.annotation.TargetApi(30)
    private fun authenticate(credential: AutomaticUnlockCredentials.Credential?, enableLanHandoff: Boolean = false) {
        if (authentication != null || Build.VERSION.SDK_INT < 30) { credential?.close(); return }
        val lock = getSystemService(KeyguardManager::class.java)
        if (lock?.isDeviceSecure != true || lock.isDeviceLocked || lock.isKeyguardLocked) {
            credential?.close(); toast("请先手动解锁手机，再检查自动解锁设置"); render(); return
        }
        pending = credential
        val reenable = credential == null && !enableLanHandoff
        val signal = CancellationSignal(); authentication = signal
        main.postDelayed(timeout, 60_000)
        try {
            BiometricPrompt.Builder(this).setTitle(if (enableLanHandoff) "确认免密码接管设置" else "确认自动解锁设置")
                .setDescription("请输入系统锁屏密码验证身份")
                .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL).build()
                .authenticate(signal, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (authentication !== signal) return
                        val value = pending
                        pending = null; authentication = null; main.removeCallbacks(timeout)
                        if (!resumed) DirectMode.leaveSettings(this@AutomaticUnlockSettingsActivity)
                        try {
                            check(result.authenticationType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL)
                            check(lock.isDeviceSecure && !lock.isDeviceLocked && !lock.isKeyguardLocked)
                            if (enableLanHandoff) AutomaticUnlockCredentials.setLanHandoffEnabled(this@AutomaticUnlockSettingsActivity, true)
                            else if (reenable) AutomaticUnlockCredentials.reenable(this@AutomaticUnlockSettingsActivity)
                            else requireNotNull(value).let { AutomaticUnlockCredentials.save(this@AutomaticUnlockSettingsActivity, it.kind, it.value) }
                            editing = false
                            toast(if (enableLanHandoff) "已开启同一局域网免密码接管" else if (reenable) "已重新启用；请确保保存的密码仍与系统密码一致" else "已保存并开启；所填密码尚未经实际解锁验证")
                        } catch (_: Exception) { toast("未能保存设置，请检查自动解锁状态") }
                        finally { value?.close(); render() }
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (authentication !== signal) return
                        cancelAuthentication(); toast("未完成系统身份验证，设置未修改"); render()
                    }
                })
        } catch (_: Exception) { cancelAuthentication(); toast("当前设备无法打开系统密码验证"); render() }
    }

    private fun secretField() = object : EditText(this) {
        override fun onTextContextMenuItem(id: Int) = false
        override fun onDragEvent(event: android.view.DragEvent) = false
        override fun onCheckIsTextEditor() = false
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? = null
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info); info.text = null; info.hintText = null; info.contentDescription = "锁屏密码"
        }
        override fun onPopulateAccessibilityEvent(event: AccessibilityEvent) {
            super.onPopulateAccessibilityEvent(event); event.text.clear(); event.beforeText = null; event.contentDescription = "锁屏密码"
        }
        override fun sendAccessibilityEventUnchecked(event: AccessibilityEvent) {
            event.text.clear(); event.beforeText = null; event.contentDescription = "锁屏密码"; super.sendAccessibilityEventUnchecked(event)
        }
    }.apply {
        hint = "请使用下方键盘输入"; textSize = 18f; setTextColor(UiTheme.ink); setHintTextColor(UiTheme.muted)
        setPadding(dp(12), dp(12), dp(12), dp(12)); background = UiTheme.surface(this@AutomaticUnlockSettingsActivity, UiTheme.pale, true)
        isSingleLine = true; isSaveEnabled = false; isSaveFromParentEnabled = false; isLongClickable = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        filterTouchesWhenObscured = true; showSoftInputOnFocus = false; isFocusable = false; isCursorVisible = false
        inputType = if (kind == AutomaticUnlockCredentials.Kind.PIN) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        transformationMethod = object : PasswordTransformationMethod() {
            override fun getTransformation(source: CharSequence, view: View): CharSequence = object : CharSequence {
                override val length get() = source.length
                override fun get(index: Int) = '\u2022'
                override fun subSequence(startIndex: Int, endIndex: Int) = "\u2022".repeat(endIndex - startIndex)
                override fun toString() = "\u2022".repeat(length)
            }
        }
        filters = arrayOf(InputFilter.LengthFilter(if (kind == AutomaticUnlockCredentials.Kind.PIN) 16 else 64))
        val noClipboard = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = false
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false
            override fun onDestroyActionMode(mode: ActionMode?) {}
        }
        customSelectionActionModeCallback = noClipboard; customInsertionActionModeCallback = noClipboard
    }

    private fun wipeDraft() { input?.text?.clear(); firstEntry?.close(); firstEntry = null }
    private fun cancelAuthentication() {
        val signal = authentication; authentication = null; pending?.close(); pending = null
        if (!resumed) DirectMode.leaveSettings(this)
        main.removeCallbacks(timeout); signal?.cancel()
    }
    private fun note(body: LinearLayout, text: String, warning: Boolean = false) {
        body.addView(UiTheme.text(this, text, 14f, if (warning) UiTheme.danger else UiTheme.muted).apply { setPadding(0, dp(8), 0, dp(14)) })
    }
    private fun dp(value: Int) = UiTheme.dp(this, value)
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
