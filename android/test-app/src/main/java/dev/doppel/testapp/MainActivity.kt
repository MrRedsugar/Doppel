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
        button("重置测试数据") { store.edit().clear().commit(); home() }
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
