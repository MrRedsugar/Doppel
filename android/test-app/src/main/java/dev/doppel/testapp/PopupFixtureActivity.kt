package dev.doppel.testapp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/** Disposable real window/permission fixture. Never posts notifications or connects to a server. */
class PopupFixtureActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private var dialogStatus: TextView? = null
    private var dialog: AlertDialog? = null
    private var backgroundClicks = 0
    private var closed = 0
    private var permissionCompleted = false
    private var permissionRequested = false
    private var session = ""
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        session = intent.getStringExtra("session").orEmpty()
        val page = FrameLayout(this)
        page.addView(Button(this).apply {
            text = "底层操作"; isAllCaps = false
            setBackgroundColor(Color.rgb(30, 40, 90)); setTextColor(Color.WHITE)
            setOnClickListener { backgroundClicks++; update() }
        }, FrameLayout.LayoutParams(-1, -1))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE) }
        controls.addView(status)
        fun button(label: String, action: () -> Unit) {
            controls.addView(Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } })
        }
        button("打开普通弹窗", ::showDialog)
        button("延迟显示弹窗") { handler.postDelayed({ showDialog() }, 700L) }
        button("请求通知权限") {
            check(Build.VERSION.SDK_INT >= 33)
            permissionRequested = true; permissionCompleted = false; update()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 73)
        }
        page.addView(controls, FrameLayout.LayoutParams(-1, -2))
        setContentView(page)
        update()
    }

    private fun update() {
        val state = JSONObject().put("session", session).put("background_clicks", backgroundClicks)
            .put("closed", closed).put("permission_requested", permissionRequested).put("permission_completed", permissionCompleted)
            .put("permission_granted", Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        status.text = "底页操作 $backgroundClicks 次 · 关闭弹窗 $closed 次"
        status.contentDescription = "popup-result:$state"
        dialogStatus?.contentDescription = "popup-result:$state"
    }

    private fun showDialog() {
        if (isFinishing || dialog?.isShowing == true) return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.rgb(31, 109, 83))
        }
        dialogStatus = TextView(this).apply { text = "前景弹窗测试"; textSize = 24f; setTextColor(Color.WHITE) }
        panel.addView(dialogStatus, LinearLayout.LayoutParams(-1, dp(110)))
        dialog = AlertDialog.Builder(this).setTitle("独立弹窗").setView(panel).setCancelable(false)
            .setPositiveButton("关闭弹窗") { _, _ -> closed++; dialogStatus = null; update() }.create()
        dialog!!.show()
        update()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 73) { permissionCompleted = true; update() }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); dialog?.dismiss(); dialog = null
        super.onDestroy()
    }
}
