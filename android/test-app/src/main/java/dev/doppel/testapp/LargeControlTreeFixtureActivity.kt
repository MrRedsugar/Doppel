package dev.doppel.testapp

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView

/** More than 400 real, visible accessibility nodes; no network or user data. */
class LargeControlTreeFixtureActivity : Activity() {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val page = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "大树扫描夹具"
        }
        setContentView(page)
        val width = resources.displayMetrics.widthPixels
        fun place(view: View, y: Int, height: Int = 44) {
            page.addView(view, FrameLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(y) })
        }
        var shallow = 0; var deep = 0; var target = 0; var description = 0; var refreshes = 0
        val feedback = TextView(this).apply { textSize = 17f; setTextColor(Color.BLACK) }
        fun update() { feedback.text = "浅层 $shallow · 深层 $deep · 目标 $target · 描述 $description" }
        place(feedback, 0); update()
        place(TextView(this).apply { id = android.R.id.checkbox; text = "前缀触发控件" }, 44, 30)
        // This branch occurs first in sibling order, but its duplicate target is deeper.
        val branch = FrameLayout(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "先出现的深层容器"
        }
        place(branch, 232)
        branch.addView(Button(this).apply {
            id = android.R.id.button1; text = "深层同ID目标"; isAllCaps = false
            setOnClickListener { deep++; update() }
        }, FrameLayout.LayoutParams(-1, -1))
        repeat(450) { index ->
            page.addView(TextView(this).apply {
                text = "·"; textSize = 4f; setTextColor(Color.DKGRAY)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = "填充节点 $index"
            }, FrameLayout.LayoutParams((width / 30).coerceAtLeast(1), dp(8)).apply {
                leftMargin = (index % 30) * (width / 30)
                topMargin = dp(84 + (index / 30) * 8)
            })
        }
        place(Button(this).apply {
            id = android.R.id.button1; text = "末尾浅层目标"; isAllCaps = false
            setOnClickListener { shallow++; update() }
        }, 280)
        place(Button(this).apply {
            id = android.R.id.button2; text = "末尾独立目标"; isAllCaps = false
            setOnClickListener { target++; update() }
        }, 328)
        place(Button(this).apply {
            contentDescription = "末尾描述目标"; isAllCaps = false
            setOnClickListener { description++; update() }
        }, 376)
        place(Button(this).apply {
            text = "刷新大树页面"; isAllCaps = false
            setOnClickListener { contentDescription = "刷新次数 ${++refreshes}" }
        }, 424)
    }
}
