package dev.doppel.testapp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.UUID

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
    private val creation = UUID.randomUUID().toString()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (intent.getBooleanExtra("capture_secure", false)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        session = intent.getStringExtra("session").orEmpty()
        val page = FrameLayout(this)
        val background = Button(this).apply {
            text = "底层操作"; isAllCaps = false
            stateListAnimator = null; elevation = 0f
            setBackgroundColor(Color.rgb(30, 40, 90)); setTextColor(Color.WHITE)
            setOnClickListener { backgroundClicks++; update() }
        }
        page.addView(background, FrameLayout.LayoutParams(-1, -1))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE) }
        controls.addView(status)
        if (intent.getBooleanExtra("capture_password", false)) controls.addView(EditText(this).apply {
            contentDescription = "截图测试密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setBackgroundColor(Color.rgb(16, 78, 56)); setTextColor(Color.WHITE)
            setText("fixture-only-screenshot-password")
            isFocusable = false; isCursorVisible = false; showSoftInputOnFocus = false
        })
        fun button(label: String, action: () -> Unit) {
            controls.addView(Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } })
        }
        button("打开普通弹窗", ::showDialog)
        button("延迟显示弹窗") { handler.postDelayed({ showDialog() }, 700L) }
        button("请求通知权限") {
            check(Build.VERSION.SDK_INT >= 33)
            permissionRequested = true; permissionCompleted = false; update()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 73)
            // Opt-in evidence that a covered activity is recaptured live, never an old PNG.
            if (intent.getBooleanExtra("capture_controls", false)) handler.postDelayed({
                background.setBackgroundColor(Color.rgb(160, 45, 90))
            }, 450L)
        }
        if (intent.getBooleanExtra("capture_controls", false)) button("重建测试窗口") {
            // Activity.recreate() may preserve its Window. Reopen only this disposable task
            // to exercise a genuinely destroyed surface and newly assigned window ID.
            startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        page.addView(controls, FrameLayout.LayoutParams(-1, -2))
        setContentView(page)
        update()
    }

    private fun update() {
        val state = JSONObject().put("session", session).put("creation", creation).put("background_clicks", backgroundClicks)
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
