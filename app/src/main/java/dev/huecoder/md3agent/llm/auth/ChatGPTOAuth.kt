package dev.huecoder.md3agent.llm.auth

import dev.huecoder.md3agent.data.settings.ApiFormat
import dev.huecoder.md3agent.data.settings.ChatGPTOAuthSession
import dev.huecoder.md3agent.data.settings.ProviderProfile
import dev.huecoder.md3agent.data.settings.SettingsRepository
import dev.huecoder.md3agent.llm.ProviderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.UUID

/** OpenAI's documented Sign in with ChatGPT flow for open-source clients. */
class ChatGPTOAuth(
    private val httpClient: () -> OkHttpClient,
    private val settings: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()

    suspend fun signIn(provider: ProviderProfile, openBrowser: (String) -> Unit): ChatGPTOAuthSession = withContext(Dispatchers.IO) {
        require(provider.format == ApiFormat.CHATGPT)
        val previous = provider.chatgptOAuth
        val hostId = previous?.hostId ?: settings.chatGPTHostId()
        val requestedClientId = previous?.clientId ?: REGISTRATION_CLIENT_ID
        val verifier = randomUrlToken(32)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
        val state = randomUrlToken(24)
        val nonce = randomUrlToken(24)
        val authorizationBuilder = AUTHORIZATION_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", requestedClientId)
            .addQueryParameter("redirect_uri", CALLBACK_URI)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("scope", SCOPES.joinToString(" "))
            .addQueryParameter("resource", RESOURCE)
            .addQueryParameter("state", state)
            .addQueryParameter("nonce", nonce)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("ext_agent_host_id", hostId)
        if (previous == null) {
            authorizationBuilder.addQueryParameter("agent_name_hint", "MD3 Agent")
        } else {
            authorizationBuilder.addQueryParameter("id_token_hint", previous.idToken)
            previous.email.takeIf(String::isNotBlank)?.let { authorizationBuilder.addQueryParameter("login_hint", it) }
        }
        val authorizationUrl = authorizationBuilder.build()

        ServerSocket(CALLBACK_PORT, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = CALLBACK_TIMEOUT_MS
            withContext(Dispatchers.Main) { openBrowser(authorizationUrl.toString()) }
            val callback = try {
                server.accept().use(::readCallback)
            } catch (_: SocketTimeoutException) {
                throw ProviderException.InvalidConfiguration("ChatGPT sign-in timed out. Try again.")
            }
            if (callback.state != state) throw ProviderException.InvalidConfiguration("ChatGPT sign-in state did not match. Try again.")
            callback.error?.let { error -> throw ProviderException.InvalidConfiguration(callback.errorDescription ?: "ChatGPT sign-in failed: $error") }
            val code = callback.code ?: throw ProviderException.InvalidConfiguration("ChatGPT did not return an authorization code.")
            val issuedClientId = if (previous == null) {
                callback.clientId?.takeIf { it.isNotBlank() && it != REGISTRATION_CLIENT_ID }
                    ?: throw ProviderException.InvalidConfiguration("ChatGPT did not return the registered client ID. Start sign-in again.")
            } else {
                if (callback.clientId != null && callback.clientId != previous.clientId) {
                    throw ProviderException.InvalidConfiguration("ChatGPT returned a different client ID. Your saved account was not changed.")
                }
                previous.clientId
            }
            exchangeCode(code, verifier, nonce, hostId, issuedClientId, previous?.subject)
        }
    }

    /** Refreshes rotated OAuth credentials before a model-list or Responses API request. */
    suspend fun freshProvider(provider: ProviderProfile): ProviderProfile {
        if (provider.format != ApiFormat.CHATGPT) return provider
        val session = provider.chatgptOAuth
            ?: throw ProviderException.InvalidConfiguration("Sign in with ChatGPT in provider settings first.")
        if (session.expiresAt > System.currentTimeMillis() / 1000 + TOKEN_REFRESH_MARGIN_SECONDS) return provider

        return refreshMutex.withLock {
            val latest = settings.current().provider(provider.id) ?: provider
            val latestSession = latest.chatgptOAuth ?: session
            if (latestSession.expiresAt > System.currentTimeMillis() / 1000 + TOKEN_REFRESH_MARGIN_SECONDS) return@withLock latest
            val refreshed = refresh(latestSession)
            val updated = latest.copy(chatgptOAuth = refreshed)
            settings.upsertProvider(updated)
            updated
        }
    }

    private suspend fun exchangeCode(
        code: String,
        verifier: String,
        nonce: String,
        hostId: String,
        clientId: String,
        previousSubject: String?,
    ): ChatGPTOAuthSession {
        val token = tokenRequest(
            "grant_type" to "authorization_code",
            "client_id" to clientId,
            "code" to code,
            "redirect_uri" to CALLBACK_URI,
            "code_verifier" to verifier,
            "resource" to RESOURCE,
        )
        val idToken = token.string("id_token") ?: throw ProviderException.InvalidConfiguration("ChatGPT did not return an ID token.")
        val claims = validateIdToken(idToken, nonce, clientId)
        val subject = claims.string("sub") ?: throw ProviderException.InvalidConfiguration("ChatGPT ID token has no subject.")
        if (previousSubject != null && subject != previousSubject) {
            throw ProviderException.InvalidConfiguration("Signed-in ChatGPT account did not match the saved account.")
        }
        val scopes = token.string("scope")?.split(' ')?.filter(String::isNotBlank).orEmpty()
        if (REQUIRED_SCOPE !in scopes) throw ProviderException.InvalidConfiguration("ChatGPT did not grant direct model access to this app.")
        val accessToken = token.string("access_token") ?: throw ProviderException.InvalidConfiguration("ChatGPT did not return an access token.")
        val refreshToken = token.string("refresh_token") ?: throw ProviderException.InvalidConfiguration("ChatGPT did not return a refresh token.")
        val expiresIn = token.long("expires_in") ?: DEFAULT_TOKEN_LIFETIME_SECONDS
        return ChatGPTOAuthSession(
            clientId = clientId,
            subject = subject,
            email = claims.string("email").orEmpty(),
            hostId = hostId,
            accessToken = accessToken,
            refreshToken = refreshToken,
            idToken = idToken,
            scopes = scopes,
            expiresAt = System.currentTimeMillis() / 1000 + expiresIn,
            tokenType = token.string("token_type") ?: "Bearer",
        )
    }

    private suspend fun refresh(previous: ChatGPTOAuthSession): ChatGPTOAuthSession {
        val token = try {
            tokenRequest(
                "grant_type" to "refresh_token",
                "client_id" to previous.clientId,
                "refresh_token" to previous.refreshToken,
                "resource" to RESOURCE,
            )
        } catch (error: ProviderException) {
            throw ProviderException.InvalidConfiguration("ChatGPT session expired. Sign in again in provider settings.")
        }
        val accessToken = token.string("access_token")
            ?: throw ProviderException.InvalidConfiguration("ChatGPT refresh did not return an access token. Sign in again.")
        val refreshToken = token.string("refresh_token") ?: previous.refreshToken
        val idToken = token.string("id_token") ?: previous.idToken
        val scopes = token.string("scope")?.split(' ')?.filter(String::isNotBlank) ?: previous.scopes
        val expiresIn = token.long("expires_in") ?: DEFAULT_TOKEN_LIFETIME_SECONDS
        return previous.copy(
            accessToken = accessToken,
            refreshToken = refreshToken,
            idToken = idToken,
            scopes = scopes,
            expiresAt = System.currentTimeMillis() / 1000 + expiresIn,
            tokenType = token.string("token_type") ?: previous.tokenType,
        )
    }

    private suspend fun tokenRequest(vararg fields: Pair<String, String>): JsonObject = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build()
        val request = Request.Builder().url(TOKEN_ENDPOINT).post(body).build()
        httpClient().newCall(request).execute().use { response ->
            val raw = response.body.string()
            val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            if (!response.isSuccessful || root == null) {
                val description = root?.string("error_description") ?: root?.string("error") ?: "HTTP ${response.code}"
                throw ProviderException.InvalidConfiguration("ChatGPT sign-in request failed: ${description.take(ERROR_PREVIEW_CHARS)}")
            }
            root
        }
    }

    private suspend fun validateIdToken(idToken: String, expectedNonce: String, expectedClientId: String): JsonObject = withContext(Dispatchers.IO) {
        val parts = idToken.split('.')
        if (parts.size != 3) throw ProviderException.InvalidConfiguration("ChatGPT returned a malformed ID token.")
        val header = decodeJson(parts[0])
        val claims = decodeJson(parts[1])
        if (header.string("alg") != "RS256") throw ProviderException.InvalidConfiguration("ChatGPT ID token uses an unsupported signature.")
        val kid = header.string("kid") ?: throw ProviderException.InvalidConfiguration("ChatGPT ID token has no signing key ID.")
        val jwk = fetchJwks().array("keys")?.mapNotNull { it as? JsonObject }?.firstOrNull { it.string("kid") == kid }
            ?: throw ProviderException.InvalidConfiguration("Could not find ChatGPT's ID token signing key.")
        val modulus = jwk.string("n")?.let(::decodeUrlBytes) ?: throw ProviderException.InvalidConfiguration("ChatGPT signing key is incomplete.")
        val exponent = jwk.string("e")?.let(::decodeUrlBytes) ?: throw ProviderException.InvalidConfiguration("ChatGPT signing key is incomplete.")
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(java.math.BigInteger(1, modulus), java.math.BigInteger(1, exponent)))
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(publicKey)
        signature.update("${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII))
        if (!signature.verify(decodeUrlBytes(parts[2]))) throw ProviderException.InvalidConfiguration("ChatGPT ID token signature is invalid.")

        val now = System.currentTimeMillis() / 1000
        val audiences = when (val audience = claims["aud"]) {
            is JsonPrimitive -> listOfNotNull(audience.contentOrNull)
            is JsonArray -> audience.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }
        if (claims.string("iss") != ISSUER || expectedClientId !in audiences || (claims.long("exp") ?: 0L) < now - CLOCK_SKEW_SECONDS ||
            claims.string("nonce") != expectedNonce || claims.string("sub").isNullOrBlank()
        ) throw ProviderException.InvalidConfiguration("ChatGPT ID token validation failed.")
        claims
    }

    private suspend fun fetchJwks(): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(JWKS_ENDPOINT).get().build()
        httpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ProviderException.InvalidConfiguration("Could not verify ChatGPT sign-in (HTTP ${response.code}).")
            runCatching { json.parseToJsonElement(response.body.string()) as? JsonObject }.getOrNull()
                ?: throw ProviderException.InvalidConfiguration("ChatGPT returned an invalid signing-key list.")
        }
    }

    private fun readCallback(socket: java.net.Socket): Callback {
        socket.soTimeout = CALLBACK_TIMEOUT_MS
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
        val firstLine = reader.readLine().orEmpty()
        while (true) {
            val headerLine = reader.readLine() ?: break
            if (headerLine.isEmpty()) break
        }
        val target = firstLine.split(' ').getOrNull(1).orEmpty()
        val url = "http://127.0.0.1:$CALLBACK_PORT$target".toHttpUrlOrNull()
        val callback = url?.takeIf { it.encodedPath == CALLBACK_PATH }?.let { callbackUrl ->
            Callback(
                code = callbackUrl.queryParameter("code"),
                clientId = callbackUrl.queryParameter("client_id"),
                state = callbackUrl.queryParameter("state"),
                error = callbackUrl.queryParameter("error"),
                errorDescription = callbackUrl.queryParameter("error_description"),
            )
        } ?: Callback(error = "invalid_callback")
        val message = if (callback.error == null && callback.code != null) "Sign-in complete. Return to MD3 Agent." else "Sign-in did not complete. Return to MD3 Agent and try again."
        val body = "<!doctype html><meta charset=utf-8><title>MD3 Agent</title><p>$message</p>"
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII))
        writer.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\nContent-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n\r\n$body")
        writer.flush()
        return callback
    }

    private fun decodeJson(part: String): JsonObject = runCatching { json.parseToJsonElement(String(decodeUrlBytes(part), StandardCharsets.UTF_8)) as? JsonObject }
        .getOrNull() ?: throw ProviderException.InvalidConfiguration("ChatGPT returned a malformed ID token.")

    private fun randomUrlToken(size: Int) = base64Url(ByteArray(size).also(java.security.SecureRandom()::nextBytes))

    private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decodeUrlBytes(value: String) = Base64.getUrlDecoder().decode(value)

    private fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(name: String) = (this[name] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.array(name: String) = this[name] as? JsonArray

    private data class Callback(
        val code: String? = null,
        val clientId: String? = null,
        val state: String? = null,
        val error: String? = null,
        val errorDescription: String? = null,
    )

    private companion object {
        const val REGISTRATION_CLIENT_ID = "dynamic_agent_client"
        const val AUTHORIZATION_ENDPOINT = "https://auth.openai.com/api/accounts/authorize"
        const val TOKEN_ENDPOINT = "https://auth.openai.com/api/accounts/oauth/token"
        const val JWKS_ENDPOINT = "https://auth.openai.com/.well-known/jwks.json"
        const val ISSUER = "https://auth.openai.com"
        const val RESOURCE = "https://api.openai.com/v1"
        const val CALLBACK_PORT = 1455
        const val CALLBACK_PATH = "/auth/callback"
        const val CALLBACK_URI = "http://127.0.0.1:1455/auth/callback"
        const val CALLBACK_TIMEOUT_MS = 10 * 60 * 1000
        const val TOKEN_REFRESH_MARGIN_SECONDS = 90
        const val DEFAULT_TOKEN_LIFETIME_SECONDS = 3600L
        const val CLOCK_SKEW_SECONDS = 60
        const val ERROR_PREVIEW_CHARS = 200
        const val REQUIRED_SCOPE = "chatgpt.tokens.use.direct"
        val SCOPES = listOf("openid", "profile", "email", "offline_access", "resource.invoke", REQUIRED_SCOPE)
    }
}
