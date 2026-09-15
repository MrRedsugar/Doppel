package dev.doppel.developer

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DirectMode
import dev.doppel.sdk.DirectSkills
import dev.doppel.sdk.ExtensionSettingsActivity
import dev.doppel.sdk.FirstUseConsent
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A unique local package through the actual Activity import/read/delete path; no model or network.
 * The system file chooser result is supplied by an instrumentation monitor with a real content URI.
 * This validates the content-provider import path, not navigation within Android's Documents UI.
 */
class SkillImportUiDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val automation by lazy { inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply {
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    } }
    private fun <T> main(block: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        var value: T? = null; inst.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < until) { if (predicate()) return; Thread.sleep(60) }
        assertTrue(label, predicate())
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun matching(label: String, click: Boolean): Boolean {
        fun visit(node: AccessibilityNodeInfo): Boolean {
            if (node.isVisibleToUser && node.text?.toString() == label) {
                if (!click) return true
                var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
                while (current != null) {
                    val previous = current
                    if (previous.isClickable && previous.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { previous.recycle(); return true }
                    current = previous.parent; previous.recycle()
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                try { if (visit(child)) return true } finally { child.recycle() }
            }
            return false
        }
        val windows = automation.windows.sortedByDescending { it.layer }
        try { return windows.any { window -> window.root?.let { root -> try { visit(root) } finally { root.recycle() } } == true } }
        finally { windows.forEach { it.recycle() } }
    }
    private fun click(label: String) = await("Missing clickable text: $label") { matching(label, true) }
    private fun visible(label: String) = await("Missing visible text: $label") { matching(label, false) }
    private fun openPage(): ExtensionSettingsActivity = inst.startActivitySync(
        // NEW_TASK alone may reuse an earlier task with an unfinished DocumentsUI on top.
        Intent(context, ExtensionSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)) as ExtensionSettingsActivity
    private fun closePage(page: Activity?) {
        if (page == null) return
        main { if (!page.isDestroyed) { if (page.isTaskRoot) page.finishAndRemoveTask() else page.finish() } }
        await("Owned Skills page did not close") { main { page.isDestroyed } }
    }
    private fun fingerprint(folder: File): Map<String, String> = if (!folder.isDirectory) emptyMap() else
        folder.walkTopDown().filter { it.isFile }.associate { file -> file.relativeTo(folder).invariantSeparatorsPath to
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) } }

    @Test fun realDocumentsPickerImportsOwnDownloadAndPreservesExistingSkills() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        assertTrue(FirstUseConsent.isAccepted(context)); assertTrue(DirectMode.isEnabled(context))
        assertNull("Do not navigate away from a running task", DeviceWorkerService.instance)
        val skills = DirectSkills(context)
        val original = fingerprint(File(context.noBackupFilesDir, "direct-skills-v1"))
        val originalNames = skills.enabledState()
        assertTrue(originalNames.size < 50)
        val name = "qa-document-${UUID.randomUUID().toString().take(8)}"
        val fileName = "$name.zip"
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }))
        var owner: Activity? = null
        val evidence = File(context.getExternalFilesDir(null), "real-skill-picker/$name").apply { check(mkdirs()) }
        fun screenshot(label: String) {
            val bitmap = requireNotNull(automation.takeScreenshot())
            try { File(evidence, "$label.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
        try {
            ZipOutputStream(requireNotNull(resolver.openOutputStream(uri))).use { zip ->
                zip.putNextEntry(ZipEntry("SKILL.md"))
                zip.write("---\nname: $name\ndescription: 真实文件选择器导入验收\n---\n这是测试资料，不操作设备。\n".toByteArray())
                zip.closeEntry()
            }
            check(resolver.update(uri, android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            }, null, null) == 1)
            val page = openPage()
            owner = page
            await("Import entry not ready") { main { views(page.window.decorView).any { it.contentDescription == "导入 Skills" && it.isEnabled } } }
            main { assertTrue(views(page.window.decorView).first { it.contentDescription == "导入 Skills" }.performClick()) }
            await("Real DocumentsUI must be foreground; no chooser monitor is installed") {
                val root = automation.rootInActiveWindow
                try { root?.packageName?.toString()?.contains("documentsui") == true } finally { root?.recycle() }
            }
            screenshot("01-system-picker")
            if (!matching(fileName, false)) {
                fun roots(node: AccessibilityNodeInfo): Boolean {
                    if (node.isClickable && node.isVisibleToUser &&
                        node.contentDescription?.toString() in setOf("显示根目录", "Show roots", "显示导航抽屉", "Open navigation drawer"))
                        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                        try { if (roots(child)) return true } finally { child.recycle() }
                    }
                    return false
                }
                await("DocumentsUI navigation drawer unavailable") {
                    val root = automation.rootInActiveWindow
                    try { root != null && roots(root) } finally { root?.recycle() }
                }
                await("Downloads location unavailable") { matching("下载", true) || matching("Downloads", true) }
            }
            visible(fileName); screenshot("02-download-selected")
            click(fileName)
            await("Actual system selection must import and persist the package") { skills.read(name).optBoolean("found") }
            visible("真实文件选择器导入验收"); screenshot("03-import-result")
            assertEquals("imported", skills.read(name).getString("source"))
            File(evidence, "result.json").writeText(org.json.JSONObject().put("passed", true)
                .put("real_documents_ui", true).put("model_calls", 0).put("skill_name", name).toString(2))
        } finally {
            val closed = runCatching { closePage(owner) }
            if (name in skills.enabledState()) skills.delete(name)
            assertEquals(1, resolver.delete(uri, null, null))
            assertEquals(originalNames, skills.enabledState())
            assertEquals(original, fingerprint(File(context.noBackupFilesDir, "direct-skills-v1")))
            closed.getOrThrow()
        }
    }

    /** Real list/import storage with its monitor held to make the lifecycle race deterministic.
     * Results enter the Android callback directly; DocumentsUI navigation is tested separately.
     */
    @Test fun selectionDuringInitialLoadSurvivesRecreationWithoutDuplicateOrClosedPageImport() {
        assertTrue(FirstUseConsent.isAccepted(context))
        assertTrue(DirectMode.isEnabled(context))
        assertTrue(DeviceWorkerService.instance?.isPaused != false)
        val skills = DirectSkills(context)
        val originalNames = skills.enabledState()
        assertTrue(originalNames.size < 50)
        val folder = File(context.noBackupFilesDir, "direct-skills-v1")
        val originalFiles = fingerprint(folder)
        val store = DirectSkills::class.java.getDeclaredField("store").apply { isAccessible = true }.get(skills)
        val name = "qa-picker-race-${UUID.randomUUID().toString().take(8)}"
        val cancelledName = "$name-cancel"
        val documents = mutableListOf<File>()
        fun document(skillName: String, content: String = "---\nname: $skillName\ndescription: 文件选择结果保留测试\n---\n仅供导入验收，不执行任务。\n"): Uri {
            val file = File(context.cacheDir, "documents/$skillName.md")
            check(file.parentFile.isDirectory || file.parentFile.mkdirs())
            file.writeText(content); documents += file
            return Class.forName("androidx.core.content.FileProvider")
                .getMethod("getUriForFile", Context::class.java, String::class.java, File::class.java)
                .invoke(null, context, "${context.packageName}.documents", file) as Uri
        }
        val uri = document(name)
        val cancelledUri = document(cancelledName)
        val malformedUri = document("$name-bad", "This is not a Skill package")
        val result = ExtensionSettingsActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
        fun deliver(page: ExtensionSettingsActivity, value: Uri) = main {
            result.invoke(page, 41, Activity.RESULT_OK, Intent().setData(value).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        fun busy(page: ExtensionSettingsActivity): Boolean = main {
            ExtensionSettingsActivity::class.java.getDeclaredField("busy").apply { isAccessible = true }.getBoolean(page)
        }
        var owner: ExtensionSettingsActivity? = null
        try {
            synchronized(store) {
                val initial = openPage(); owner = initial
                assertTrue("The real initial list request must still be blocked", busy(initial))
                deliver(initial, uri); deliver(initial, uri)
                main { initial.recreate() }
                await("Recreated extension page did not resume") {
                    owner = main { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<ExtensionSettingsActivity>().firstOrNull { it !== initial } }
                    owner != null
                }
                assertTrue(busy(owner!!))
            }
            await("Selection was lost across the pending list request and recreation") { skills.read(name).optBoolean("found") }
            await("Import and detail load did not finish") { !busy(owner!!) }
            visible("文件选择结果保留测试")
            val status = main { ExtensionSettingsActivity::class.java.getDeclaredField("status").apply { isAccessible = true }
                .get(owner) as android.widget.TextView }
            assertEquals("A repeated result must not attempt the same import twice", "", main { status.text.toString() })
            click("关闭")
            closePage(owner)

            lateinit var closedExecutor: java.util.concurrent.ExecutorService
            synchronized(store) {
                val closing = openPage(); owner = closing
                assertTrue(busy(closing)); deliver(closing, cancelledUri)
                closedExecutor = ExtensionSettingsActivity::class.java.getDeclaredField("io").apply { isAccessible = true }
                    .get(closing) as java.util.concurrent.ExecutorService
                closePage(closing)
            }
            assertTrue(closedExecutor.awaitTermination(8, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse("A queued import must not run after its owner closes", skills.read(cancelledName).optBoolean("found"))

            synchronized(store) {
                val bad = openPage(); owner = bad
                assertTrue(busy(bad)); deliver(bad, malformedUri)
            }
            await("Malformed pending import did not report its validation failure") {
                main { val value = ExtensionSettingsActivity::class.java.getDeclaredField("status").apply { isAccessible = true }
                    .get(owner) as android.widget.TextView
                    value.text.contains("frontmatter") && !busy(owner!!) }
            }
            assertTrue("A bad subsequent selection cannot remove the good package", skills.read(name).optBoolean("found"))
        } finally {
            val closed = runCatching { closePage(owner) }
            if (name in skills.enabledState()) skills.delete(name)
            if (cancelledName in skills.enabledState()) skills.delete(cancelledName)
            documents.forEach { assertTrue(!it.exists() || it.delete()) }
            assertEquals(originalNames, skills.enabledState())
            assertEquals(originalFiles, fingerprint(folder))
            closed.getOrThrow()
        }
    }

    @Test fun importedPackageCanBeReadWithReferencesAndRemovedFromSettings() {
        assertTrue("Keep the existing accepted consent", FirstUseConsent.isAccepted(context))
        assertTrue("Use the existing local connection; never change model routing", DirectMode.isEnabled(context))
        assertTrue("Do not modify Skills while a worker is running", DeviceWorkerService.instance?.isPaused != false)
        val prefs = context.getSharedPreferences("doppel", Context.MODE_PRIVATE)
        val pointer = prefs.getString("active_run", null)
        val runFile = File(context.noBackupFilesDir, "direct-runs-v1.json")
        val originalRuns = runFile.takeIf { it.isFile }?.readText()
        val storeFolder = File(context.noBackupFilesDir, "direct-skills-v1")
        val originalPackages = fingerprint(storeFolder)
        val switches = context.getSharedPreferences("doppel_skill_switches", Context.MODE_PRIVATE).all.toMap()
        val skills = DirectSkills(context)
        val originalNames = skills.enabledState()
        assertTrue("Leave space for one isolated verification package", originalNames.size < 50)
        val name = "qa-import-${UUID.randomUUID().toString().take(8)}"
        assertFalse(name in originalNames)
        val description = "本机导入与资料读取验收"
        val reference = "仅供验收：打开设置后查看页面标题，不执行任何任务。"
        val document = File(context.cacheDir, "documents/$name.zip")
        check(document.parentFile.isDirectory || document.parentFile.mkdirs())
        ZipOutputStream(document.outputStream()).use { zip ->
            mapOf("SKILL.md" to "---\nname: $name\ndescription: $description\n---\n# 验收操作\n请阅读 references/guide.md 中的操作说明。\n",
                "references/guide.md" to reference).forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
        }
        val uri = Class.forName("androidx.core.content.FileProvider")
            .getMethod("getUriForFile", Context::class.java, String::class.java, File::class.java)
            .invoke(null, context, "${context.packageName}.documents", document) as Uri
        var monitor: Instrumentation.ActivityMonitor? = null
        val originalFlags = automation.serviceInfo.flags
        var owner: Activity? = null
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val page = openPage()
            owner = page
            await("Skills page import entry did not become ready") {
                main { views(page.window.decorView).any { it.contentDescription == "导入 Skills" && it.isEnabled } }
            }
            // A null-action explicit Activity intent can match an action-only filter. Install
            // this monitor only around the chooser, including its actual MIME/category.
            val chooserFilter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*")
            }
            assertTrue("The monitor must not consume an explicit page launch", chooserFilter.match(context.contentResolver,
                Intent(context, ExtensionSettingsActivity::class.java), false, "SkillsImportTest") < 0)
            val pickerMonitor = inst.addMonitor(chooserFilter,
                Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)), true)
            monitor = pickerMonitor
            main { assertTrue(views(page.window.decorView).first { it.contentDescription == "导入 Skills" }.performClick()) }
            await("File chooser intent was not emitted") { pickerMonitor.hits == 1 }
            inst.removeMonitor(pickerMonitor); monitor = null
            await("Imported Skill was not persisted") { skills.read(name).optBoolean("found") }
            val read = DirectSkills(context).read(name)
            assertEquals("imported", read.getString("source"))
            assertTrue(read.getString("instructions").contains("references/guide.md"))
            assertEquals(reference, skills.resource(name, "references/guide.md", read.getString("revision")).getString("content"))
            visible(description)
            click("references/guide.md")
            visible(reference)
            click("关闭")
            click("移除")
            visible("移除 $name？")
            click("移除")
            await("Removed Skill remains readable") { !DirectSkills(context).read(name).optBoolean("found") }
            closePage(page)
            val reopened = openPage()
            owner = reopened
            await("Reloaded Skills page is busy") {
                main { views(reopened.window.decorView).any { it.contentDescription == "刷新扩展" && it.isEnabled } }
            }
            assertFalse("Deleted package remained in the reopened list", matching(name, false))
        } finally {
            monitor?.let(inst::removeMonitor)
            val closed = runCatching { closePage(owner) }
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            // Delete only this unique package if a preceding UI assertion failed.
            if (name in skills.enabledState()) skills.delete(name)
            assertTrue(!document.exists() || document.delete())
            assertEquals(originalNames, skills.enabledState())
            assertEquals(originalPackages, fingerprint(storeFolder))
            assertEquals(switches, context.getSharedPreferences("doppel_skill_switches", Context.MODE_PRIVATE).all.toMap())
            assertEquals(pointer, prefs.getString("active_run", null))
            assertEquals(originalRuns, runFile.takeIf { it.isFile }?.readText())
            closed.getOrThrow()
        }
    }
}
