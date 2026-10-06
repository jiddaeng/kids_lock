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
        require(token.length in 32..512 && token.none { it.isWhitespace() } && !token.startsWith("sk-"))
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
    fun validEndpoint(endpoint: String): Boolean = runCatching {
        val url = URL(endpoint)
        url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null && url.query == null && url.ref == null
    }.getOrDefault(false)

    fun analyze(endpoint: String, token: String, redacted: String): Analysis {
        require(validEndpoint(endpoint) && token.isNotBlank())
        val connection = URL(endpoint.trimEnd('/') + "/v1/analyze").openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000; connection.readTimeout = 25_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write(JSONObject().put("text", redacted).toString().toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode == 200) { "분석 서버 응답 실패 (${connection.responseCode})" }
            val bytes = connection.inputStream.use { it.readNBytes(8193) }
            check(bytes.size <= 8192)
            val result = JSONObject(String(bytes, Charsets.UTF_8))
            check(result.getString("model") == "gpt-4o")
            val topics = result.getJSONArray("topics")
            return AnalysisVocabulary.result(result.getString("purpose"), (0 until topics.length()).map { topics.getString(it) },
                result.getString("risk"), result.getString("confidence"))
        } finally { connection.disconnect() }
    }
}
