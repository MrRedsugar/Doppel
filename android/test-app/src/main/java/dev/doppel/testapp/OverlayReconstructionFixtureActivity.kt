package dev.doppel.testapp

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View

/** Static, disposable pixels for screenshot reconstruction measurements, including fine text. */
class OverlayReconstructionFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 5894 // Fullscreen, layout stable, immersive sticky.
        setContentView(object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(Color.WHITE)
                for (y in 0 until height step 16) for (x in 0 until width step 16) {
                    paint.color = Color.rgb((x * 7 + y * 3) % 256, (x + y * 5) % 256, (x * 3 + y) % 256)
                    canvas.drawRect(x.toFloat(), y.toFloat(), (x + 16).toFloat(), (y + 16).toFloat(), paint)
                }
                for (y in 20 until height step 64) {
                    paint.color = Color.WHITE
                    canvas.drawRect(0f, y.toFloat(), width.toFloat(), (y + 34).toFloat(), paint)
                    paint.color = Color.BLACK; paint.textSize = 22f
                    canvas.drawText("Doppel 0123456789 登录 确认 取消 | ABC xyz".repeat(4), 0f, (y + 26).toFloat(), paint)
                }
            }
        })
    }
}
