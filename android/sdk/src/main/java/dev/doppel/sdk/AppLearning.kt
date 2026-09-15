package dev.doppel.sdk

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Public Android adapter for local application knowledge. Never operates a device or calls a model. */
@Deprecated("Application learning is retired; retained for legacy data access. Use DirectSkills for current skills.")
class AppLearning(context: Context) {
    private val context = context.applicationContext
    private val prefs get() = context.getSharedPreferences("doppel_learning", Context.MODE_PRIVATE)
    val store = LearnedSkillStore(File(this.context.noBackupFilesDir, "learned-skills-v1"), ::installedIdentity)
    val manual = ManualSkillStore(File(this.context.noBackupFilesDir, "manual-skills-v1"))
    private val pendingFile get() = android.util.AtomicFile(File(context.noBackupFilesDir, "pending-skill-demo.json"))
    fun savePendingEvidence(evidence: JSONObject) {
        val bytes = evidence.toString().toByteArray(); require(bytes.size <= 24 * 1024 * 1024)
        val output = pendingFile.startWrite()
        try { output.write(bytes); pendingFile.finishWrite(output) } catch (error: Exception) { pendingFile.failWrite(output); throw error }
    }
    fun pendingEvidence(): JSONObject? = runCatching { pendingFile.openRead().use { JSONObject(it.bufferedReader().readText()) } }.getOrNull()
    fun clearPendingEvidence() { pendingFile.delete() }
    fun enabled() = prefs.getBoolean("automatic_learning", true)
    fun setEnabled(enabled: Boolean) {
        if (enabled) FirstUseConsent.requireAccepted(context)
        check(prefs.edit().putBoolean("automatic_learning", enabled).commit())
    }
    fun installedIdentity(packageName: String): JSONObject? = runCatching {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        JSONObject().put("package_name", packageName).put("version_code", code.toString())
            .put("version_name", info.versionName?.ifBlank { null } ?: "unknown")
            .put("system", "${Build.MANUFACTURER}/${Build.VERSION.SDK_INT}/${Build.DISPLAY}".take(255))
            .put("locale", Locale.getDefault().toLanguageTag())
    }.getOrNull()
    internal fun recordingIdentity(packageName: String) = if (enabled() && FirstUseConsent.isAccepted(context) && packageName != context.packageName) installedIdentity(packageName) else null
    fun remember(trace: JSONObject): JSONObject {
        FirstUseConsent.requireAccepted(context)
        require(LearningTrace.signature(trace.optJSONObject("app")) == LearningTrace.signature(installedIdentity(trace.getJSONObject("app").getString("package_name")))) { "应用版本已经变化，本次经验未保存" }
        return store.save(trace).put("status", "saved")
    }
    internal fun rememberAutomatic(trace: JSONObject): JSONObject? = if (enabled() && FirstUseConsent.isAccepted(context)) remember(trace) else null
    fun relevant(goal: String, packageName: String): JSONObject? = if (FirstUseConsent.isAccepted(context)) store.relevant(goal, packageName) else null
}
