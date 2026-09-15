package dev.doppel.sdk

import org.json.JSONObject

/** Current host-observation metadata, never an authorization or a persisted device capability. */
internal object ScreenCapturePrivacy {
    const val LOGIN_SENSITIVE = "login_sensitive"
    private const val FIELD = "screenshot_privacy"

    fun attach(observation: JSONObject, loginSensitive: Boolean): JSONObject = observation.apply {
        remove(FIELD)
        if (loginSensitive) put(FIELD, JSONObject().put("source", "android_host").put("reason", LOGIN_SENSITIVE)
            .put("screen_id", getString("screen_id")).put("package_name", getString("package_name"))
            .put("captured_at", getLong("captured_at")))
    }

    fun unavailable(observation: JSONObject?): Boolean {
        if (observation == null) return false
        val state = observation.optJSONObject(FIELD) ?: return false
        val screen = observation.optString("screen_id")
        val pkg = observation.optString("package_name")
        val at = observation.opt("captured_at") as? Number ?: return false
        return screen.isNotBlank() && pkg.isNotBlank() && at.toLong() > 0 &&
            state.opt("source") == "android_host" && state.opt("reason") == LOGIN_SENSITIVE &&
            state.opt("screen_id") == screen && state.opt("package_name") == pkg &&
            state.opt("captured_at") == observation.opt("captured_at")
    }

    fun explicitlyOmitted(observation: JSONObject?, data: JSONObject) = unavailable(observation) &&
        data.opt("screenshot_omitted_reason") == LOGIN_SENSITIVE
}
