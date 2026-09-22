package dev.doppel.sdk

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Keeps old settings links valid; all management and PIN checks live in the unified page. */
class PasswordSettingsActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val targetPackage = intent.getStringExtra("package_name")
        startActivity(Intent(this, LoginSettingsActivity::class.java).apply {
            targetPackage?.let { putExtra("package_name", it) }
        })
        finish()
    }
}
