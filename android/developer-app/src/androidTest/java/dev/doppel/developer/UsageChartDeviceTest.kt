package dev.doppel.developer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.UsageChartView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** Regression found in visual review: a one-day path rendered only the grid.
 * Real Android Canvas rendering; no Activity, user data, model or network requests.
 */
class UsageChartDeviceTest {
    @Test fun singleAndMultipleDaysRenderBothSeriesAndTheirActualDates() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val density = context.resources.displayMetrics.density
        val width = (320 * density).roundToInt()
        val height = (132 * density).roundToInt()
        val folder = File(context.getExternalFilesDir(null), "usage-chart/${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}")
            .apply { check(mkdirs()) }
        val rows = JSONArray()
        val report = JSONObject().put("status", "running").put("origin", "entries-final/03-usage.png visual review")
            .put("scope", "real Android Canvas, single and multiple day fixture rendering")
            .put("density", density).put("model_requests", 0).put("submitted_tasks", 0).put("rows", rows)
        fun differences(a: Bitmap, b: Bitmap, left: Int, right: Int, top: Int, bottom: Int): Int {
            var count = 0
            for (y in top until bottom) for (x in left until right) if (a.getPixel(x, y) != b.getPixel(x, y)) count++
            return count
        }
        try {
            inst.runOnMainSync {
                for (size in listOf(1, 3)) {
                    val label = if (size == 1) "single" else "multiple"
                    val dates = if (size == 1) listOf("2026-09-16") else listOf("2026-09-10", "2026-09-13", "2026-09-16")
                    val bitmaps = mutableListOf<Bitmap>()
                    fun render(name: String, input: LongArray, output: LongArray, labels: List<String> = dates): Bitmap {
                        val view = UsageChartView(context).apply { setSeries(input, output, labels) }
                        labels.filter { it.isNotEmpty() }.forEach { assertTrue("Retain every actual date for accessibility", view.contentDescription?.contains(it) == true) }
                        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        view.layout(0, 0, width, height)
                        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                            bitmaps += bitmap
                            view.draw(Canvas(bitmap))
                            File(folder, "$label-$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        }
                    }
                    try {
                        val low = if (size == 1) longArrayOf(25) else longArrayOf(25, 50, 25)
                        val high = if (size == 1) longArrayOf(75) else longArrayOf(75, 25, 75)
                        val fixed = LongArray(size) { 100 }
                        val inputA = render("input-a", low, fixed)
                        val inputB = render("input-b", high, fixed)
                        val outputA = render("output-a", fixed, low)
                        val outputB = render("output-b", fixed, high)
                        // Max=100, dates, grid and dimensions stay identical. Only that series
                        // can change pixels here; a moveTo-only empty path cannot pass.
                        val inputChanges = differences(inputA, inputB, width / 3, width * 2 / 3, 0, height * 2 / 3)
                        val outputChanges = differences(outputA, outputB, width / 3, width * 2 / 3, 0, height * 2 / 3)
                        val otherDates = if (size == 1) listOf("2025-01-02") else listOf("2025-01-02", "2025-01-11", "2025-02-03")
                        val changedDates = render("changed-dates", low, fixed, otherDates)
                        val dateChanges = differences(inputA, changedDates, 0, width, height * 3 / 4, height)
                        val row = JSONObject().put("days", size).put("input_changed_pixels", inputChanges)
                            .put("output_changed_pixels", outputChanges).put("date_changed_pixels", dateChanges)
                        rows.put(row)
                        assertTrue("$label input series must render beyond the fixed grid", inputChanges > 0)
                        assertTrue("$label output series must render beyond the fixed grid", outputChanges > 0)
                        assertTrue("$label changing actual dates must change visible bottom text", dateChanges > 0)
                        if (size > 1) {
                            // This interior strip lies between the three sample positions,
                            // so moving isolated markers without connecting lines is insufficient.
                            val lineChanges = differences(inputA, inputB, width / 4, width / 3, 0, height * 3 / 4)
                            row.put("between_sample_changed_pixels", lineChanges)
                            assertTrue("Multiple days must draw connecting line segments", lineChanges > 0)
                        }
                    } finally { bitmaps.forEach(Bitmap::recycle) }
                }
            }
            report.put("status", "passed")
        } finally {
            if (report.optString("status") != "passed") report.put("status", "failed")
            File(folder, "report.json").writeText(report.toString(2))
            inst.sendStatus(0, Bundle().apply { putString("stream", "\nUsage chart evidence: ${folder.absolutePath}/report.json\n") })
        }
    }
}
