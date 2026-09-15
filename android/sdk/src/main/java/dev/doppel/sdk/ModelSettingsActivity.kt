package dev.doppel.sdk

import android.app.Activity
import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Connection and capability settings. Provider requests run off the UI thread. */
open class ModelSettingsActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val activeConnection = AtomicReference<HttpURLConnection?>()
    private val sensitiveFields = mutableListOf<EditText>()
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var providers: ModelProviders
    private var busy = false
    private var message = ""
    private val providerStatus = linkedMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DirectMode.isDeveloperBuild(this)) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        UiTheme.init(this); providers = ModelProviders(this)
        val root = column()
        UiTheme.bind(root) { root.setBackgroundColor(UiTheme.background) }
        UiTheme.window(this, root); setContentView(root)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(20), dp(8)) }
        header.addView(UiTheme.icon(this, UiIcons.back, "返回") { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(UiTheme.text(this, "模型连接", 18f, UiTheme.ink, true)); root.addView(header)
        content = column().apply { setPadding(dp(22), dp(20), dp(22), dp(32)) }
        root.addView(ScrollView(this).apply { addView(content); isVerticalScrollBarEnabled = false }, LinearLayout.LayoutParams(-1, 0, 1f))
        render()
    }
    private fun render() {
        if (isDestroyed || !::content.isInitialized) return
        content.removeAllViews()
        content.addView(UiTheme.text(this, "让手机看懂任务", 25f, UiTheme.ink, true))
        paragraph(content, "选择支持图片的模型，连接你自己的平台账户。费用直接计入平台账户，不扣 Doppel 积分。", 12)
        status = UiTheme.text(this, message, 14f, UiTheme.muted).apply { setPadding(0, dp(16), 0, dp(16)) }
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; content.addView(status)
        val routing = try { providers.routing() } catch (_: Exception) {
            status.text = "保存的模型连接无法读取，请重置后重新配置。"
            button(content, "重置模型连接") { resetDialog() }; return
        }
        val platforms = providers.list()
        val primary = card("默认模型")
        selectionSummary(primary, routing.primary, platforms)
        button(primary, "选择默认模型") { selectModel(false) }
        button(primary, "验证默认模型的视觉能力") { probe(routing.primary, "primary") }
        val enhanced = card("视觉增强")
        enhanced.addView(UiTheme.toggle(this, "独立视觉增强", routing.enhancementEnabled) { enabled ->
            perform("正在保存视觉增强设置") {
                requireIdle(); providers.saveRouting(providers.routing().copy(enhancementEnabled = enabled)); "视觉增强设置已保存"
            }
        }.apply { isEnabled = !busy })
        paragraph(enhanced, "开启后，由增强模型协助处理界面操作，建议选择视觉能力更强的模型。关闭后，仅使用默认模型直接操作手机，减少模型请求。默认模型必须支持图片；开启增强时，增强模型也必须支持图片，并会产生额外费用。")
        if (routing.enhancementEnabled) {
            selectionSummary(enhanced, routing.enhancement, platforms)
            button(enhanced, "选择增强模型") { selectModel(true) }
            button(enhanced, "验证增强模型的视觉能力") { probe(routing.enhancement, "grounding") }
        } else paragraph(enhanced, "当前仅使用默认模型直接操作手机。", 12)
        val connections = card("我的平台")
        platforms.forEach { provider ->
            val credentialLabel = if (providers.hasCredentials(provider.id)) "认证已加密保存" else "待填写 API Key"
            val state = providerStatus[provider.id]?.let { "\n状态：$it" }.orEmpty()
            connections.addView(UiTheme.row(this, provider.name, "$credentialLabel\n${provider.baseUrl}$state", UiIcons.settings) {
                if (!busy) editProvider(provider)
            }.apply { isEnabled = !busy })
        }
        button(connections, "刷新所有平台状态") { refreshProviderStatus() }
        button(connections, "添加平台") { addProvider() }
        if (DirectCredentials(this).hasKey()) button(connections, "导入旧 MiMo 连接") { importLegacy() }
        val ready = providers.isReady()
        if (message.isBlank()) status.text = when {
            DirectMode.isEnabled(this) && ready -> "正在使用本机模型连接"
            ready -> "模型已就绪 · 可以启用本机连接"
            else -> "保存平台认证，并验证所选模型的视觉能力后即可启用"
        }
        button(content, if (DirectMode.isEnabled(this)) "重新检查并启用本机连接" else "启用本机连接", true) {
            perform("正在启用本机连接") {
                check(providers.isReady()) { "请先保存认证，并完成所选模型的视觉验证" }
                DirectMode.configure(this, true); "本机连接已启用"
            }
        }
        if (DirectMode.isEnabled(this)) button(content, "切回网关服务") {
            perform("正在切换连接") { DirectMode.configure(this, false); "已切回网关服务" }
        }
        paragraph(content, "屏幕截图和任务内容会发送到所选平台。API Key 与附加请求头由 Android Keystore 加密保存在本机，不参与系统备份。视觉验证只上传生成的色块图片，也会产生少量平台费用。", 22)
    }
    private fun selectionSummary(parent: LinearLayout, selected: ModelSelection, platforms: List<ModelProvider>) {
        val provider = platforms.firstOrNull { it.id == selected.providerId }
        parent.addView(UiTheme.text(this, selected.model, 18f, UiTheme.ink, true).apply { setPadding(0, dp(14), 0, dp(7)) })
        paragraph(parent, provider?.name ?: "请重新选择平台")
        val vision = provider?.let { providers.vision(it.id, selected.model) } ?: ModelVision.UNKNOWN
        parent.addView(UiTheme.text(this, vision.label, 13f, if (vision == ModelVision.VERIFIED) UiTheme.green else UiTheme.muted).apply { setPadding(0, dp(10), 0, 0) })
    }
    private fun addProvider() {
        val presets = ModelProvider.presets()
        UiDialog.Builder(this).setTitle("添加模型平台")
            .setItems((presets.map { it.name } + "自定义兼容平台").toTypedArray()) { _, index ->
                val preset = presets.getOrNull(index)
                if (preset == null) editProvider(ModelProvider("", "", ""))
                else editProvider(if (providers.list().any { it.id == preset.id }) preset.copy(id = "") else preset)
            }.setNegativeButton("取消", null).show()
    }
    private fun editProvider(provider: ModelProvider) {
        val exists = providers.list().any { it.id == provider.id }
        val form = column()
        val name = input(form, "平台名称", provider.name)
        val address = input(form, "API 地址", provider.baseUrl)
        address.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        paragraph(form, "填写兼容接口的基础地址，例如 https://example.com/v1。支持直接粘贴 /chat/completions 地址。")
        val key = input(form, if (exists) "新的 API Key（留空保留）" else "API Key", secret = true)
        paragraph(form, "附加请求头（可选 JSON）", 12)
        val names = if (exists) providers.headerNames(provider.id) else emptyList()
        if (names.isNotEmpty()) paragraph(form, "已加密保存：${names.joinToString("、")}。留空保留。")
        val headers = input(form, "例如 {\"X-Tenant\":\"名称\"}", secret = true)
        val clearHeaders = UiTheme.check(this, "清除已保存的附加请求头", false)
        if (exists && names.isNotEmpty()) form.addView(clearHeaders)
        paragraph(form, "更换 API 域名时，请重新输入认证信息。保存连接后，视觉能力需要重新验证。", 12)
        val error = UiTheme.text(this, "", 13f, UiTheme.danger).apply { setPadding(0, dp(10), 0, 0) }; form.addView(error)
        val dialog = UiDialog.Builder(this).setTitle(if (exists) "编辑平台" else "添加平台").setView(form)
            .setPositiveButton("保存平台", null).setNegativeButton("取消", null)
        if (exists) dialog.setNeutralButton("删除", { _, _ -> deleteProvider(provider) })
        val sheet = dialog.create(); sheet.show()
        sheet.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val values = runCatching {
                val updated = provider.copy(name = name.text.toString(), baseUrl = ModelEndpoint.normalize(address.text.toString()))
                val changedHost = exists && ModelEndpoint.authenticationMustChange(provider.baseUrl, updated.baseUrl)
                val newHeaders = when {
                    clearHeaders.isChecked -> emptyMap()
                    headers.text.isNotBlank() -> ModelHeaders.parse(headers.text.toString())
                    changedHost || !exists -> emptyMap()
                    else -> null
                }
                val newKey = key.text.toString().trim().takeIf { it.isNotBlank() } ?: if (!exists || changedHost && newHeaders?.isNotEmpty() == true) "" else null
                require(!changedHost || newKey != null) { "API 域名已更换，请重新输入 API Key 或认证请求头" }
                Triple(updated, newKey, newHeaders)
            }
            if (values.isFailure) { error.text = safeMessage(values.exceptionOrNull()); return@setOnClickListener }
            val (updated, newKey, newHeaders) = values.getOrThrow()
            key.setText(""); headers.setText(""); sheet.dismiss()
            perform("正在加密保存平台") {
                requireIdle(); providers.saveProvider(updated, newKey, newHeaders); "平台已保存 · 可选择模型并验证视觉能力"
            }
        }
        sheet.setOnDismissListener { key.setText(""); headers.setText(""); sensitiveFields.remove(key); sensitiveFields.remove(headers) }
    }
    private fun deleteProvider(provider: ModelProvider) {
        UiDialog.Builder(this).setTitle("删除 ${provider.name}")
            .setMessage("删除此平台保存的认证信息与视觉验证记录。正在使用的平台需要先替换模型选择。")
            .setPositiveButton("删除平台") { _, _ -> perform("正在删除平台") { requireIdle(); providers.deleteProvider(provider.id); "平台已删除" } }
            .setNegativeButton("保留", null).show()
    }
    private fun selectModel(enhancement: Boolean) {
        val options = providers.list()
        if (options.isEmpty()) { addProvider(); return }
        val selected = if (enhancement) providers.routing().enhancement else providers.routing().primary
        var platform = options.firstOrNull { it.id == selected.providerId } ?: options.first()
        val form = column()
        val model = UiTheme.field(this, "输入模型名称", selected.model).apply { maxLines = 1 }
        paragraph(form, "平台")
        form.addView(UiTheme.selector(this, "选择平台", options.map { it.name }, options.indexOf(platform)) { index ->
            platform = options[index]
            model.setText(when (platform.preset) { "qwen" -> if (enhancement) "qwen3.8-max" else "qwen3.8-flash"; "mimo" -> "mimo-v2.5-pro"; "deepseek" -> "deepseek-flash"; else -> "" })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        paragraph(form, "模型名称", 16)
        form.addView(model, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val localStatus = UiTheme.text(this, "可手工输入平台支持的视觉模型。", 13f, UiTheme.muted).apply { setPadding(0, dp(10), 0, 0) }; form.addView(localStatus)
        val fetch = UiTheme.command(this, "获取平台模型列表") { }
        form.addView(fetch, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        val sheet = UiDialog.Builder(this).setTitle(if (enhancement) "选择增强模型" else "选择默认模型").setView(form)
            .setPositiveButton("保存选择", null).setNegativeButton("取消", null).create()
        fetch.setOnClickListener {
            if (busy) return@setOnClickListener
            val current = platform
            busy = true; fetch.isEnabled = false; localStatus.text = "正在获取模型列表…"
            io.execute {
                val result = runCatching { ModelApi(this).discoverModels(current.id) { activeConnection.set(it) } }; activeConnection.set(null)
                runOnUiThread {
                    busy = false
                    if (isDestroyed || !sheet.isShowing) return@runOnUiThread
                    fetch.isEnabled = true
                    if (platform.id != current.id) { localStatus.text = "平台已改变，请重新获取列表"; return@runOnUiThread }
                    val models = result.getOrNull()
                    when {
                        result.isFailure -> localStatus.text = safeMessage(result.exceptionOrNull()) + "；也可以手工输入模型名称。"
                        models.isNullOrEmpty() -> localStatus.text = "平台未提供模型列表，请手工输入模型名称。"
                        else -> { localStatus.text = "找到 ${models.size} 个模型；选择后仍需验证图片能力。"; chooseDiscoveredModel(models, model) }
                    }
                }
            }
        }
        sheet.show()
        sheet.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            if (busy) { localStatus.text = "正在获取模型列表，请稍候"; return@setOnClickListener }
            val choice = ModelSelection(platform.id, model.text.toString().trim())
            if (choice.model.isBlank()) { localStatus.text = "请输入或选择模型名称"; return@setOnClickListener }
            sheet.dismiss()
            perform("正在保存模型选择") {
                requireIdle(); val current = providers.routing()
                val next = if (enhancement) current.copy(enhancement = choice, enhancementEnabled = true)
                else current.copy(primary = choice) // Only the explicit enhancement control changes execution mode.
                providers.saveRouting(next); "模型选择已保存 · 请验证视觉能力"
            }
        }
    }
    private fun chooseDiscoveredModel(models: List<String>, target: EditText) {
        val form = column(); val search = UiTheme.field(this, "搜索模型"); form.addView(search)
        val results = column(); form.addView(results)
        val sheet = UiDialog.Builder(this).setTitle("平台模型列表").setView(form).setNegativeButton("关闭", null).create()
        fun update(query: String) {
            results.removeAllViews()
            val matching = models.filter { it.contains(query.trim(), ignoreCase = true) }
            paragraph(results, if (matching.isEmpty()) "没有匹配模型，可返回后手工输入。" else "${matching.size} 个匹配结果", 10)
            matching.take(60).forEach { value -> results.addView(UiTheme.command(this, value) { target.setText(value); sheet.dismiss() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) }) }
            if (matching.size > 60) paragraph(results, "输入更完整的名称以缩小结果。", 10)
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { update(s.toString()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        update(""); sheet.show()
    }
    private fun probe(selected: ModelSelection, role: String) {
        perform("正在验证 ${selected.model}，仅上传测试色块图片…") {
            val result = ModelApi(this).probeVision(selected.providerId, selected.model, role) { activeConnection.set(it) }
            if (result.vision == ModelVision.VERIFIED) DirectMode.enableConfiguredDefault(this)
            "${result.vision.label} · ${result.message}（${result.elapsedMs / 1000} 秒）" +
                if (providers.isReady() && DirectMode.isEnabled(this)) " · 本机连接已启用" else ""
        }
    }
    private fun refreshProviderStatus() {
        if (busy) return
        busy = true; message = "正在检查所有平台连接…"; render()
        val snapshot = providers.list()
        io.execute {
            val results = snapshot.associate { provider ->
                if (Thread.currentThread().isInterrupted || isDestroyed) return@execute
                provider.id to runCatching {
                    check(providers.hasCredentials(provider.id)) { "未配置认证" }
                    val count = try { ModelApi(this).discoverModels(provider.id) { activeConnection.set(it) } }
                        finally { activeConnection.set(null) }
                    if (count.isEmpty()) "连接正常 · 未提供模型列表" else "连接正常 · ${count.size} 个模型"
                }.getOrElse { error ->
                    when (error.message.orEmpty()) {
                        "未配置认证" -> "未配置认证"
                        else -> "连接失败 · ${safeMessage(error)}"
                    }
                }
            }
            runOnUiThread {
                providerStatus.clear(); providerStatus.putAll(results)
                busy = false; message = "平台状态已刷新（${results.size} 个）"; if (!isDestroyed) render()
            }
        }
    }
    private fun importLegacy() {
        UiDialog.Builder(this).setTitle("导入旧 MiMo 连接")
            .setMessage("仅将旧密钥复制到小米 MiMo 官方接口，并将默认模型设为 mimo-v2.5-pro。旧凭据会保留；导入后需验证图片能力。")
            .setPositiveButton("导入 MiMo") { _, _ -> perform("正在导入旧 MiMo") { requireIdle(); providers.importLegacyMimo(); "已导入 MiMo · 请验证视觉能力" } }
            .setNegativeButton("取消", null).show()
    }
    private fun resetDialog() {
        UiDialog.Builder(this).setTitle("重置模型连接")
            .setMessage("清除所有已保存平台和认证信息，恢复未配置的千问预设。旧 MiMo 凭据仍会保留。")
            .setPositiveButton("重置") { _, _ -> perform("正在重置模型连接") { requireIdle(); if (DirectMode.isEnabled(this)) DirectMode.configure(this, false); providers.reset(); "模型连接已重置" } }
            .setNegativeButton("取消", null).show()
    }
    private fun perform(label: String, operation: () -> String) {
        if (busy) return
        busy = true; message = label; render()
        io.execute {
            val result = runCatching(operation); activeConnection.set(null)
            runOnUiThread { busy = false; if (!isDestroyed) { message = result.getOrElse { safeMessage(it) }; render() } }
        }
    }
    private fun requireIdle() { check(!DirectRuntime.get(this).hasUnfinishedRun()) { "请先结束本机任务再修改模型连接" } }
    private fun safeMessage(error: Throwable?): String = when (error) {
        is IllegalArgumentException, is IllegalStateException, is java.io.IOException -> error.message?.take(200) ?: "操作未完成，请重试"
        else -> "操作未完成，请检查配置后重试"
    }
    private fun card(title: String): LinearLayout = column().apply {
        background = UiTheme.glass(this@ModelSettingsActivity, 22); setPadding(dp(18), dp(18), dp(18), dp(18))
        addView(UiTheme.text(this@ModelSettingsActivity, title, 16f, UiTheme.ink, true))
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
    }
    private fun paragraph(parent: LinearLayout, value: String, top: Int = 4) {
        parent.addView(UiTheme.text(this, value, 13f, UiTheme.muted).apply { setLineSpacing(dp(3).toFloat(), 1f); setPadding(0, dp(top), 0, 0) })
    }
    private fun button(parent: LinearLayout, label: String, primary: Boolean = false, action: () -> Unit) {
        parent.addView(UiTheme.command(this, label, primary, action).apply { isEnabled = !busy }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
    }
    private fun input(parent: LinearLayout, hint: String, initial: String = "", secret: Boolean = false): EditText {
        val field = UiTheme.field(this, hint, initial, secret).apply { maxLines = 1; isSingleLine = true }
        if (secret) {
            field.isSaveEnabled = false; field.isSaveFromParentEnabled = false
            field.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            field.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            field.setTextIsSelectable(false); field.isLongClickable = false; sensitiveFields.add(field)
        }
        parent.addView(field, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(10) }); return field
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    override fun onResume() { super.onResume(); DirectMode.enterSettings(this); DeviceWorkerService.instance?.suspendLocally() }
    override fun onPause() {
        sensitiveFields.forEach { it.setText("") }; getSystemService(AutofillManager::class.java)?.cancel()
        DirectMode.leaveSettings(this); super.onPause()
    }
    override fun onDestroy() { activeConnection.getAndSet(null)?.disconnect(); io.shutdownNow(); DirectMode.leaveSettings(this); super.onDestroy() }
    private fun dp(value: Int) = UiTheme.dp(this, value)
}
