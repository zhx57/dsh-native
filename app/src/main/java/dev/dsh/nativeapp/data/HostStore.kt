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
     * Session the operator was last reading.
     *
     * "Most recently updated" is not the same thing as "the conversation I was
     * looking at": a background session can be touched by a job or a subagent while
     * the operator is reading an older one, and a cold start then jumped to that
     * other session. Remembering the actual last view is what makes relaunch resume
     * where they left off.
     */
    fun lastSessionId(): String? = prefs.getString(KEY_LAST_SESSION, null)

    fun saveLastSessionId(sessionId: String) {
        prefs.edit().putString(KEY_LAST_SESSION, sessionId).apply()
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
            ?.takeIf { it.isNotBlank() }

        if (token == null) {
            // A bare origin cannot mint a session: the launch token is the only
            // exchange credential, so ask for the printed URL instead of failing
            // with an opaque 401.
            return PairResult.Failure(
                "地址里没有 token。请复制 dsh web 启动时打印的完整链接（形如 http://主机:3080/?token=…）",
            )
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
                    val detail = when (response.code) {
                        401 -> "配对密钥无效或已过期，请在电脑上重新生成链接"
                        403 -> "主机拒绝了这个地址（Host 校验失败），确认填的是电脑实际监听地址"
                        else -> "服务器未下发会话凭据（HTTP ${response.code}）"
                    }
                    return@use PairResult.Failure("配对失败：$detail")
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
        const val KEY_LAST_SESSION = "last_session_id"
    }
}
