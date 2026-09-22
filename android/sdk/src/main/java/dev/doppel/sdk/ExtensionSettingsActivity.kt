package dev.doppel.sdk

import android.app.Activity
import android.content.DialogInterface
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.Executors

class ExtensionSettingsActivity : Activity() {
    private lateinit var gateway: Gateway
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var add: ImageButton
    private lateinit var refresh: ImageButton
    private val io = Executors.newSingleThreadExecutor { Thread(it, "doppel-extension-settings").apply { isDaemon = true } }
    private var busy = false
    private var closed = false
    private var loadedItems = JSONArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiTheme.init(this)
        gateway = Gateway(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        UiTheme.bind(root) { root.setBackgroundColor(UiTheme.background) }
        UiTheme.window(this, root)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        header.addView(UiTheme.icon(this, UiIcons.back, "返回") { finish() }, square())
        header.addView(UiTheme.text(this, "扩展", 21f, UiTheme.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        refresh = UiTheme.icon(this, UiIcons.refresh, "刷新扩展") { load() }
        add = UiTheme.icon(this, UiIcons.plus, "添加服务") { editService(null) }
        header.addView(refresh, square()); header.addView(add, square()); root.addView(header)
        root.addView(UiTheme.divider(this))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(12), dp(22), dp(24)) }
        root.addView(ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        status = UiTheme.text(this, "", 13f, UiTheme.muted).apply { setPadding(dp(22), dp(10), dp(22), dp(18)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(status)
        setContentView(root)
        load()
    }

    private fun dp(value: Int) = UiTheme.dp(this, value)
    private fun square() = LinearLayout.LayoutParams(dp(44), dp(44))
    private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun controls() {
        val enabled = !busy && !DirectMode.isEnabled(this)
        refresh.isEnabled = enabled; add.isEnabled = enabled
        add.tooltipText = add.contentDescription
    }

    private fun load(after: () -> Unit = {}) {
        if (busy || closed) return
        controls()
        if (DirectMode.isEnabled(this)) {
            loadedItems = JSONArray(); render()
            status.text = "MCP 服务需要网关连接"
            return
        }
        request("正在加载", { gateway.request("GET", "/extensions") }) { response ->
            loadedItems = response.optJSONArray("items") ?: JSONArray()
            render(); after()
        }
    }

    private fun render() {
        body.removeAllViews()
        if (loadedItems.length() == 0) {
            body.addView(UiTheme.text(this, "暂无服务", 17f, UiTheme.muted).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(72), 0, dp(24))
            })
            if (!DirectMode.isEnabled(this)) body.addView(UiTheme.command(this, "添加服务", true) {
                if (!busy) editService(null)
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        for (index in 0 until loadedItems.length()) {
            val item = loadedItems.getJSONObject(index)
            serviceRow(item)
            if (index < loadedItems.length() - 1) body.addView(UiTheme.divider(this))
        }
    }

    private fun serviceRow(item: JSONObject) {
        val name = item.getString("name")
        val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(12)) }
        val url = try { URI(item.getString("url")).let { "${it.host}${if (it.port >= 0) ":${it.port}" else ""}${it.path.orEmpty()}" } } catch (_: Exception) { "服务地址" }
        group.addView(UiTheme.row(this, name, url, UiIcons.connection) { if (!busy) tools(item) })
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val count = item.optJSONArray("allowed_tools")?.length() ?: 0
        actions.addView(UiTheme.text(this, "已允许 $count 项工具", 12f, UiTheme.muted), LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(UiTheme.icon(this, UiIcons.settings, "管理 $name 的工具") { if (!busy) tools(item) }, square())
        actions.addView(UiTheme.icon(this, UiIcons.edit, "编辑服务 $name") { if (!busy) editService(item) }, square())
        actions.addView(UiTheme.icon(this, UiIcons.delete, "删除服务 $name") { if (!busy) deleteService(item) }, square())
        group.addView(actions); body.addView(group)
    }

    private fun editService(item: JSONObject?) {
        if (busy) return
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val name = UiTheme.field(this, "服务名称", item?.getString("name").orEmpty()).apply {
            setSingleLine(true); filters = arrayOf(InputFilter.LengthFilter(64)); isEnabled = item == null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val url = UiTheme.field(this, "https://example.com/mcp", item?.getString("url").orEmpty()).apply {
            setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            filters = arrayOf(InputFilter.LengthFilter(2048))
        }
        form.addView(UiTheme.text(this, "名称", 12f, UiTheme.muted)); form.addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(18) })
        form.addView(UiTheme.text(this, "服务地址", 12f, UiTheme.muted)); form.addView(url, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val error = UiTheme.text(this, "", 12f, UiTheme.danger).apply { setPadding(0, dp(12), 0, 0) }; form.addView(error)
        val sheet = UiDialog.Builder(this).setTitle(if (item == null) "添加服务" else "编辑服务").setView(form)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).show()
        sheet.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val serviceName = name.text.toString().trim()
            val address = url.text.toString().trim()
            if (!Regex("[A-Za-z0-9_-]{1,64}").matches(serviceName)) { error.text = "名称需为 1 至 64 位字母、数字、短横线或下划线"; return@setOnClickListener }
            val valid = try { URI(address).let { it.scheme in setOf("http", "https") && it.host != null && it.userInfo == null && it.fragment == null } } catch (_: Exception) { false }
            if (!valid) { error.text = "请输入有效的 HTTP 或 HTTPS 服务地址"; return@setOnClickListener }
            val moved = item != null && address != item.getString("url")
            val payload = JSONObject().put("name", serviceName).put("url", address)
                .put("allowed_tools", if (moved) JSONArray() else item?.optJSONArray("allowed_tools") ?: JSONArray())
                .put("read_only_tools", if (moved) JSONArray() else item?.optJSONArray("read_only_tools") ?: JSONArray())
            item?.optString("revision")?.let { payload.put("expected_revision", it) }
            sheet.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = false
            request("正在保存", { gateway.request(if (item == null) "POST" else "PUT", if (item == null) "/extensions" else "/extensions/${encoded(serviceName)}", payload) },
                onError = { message -> error.text = message; sheet.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = true }) { saved ->
                sheet.dismiss(); load { if (item == null || moved) tools(saved) }
            }
        }
    }

    private fun tools(config: JSONObject) {
        val name = config.getString("name")
        request("正在连接服务", { gateway.request("GET", "/extensions/${encoded(name)}/tools") }) { result ->
            val descriptors = result.optJSONArray("items") ?: JSONArray()
            val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val allowed = linkedSetOf<String>(); val readOnly = linkedSetOf<String>()
            if (descriptors.length() == 0) form.addView(UiTheme.text(this, "此服务暂无工具", 15f, UiTheme.muted))
            for (index in 0 until descriptors.length()) {
                val tool = descriptors.getJSONObject(index)
                if (tool.optString("configuration_revision") != config.optString("revision")) {
                    status.text = "服务配置已变化，请刷新后重试"; return@request
                }
                val raw = tool.getString("name").removePrefix("mcp.$name.")
                val blocked = tool.optBoolean("blocked")
                val allow = UiTheme.check(this, raw, tool.optBoolean("allowed") && !blocked).apply { isEnabled = !blocked }
                val read = UiTheme.check(this, "由我确认为只读", tool.optBoolean("read_only") && allow.isChecked).apply { isEnabled = allow.isChecked }
                if (allow.isChecked) allowed.add(raw)
                if (read.isChecked) readOnly.add(raw)
                allow.setOnCheckedChangeListener { _, checked ->
                    if (checked) allowed.add(raw) else { allowed.remove(raw); read.isChecked = false; readOnly.remove(raw) }
                    read.isEnabled = checked
                }
                read.setOnCheckedChangeListener { _, checked -> if (checked && allow.isChecked) readOnly.add(raw) else readOnly.remove(raw) }
                form.addView(allow)
                val description = if (blocked) "此工具不可授权" else tool.optString("description")
                if (description.isNotBlank()) form.addView(UiTheme.text(this, description.take(1000), 12f, UiTheme.muted).apply { maxLines = 5; setPadding(dp(8), 0, 0, dp(4)) })
                if (!blocked) form.addView(read)
                if (index < descriptors.length() - 1) form.addView(UiTheme.divider(this))
            }
            val error = UiTheme.text(this, "", 12f, UiTheme.danger); form.addView(error)
            val sheet = UiDialog.Builder(this).setTitle(name).setView(form).setNegativeButton("关闭", null).setPositiveButton("保存授权", null).show()
            sheet.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val payload = JSONObject().put("name", name).put("url", config.getString("url"))
                    .put("allowed_tools", JSONArray(allowed.toList())).put("read_only_tools", JSONArray(readOnly.toList()))
                    .put("expected_revision", config.getString("revision"))
                sheet.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = false
                request("正在保存授权", { gateway.request("PUT", "/extensions/${encoded(name)}", payload) },
                    onError = { message -> error.text = message; sheet.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = true }) {
                    sheet.dismiss(); load()
                }
            }
        }
    }

    private fun deleteService(item: JSONObject) {
        val name = item.getString("name")
        UiDialog.Builder(this).setTitle("删除 $name？").setMessage("删除后将停止此服务的后续调用。")
            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                request("正在删除", { gateway.request("DELETE", "/extensions/${encoded(name)}") }) { load() }
            }.show()
    }

    private fun <T> request(message: String, work: () -> T, onError: (String) -> Unit = {}, done: (T) -> Unit) {
        if (busy || closed) return
        if (DirectMode.isEnabled(this)) { status.text = "MCP 服务需要网关连接"; return }
        busy = true; controls(); status.text = message
        io.execute {
            val result = runCatching(work)
            runOnUiThread {
                if (closed || isFinishing || isDestroyed) return@runOnUiThread
                busy = false; controls(); status.text = ""
                result.fold(done, { error ->
                    val text = if (error is IllegalArgumentException || error is IllegalStateException) error.message.orEmpty().take(160) else "连接未完成，请检查服务后重试"
                    status.text = text; onError(text)
                })
            }
        }
    }

    override fun onDestroy() {
        closed = true; io.shutdownNow()
        super.onDestroy()
    }
}
