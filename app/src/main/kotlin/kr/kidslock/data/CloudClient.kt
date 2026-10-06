package kr.kidslock.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kr.kidslock.core.*
import org.json.JSONObject
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

/** A revocable relay credential, never an OpenAI API key. Excluded from OS backup. */
class RelayCredential(context: Context) {
    private val prefs = context.getSharedPreferences("relay", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("gauroo-relay", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("gauroo-relay", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(token: String) {
        require(token.length in 32..512 && token.none { it.isWhitespace() } && !token.startsWith("sk-") && !token.startsWith("AQ.") && !token.startsWith("AIza"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("token", Base64.encodeToString(cipher.doFinal(token.toByteArray()), Base64.NO_WRAP)).apply()
    }
    fun read(): String = runCatching {
        val encrypted = prefs.getString("token", null) ?: return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
        }
        String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrDefault("")
}

object CloudClient {
    data class DetailedResult(val analysis: Analysis, val reason: String)
    class Failure(val reason: String) : Exception(reason)
    fun explain(error: Throwable): String = when ((error as? Failure)?.reason) {
        "unauthorized" -> "연결 토큰이 맞지 않아요 · 분석 서버 연결에서 다시 입력해 주세요"
        "service_not_configured" -> "서버의 API 키 또는 연결 토큰 설정이 빠져 있어요"
        "upstream_quota" -> "OpenAI API 사용 가능 잔액/한도가 없어요 · 서버 계정의 결제·사용 한도를 확인해 주세요"
        "upstream_auth" -> "서버의 OpenAI API 키가 유효하지 않아요"
        "upstream_model_access" -> "서버 API 계정에 GPT-4o 접근 권한이 없어요"
        "request_budget", "upstream_rate_limit" -> "분석 요청 한도에 도달했어요 · 잠시 후 다시 시도해 주세요"
        "upstream_request" -> "서버의 GPT-4o 요청 설정을 수정해야 해요"
        else -> if (error is java.net.SocketTimeoutException) "서버 응답이 늦어요 · 잠시 후 다시 시도해 주세요"
            else "분석 서버 통신에 실패했어요 · 네트워크와 서버 상태를 확인해 주세요"
    }
    private fun requireSuccess(connection: HttpsURLConnection) {
        if (connection.responseCode == 200) return
        val reason = runCatching {
            val bytes = connection.errorStream?.use { it.readBytesLimited() } ?: byteArrayOf()
            JSONObject(String(bytes, Charsets.UTF_8)).optString("error")
        }.getOrDefault("")
        throw Failure(if (connection.responseCode == 401) "unauthorized" else reason)
    }
    private fun java.io.InputStream.readBytesLimited(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (output.size() < 8192) {
            val n = read(buffer, 0, minOf(buffer.size, 8192 - output.size()))
            if (n < 0) break
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }
    /** Wakes a sleeping host before sending OCR; does not invoke OpenAI. */
    fun checkConnection(endpoint: String, token: String) {
        require(validEndpoint(endpoint) && token.isNotBlank())
        val c = URL(endpoint.trimEnd('/') + "/v1/status").openConnection() as HttpsURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000; c.readTimeout = 90_000
            c.setRequestProperty("Authorization", "Bearer $token")
            requireSuccess(c)
            val data = JSONObject(String(c.inputStream.use { it.readBytesLimited() }, Charsets.UTF_8))
            check(data.getBoolean("ready") && data.getString("model") == "qwen3:4b")
        } finally { c.disconnect() }
    }

    fun validEndpoint(endpoint: String): Boolean = runCatching {
        val url = URL(endpoint)
        url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null && url.query == null && url.ref == null
    }.getOrDefault(false)

    fun analyze(endpoint: String, token: String, redacted: String, maySend: () -> Boolean = { true }): Analysis =
        analyzeDetailed(endpoint, token, redacted, maySend).analysis

    fun analyzeDetailed(endpoint: String, token: String, redacted: String, maySend: () -> Boolean = { true }): DetailedResult {
        require(validEndpoint(endpoint) && token.isNotBlank())
        checkConnection(endpoint, token)
        check(maySend()) { "analysis_cancelled" }
        val connection = URL(endpoint.trimEnd('/') + "/v1/analyze").openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000; connection.readTimeout = 180_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write(JSONObject().put("text", redacted).toString().toByteArray(Charsets.UTF_8)) }
            requireSuccess(connection)
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (output.size() <= 8192) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 8193 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            check(bytes.size <= 8192)
            val result = JSONObject(String(bytes, Charsets.UTF_8))
            check(result.getString("model") == "qwen3:4b")
            val topics = result.getJSONArray("topics")
            val reason = result.getString("reason")
            check(reason in AnalysisVocabulary.reasons)
            return DetailedResult(
                AnalysisVocabulary.result(result.getString("purpose"), (0 until topics.length()).map { topics.getString(it) },
                    result.getString("risk"), result.getString("confidence")),
                reason
            )
        } finally { connection.disconnect() }
    }
}
        "local_worker_offline" -> "분석 PC가 연결되지 않았어요 · PC에서 가루 로컬 분석 시작을 실행해 주세요"
        "local_worker_busy" -> "PC에서 앞선 화면을 분석 중이에요 · 잠시 기다려 주세요"
        "local_worker_timeout" -> "PC 분석 시간이 초과됐어요 · PC 상태를 확인해 주세요"
        "local_analysis_failed" -> "로컬 모델이 분석을 완료하지 못했어요 · PC 모델 상태를 확인해 주세요"
