package dev.doppel.sdk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** A repackaging tripwire, not a substitute for a server entitlement check. */
object ReleaseIntegrity {
    fun isTrusted(context: Context): Boolean {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return true
        return runCatching {
            val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            if (info.metaData?.getBoolean("dev.doppel.REQUIRE_SIGNING_PIN", false) != true) return@runCatching true
            val expected = info.metaData?.getString("dev.doppel.SIGNING_SHA256").orEmpty().lowercase()
            if (!expected.matches(Regex("[0-9a-f]{64}"))) return@runCatching false
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val pkg = context.packageManager.getPackageInfo(context.packageName, flags)
            @Suppress("DEPRECATION")
            val certificates = if (Build.VERSION.SDK_INT >= 28) pkg.signingInfo?.apkContentsSigners else pkg.signatures
            certificates?.size == 1 && certificates.all { certificate ->
                MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray()).joinToString("") { "%02x".format(it) } == expected
            }
        }.getOrDefault(false)
    }
}
