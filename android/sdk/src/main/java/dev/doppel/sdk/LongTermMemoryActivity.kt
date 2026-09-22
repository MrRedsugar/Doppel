package dev.doppel.sdk

import android.app.Activity
import android.content.DialogInterface
import android.os.Bundle
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
import java.net.URLEncoder
import java.util.concurrent.Executors

class LongTermMemoryActivity : Activity() {
    private lateinit var gateway: Gateway
    private lateinit var connection: Gateway.ReviewConnection
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var refresh: ImageButton
    private lateinit var add: ImageButton
    private val io = Executors.newSingleThreadExecutor()
    private var busy = false
    private var closed = false
    private var editor: UiDialog? = null
    private var confirmation: UiDialog? = null
    private var editorField: EditText? = null
    private var editingItem: JSONObject? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        UiTheme.init(this)
        gateway = Gateway(applicationContext)
        connection = gateway.captureReviewConnection()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        UiTheme.bind(root) { root.setBackgroundColor(UiTheme.background) }
        UiTheme.window(this, root)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        header.addView(UiTheme.icon(this, UiIcons.back, "返回") { finish() }, square())
        header.addView(UiTheme.text(this, "长期记忆", 21f, UiTheme.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        refresh = UiTheme.icon(this, UiIcons.refresh, "刷新记忆") { load() }
        add = UiTheme.icon(this, UiIcons.plus, "添加记忆") { edit(null) }
        header.addView(refresh, square()); header.addView(add, square()); root.addView(header)
        root.addView(UiTheme.text(this, if (connection.direct)
            "记忆保存在本机，相关内容会提供给所配置的模型。你可以随时编辑或删除。"
        else "这里管理当前账户的长期记忆，相关内容会提供给所配置的模型。你可以随时编辑或删除。",
            13f, UiTheme.muted).apply { setPadding(dp(22), dp(10), dp(22), dp(16)) })
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), 0, dp(22), dp(24)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        status = UiTheme.text(this, "", 13f, UiTheme.muted).apply {
            setPadding(dp(22), dp(10), dp(22), dp(18)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status); setContentView(root)
        if (state?.getString("editor_scope") == connection.scope) state.getString("editor_text")?.let { draft ->
            val item = state.getString("editor_item")?.let { runCatching { JSONObject(it) }.getOrNull() }
            edit(item, draft)
        }
    }

    override fun onResume() { super.onResume(); if (editor == null) load() }

    override fun onSaveInstanceState(out: Bundle) {
        editorField?.let {
            out.putString("editor_text", it.text.toString()); out.putString("editor_scope", connection.scope)
            editingItem?.let { item -> out.putString("editor_item", item.toString()) }
        }
        super.onSaveInstanceState(out)
    }

    private fun load() {
        if (busy || closed || editor != null) return
        request("正在读取记忆", {
            val all = JSONArray()
            val cursors = mutableSetOf<String>()
            var path = "/memories"
            do {
                val page = connection.request("GET", path)
                val items = page.optJSONArray("items") ?: JSONArray()
                for (index in 0 until items.length()) all.put(items.getJSONObject(index))
                if (!page.optBoolean("has_more")) break
                val cursor = page.optString("next_cursor")
                check(cursor.isNotBlank() && cursors.add(cursor)) { "记忆分页信息无效，请刷新重试" }
                path = "/memories?before=${encoded(cursor)}"
            } while (true)
            JSONObject().put("items", all)
        }) { response ->
            val items = response.optJSONArray("items") ?: JSONArray()
            body.removeAllViews()
            if (items.length() == 0) body.addView(UiTheme.text(this, "还没有长期记忆", 17f, UiTheme.muted).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(64), 0, dp(24))
            })
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val row = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(64); setPadding(0, dp(14), 0, dp(14))
                    isFocusable = true; setOnClickListener { if (!busy) edit(item) }
                }
                row.addView(UiTheme.text(this, memoryText(item), 15f), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(UiTheme.icon(this, UiIcons.edit, "编辑记忆 ${index + 1}") { if (!busy) edit(item) }, square())
                body.addView(row); body.addView(UiTheme.divider(this))
            }
            status.text = "${items.length()} 条记忆"
        }
    }

    private fun edit(item: JSONObject?, draft: String = memoryText(item)) {
        if (busy || editor != null || closed) return
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val field = UiTheme.field(this, "希望 Doppel 记住什么", draft).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP; minLines = 4; contentDescription = "记忆内容"
        }
        val error = UiTheme.text(this, "", 13f, UiTheme.danger).apply {
            setPadding(0, dp(10), 0, 0); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        form.addView(field); form.addView(error)
        val builder = UiDialog.Builder(this).setTitle(if (item == null) "添加记忆" else "编辑记忆")
            .setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null)
        if (item != null) builder.setNeutralButton("删除", null)
        val sheet = builder.show()
        editor = sheet; editorField = field; editingItem = item
        sheet.setOnDismissListener {
            if (editor === sheet) { editor = null; editorField = null; editingItem = null }
            load()
        }
        sheet.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            if (busy) return@setOnClickListener
            val content = field.text.toString().trim()
            val limit = if (connection.direct) 1600 else 10000
            if (content.length !in 2..limit) { error.text = "请输入 2 至 $limit 字的记忆内容"; return@setOnClickListener }
            error.text = ""; field.isEnabled = false
            sheet.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = false
            val payload = JSONObject().put("content", content)
            item?.opt("revision")?.takeIf { it != JSONObject.NULL }?.let { payload.put("expected_revision", it) }
            request("正在保存记忆", {
                connection.request(if (item == null) "POST" else "PATCH", if (item == null) "/memories" else memoryPath(item), payload)
            }, onError = { message ->
                error.text = message; field.isEnabled = true
                sheet.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = true
            }) { sheet.dismiss(); load() }
        }
        if (item != null) sheet.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            if (busy || confirmation != null) return@setOnClickListener
            val confirm = UiDialog.Builder(this).setTitle("删除这条记忆？")
                .setMessage("删除后，之后的对话和任务将不再参考这条记忆。")
                .setNegativeButton("保留", null).setPositiveButton("删除", null).show()
            confirmation = confirm
            confirm.setOnDismissListener { if (confirmation === confirm) confirmation = null }
            confirm.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener delete@{
                if (busy) return@delete
                confirm.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = false
                field.isEnabled = false
                request("正在删除记忆", {
                    val payload = if (connection.direct) JSONObject().put("expected_revision", item.get("revision")) else null
                    connection.request("DELETE", memoryPath(item), payload)
                }, onError = { message ->
                    confirm.dismiss(); error.text = message; field.isEnabled = true
                }) { confirm.dismiss(); sheet.dismiss(); load() }
            }
        }
    }

    private fun request(message: String, work: () -> JSONObject, onError: (String) -> Unit = {}, done: (JSONObject) -> Unit) {
        if (busy || closed) return
        busy = true; refresh.isEnabled = false; add.isEnabled = false; status.text = message
        io.execute {
            val result = runCatching {
                check(connection.scope == gateway.captureReviewConnection().scope) { "连接已更改，请返回设置后重新打开记忆" }
                work().also { check(connection.scope == gateway.captureReviewConnection().scope) { "连接已更改，请返回设置后重新打开记忆" } }
            }
            runOnUiThread {
                if (closed || isFinishing || isDestroyed) return@runOnUiThread
                busy = false; refresh.isEnabled = true; add.isEnabled = true
                result.fold({ status.text = ""; done(it) }, {
                    val detail = if (it is GatewayHttpException && it.statusCode == 409)
                        "记忆已变化，本次修改未保存。请复制内容后取消编辑并刷新。"
                    else it.message ?: "操作未完成，请重试"
                    status.text = detail; onError(detail)
                })
            }
        }
    }

    override fun onDestroy() {
        closed = true; confirmation?.dismiss(); editor?.dismiss(); io.shutdown()
        super.onDestroy()
    }

    private fun memoryText(item: JSONObject?) = item?.optString("content")?.takeIf { it.isNotBlank() }
        ?: item?.optString("correction").orEmpty()
    private fun memoryPath(item: JSONObject) = "/memories/" + encoded(item.getString("id"))
    private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun dp(value: Int) = UiTheme.dp(this, value)
    private fun square() = LinearLayout.LayoutParams(dp(44), dp(44))
}
