package dev.doppel.developer

import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Explicit local fixture gate. This test never records a microphone or starts a task. */
class EmbeddedAsrLiveTest {
    @Test fun bundledModelDecodesChineseDigitsAppNamesPreviewsAndSilence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("embedded_asr") == "true")
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "asr-fixtures").takeIf { it.isDirectory }
            ?: File(context.getExternalFilesDir(null), "asr-fixtures")
        val expected = listOf("请打开地图搜索北京天安门", "打开微信找到文件传输助手", "请打开支付宝查看账单",
            "用计算器计算一百二十三加四百五十六", "明天上午九点三十分提醒我开会", "打开WPS创建一个新的文档",
            "打开明日方舟进入作战页面", "搜索手机号码一三八零零一三八零零零")
        val type = Class.forName("dev.doppel.sdk.EmbeddedAsr")
        val asr = type.getConstructor(android.content.Context::class.java).newInstance(context)
        val transcribe = type.getMethod("transcribe", ByteArray::class.java, Boolean::class.javaPrimitiveType)
        val report = JSONObject().put("abi", android.os.Build.SUPPORTED_ABIS.joinToString())
            .put("android_sdk", android.os.Build.VERSION.SDK_INT).put("network_required", false)
        val samples = JSONArray()
        val baseline = Debug.getPss()
        val peak = AtomicLong(baseline)
        val running = AtomicBoolean(true)
        val monitor = Thread {
            while (running.get()) {
                peak.updateAndGet { previous -> maxOf(previous, Debug.getPss()) }
                try { Thread.sleep(40) } catch (_: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true; start() }
        var exact = 0
        try {
            for (index in expected.indices) {
                val file = File(directory, "$index.wav")
                assertTrue("Push canonical fixture ${file.absolutePath} first", file.isFile)
                val wav = file.readBytes()
                val start = SystemClock.elapsedRealtime()
                val result = transcribe.invoke(asr, wav, true) as JSONObject
                val text = result.getString("text").replace(Regex("[\\s，。,.!?！？]"), "")
                val matched = expected[index].equals(text, ignoreCase = true)
                if (matched) exact++
                samples.put(result.put("fixture", file.name).put("expected", expected[index]).put("exact_normalized", matched)
                    .put("wall_ms", SystemClock.elapsedRealtime() - start).put("pss_kib", Debug.getPss()))
                assertTrue(result.getBoolean("offline"))
                assertEquals("paraformer-zh-small-int8-2024-03-09", result.getString("model"))
                if (index != 5) assertEquals("Chinese fixture $index", expected[index], text)
            }
            val silence = wav(ShortArray(16000))
            assertEquals("", (transcribe.invoke(asr, silence, true) as JSONObject).getString("text"))
            report.put("silence_empty", true)
            val complete = File(directory, "0.wav").readBytes()
            val pcm = ShortArray((complete.size - 44) / 2)
            ByteBuffer.wrap(complete, 44, complete.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
            val previews = JSONArray()
            for (length in listOf(16000, 32000, 48000)) {
                previews.put(transcribe.invoke(asr, wav(pcm.copyOf(length)), false) as JSONObject)
            }
            val final = transcribe.invoke(asr, complete, true) as JSONObject
            assertEquals(expected.first(), final.getString("text").replace(" ", ""))
            report.put("previews", previews).put("release_result", final).put("preview_then_final_pass", true)
            assertTrue("Representative Chinese baseline", exact >= 7)
        } finally {
            running.set(false); monitor.interrupt(); monitor.join(1000)
            report.put("rows", samples).put("exact_phrases", exact).put("baseline_pss_kib", baseline)
                .put("peak_pss_kib", peak.get()).put("peak_sampling_ms", 40)
            File(context.getExternalFilesDir(null), "embedded-asr-evidence.json").writeText(report.toString(2))
        }
    }

    private fun wav(samples: ShortArray): ByteArray {
        val data = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()); data.putInt(data.capacity() - 8)
        data.put("WAVEfmt ".toByteArray()); data.putInt(16); data.putShort(1); data.putShort(1)
        data.putInt(16000); data.putInt(32000); data.putShort(2); data.putShort(16)
        data.put("data".toByteArray()); data.putInt(samples.size * 2); samples.forEach { data.putShort(it) }
        return data.array()
    }
}
