package dev.doppel.sdk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class GuiGroundingClient(private val context: Context) {
    private val prefs = context.getSharedPreferences("doppel_gui_grounding", Context.MODE_PRIVATE)
    private val alias = "${context.packageName}.gui.grounding.v1"
    private val http = OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(48,TimeUnit.SECONDS)
        .callTimeout(50,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    fun enabled() = DirectMode.isEnabled(context) && prefs.getBoolean("enabled",false) && prefs.contains("ciphertext")
    fun endpoint() = prefs.getString("endpoint","http://127.0.0.1:8791").orEmpty()
    private fun key(create: Boolean): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun configure(endpoint: String, token: String, enabled: Boolean) {
        check(DirectMode.isDeveloperBuild(context));check(!DirectRuntime.get(context).hasUnfinishedRun()) { "请先结束当前任务" }
        val editor=prefs.edit().putString("endpoint",GuiGroundingProtocol.endpoint(endpoint)).putBoolean("enabled",enabled)
        if(token.isNotBlank()) {
            require(token.matches(Regex("[0-9a-f]{64}"))) { "请输入定位服务生成的 64 位连接令牌" }
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key(true)) }
            val raw=token.toByteArray();try {
                editor.putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
                    .putString("ciphertext",Base64.encodeToString(cipher.doFinal(raw),Base64.NO_WRAP))
            } finally {raw.fill(0)}
        } else check(!enabled || prefs.contains("ciphertext")) { "首次连接需要令牌" }
        check(editor.commit())
    }
    private fun token(): String {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(false),
            GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.NO_WRAP))) }
        val raw=cipher.doFinal(Base64.decode(prefs.getString("ciphertext",""),Base64.NO_WRAP))
        return try {String(raw,Charsets.UTF_8)} finally {raw.fill(0)}
    }
    fun locate(payload: JSONObject, isCurrent: () -> Boolean): JSONObject {
        check(enabled() && isCurrent());FirstUseConsent.requireAccepted(context)
        val frame=VisualFrame.parse(payload.getJSONObject("visual_frame"))
        val body=GuiGroundingProtocol.request(payload)
        val call=http.newCall(Request.Builder().url(GuiGroundingProtocol.endpoint(endpoint())+"/v1/ground")
            .header("Authorization","Bearer "+token()).post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build())
        val done=java.util.concurrent.atomic.AtomicBoolean(false)
        val guard=Thread({ while(!done.get()) { if(!isCurrent()) {call.cancel();break};try {Thread.sleep(50)} catch (_: InterruptedException) {break} } },"gui-cancel").apply {isDaemon=true;start()}
        try {
            return call.execute().use { response ->
                check(isCurrent())
                if (!response.isSuccessful) throw GuiGroundingHttpException(response.code)
                val stream=requireNotNull(response.body).byteStream();val output=java.io.ByteArrayOutputStream()
                val buffer=ByteArray(4096)
                while(output.size() <= 32768) { val n=stream.read(buffer,0,minOf(buffer.size,32769-output.size()));if(n<0)break;output.write(buffer,0,n) }
                val bytes=output.toByteArray()
                require(bytes.size<=32768);val result=JSONObject(String(bytes,Charsets.UTF_8))
                GuiGroundingProtocol.proposal(result,frame,payload) // validate provenance and coordinates before delivery
                result
            }
        } finally { done.set(true);guard.interrupt() }
    }
    fun cancel() { http.dispatcher.cancelAll() }
}
