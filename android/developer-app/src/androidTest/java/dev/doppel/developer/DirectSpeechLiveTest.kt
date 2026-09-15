package dev.doppel.developer

import android.content.Context
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import dev.doppel.sdk.DeviceWorkerService
import dev.doppel.sdk.DirectCredentials
import dev.doppel.sdk.DirectMode
import dev.doppel.sdk.DirectRuntime
import dev.doppel.sdk.FirstUseConsent
import dev.doppel.sdk.Gateway
import dev.doppel.sdk.VoiceActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.MessageDigest

/**
 * Paid provider acceptance using synthetic speech, with no task or screen operation.
 * Stage files/direct-speech-qa.wav (PCM16 mono 16kHz) and, only if needed,
 * files/direct-qa-key.txt. Run with:
 * -e class dev.doppel.developer.DirectSpeechLiveTest -e direct_speech_live true -e expected 你好/任务
 * File presence alone NEVER opts in. Existing encrypted credentials are reused unchanged.
 */
class DirectSpeechLiveTest {
    @Test
    fun transcribesStagedSyntheticSpeechViaRealDirectProvider() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Paid speech acceptance requires explicit direct_speech_live=true", args.getString("direct_speech_live") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gateway = Gateway(context)
        val prefs = gateway.prefs
        val consent = context.getSharedPreferences("doppel_consent", Context.MODE_PRIVATE)
        val credentialsPrefs = context.getSharedPreferences("doppel_direct_credentials", Context.MODE_PRIVATE)
        val before = snapshot(prefs)
        val consentBefore = snapshot(consent)
        val credentialsBefore = snapshot(credentialsPrefs)
        val credentials = DirectCredentials(context)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "${context.packageName}.developer.mimo.v1"
        val hadAlias = store.containsAlias(alias)
        val hadCredential = credentials.hasKey()
        val wavFile = StagedFile.capture(File(context.filesDir, "direct-speech-qa.wav"), 960044)
        val keyFile = StagedFile.capture(File(context.filesDir, "direct-qa-key.txt"), 1024)
        val evidence = JSONObject().put("status", "failed").put("synthetic_audio", true)
            .put("credential_reused", hadCredential).put("provider_calls", 0)
        var stage = "preconditions"
        var primaryFailure: Throwable? = null
        var wav = ByteArray(0)
        var keyBytes = ByteArray(0)
        var preferencesChanged = false
        var credentialChanged = false
        var requests = 0
        try {
            assertTrue("Developer build required", DirectMode.isDeveloperBuild(context))
            assertNull("An existing worker must be left untouched", DeviceWorkerService.instance)
            assertTrue("An active task must be ended first", prefs.getString("active_run", "").isNullOrBlank())
            assertTrue("A pending voice task must be ended first", prefs.getString("voice_pending_worker_run", "").isNullOrBlank())
            assertFalse("Close the voice editor first", VoiceActivity.isVisible)
            assertFalse("Unfinished local tasks must be ended first", DirectRuntime.get(context).hasUnfinishedRun())
            val expected = args.getString("expected", "你好/任务").orEmpty()
                .split(Regex("[,，/|]")).map { normalize(it) }.filter { it.isNotEmpty() }
            assertTrue("Supply at least one expected synthetic speech keyword", expected.isNotEmpty())
            stage = "audio_validation"
            wav = wavFile.readOwned()
            val duration = validateWav(wav)
            evidence.put("duration_ms", duration)
            stage = "credential_setup"
            if (!hadCredential) {
                keyBytes = keyFile.readOwned()
                val key = String(keyBytes, Charsets.UTF_8).trim()
                assertTrue("Staged credential format is invalid", key.length in 16..512 && key.all { it.code in 33..126 })
                credentialChanged = true // save can create an alias before failing.
                credentials.save(key)
            }
            preferencesChanged = true
            check(FirstUseConsent.accept(context))
            DirectMode.configure(context, true)
            assertTrue("Speech test must use direct mode", gateway.isDirectMode())
            stage = "reflection"
            val method = Gateway::class.java.declaredMethods.single {
                it.name.startsWith("transcribeSpeech") && !Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.size == 3 && it.parameterTypes[0] == ByteArray::class.java &&
                    it.parameterTypes[1] == java.lang.Boolean.TYPE
            }.apply { isAccessible = true }
            val onConnection: (HttpURLConnection) -> Unit = { connection ->
                check(connection.url.protocol == "https" && connection.url.host == "api.xiaomimimo.com" &&
                    connection.url.path == "/v1/chat/completions") { "Unexpected speech endpoint" }
                requests += 1
                check(requests == 1) { "Unexpected duplicate speech request" }
            }
            stage = "provider"
            val response = method.invoke(gateway, wav, true, onConnection) as JSONObject
            stage = "response_validation"
            evidence.put("elapsed_ms", response.optLong("elapsed_ms"))
                .put("model", response.optString("model"))
                .put("transcript", response.optString("text").replace(Regex("[!-~]{16,}"), "[redacted]"))
            assertEquals("mimo-v2.5-asr", response.getString("model"))
            assertEquals("Provider duration must match the actual PCM samples", duration, response.getLong("duration_ms"))
            val transcript = response.getString("text")
            assertTrue("Speech result must be nonempty", transcript.isNotBlank())
            val normalized = normalize(transcript)
            assertTrue("Synthetic speech keywords were not recognized", expected.all { normalized.contains(it) })
            assertEquals("Exactly one provider call is allowed", 1, requests)
            // Keep only synthetic speech text, never arbitrary raw response or key-shaped tokens.
            evidence.put("transcript", transcript.replace(Regex("[!-~]{16,}"), "[redacted]"))
                .put("model", response.getString("model")).put("status", "passed")
        } catch (error: Throwable) {
            primaryFailure = if (error is InvocationTargetException) error.targetException else error
            evidence.put("failure_stage", stage).put("failure_class", primaryFailure!!.javaClass.simpleName)
        } finally {
            wav.fill(0)
            keyBytes.fill(0)
            fun cleanup(name: String, operation: () -> Unit) {
                try { operation(); evidence.put(name, true) }
                catch (error: Throwable) {
                    evidence.put(name, false)
                    if (primaryFailure == null) primaryFailure = AssertionError("Speech cleanup failed: $name")
                }
            }
            cleanup("gateway_preferences_restored") { if (preferencesChanged) restore(prefs, before); check(before == snapshot(prefs)) }
            cleanup("consent_restored") { if (preferencesChanged) restore(consent, consentBefore); check(consentBefore == snapshot(consent)) }
            cleanup("credentials_restored") { if (credentialChanged) restore(credentialsPrefs, credentialsBefore); check(credentialsBefore == snapshot(credentialsPrefs)) }
            cleanup("keystore_restored") {
                if (credentialChanged && !hadAlias && store.containsAlias(alias)) store.deleteEntry(alias)
                check(store.containsAlias(alias) == hadAlias)
            }
            cleanup("staged_wav_removed") { wavFile.deleteOwned() }
            cleanup("staged_key_removed") { keyFile.deleteOwned() }
            cleanup("worker_untouched") { check(DeviceWorkerService.instance == null) }
            evidence.put("provider_calls", requests)
            if (primaryFailure != null) evidence.put("status", "failed")
            File(context.filesDir, "direct-speech-live.json").writeText(evidence.toString(2))
        }
        // Never propagate provider error messages, request bodies, or credentials to instrumentation logs.
        primaryFailure?.let { throw AssertionError("Direct speech acceptance failed at $stage (${it.javaClass.simpleName}); see redacted evidence") }
    }

    private fun normalize(value: String) = value.replace(Regex("[\\p{P}\\p{Z}\\s]"), "").lowercase()

    private fun validateWav(wav: ByteArray): Long {
        check(wav.size in 46..960044 && wav.size % 2 == 0) { "Invalid WAV size" }
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(offset: Int) = String(wav, offset, 4, Charsets.US_ASCII)
        check(tag(0) == "RIFF" && tag(8) == "WAVE" && tag(12) == "fmt " && tag(36) == "data")
        check(buffer.getInt(4) == wav.size - 8 && buffer.getInt(16) == 16 && buffer.getShort(20).toInt() == 1)
        check(buffer.getShort(22).toInt() == 1 && buffer.getInt(24) == 16000 && buffer.getInt(28) == 32000)
        check(buffer.getShort(32).toInt() == 2 && buffer.getShort(34).toInt() == 16 && buffer.getInt(40) == wav.size - 44)
        return (((wav.size - 44) / 2L) * 1000L + 15999L) / 16000L
    }

    private fun snapshot(prefs: SharedPreferences): Map<String, *> = prefs.all.mapValues { (_, value) ->
        if (value is Set<*>) value.toSet() else value
    }

    private fun restore(prefs: SharedPreferences, values: Map<String, *>) {
        val editor = prefs.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        check(editor.commit())
    }

    private class StagedFile(private val file: File, private val digest: ByteArray?, private val maxBytes: Int) {
        companion object {
            fun capture(file: File, maxBytes: Int): StagedFile {
                check(file.canonicalFile.parentFile == file.parentFile!!.canonicalFile)
                val digest = if (file.isFile && file.length() in 1..maxBytes.toLong()) hash(file) else null
                return StagedFile(file.canonicalFile, digest, maxBytes)
            }
            private fun hash(file: File): ByteArray = file.inputStream().use { input ->
                val sha = MessageDigest.getInstance("SHA-256")
                val bytes = ByteArray(4096)
                try { while (true) { val size = input.read(bytes); if (size < 0) break; sha.update(bytes, 0, size) }; sha.digest() }
                finally { bytes.fill(0) }
            }
        }
        fun readOwned(): ByteArray {
            check(digest != null && file.isFile && file.length() in 1..maxBytes.toLong() && digest.contentEquals(hash(file))) { "Missing or changed staged file" }
            return file.readBytes()
        }
        fun deleteOwned() {
            if (digest == null || !file.exists()) return
            check(file.isFile && file.length() in 1..maxBytes.toLong() && digest.contentEquals(hash(file))) { "Staged file changed; retained" }
            check(file.delete())
        }
    }
}
