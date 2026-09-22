package dev.doppel.testapp

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Disposable, static IME pixels. Enabled/selected only by the external device-test host. */
class CaptureFixtureInputMethodService : InputMethodService() {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onEvaluateFullscreenMode() = false
    override fun onEvaluateInputViewShown() = true

    override fun onCreateInputView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        setBackgroundColor(Color.rgb(38, 46, 60))
        contentDescription = "Doppel 截图测试键盘"
        addView(TextView(context).apply {
            text = "Doppel · Test keyboard"; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(-1, dp(28)))
        for (letters in listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")) {
            addView(LinearLayout(context).apply {
                for (letter in letters) addView(Button(context).apply {
                    text = letter.toString(); textSize = 16f; minWidth = 0
                    setPadding(0, 0, 0, 0)
                    setOnClickListener { currentInputConnection?.commitText(letter.toString().lowercase(), 1) }
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        addView(LinearLayout(context).apply {
            fun key(label: String, action: () -> Unit) {
                addView(Button(context).apply { text = label; minWidth = 0; setOnClickListener { action() } },
                    LinearLayout.LayoutParams(0, dp(42), 1f))
            }
            key("Space") { currentInputConnection?.commitText(" ", 1) }
            key("Delete") { currentInputConnection?.deleteSurroundingText(1, 0) }
            key("Hide") { requestHideSelf(0) }
        }, LinearLayout.LayoutParams(-1, -2))
    }
}
