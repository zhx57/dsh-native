package dev.dsh.nativeapp.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** A paired DSH host: base URL plus the signed browser-session cookie. */
data class PairedHost(
    val baseUrl: String,
    val cookie: String,
    val label: String,
    val pairedAt: Long,
) {
    val authority: String
        get() = baseUrl.removePrefix("https://").removePrefix("http://").trimEnd('/').substringBefore('/')
}

/** Outcome of a pairing attempt. */
sealed interface PairResult {
    data class Success(val host: PairedHost) : PairResult
    data class Failure(val message: String) : PairResult
}

/**
 * Exchanges a DSH launch token for a signed browser-session cookie and persists it.
 *
 * `dsh web` prints `http://<authority>/?token=<launch-token>`. `GET /` with that
 * query answers `303` and sets `dsh-auth-<authority-hash>`; the app keeps only the
 * cookie and the origin, so later RPC calls authenticate without the token.
 */
class HostStore(context: Context) {

    private val prefs = run {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "dsh_hosts",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    fun saved(): PairedHost? {
        val baseUrl = prefs.getString(KEY_BASE, null) ?: return null
        val cookie = prefs.getString(KEY_COOKIE, null) ?: return null
        return PairedHost(
            baseUrl = baseUrl,
            cookie = cookie,
            label = prefs.getString(KEY_LABEL, null) ?: baseUrl,
            pairedAt = prefs.getLong(KEY_PAIRED_AT, 0L),
        )
    }

    fun save(host: PairedHost) {
        prefs.edit()
            .putString(KEY_BASE, host.baseUrl)
            .putString(KEY_COOKIE, host.cookie)
            .putString(KEY_LABEL, host.label)
            .putLong(KEY_PAIRED_AT, host.pairedAt)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * Pair from a launch URL such as `http://127.0.0.1:3080/?token=abc`.
     *
     * A bare origin (`http://host:3080`) is also accepted when the caller already
     * holds a cookie value; without a token the exchange cannot succeed.
     */
    fun pair(rawInput: String): PairResult {
        val input = rawInput.trim()
        if (input.isEmpty()) return PairResult.Failure("请输入配对链接")

        val normalized = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            else -> "http://$input"
        }
        val parsed = runCatching { java.net.URI(normalized) }.getOrNull()
            ?: return PairResult.Failure("链接格式无法解析")
        val host = parsed.host ?: return PairResult.Failure("链接缺少主机名")
        val scheme = parsed.scheme?.lowercase() ?: "http"
        val port = if (parsed.port > 0) parsed.port else if (scheme == "https") 443 else 80
        val base = buildString {
            append(scheme).append("://").append(host)
            if (port != 443 && port != 80) append(':').append(port)
        }
        val token = parsed.query
            ?.split('&')
            ?.firstOrNull { it.startsWith("token=") }
            ?.removePrefix("token=")

        if (token.isNullOrBlank()) {
            return PairResult.Failure("链接缺少 token 参数，请复制 dsh web 打印的完整地址")
        }

        val request = Request.Builder()
            .url("$base/?token=$token")
            .header("referer", "no-referrer")
            .get()
            .build()

        return runCatching {
            http.newCall(request).execute().use { response ->
                val setCookies = response.headers.values("set-cookie")
                    .filter { it.startsWith("dsh-auth-") }
                if (setCookies.isEmpty()) {
                    return@use PairResult.Failure(
                        "配对失败：服务器未下发会话凭据（HTTP ${response.code}）。请重新生成配对链接。",
                    )
                }
                val cookie = setCookies.joinToString("; ") { it.substringBefore(';') }
                val paired = PairedHost(
                    baseUrl = base,
                    cookie = cookie,
                    label = host,
                    pairedAt = System.currentTimeMillis(),
                )
                save(paired)
                PairResult.Success(paired)
            }
        }.getOrElse { error ->
            PairResult.Failure("无法连接 ${base}：${error.message ?: "网络错误"}")
        }
    }

    private companion object {
        const val KEY_BASE = "base_url"
        const val KEY_COOKIE = "cookie"
        const val KEY_LABEL = "label"
        const val KEY_PAIRED_AT = "paired_at"
    }
}
