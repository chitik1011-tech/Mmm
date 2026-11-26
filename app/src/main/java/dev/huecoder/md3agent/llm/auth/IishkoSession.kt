package dev.huecoder.md3agent.llm.auth

import dev.huecoder.md3agent.BuildConfig
import dev.huecoder.md3agent.data.settings.ApiFormat
import dev.huecoder.md3agent.data.settings.ProviderProfile
import dev.huecoder.md3agent.llm.ProviderException
import dev.huecoder.md3agent.net.HttpClientProvider
import dev.huecoder.md3agent.net.executeCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Device sessions are issued by IISHKO, not fabricated by the client. */
class IishkoSession(private val http: HttpClientProvider) {
    private data class Cached(val device: String, val token: String, val refreshAt: Long)
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, Cached>()

    suspend fun connect(profile: ProviderProfile): ProviderProfile {
        val key = profile.apiKey.trim()
        if (key.isEmpty()) throw ProviderException.InvalidConfiguration("Введите API-ключ ИИШКО")
        val response = post("key-login", buildJsonObject {
            put("client", "md3-agent")
            put("api_key", key)
            put("device", "MD3 Agent Android")
            put("platform", "android")
            put("version", BuildConfig.VERSION_NAME)
        })
        val device = response.text("device_token")
        if (response.text("status") != "approved" || device.isNullOrBlank()) {
            throw ProviderException.InvalidConfiguration("ИИШКО не подтвердил подключение устройства")
        }
        val connected = profile.copy(baseUrl = PROXY, apiKey = "", extraHeaders = emptyList(), iishkoDeviceToken = device)
        freshProvider(connected)
        return connected
    }

    suspend fun freshProvider(profile: ProviderProfile): ProviderProfile {
        if (!isIishko(profile)) return profile
        val device = profile.iishkoDeviceToken
            ?: throw ProviderException.InvalidConfiguration("Подключите аккаунт ИИШКО в настройках провайдера")
        return mutex.withLock {
            val now = System.currentTimeMillis() / 1000
            val previous = cache[profile.id]
            val session = if (previous != null && previous.device == device && previous.refreshAt > now) previous else {
                val response = post("token", buildJsonObject {}, device)
                val token = response.text("token")?.takeIf { it.isNotBlank() }
                    ?: throw ProviderException.InvalidConfiguration("ИИШКО не выдал токен сессии")
                val expires = (response["expires_at"] as? JsonPrimitive)?.longOrNull ?: (now + 60)
                val refresh = (response["refresh_in"] as? JsonPrimitive)?.longOrNull ?: 60
                Cached(device, token, minOf(expires - 30, now + refresh.coerceAtLeast(1))).also { cache[profile.id] = it }
            }
            profile.copy(baseUrl = PROXY, apiKey = session.token, extraHeaders = emptyList())
        }
    }

    private suspend fun post(path: String, payload: JsonObject, bearer: String? = null): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$ACCOUNT/$path")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .build()
        http.current().newBuilder().followRedirects(false).followSslRedirects(false).build()
            .newCall(request).executeCancellable { response ->
                if (!response.isSuccessful) throw ProviderException.Http(response.code, "ИИШКО: сервер отклонил запрос ($path, HTTP ${response.code})")
                Json.parseToJsonElement(response.body.string()) as? JsonObject
                    ?: throw ProviderException.MalformedResponse("Некорректный ответ ИИШКО")
            }
    }

    private fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val ACCOUNT = "https://api.reformboss.com/webapp/api/ide"
        const val PROXY = "$ACCOUNT/v1"
        const val DIRECT = "https://api.reformboss.com/v1"
        fun isIishko(profile: ProviderProfile): Boolean =
            profile.format == ApiFormat.OPENAI && profile.baseUrl.trim().trimEnd('/') in setOf(DIRECT, PROXY)
    }
}
