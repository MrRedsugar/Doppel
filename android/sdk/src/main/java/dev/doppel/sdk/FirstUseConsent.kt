package dev.doppel.sdk

import android.content.Context
import android.app.Activity
import android.content.Intent

data class ConsentRecord(val termsVersion: String?, val privacyVersion: String?, val acceptedAt: Long) {
    fun accepts(terms: String, privacy: String) = termsVersion == terms && privacyVersion == privacy && acceptedAt > 0L
}

/** Local proof of the exact preview notices accepted; Android permissions remain independent. */
object FirstUseConsent {
    const val TERMS_VERSION = "preview-2026-09-11.1"
    const val PRIVACY_VERSION = "preview-2026-09-11.1"
    const val GUIDE_VERSION = 1
    const val REQUIRED_MESSAGE = "请先阅读并同意使用条款与隐私说明"
    private fun prefs(context: Context) = context.getSharedPreferences("doppel_consent", 0)
    fun record(context: Context): ConsentRecord = prefs(context).let {
        ConsentRecord(it.getString("terms_version", null), it.getString("privacy_version", null), it.getLong("accepted_at", 0))
    }
    fun isAccepted(context: Context) = record(context).accepts(TERMS_VERSION, PRIVACY_VERSION)
    fun requireAccepted(context: Context) { check(isAccepted(context)) { REQUIRED_MESSAGE } }
    /** Queries and explicit cleanup remain available after withdrawal; new processing does not. */
    fun requireRequest(context: Context, method: String, path: String) {
        val verb = method.uppercase(java.util.Locale.ROOT)
        val route = path.substringBefore('?').trim('/').split('/')
        val cleanup = verb == "POST" && (
            route.size == 3 && route[0] == "runs" && route[2] in setOf("pause", "cancel") ||
                route.size == 5 && route[0] == "devices" && route[2] == "data-cleanup" && route[4] == "ack")
        if (verb !in setOf("GET", "HEAD", "OPTIONS", "DELETE") && !cleanup) requireAccepted(context)
    }
    /** Retained notifications and task windows must re-enter explicit consent after withdrawal. */
    fun allowEntry(activity: Activity): Boolean {
        if (isAccepted(activity)) return true
        if (!activity.isFinishing && !activity.isDestroyed) {
            activity.startActivity(Intent(activity, OnboardingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            activity.finish()
        }
        return false
    }
    fun accept(context: Context): Boolean = prefs(context).edit().putString("terms_version", TERMS_VERSION)
        .putString("privacy_version", PRIVACY_VERSION).putLong("accepted_at", System.currentTimeMillis()).commit()
    fun needsGuide(context: Context) = prefs(context).getInt("guide_version", 0) != GUIDE_VERSION
    fun finishGuide(context: Context): Boolean = prefs(context).edit().putInt("guide_version", GUIDE_VERSION).commit()
    fun revoke(context: Context): Boolean {
        DemonstrationSession.cancel()
        return prefs(context).edit().remove("terms_version").remove("privacy_version").remove("accepted_at").commit()
    }
}
