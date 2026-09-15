package dev.doppel.sdk

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
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
    private lateinit var importer: SkillImportClient
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var add: ImageButton
    private lateinit var refresh: ImageButton
    private val tabs = mutableListOf<TextView>()
    private val io = Executors.newSingleThreadExecutor { Thread(it, "doppel-extension-settings").apply { isDaemon = true } }
    private var selected = 0
    private var busy = false
    private var closed = false
    private var loadedItems = JSONArray()
    private var pendingSkillUri: Uri? = null
    private var importingSkill = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiTheme.init(this)
        gateway = Gateway(this)
        importer = SkillImportClient(this, gateway)
        selected = savedInstanceState?.getInt("tab")?.coerceIn(0, 1) ?: if (DirectMode.isEnabled(this)) 1 else 0
        pendingSkillUri = savedInstanceState?.getString("pending_skill_uri")?.let(Uri::parse)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        UiTheme.bind(root) { root.setBackgroundColor(UiTheme.background) }
        UiTheme.window(this, root)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        header.addView(UiTheme.icon(this, UiIcons.back, "返回") { finish() }, square())
        header.addView(UiTheme.text(this, "扩展", 21f, UiTheme.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        refresh = UiTheme.icon(this, UiIcons.refresh, "刷新扩展") { load() }
        add = UiTheme.icon(this, UiIcons.plus, "添加服务") { if (selected == 0) editService(null) else chooseSkill() }
        header.addView(refresh, square()); header.addView(add, square()); root.addView(header)
        val navigation = LinearLayout(this).apply { setPadding(dp(22), 0, dp(22), 0) }
        listOf("服务", "Skills").forEachIndexed { index, label ->
            val tab = UiTheme.text(this, label, 15f, UiTheme.ink, true).apply {
                gravity = Gravity.CENTER; minHeight = dp(48); contentDescription = "$label 扩展列表"
                setOnClickListener { if (!busy && selected != index) { selected = index; load() } }
            }
            UiTheme.bind(tab) { tab.setTextColor(if (selected == index) UiTheme.ink else UiTheme.muted) }
            tabs.add(tab)
            navigation.addView(tab, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        root.addView(navigation)
        root.addView(UiTheme.divider(this))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(12), dp(22), dp(24)) }
        root.addView(ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        status = UiTheme.text(this, "", 13f, UiTheme.muted).apply { setPadding(dp(22), dp(10), dp(22), dp(18)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(status)
        setContentView(root)
        load {
            if (savedInstanceState?.getBoolean("importing_skill") == true)
                status.text = "导入页面已重建，请先检查列表；未导入的文件可重新选择。"
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", selected)
        pendingSkillUri?.let { outState.putString("pending_skill_uri", it.toString()) }
        outState.putBoolean("importing_skill", importingSkill)
        super.onSaveInstanceState(outState)
    }

    private fun dp(value: Int) = UiTheme.dp(this, value)
    private fun square() = LinearLayout.LayoutParams(dp(44), dp(44))
    private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun controls() {
        val enabled = !busy && (!DirectMode.isEnabled(this) || selected == 1)
        refresh.isEnabled = enabled; add.isEnabled = enabled
        tabs.forEachIndexed { index, tab ->
            tab.isEnabled = !busy; tab.isSelected = selected == index
            tab.setTextColor(if (selected == index) UiTheme.ink else UiTheme.muted)
        }
        add.contentDescription = if (selected == 0) "添加服务" else "导入 Skills"
        add.tooltipText = add.contentDescription
    }

    private fun load(after: () -> Unit = {}) {
        if (busy || closed) return
        controls()
        if (DirectMode.isEnabled(this) && selected == 0) {
            loadedItems = JSONArray(); render()
            status.text = "MCP 服务需要网关连接；本机 Skills 可在另一个标签中管理"
            return
        }
        request("正在加载", { gateway.request("GET", if (selected == 0) "/extensions" else "/skills") }) { response ->
            loadedItems = response.optJSONArray("items") ?: JSONArray()
            render(); after()
            val invalid = response.optJSONArray("errors")?.length() ?: 0
            if (invalid > 0) status.text = "$invalid 个 Skills 未通过校验，其余资料仍可使用"
        }
    }

    private fun render() {
        body.removeAllViews()
        if (loadedItems.length() == 0) {
            body.addView(UiTheme.text(this, if (selected == 0) "暂无服务" else "暂无 Skills", 17f, UiTheme.muted).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(72), 0, dp(24))
            })
            if (!DirectMode.isEnabled(this) || selected == 1) body.addView(UiTheme.command(this, if (selected == 0) "添加服务" else "导入 Skills", true) {
                if (!busy) { if (selected == 0) editService(null) else chooseSkill() }
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        for (index in 0 until loadedItems.length()) {
            val item = loadedItems.getJSONObject(index)
            if (selected == 0) serviceRow(item) else skillRow(item)
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

    private fun skillRow(item: JSONObject) {
        val name = item.getString("name")
        val details = item.optString("description") + when (item.optString("source")) { "host" -> "\n由服务器管理"; "bundled" -> "\n内置知识 · ${item.optString("included_source_version")}"; else -> "" }
        body.addView(UiTheme.row(this, name, details, UiIcons.files) { if (!busy) openSkill(name) })
    }

    private fun openSkill(name: String) {
        request("正在读取 Skill", { gateway.request("GET", "/skills/${encoded(name)}") }) { item ->
            if (!item.optBoolean("found", true)) { status.text = "Skill 不存在或已变化，请刷新后重试"; return@request }
            val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            form.addView(UiTheme.text(this, item.optString("description"), 14f, UiTheme.muted))
            form.addView(UiTheme.text(this, "未验证的外部内容", 12f, UiTheme.muted).apply { setPadding(0, dp(10), 0, dp(16)) })
            if (item.optBoolean("truncated")) form.addView(UiTheme.text(this, "正文显示前 12000 字符，详细资料可通过资源读取接口按需获取", 12f, UiTheme.muted))
            form.addView(UiTheme.text(this, item.optString("instructions"), 14f, UiTheme.ink).apply { setTextIsSelectable(true); setLineSpacing(dp(3).toFloat(), 1f) })
            val resources = item.optJSONArray("resources")
            if (resources != null) for (index in 0 until resources.length()) {
                val path = resources.getString(index)
                form.addView(UiTheme.row(this, path, "按需读取资料", UiIcons.files) { if (!busy) openSkillResource(name, path) })
            }
            val dialog = UiDialog.Builder(this).setTitle(name).setView(form).setNegativeButton("关闭", null)
            if (item.optString("source") == "imported") dialog.setNeutralButton("移除") { _, _ ->
                UiDialog.Builder(this).setTitle("移除 $name？").setNegativeButton("取消", null).setPositiveButton("移除") { _, _ ->
                    request("正在移除", { gateway.request("DELETE", "/skills/${encoded(name)}") }) { load() }
                }.show()
            }
            dialog.show()
        }
    }

    private fun openSkillResource(name: String, path: String) {
        request("正在读取资料", { gateway.request("GET", "/skills/${encoded(name)}/resources?path=${encoded(path)}") }) { item ->
            if (!item.optBoolean("found", true)) { status.text = "资源不存在或已变化，请刷新后重试"; return@request }
            val text = item.optString("content") + if (item.optBoolean("truncated")) "\n\n此资源超过显示上限，仅显示前 12000 字符。" else ""
            UiDialog.Builder(this).setTitle(path).setMessage(text).setNegativeButton("关闭", null).show()
        }
    }

    private fun chooseSkill() {
        if (busy) return
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "text/markdown", "text/plain", "application/octet-stream"))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, 41)
        } catch (_: Exception) { status.text = "此设备暂时无法打开文件选择器" }
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 41 || resultCode != RESULT_OK) return
        if (closed || isFinishing || isDestroyed || pendingSkillUri != null || importingSkill) return
        val uri = data?.data ?: run { status.text = "没有读取到所选文件，请重新选择"; return }
        pendingSkillUri = uri
        importPendingSkill()
    }

    private fun importPendingSkill() {
        if (busy || closed || isFinishing || isDestroyed) return
        val uri = pendingSkillUri ?: return
        pendingSkillUri = null
        selected = 1
        importingSkill = true
        // The picker result can arrive while onCreate's list request is still running.
        // Consume it once after that request. Never replay an in-flight POST on recreation.
        request("正在校验并导入", { importer.upload(uri) }, onError = { importingSkill = false }) { item ->
            importingSkill = false
            load { openSkill(item.getString("name")) }
        }
    }

    private fun <T> request(message: String, work: () -> T, onError: (String) -> Unit = {}, done: (T) -> Unit) {
        if (busy || closed) return
        if (DirectMode.isEnabled(this) && selected == 0) { status.text = "MCP 服务需要网关连接；本机 Skills 可在另一个标签中管理"; return }
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
                importPendingSkill()
            }
        }
    }

    override fun onDestroy() {
        closed = true; importer.close(); io.shutdownNow()
        super.onDestroy()
    }
}
