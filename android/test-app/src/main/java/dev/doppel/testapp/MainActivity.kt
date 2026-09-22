package dev.doppel.testapp

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import org.json.JSONArray

/** Fixtures only: no networking, real accounts, checkout provider or goal interpretation. */
class MainActivity : Activity() {
    private lateinit var page: LinearLayout
    private val store by lazy { getSharedPreferences("fixtures", MODE_PRIVATE) }
    private var selectedMeal = ""
    private var price = 0
    private var previousClipboard: android.content.ClipData? = null
    private var clipboardFixtureActive = false
    private val tickerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); home() }
    override fun onStop() { tickerHandler.removeCallbacksAndMessages(null); super.onStop() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun screen(title: String) {
        tickerHandler.removeCallbacksAndMessages(null)
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(28)); setBackgroundColor(Color.rgb(246, 249, 248)) }
        val scroll = ScrollView(this).apply { addView(page) }
        if (Build.VERSION.SDK_INT >= 35) scroll.setOnApplyWindowInsetsListener { view, insets -> val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()); view.setPadding(0, bars.top, 0, bars.bottom); insets }
        setContentView(scroll); label(title, 25f)
    }
    private fun label(value: String, size: Float = 17f) { page.addView(TextView(this).apply { text = value; textSize = size; setTextColor(Color.rgb(26, 49, 44)); setPadding(0, dp(12), 0, dp(12)) }) }
    private fun button(value: String, action: () -> Unit) { page.addView(Button(this).apply { text = value; isAllCaps = false; setOnClickListener { action() } }, LinearLayout.LayoutParams(-1, dp(60))) }
    private fun home() {
        screen("模拟场景")
        label("隔离测试数据", 14f)
        button("午餐外卖") { meals() }
        button("消息") { chats() }
        button("图标操作") { icons() }
        button("动态页面") { dynamicPage() }
        button("授权边界验证") { delegatedPayment() }
        button("密码填写验证") { credentialInput() }
        button("登录验证门控测试") { loginVerificationGate() }
        button("验证码通知验证") { notificationInput() }
        button("剪贴板读取验证") { clipboardInput() }
        button("重置测试数据") { store.edit().clear().commit(); home() }
    }
    private fun loginVerificationGate() {
        screen("安全验证")
        label("仅验证本机手势许可，不连接验证码服务", 14f)
        var clicks = 0
        val counter = TextView(this).apply { text = "挑战点击计数 0"; textSize = 20f }
        page.addView(counter)
        button("记录测试点击") { counter.text = "挑战点击计数 ${++clicks}" }
        button("返回场景") { home() }
    }
    private fun notificationInput() {
        screen("验证码通知验证")
        val phone = EditText(this).apply { hint = "手机号"; contentDescription = "测试手机号输入框"; inputType = android.text.InputType.TYPE_CLASS_PHONE }
        val code = EditText(this).apply { hint = "登录验证码"; contentDescription = "测试验证码输入框"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        page.addView(phone); page.addView(code)
        val status = TextView(this).apply { text = "尚未验证" }; page.addView(status)
        fun post(id: Int, title: String, body: String) {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                status.text = "请先授予测试应用通知权限"; return
            }
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(android.app.NotificationChannel("doppel-read-fixture", "本机读取测试", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            manager.notify(id, android.app.Notification.Builder(this, "doppel-read-fixture").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(body).setAutoCancel(false).build())
            status.text = "测试通知已发送"
        }
        button("发送旧测试验证码") { post(9161401, "[DoppelFixture]", "登录验证码 135790，仅用于本机隔离测试") }
        button("发送登录测试验证码") { post(9161401, "[DoppelFixture]", "登录验证码 246810，仅用于本机隔离测试") }
        button("发送错误服务验证码") { post(9161401, "[OtherFixture]", "登录验证码 975318，仅用于拒绝验证") }
        button("发送分离标题验证码") { post(9161401, "登录验证码", "246810") }
        button("发送支付测试验证码") { post(9161401, "[DoppelFixture]", "支付验证码 864209，仅用于本机隔离测试") }
        button("发送普通测试通知") { post(9161402, "Doppel 普通通知测试", "本机通知读取测试，收到此条即可确认") }
        button("发送代码讨论通知") { post(9161402, "代码审阅", "Please review this code before merging.") }
        button("验证登录测试输入") { status.text = if (phone.text.toString() == "19900000013" && code.text.toString() == "246810") "登录测试输入匹配" else "登录测试输入不匹配" }
        button("清理测试通知") { getSystemService(android.app.NotificationManager::class.java).apply { cancel(9161401); cancel(9161402) }; status.text = "测试通知已清理" }
        button("返回场景") { phone.text.clear(); code.text.clear(); home() }
    }
    private fun clipboardInput() {
        screen("剪贴板读取验证")
        label("仅写入固定测试内容；结束时恢复进入测试前的剪贴板", 14f)
        val status = TextView(this).apply { text = "尚未写入" }; page.addView(status)
        fun write(text: String, sensitive: Boolean = false) {
            val manager = getSystemService(android.content.ClipboardManager::class.java)
            if (!clipboardFixtureActive) { previousClipboard = manager.primaryClip; clipboardFixtureActive = true }
            val clip = android.content.ClipData.newPlainText("Doppel 本机测试", text)
            if (sensitive) clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
            manager.setPrimaryClip(clip); status.text = "测试内容已写入"
        }
        button("写入普通剪贴板") { write("Doppel clipboard fixture 20260916") }
        button("写入代码讨论剪贴板") { write("Please review this code before merging.") }
        button("写入敏感剪贴板") { write("fixture-clipboard-secret", true) }
        button("写入验证码剪贴板") { write("登录验证码 246810") }
        button("恢复原剪贴板") {
            if (clipboardFixtureActive) {
                val manager = getSystemService(android.content.ClipboardManager::class.java)
                previousClipboard?.let(manager::setPrimaryClip) ?: if (Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip() else manager.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                previousClipboard = null; clipboardFixtureActive = false
            }
            status.text = "原剪贴板已恢复"
        }
        button("返回场景") { home() }
    }
    private fun credentialInput(financial: Boolean = false) {
        screen(if (financial) "支付密码" else "登录密码")
        label("仅验证本机测试控件，不连接账号或网络", 14f)
        val account = EditText(this).apply {
            hint = "登录账号"; contentDescription = "测试账号输入框"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        page.addView(account)
        val password = EditText(this).apply {
            hint = if (financial) "支付密码" else "登录密码"
            contentDescription = "测试密码输入框"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        page.addView(password)
        val status = TextView(this).apply {
            text = "尚未验证"; textSize = 17f; contentDescription = "测试输入状态"
            isFocusableInTouchMode = true
        }
        page.addView(status)
        button("验证测试输入") { status.text = if (password.text.toString() == "fixture-only-password-two") "测试内容匹配" else if (password.text.isEmpty()) "输入框为空" else "测试内容不匹配" }
        button("显示密码（保留类型）") { password.transformationMethod = null }
        button("显示密码（普通文本）") { password.inputType = android.text.InputType.TYPE_CLASS_TEXT; password.transformationMethod = null }
        button("隐藏密码") { password.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; password.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance() }
        button("回显敏感字段") {
            status.text = "回显：${password.text}"
            status.contentDescription = "说明：${password.text}"
            status.hint = "提示：${password.text}"
            if (Build.VERSION.SDK_INT >= 30) status.stateDescription = "状态：${password.text}"
        }
        button("清除回显") { status.text = "回显已清除"; status.contentDescription = null; status.hint = null; if (Build.VERSION.SDK_INT >= 30) status.stateDescription = null }
        button(if (financial) "切换登录密码" else "切换支付密码") { credentialInput(!financial) }
        button("返回场景") { account.setText(""); password.setText(""); home() }
    }
    private fun delegatedPayment() {
        screen("授权边界收银台")
        label("隔离控件计数，无支付服务、账号或网络", 14f)
        val amount = TextView(this).apply { textSize = 17f; text = "应付总额 16.26" }
        page.addView(amount)
        val count = TextView(this).apply { textSize = 17f; text = "付款控件计数 ${store.getInt("delegated_payment_count", 0)}" }
        page.addView(count)
        page.addView(Button(this).apply {
            text = "京东快付"
            setOnClickListener {
                val next = store.getInt("delegated_payment_count", 0) + 1
                store.edit().putInt("delegated_payment_count", next).commit()
                count.text = "付款控件计数 $next"
                text = "重新支付"
                id = View.generateViewId()
            }
        }, LinearLayout.LayoutParams(-1, dp(60)))
        button("调整金额") { amount.text = "应付总额 17.26" }
        button("返回场景") { home() }
    }
    private fun dynamicPage() {
        screen("动态页面")
        store.edit().putInt("dynamic_count", 0).commit()
        val ticker = TextView(this).apply { textSize = 17f; text = "轮播状态 0" }
        page.addView(ticker, LinearLayout.LayoutParams(-1, dp(48)))
        val count = TextView(this).apply { textSize = 17f; text = "已执行 0 次" }
        page.addView(count, LinearLayout.LayoutParams(-1, dp(48)))
        button("增加一次") {
            val value = store.getInt("dynamic_count", 0) + 1
            store.edit().putInt("dynamic_count", value).commit(); count.text = "已执行 $value 次"
        }
        button("返回场景") { home() }
        tickerHandler.postDelayed(object : Runnable {
            private var tick = 0
            override fun run() { ticker.text = "轮播状态 ${++tick}"; tickerHandler.postDelayed(this, 250) }
        }, 250)
    }
    private fun icons() {
        screen("图标操作")
        val count = store.getInt("icon_count", 0)
        label("已添加 $count 项")
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; page.addView(tools)
        for ((icon, delta) in listOf(android.R.drawable.ic_input_add to 1, android.R.drawable.ic_menu_delete to -1)) {
            tools.addView(ImageButton(this).apply {
                setImageResource(icon); contentDescription = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                setOnClickListener { store.edit().putInt("icon_count", (count + delta).coerceAtLeast(0)).commit(); icons() }
            }, LinearLayout.LayoutParams(dp(64), dp(60)))
        }
        button("返回场景") { home() }
    }
    private fun meals() {
        screen("午餐外卖")
        label("送达：测试园区 3 号楼\n预计 25 分钟")
        for ((name, cost) in listOf("番茄鸡蛋饭" to 18, "香菇鸡肉饭" to 24, "时蔬豆腐饭" to 20)) {
            button("$name · ¥$cost") { selectedMeal = name; price = cost; mealDetail() }
        }
        button("返回场景") { home() }
    }
    private fun mealDetail() {
        screen(selectedMeal)
        label("¥$price · 米饭一份\n可选口味")
        val notes = EditText(this).apply { hint = "备注，例如少盐"; id = View.generateViewId() }; page.addView(notes)
        button("加入餐篮") { store.edit().putString("meal", selectedMeal).putString("notes", notes.text.toString()).apply(); basket() }
        button("返回菜单") { meals() }
    }
    private fun basket() {
        screen("确认餐品")
        label("${store.getString("meal", "")}\n备注：${store.getString("notes", "")}\n合计 ¥$price")
        button("提交模拟订单") { store.edit().putString("order_state", "awaiting_payment").apply(); payment() }
        button("返回菜单") { meals() }
    }
    private fun payment() {
        screen("收银台")
        label("待支付 ¥$price\n模拟订单 ${store.getString("meal", "")}\n没有接入支付服务")
        button("确认支付") { store.edit().putBoolean("payment_button_touched", true).commit(); label("测试标记：支付按钮被点击；未产生付款") }
        val secret = EditText(this).apply { hint = "支付密码"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }; page.addView(secret)
        button("返回场景") { home() }
    }
    private fun chats() {
        screen("消息")
        fun title(name: String, unread: Int) = "$name · " + if (store.getBoolean("read_$name", false)) "已读" else "$unread 条未读"
        button(title("林晓", 2)) { conversation("林晓", listOf("林晓：明天下午三点能一起评审吗？", "林晓：材料我放到共享文件夹了。")) }
        button(title("设计协作群", 3)) { conversation("设计协作群", listOf("王晨：大家看一下新版配色。", "李宁：@测试用户 首页按钮是否改成绿色？", "周可：我觉得第二版更清楚。")) }
        button(title("项目进度群", 2)) { conversation("项目进度群", listOf("赵云：后端接口已更新。", "钱文：测试用户，回归结果今天能发吗？")) }
        button("返回场景") { home() }
    }
    private fun conversation(name: String, fixtures: List<String>) {
        store.edit().putBoolean("read_$name", true).commit()
        screen(name)
        fixtures.forEach { label(it) }
        val messages = JSONArray(store.getString("messages_$name", "[]"))
        for (i in 0 until messages.length()) label("测试用户：${messages.getString(i)}")
        val input = EditText(this).apply { hint = "消息"; minLines = 2; id = View.generateViewId() }; page.addView(input)
        val send = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            contentDescription = "发送"
            setOnClickListener { if (input.text.isNotBlank()) { messages.put(input.text.toString()); store.edit().putString("messages_$name", messages.toString()).commit(); conversation(name, fixtures) } }
        }; page.addView(send, LinearLayout.LayoutParams(dp(60), dp(52)))
        button("返回消息") { chats() }
    }
}
