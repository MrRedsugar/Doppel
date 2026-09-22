@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.doppel.developer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.CloudAccountActivity
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.FirstUseConsent
import dev.doppel.sdk.cloud.CloudConnectionService
import dev.doppel.sdk.cloud.CloudSessionStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/** Only invalid local input is submitted. No user session, model, or cloud account is modified. */
class CloudAccountEntryDeviceTest {
    @Test fun securePageRejectsInvalidInputLocallyAndClearsPasswordOnBackground() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        assertTrue("Complete ordinary first-use consent before this page-only test", FirstUseConsent.isAccepted(app))
        assertNull("Do not interrupt an existing device task", DeviceWorkerService.instance)
        assertNull("Use a device with no cloud account; never overwrite a real session", CloudSessionStore(app).load())
        val beforeState = CloudConnectionService.state
        val files = listOf("cloud-session-v1.bin", "cloud-session-v1.bin.bak").map { File(app.noBackupFilesDir, it) }
        val before = files.map { if (it.exists()) it.readBytes().toList() else null }
        val activity = inst.startActivitySync(Intent(app, CloudAccountActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CloudAccountActivity
        fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
        fun views() = all(activity.window.decorView)
        fun field(description: String) = views().filterIsInstance<EditText>().single { it.tag == description }
        fun submit() = views().filterIsInstance<TextView>().single { it.text.toString() == "登录并连接" }.performClick()
        fun hasText(value: String) = views().filterIsInstance<TextView>().any { it.text.toString() == value }
        try {
            inst.waitForIdleSync()
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { trap ->
                trap.soTimeout = 800
                inst.runOnMainSync {
                    assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    assertTrue(hasText("账号与服务器"))
                    assertEquals("", field("cloud_password").text.toString())
                    assertFalse(field("cloud_password").isSaveEnabled)
                    assertFalse(field("cloud_password").isSaveFromParentEnabled)
                    assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, field("cloud_password").importantForAutofill)
                    field("cloud_server").setText("http://127.0.0.1:${trap.localPort}")
                    field("cloud_account").setText("local-ui-probe")
                    field("cloud_password").setText("short")
                    assertTrue(submit())
                    assertTrue(hasText("密码需要 12–128 个字符，空格也是密码的一部分。"))
                    field("cloud_server").setText("http://127.0.0.1:${trap.localPort}")
                    field("cloud_account").setText("")
                    field("cloud_password").setText("  test-password  ")
                    assertTrue(submit())
                    assertTrue(hasText("请输入账号（最多 256 个字符）。"))
                    field("cloud_server").setText("https://user:secret@example.invalid")
                    field("cloud_account").setText("local-ui-probe")
                    field("cloud_password").setText("  test-password  ")
                    assertTrue(submit())
                    assertTrue(hasText("请输入有效的 HTTPS 服务器地址，不能包含账号、密码或查询参数。"))
                }
                try { trap.accept().use { fail("Opening the page or invalid input must never start a request") } }
                catch (_: SocketTimeoutException) { /* Expected: validation completes before HTTP. */ }
            }
            var password: EditText? = null
            inst.runOnMainSync {
                password = field("cloud_password")
                password!!.setText("  unsaved-private-password  ")
                val saved = Bundle()
                inst.callActivityOnSaveInstanceState(activity, saved)
                assertFalse(saved.toString().contains("unsaved-private-password"))
                activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            inst.waitForIdleSync()
            inst.runOnMainSync { assertEquals("", password!!.text.toString()) }
            assertNull(CloudSessionStore(app).load())
            assertEquals("Page must not enable or disable connections", beforeState, CloudConnectionService.state)
            assertEquals("Page must preserve existing encrypted files/tombstones", before, files.map { if (it.exists()) it.readBytes().toList() else null })
        } finally {
            inst.runOnMainSync { activity.finish() }
            inst.waitForIdleSync()
        }
    }
}
