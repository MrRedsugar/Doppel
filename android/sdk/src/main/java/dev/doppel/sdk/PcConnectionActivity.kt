package dev.doppel.sdk

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONObject

/** Phone-only pairing approval. No secret goes into a Bundle, log, model, or saved view state. */
class PcConnectionActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private var visible = false
    private var lastState = ""
    private var pairingPage = false
    private var invitation: String? = null
    private var ownsPairing = false
    private var selectedPair: String? = null
    private val scopes = linkedSetOf("state")
    private val scopeLabels = linkedMapOf("state" to "查看任务状态", "history" to "读取历史记录",
        "submit" to "发起任务", "control" to "控制当前任务", "screen_control" to "局域网人工接管屏幕")
    private fun dp(value: Int) = UiTheme.dp(this, value)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(UiTheme.background) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(20), dp(8)) }
        header.addView(UiTheme.icon(this, android.R.drawable.ic_media_previous, "返回") { goBack() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(UiTheme.text(this, "PC 连接", 18f, bold = true))
        root.addView(header)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(32)) }
        root.addView(ScrollView(this).apply { addView(content); isFillViewport = true }, LinearLayout.LayoutParams(-1, 0, 1f))
        UiTheme.window(this, root)
        setContentView(root)
    }
    override fun onResume() { super.onResume(); visible = true; lastState = ""; refresh.run() }
    override fun onPause() {
        visible = false
        handler.removeCallbacks(refresh)
        clearInvitation()
        pairingPage = false
        super.onPause()
    }
    @Deprecated("Deprecated in Java") override fun onBackPressed() { goBack() }
    private fun goBack() {
        if (pairingPage || selectedPair != null) {
            clearInvitation(); pairingPage = false; selectedPair = null; update()
        } else finish()
    }
    private fun clearInvitation() {
        val old = invitation
        invitation = null
        if (ownsPairing) SdkCompanionService.instance?.cancelPairing()
        ownsPairing = false
        // Remove only this page's copied secret; never erase unrelated clipboard data.
        if (old != null) {
            val clipboard = getSystemService(ClipboardManager::class.java)
            if (clipboard.primaryClip?.getItemAt(0)?.text?.toString() == old) {
                if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
                else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }
    private val refresh = object : Runnable {
        override fun run() { if (visible) { update(false); handler.postDelayed(this, 1000) } }
    }
    private fun update(force: Boolean = true) {
        if (!::content.isInitialized) return
        try {
            val state = SdkCompanionService.currentState(this)
            val key = state.toString() + pairingPage + selectedPair + scopes.joinToString()
            if (!force && key == lastState) return
            lastState = key
            content.removeAllViews()
            if (pairingPage) renderPairing(state) else if (selectedPair != null) renderDevice(state) else renderMain(state)
        } catch (_: Exception) {
            content.removeAllViews(); heading("连接信息暂不可用")
            note("未能读取本机配对记录，请稍后重试。")
        }
    }
    private fun heading(text: String) { content.addView(UiTheme.text(this, text, 23f, bold = true).apply { setPadding(0, 0, 0, dp(12)) }) }
    private fun note(text: String) { content.addView(UiTheme.text(this, text, 14f, UiTheme.muted).apply { setPadding(0, dp(8), 0, dp(16)) }) }
    private fun button(text: String, primary: Boolean = false, action: () -> Unit) {
        content.addView(UiTheme.command(this, text, primary) { attempt(action) }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
    }
    private fun attempt(action: () -> Unit) {
        try { action(); update() } catch (error: Exception) {
            Toast.makeText(this, error.message?.takeIf { !it.contains("doppel-pair:") } ?: "操作未完成，请重试", Toast.LENGTH_LONG).show()
        }
    }
    private fun renderMain(state: JSONObject) {
        heading("电脑发起，手机完成")
        note("在同一网络中连接电脑，由手机完成操作。")
        val enabled = state.optBoolean("service_enabled")
        val starting = state.optBoolean("starting")
        content.addView(UiTheme.toggle(this, "允许 PC 连接", enabled) { on ->
            attempt { if (on) SdkCompanionService.enable(this) else SdkCompanionService.disable(this) }
        }.apply { isEnabled = !starting })
        note(when {
            starting -> "正在开启连接…"
            enabled && state.optBoolean("trusted_lan_presence") -> "已确认已配对电脑在同一局域网；免密码接管仍需在自动解锁设置中开启。"
            enabled && state.optBoolean("discovery_ready") -> "已开启，等待已配对的电脑连接"
            enabled -> "正在发布局域网发现…"
            !state.isNull("error_code") -> "连接未能保持，请检查局域网后重新开启。"
            else -> "关闭时，电脑无法连接此手机"
        })
        if (enabled) button("配对电脑", true) { beginPairing() }
        note("已配对的电脑")
        val pairs = state.optJSONArray("pairs")
        if (pairs == null || pairs.length() == 0) note("还没有配对的电脑。开启连接后，添加你信任的电脑。")
        else for (i in 0 until pairs.length()) {
            val pair = pairs.getJSONObject(i)
            content.addView(UiTheme.row(this, pair.getString("client_name"), "已配对 · 权限可随时撤销", UiIcons.device) {
                selectedPair = pair.getString("pair_id"); update()
            })
        }
        note("关闭连接不会结束手机已经接收的任务。")
    }
    private fun beginPairing() {
        clearInvitation()
        invitation = checkNotNull(SdkCompanionService.instance) { "请先开启连接" }.openPairing()
        ownsPairing = true; pairingPage = true; scopes.clear(); scopes.add("state")
    }
    private fun renderPairing(state: JSONObject) {
        val pairing = state.optJSONObject("pairing")
        val status = pairing?.optString("state")
        when {
            status == "active" -> {
                ownsPairing = false; clearInvitation()
                heading("配对已完成"); note("这台电脑已获得你允许的权限。")
                button("返回 PC 连接", true) { pairingPage = false }
            }
            pairing == null -> {
                clearInvitation()
                heading("配对链接已失效"); note("请重新生成链接，再在电脑上发起连接。")
                button("重新生成链接", true) { beginPairing() }
            }
            status == "pending" && !pairing.isNull("pairing_request_id") -> {
                heading("允许这台电脑连接？")
                note(pairing.optString("client_name"))
                scopeLabels.forEach { (scope, label) ->
                    content.addView(UiTheme.toggle(this, label, scope in scopes) { checked ->
                        if (checked) scopes.add(scope) else scopes.remove(scope)
                        if (checked && scope in setOf("submit", "control", "screen_control")) scopes.add("state")
                        if (!checked && scope == "state") { scopes.remove("submit"); scopes.remove("control"); scopes.remove("screen_control") }
                        update()
                    })
                }
                note("发起或控制任务需要同时允许查看状态。屏幕接管仅限同一局域网和中断中的任务，可查看手机画面并手动操作；锁屏和本机敏感设置仍需在手机处理。")
                val id = pairing.getString("pairing_request_id")
                button("拒绝") { SdkCompanionService.instance?.decidePairing(id, false, emptySet()); clearInvitation(); pairingPage = false }
                button("允许连接", true) { checkNotNull(SdkCompanionService.instance).decidePairing(id, true, scopes.toSet()) }
            }
            status == "approved" || status == "delivered" -> {
                heading("已允许，等待电脑确认")
                note("尚未激活连接。若链接过期或电脑未收到授权，请重新配对。")
                button("取消本次配对") { clearInvitation(); pairingPage = false }
            }
            else -> {
                heading("让电脑找到这部手机")
                note("将配对链接粘贴到电脑端 Doppel，随后在这里确认。")
                note("5 分钟内有效 · 仅分享给你信任的电脑")
                button("复制配对链接", true) {
                    val clip = ClipData.newPlainText("Doppel 配对链接", checkNotNull(invitation) { "请重新生成链接" })
                    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                    Toast.makeText(this, "已复制，请保持此页打开", Toast.LENGTH_SHORT).show()
                }
                note("链接只能使用一次。离开此页会取消未完成配对并清除本页复制的链接。")
                button("取消本次配对") { clearInvitation(); pairingPage = false }
            }
        }
    }
    private fun renderDevice(state: JSONObject) {
        val pairs = state.optJSONArray("pairs")
        val pair = (0 until (pairs?.length() ?: 0)).map { pairs!!.getJSONObject(it) }.firstOrNull { it.optString("pair_id") == selectedPair }
        if (pair == null) { heading("这台电脑已撤销"); button("返回") { selectedPair = null }; return }
        heading(pair.getString("client_name"))
        note("配对成功不代表允许所有操作。")
        val allowed = pair.getJSONArray("granted_scopes")
        for (i in 0 until allowed.length()) note(scopeLabels[allowed.getString(i)] ?: "未知权限")
        button("撤销这台电脑") {
            val id = pair.getString("pair_id")
            UiDialog.Builder(this).setTitle("撤销电脑连接？")
                .setMessage("立即撤销访问权限。电脑在获知撤销后清理本机缓存，手机已有任务与记录不会删除。")
                .setNegativeButton("保留连接", null).setPositiveButton("撤销连接") { _, _ ->
                    attempt { SdkCompanionService.revoke(this, id); selectedPair = null }
                }.show()
        }
    }
}
