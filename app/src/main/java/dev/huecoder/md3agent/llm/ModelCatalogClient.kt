package dev.huecoder.md3agent.llm

import dev.huecoder.md3agent.data.settings.ApiFormat
import dev.huecoder.md3agent.data.settings.ProviderProfile
import dev.huecoder.md3agent.llm.models.ModelInfo
import dev.huecoder.md3agent.llm.models.ModelInfoParser
import dev.huecoder.md3agent.llm.auth.ChatGPTOAuth
import dev.huecoder.md3agent.net.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.IOException

class ModelCatalogClient(private val httpClients: HttpClientProvider, private val chatGPTOAuth: ChatGPTOAuth? = null) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun listModels(provider: ProviderProfile): List<String> = listModelInfo(provider).map { it.id }

    suspend fun listModelInfo(provider: ProviderProfile, withDetails: Boolean = false): List<ModelInfo> = withContext(Dispatchers.IO) {
        val requestProvider = if (provider.format == ApiFormat.CHATGPT) chatGPTOAuth?.freshProvider(provider) ?: provider else provider
        val endpoint = ProviderEndpoint(requestProvider)
        val url = endpoint.url(MODELS_PATH).newBuilder().apply {
            when (provider.format) {
                ApiFormat.OPENAI, ApiFormat.CHATGPT, ApiFormat.ELEVENLABS -> Unit
                ApiFormat.ANTHROPIC -> addQueryParameter("limit", PAGE_SIZE)
                ApiFormat.GEMINI -> addQueryParameter("pageSize", PAGE_SIZE)
            }
        }.build()
        val request = endpoint.authorizedRequest(url).get().build()
        try {
            httpClients.current().newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    throw ProviderException.Http(response.code, "HTTP ${response.code}: ${body.take(ERROR_PREVIEW_CHARS)}")
                }
                val data = json.parseToJsonElement(body)
                if (provider.format == ApiFormat.CHATGPT) parseChatGPTModels(data) else ModelInfoParser.parseList(data, withDetails)
            }
        } catch (e: IOException) {
            throw ProviderException.Network(e)
        } catch (e: SerializationException) {
            throw ProviderException.MalformedResponse(UNKNOWN_FORMAT)
        } catch (e: IllegalArgumentException) {
            throw ProviderException.MalformedResponse(UNKNOWN_FORMAT)
        }
    }

    private fun parseChatGPTModels(raw: kotlinx.serialization.json.JsonElement): List<ModelInfo> {
        val models = ((raw as? JsonObject)?.get("models") as? JsonArray)
            ?: throw ProviderException.MalformedResponse(UNKNOWN_FORMAT)
        return models.mapNotNull { element ->
            val model = element as? JsonObject ?: return@mapNotNull null
            val slug = model.string("slug") ?: model.string("id") ?: return@mapNotNull null
            val visibility = model.string("visibility")
            if (visibility != "list") return@mapNotNull null
            val levels = model["supported_reasoning_levels"] as? JsonArray
                ?: model["supported_reasoning_efforts"] as? JsonArray
            val efforts = levels?.mapNotNull { level ->
                val wireName = when (level) {
                    is JsonObject -> level.string("effort") ?: level.string("reasoning_effort") ?: level.string("reasoningEffort")
                    is JsonPrimitive -> level.contentOrNull
                    else -> null
                }
                wireName?.let(dev.huecoder.md3agent.llm.models.ReasoningEffort::fromWire)
            }.orEmpty().distinct()
            val inputModalities = model.strings("input_modalities") ?: model.obj("modalities")?.strings("input")
            ModelInfo(
                id = slug,
                name = model.string("display_name") ?: model.string("name"),
                contextLength = model.int("context_window") ?: model.int("context_length") ?: model.int("max_context_window"),
                vision = inputModalities?.contains("image"),
                reasoning = when {
                    efforts.isNotEmpty() -> true
                    levels != null -> false
                    else -> null
                },
                reasoningEfforts = efforts,
                reasoningControl = if (efforts.isNotEmpty()) dev.huecoder.md3agent.llm.models.ReasoningControl.REASONING_OBJECT else null,
                toolUse = true,
            )
        }.distinctBy(ModelInfo::id).sortedBy(ModelInfo::id)
    }

    private fun JsonObject.obj(name: String) = this[name] as? JsonObject

    private fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.int(name: String) = (this[name] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.strings(name: String) = (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }

    private companion object {
        const val MODELS_PATH = "models"
        const val PAGE_SIZE = "1000"
        const val ERROR_PREVIEW_CHARS = 300
        const val UNKNOWN_FORMAT = "Server returned the model list in an unknown format"
    }
}
