package dev.doppel.sdk.cloud

import android.content.Context
import dev.doppel.sdk.DirectMode
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

internal class CloudHttpException(val status: Int, val code: String) : IOException("$code ($status)")

/** Blocking calls: the account UI uses its background executor; no credentials are installed implicitly. */
internal class CloudAccountClient(private val allowLocalHttp: Boolean) {
    constructor(context: Context) : this(DirectMode.isDeveloperBuild(context))

    internal val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()

    fun login(baseUrl: String, accountName: String, password: String): CloudSession {
        require(accountName.isNotBlank() && accountName.length <= 256 && validPassword(password))
        val base = CloudSession.validatedBaseUrl(baseUrl, allowLocalHttp)
        val result = request(base, "POST", "v1/auth/login", null, JSONObject()
            .put("account_name", accountName).put("password", password).put("device_kind", "phone"))
        return try {
            CloudSession(base, result.getString("account_id"), result.getString("session_id"),
                result.getString("access_token"), allowLocalHttp)
        } catch (_: Exception) { throw CloudHttpException(200, "invalid_response") }
    }

    fun changePassword(session: CloudSession, oldPassword: String, newPassword: String) {
        require(validPassword(oldPassword) && validPassword(newPassword))
        checkOk(request(session.baseUrl, "POST", "v1/auth/password", session.token,
            JSONObject().put("old_password", oldPassword).put("new_password", newPassword)))
    }

    fun logout(session: CloudSession) = checkOk(request(session.baseUrl, "POST", "v1/auth/logout", session.token, JSONObject()))

    private fun validPassword(value: String) = value.codePointCount(0, value.length) in 12..128

    fun readSession(session: CloudSession): JSONObject {
        val result = request(session.baseUrl, "GET", "v1/session", session.token)
        if (result.optString("account_id") != session.accountId || result.optString("session_id") != session.sessionId ||
            result.optString("device_kind") != "phone") throw CloudHttpException(200, "session_mismatch")
        return result
    }

    private fun checkOk(result: JSONObject) {
        if (result.opt("ok") != true) throw CloudHttpException(200, "invalid_response")
    }

    private fun request(baseUrl: String, method: String, path: String, token: String?, body: JSONObject? = null): JSONObject {
        val base = CloudSession.validatedBaseUrl(baseUrl, allowLocalHttp)
        val request = Request.Builder().url("$base/".toHttpUrl().newBuilder().addPathSegments(path).build())
            .header("Accept", "application/json").apply { if (token != null) header("Authorization", "Bearer $token") }
            .method(method, body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            if (response.code in 300..399) throw CloudHttpException(response.code, "redirect_rejected")
            val payload = response.body ?: throw CloudHttpException(response.code, "invalid_response")
            val result = try {
                require(payload.contentLength() <= 65536)
                val source = payload.source()
                source.request(65537)
                require(source.buffer.size <= 65536)
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                val reader = JSONTokener(decoder.decode(ByteBuffer.wrap(source.readByteArray())).toString())
                val value = reader.nextValue() as? JSONObject ?: error("object required")
                require(reader.nextClean() == '\u0000')
                value
            } catch (_: Exception) { throw CloudHttpException(response.code, "invalid_response") }
            if (!response.isSuccessful) {
                val code = result.optString("code").takeIf { it.matches(Regex("[a-z0-9_]{1,64}")) } ?: "invalid_response"
                throw CloudHttpException(response.code, code)
            }
            return result
        }
    }
}
