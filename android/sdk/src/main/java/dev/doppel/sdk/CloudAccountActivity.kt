package dev.doppel.sdk

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.doppel.sdk.cloud.CloudAccountClient
import dev.doppel.sdk.cloud.CloudConnectionService
import dev.doppel.sdk.cloud.CloudHttpException
import dev.doppel.sdk.cloud.CloudSession
import dev.doppel.sdk.cloud.CloudSessionStore
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit account operations only. Passwords never enter preferences, saved Views or logs. */
class CloudAccountActivity : Activity() {
    private companion object {
        // A recreated page cannot submit a second password change while the first is unresolved.
        val busy = AtomicBoolean(false)
        @Volatile var message = ""
        @Volatile var offerLocalLogout = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val secrets = mutableListOf<EditText>()
    private lateinit var content: LinearLayout
    private var visible = false
    private var changingPassword = false
    private var renderKey = ""
    private fun dp(value: Int) = UiTheme.dp(this, value)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiTheme.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        if (!FirstUseConsent.allowEntry(this)) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(UiTheme.background)
            isSaveEnabled = false; isSaveFromParentEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(20), dp(8)) }
        header.addView(UiTheme.icon(this, android.R.drawable.ic_media_previous, "返回") { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(UiTheme.text(this, "账号与服务器", 18f, bold = true))
        root.addView(header)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(32)) }
        root.addView(ScrollView(this).apply { addView(content); isFillViewport = true }, LinearLayout.LayoutParams(-1, 0, 1f))
        UiTheme.window(this, root)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (!FirstUseConsent.allowEntry(this) || !::content.isInitialized) return
        DirectMode.enterSettings(this); DeviceWorkerService.instance?.suspendLocally()
        visible = true; renderKey = ""; refresh.run()
    }

    override fun onPause() {
        visible = false; main.removeCallbacks(refresh); clearPasswords()
        getSystemService(AutofillManager::class.java)?.cancel()
        DirectMode.leaveSettings(this)
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        // Finish an explicitly submitted request once; lifecycle changes must not retry it.
        io.shutdown()
        DirectMode.leaveSettings(this)
        super.onDestroy()
    }

    private val refresh = object : Runnable {
        override fun run() {
            if (!visible) return
            val key = stateKey()
            if (key != renderKey) { renderKey = key; render() }
            main.postDelayed(this, 750)
        }
    }

    private fun stateKey() = "${CloudConnectionService.state}|${busy.get()}|$message|$offerLocalLogout|$changingPassword"

    private fun render() {
        renderKey = stateKey()
        clearPasswords(); secrets.clear(); content.removeAllViews()
        if (message.isNotEmpty()) note(message)
        val session = try { CloudSessionStore(this).load() } catch (_: Exception) {
            heading("会话暂不可用")
            note("无法读取本机会话，不能确认当前账号。请关闭连接并检查本机存储，避免误删其他会话。")
            button("关闭连接") { perform("正在关闭连接…") { CloudConnectionService.disable(applicationContext); "已请求关闭连接，请以实际状态为准。" } }
            return
        }
        if (session == null) {
            changingPassword = false
            note(connectionText(CloudConnectionService.state))
            heading("登录账号")
            note("使用管理员提供的账号。同一账号的电脑通过服务器连接这部手机。")
            val server = field("服务器地址", "cloud_server", "https://doppelai.net")
            val account = field("账号", "cloud_account")
            val password = field("密码", "cloud_password", secret = true)
            button("登录并连接", true) {
                val base = try { CloudSession.validatedBaseUrl(server.text.toString().trim(), DirectMode.isDeveloperBuild(this)) }
                catch (_: IllegalArgumentException) { feedback("请输入有效的 HTTPS 服务器地址，不能包含账号、密码或查询参数。"); return@button }
                val name = account.text.toString().trim()
                val pass = password.text.toString()
                if (name.isBlank() || name.length > 256) { feedback("请输入账号（最多 256 个字符）。"); return@button }
                if (!validPassword(pass)) { feedback("密码需要 12–128 个字符，空格也是密码的一部分。"); return@button }
                perform("正在登录…") {
                    FirstUseConsent.requireAccepted(applicationContext)
                    val loggedIn = CloudAccountClient(applicationContext).login(base, name, pass)
                    try {
                        CloudConnectionService.installSession(applicationContext, loggedIn)
                        "登录成功，正在建立连接。"
                    } catch (_: Exception) {
                        "服务器已接受登录，但本机会话保存或连接开启未完成。请查看当前状态，再手动处理。"
                    }
                }
            }
            return
        }

        heading("当前账号")
        note("账号 ID：${session.accountId}")
        note("服务器：${session.baseUrl}")
        note(connectionText(CloudConnectionService.state))
        button("开启连接") { perform("正在开启连接…") { CloudConnectionService.enable(applicationContext); "已请求开启连接，请以连接状态为准。" } }
        button("关闭连接") { perform("正在关闭连接…") { CloudConnectionService.disable(applicationContext); "已请求关闭连接。远程任务的停止结果以实际状态为准。" } }
        if (changingPassword) {
            heading("修改密码")
            note("修改后手机和电脑都需要重新登录，当前账号的手机任务将结束。")
            val old = field("原密码", "cloud_old_password", secret = true)
            val new = field("新密码（12–128 个字符）", "cloud_new_password", secret = true)
            button("确认修改密码", true) {
                val previous = old.text.toString(); val next = new.text.toString()
                if (!validPassword(previous) || !validPassword(next)) { feedback("原密码和新密码都需要 12–128 个字符。"); return@button }
                perform("正在修改密码，请勿重复提交…", passwordChange = true) {
                    FirstUseConsent.requireAccepted(applicationContext)
                    CloudAccountClient(applicationContext).changePassword(session, previous, next)
                    CloudConnectionService.clear(applicationContext, session.sessionId, "password_changed")
                    "密码已修改，请重新登录。已请求清除本机会话并终止任务。"
                }
            }
            button("取消修改") { changingPassword = false; render() }
        } else button("修改密码") { changingPassword = true; render() }
        button("退出账号") {
            perform("正在请求服务器注销…", logout = true) {
                CloudAccountClient(applicationContext).logout(session)
                CloudConnectionService.clear(applicationContext, session.sessionId)
                "服务器已撤销本次手机会话，正在清除本机会话并终止任务。"
            }
        }
        if (offerLocalLogout) localLogoutButton(session.sessionId)
    }

    private fun localLogoutButton(expectedSessionId: String) {
        button("仅退出本机") {
            perform("正在请求本机退出…") {
                CloudConnectionService.clear(applicationContext, expectedSessionId)
                "已请求清除本机会话并终止任务，未确认服务器注销。请以实际停止状态为准。"
            }
        }
    }

    private fun perform(label: String, logout: Boolean = false, passwordChange: Boolean = false, action: () -> String) {
        if (!busy.compareAndSet(false, true)) return
        message = label; offerLocalLogout = false; clearPasswords(); render()
        io.execute {
            try { message = action() }
            catch (error: Exception) {
                message = safeError(error) + when {
                    logout -> " 未确认服务器注销；可选择仅退出本机，清除会话并终止任务。"
                    passwordChange && error is IOException && error !is CloudHttpException -> " 改密结果未确认，请先重新登录核对，不要重复提交。"
                    else -> ""
                }
                offerLocalLogout = logout
            } finally { busy.set(false) }
        }
    }

    private fun feedback(value: String) { message = value; render() }
    private fun clearPasswords() { secrets.forEach { it.text?.clear() } }
    private fun validPassword(value: String) = value.codePointCount(0, value.length) in 12..128
    private fun heading(value: String) { content.addView(UiTheme.text(this, value, 22f, bold = true).apply { setPadding(0, dp(12), 0, dp(8)) }) }
    private fun note(value: String) { content.addView(UiTheme.text(this, value, 14f, UiTheme.muted).apply { setPadding(0, dp(6), 0, dp(10)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }) }
    private fun button(value: String, primary: Boolean = false, action: () -> Unit) {
        content.addView(UiTheme.command(this, value, primary, action).apply { isEnabled = !busy.get() }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
    }
    private fun field(hint: String, description: String, initial: String = "", secret: Boolean = false): EditText {
        val field = UiTheme.field(this, hint, initial, secret).apply {
            tag = description; contentDescription = hint; isSingleLine = true; isEnabled = !busy.get()
            isSaveEnabled = false; isSaveFromParentEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        if (secret) secrets.add(field)
        content.addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        return field
    }

    private fun safeError(error: Exception): String = when (error) {
        is CloudHttpException -> when (error.code) {
            "invalid_credentials" -> "账号或密码校验未通过。"
            "session_revoked" -> "登录会话已失效，请重新登录。"
            "account_disabled" -> "账号已停用，请联系管理员。"
            "redirect_rejected" -> "服务器返回了跳转，已拒绝发送凭据，请检查服务器地址。"
            "invalid_response", "session_mismatch" -> "服务器响应不符合接口要求，未确认操作成功。"
            else -> when {
                error.status == 429 -> "请求过于频繁，请稍后再试。"
                error.status in 500..599 -> "服务器暂不可用（HTTP ${error.status}），未确认操作成功。"
                else -> "服务器拒绝请求（HTTP ${error.status}），请检查账号和服务器配置。"
            }
        }
        is javax.net.ssl.SSLException -> "安全连接校验失败，请检查服务器证书和设备时间。"
        is IOException -> "未能获得服务器确认，请检查网络和服务器地址。"
        else -> "操作未完成，请检查本机会话与连接状态。"
    }

    private fun connectionText(state: String): String = when (state) {
        "online" -> "已连接服务器"
        "connecting" -> "正在连接服务器…"
        "disconnected", "connection_closed" -> "连接已关闭"
        "signed_out", "session_revoked" -> "会话已失效或已退出"
        "stop_unconfirmed" -> "任务停止尚未确认，请在手机核对"
        "storage_unavailable" -> "会话存储不可用，请处理本机会话"
        "connection_replaced" -> "连接已被替换，请重新登录确认"
        "connection_lost", "lease_expired" -> "连接已中断，正在等待重新连接"
        else -> "连接不可用，请手动检查"
    }
}
