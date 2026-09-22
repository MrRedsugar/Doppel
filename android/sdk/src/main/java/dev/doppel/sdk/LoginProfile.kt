package dev.doppel.sdk

data class LoginProfile(val packageName: String, val phone: String, val enabled: Boolean = true,
                        val method: String = "sms", val credentialId: String = "")

/** Preserve old opt-ins; conflicting authorizations require the owner to choose a method. */
internal fun mergeLegacyLoginProfiles(sms: List<LoginProfile>, passwords: Map<String, Boolean>): List<LoginProfile> {
    val byPackage = sms.associateBy { it.packageName }
    return (byPackage.keys + passwords.keys).map { pkg ->
        val old = byPackage[pkg]
        val smsEnabled = old?.enabled == true
        val passwordEnabled = passwords[pkg] == true
        when {
            smsEnabled && passwordEnabled -> old!!.copy(enabled = false, method = "")
            passwordEnabled -> LoginProfile(pkg, old?.phone.orEmpty(), true, "password")
            smsEnabled -> old!!
            passwords.containsKey(pkg) -> LoginProfile(pkg, old?.phone.orEmpty(), false, "password")
            else -> old!!
        }
    }
}
