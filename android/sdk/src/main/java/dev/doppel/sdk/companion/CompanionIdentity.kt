package dev.doppel.sdk.companion

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID

internal object CompanionIdentity {
    @Synchronized fun loadOrCreate(context: Context): String {
        val folder = File(context.noBackupFilesDir, "companion")
        check(folder.isDirectory || folder.mkdirs()) { "Companion identity storage unavailable" }
        val file = AtomicFile(File(folder, "installation-id"))
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            val value = file.readFully().toString(Charsets.US_ASCII)
            check(value.length == 36 && UUID.fromString(value).toString() == value) { "Companion identity invalid" }
            return value
        }
        val value = UUID.randomUUID().toString()
        val output = file.startWrite()
        try { output.write(value.toByteArray(Charsets.US_ASCII)); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
        return value
    }
}
