package dev.huecoder.md3agent.llm.wire

import dev.huecoder.md3agent.llm.ChatTurn
import dev.huecoder.md3agent.llm.CompletionEvent
import dev.huecoder.md3agent.llm.ProviderEndpoint
import dev.huecoder.md3agent.llm.TokenUsage
import dev.huecoder.md3agent.llm.models.ModelOutput
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** OpenAI Responses API wire format used by the official ChatGPT account-sharing flow. */
object ResponsesWire : ChatWire {
    override fun request(endpoint: ProviderEndpoint, chat: ChatRequest): Request = endpoint
        .authorizedRequest("responses")
        .header("Accept", "text/event-stream")
        .post(body(chat).toString().toRequestBody(JSON_MEDIA_TYPE))
        .build()

    override fun newStream() = ChatStream(::events)

    private fun events(chunk: JsonObject): List<CompletionEvent> {
        val result = mutableListOf<CompletionEvent>()
        when (chunk.text("type")) {
            "response.output_text.delta" -> chunk.text("delta")?.takeIf(String::isNotEmpty)?.let { result += CompletionEvent.ContentDelta(it) }
            "response.reasoning_summary_text.delta" -> chunk.text("delta")?.takeIf(String::isNotEmpty)?.let { result += CompletionEvent.ReasoningDelta(it) }
            "response.output_item.added", "response.output_item.done" -> {
                val item = chunk.obj("item")
                when (item?.text("type")) {
                    "function_call" -> if (chunk.text("type") == "response.output_item.added") {
                        result += CompletionEvent.ToolCallDelta(
                            index = chunk.int("output_index") ?: 0,
                            id = item.text("call_id") ?: item.text("id"),
                            name = item.text("name"),
                            arguments = item.text("arguments").orEmpty().takeIf(String::isNotEmpty),
                        )
                    }
                    "image_generation_call" -> if (chunk.text("type") == "response.output_item.done") {
                        item.text("result")?.let { result += CompletionEvent.ImageOutput(InlineData.url("image/png", it)) }
                    }
                }
            }
            "response.function_call_arguments.delta" -> chunk.text("delta")?.takeIf(String::isNotEmpty)?.let {
                result += CompletionEvent.ToolCallDelta(chunk.int("output_index") ?: 0, id = null, name = null, arguments = it)
            }
            "response.completed", "response.incomplete" -> {
                val response = chunk.obj("response")
                response?.obj("usage")?.let(::usage)?.let { result += it }
                val status = if (chunk.text("type") == "response.incomplete") "length" else "stop"
                result += CompletionEvent.Finished(status)
            }
            "response.failed", "error" -> {
                val response = chunk.obj("response")
                val message = response?.obj("error")?.text("message") ?: chunk.obj("error")?.text("message")
                throw dev.huecoder.md3agent.llm.ProviderException.Http(0, message ?: "ChatGPT response failed")
            }
        }
        return result
    }

    private fun usage(value: JsonObject): CompletionEvent.Usage? {
        val prompt = value.int("input_tokens") ?: return null
        val completion = value.int("output_tokens") ?: return null
        val cached = value.obj("input_tokens_details")?.int("cached_tokens") ?: 0
        return CompletionEvent.Usage(TokenUsage(prompt, completion, cached))
    }

    private fun body(chat: ChatRequest) = buildJsonObject {
        put("model", chat.model)
        put("stream", true)
        put("store", false)
        chat.options.reasoningEffort?.let { effort ->
            if (effort.wireName != "none") putJsonObject("reasoning") {
                put("effort", effort.wireName)
                put("summary", "auto")
            }
        }
        putJsonArray("input") {
            chat.turns.forEach { turn ->
                when {
                    turn.role == ChatTurn.Role.TOOL && turn.toolCallId != null -> addJsonObject {
                        put("type", "function_call_output")
                        put("call_id", turn.toolCallId)
                        put("output", turn.content)
                    }
                    else -> {
                        if (turn.content.isNotEmpty() || turn.imageUrls.isNotEmpty()) add(message(turn))
                        turn.toolCalls.forEach { call -> addJsonObject {
                            put("type", "function_call")
                            put("call_id", call.id)
                            put("name", call.name)
                            put("arguments", call.arguments)
                        } }
                    }
                }
            }
        }
        if (chat.tools.isNotEmpty() || chat.options.output != ModelOutput.TEXT) {
            putJsonArray("tools") {
                chat.tools.forEach { tool -> addJsonObject {
                    put("type", "function")
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parameters)
                } }
                if (chat.options.output != ModelOutput.TEXT) addJsonObject { put("type", "image_generation") }
            }
        }
    }

    private fun message(turn: ChatTurn) = buildJsonObject {
        put("role", turn.role.wireName)
        if (turn.imageUrls.isEmpty()) {
            put("content", turn.content)
        } else {
            putJsonArray("content") {
                if (turn.content.isNotEmpty()) addJsonObject {
                    put("type", "input_text")
                    put("text", turn.content)
                }
                turn.imageUrls.forEach { url -> addJsonObject {
                    put("type", "input_image")
                    put("image_url", url)
                    put("detail", "auto")
                } }
            }
        }
    }

    private fun JsonObject.obj(name: String) = this[name] as? JsonObject

    private fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.int(name: String) = (this[name] as? JsonPrimitive)?.intOrNull
}
