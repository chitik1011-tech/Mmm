package dev.huecoder.md3agent.tools.system

import dev.huecoder.md3agent.data.settings.ToolPermission
import dev.huecoder.md3agent.data.settings.ToolSettings
import dev.huecoder.md3agent.llm.ChatCompletionClient
import dev.huecoder.md3agent.llm.ChatTurn
import dev.huecoder.md3agent.llm.CompletionEvent
import dev.huecoder.md3agent.llm.CompletionOptions
import dev.huecoder.md3agent.llm.ToolSpec
import dev.huecoder.md3agent.llm.ToolCallAccumulator
import dev.huecoder.md3agent.tools.ContextualTool
import dev.huecoder.md3agent.tools.ToolContext
import dev.huecoder.md3agent.tools.ToolOutcome
import dev.huecoder.md3agent.tools.objectSchema
import dev.huecoder.md3agent.tools.optionalArray
import dev.huecoder.md3agent.tools.stringArrayProperty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Independent, bounded delegates with explicitly automatic parent tools only. */
class SubagentsTool(private val client: ChatCompletionClient) : ContextualTool {
    override val defaultPermission = ToolPermission.ASK
    override val timeoutMs = 600_000L
    override val spec = ToolSpec(
        NAME,
        "Run up to five independent subagents concurrently using the current chat model. " +
            "Give each a self-contained assignment (planning, review, debugging, or code proposal). " +
            "They can use only tools already set to Auto and allowed by the parent. Assign disjoint files/directories. Integrate their findings yourself.",
        objectSchema(listOf("tasks")) {
            stringArrayProperty("tasks", "One to five independent task descriptions, including necessary context")
        },
    )

    override suspend fun execute(arguments: JsonObject, settings: ToolSettings, context: ToolContext): ToolOutcome {
        val provider = context.provider ?: return ToolOutcome.error("No current chat provider")
        val model = context.modelId ?: return ToolOutcome.error("No current chat model")
        val raw = arguments.optionalArray("tasks")
        if (raw.size !in 1..5) return ToolOutcome.error("Provide one to five tasks")
        val tasks = raw.map {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content?.trim()
                ?.takeIf { task -> task.isNotEmpty() && task.length <= 12000 }
                ?: return ToolOutcome.error("Each task must be a non-empty string of at most 12000 characters")
        }
        val results = coroutineScope {
            tasks.mapIndexed { index, task ->
                async {
                    try {
                        withTimeout(580_000L) {
                            val turns = mutableListOf(
                                ChatTurn(ChatTurn.Role.SYSTEM, "You are an independent subagent. Solve only the supplied task. Use only supplied tools, stay within assigned files, and report actual results. If a tool is absent, give a proposal instead."),
                                ChatTurn(ChatTurn.Role.USER, task),
                            )
                            var summary = "Reached the subagent tool-round limit"
                            for (round in 0 until 8) {
                                val answer = StringBuilder()
                                val calls = ToolCallAccumulator()
                                client.stream(provider, model, turns, context.delegatedTools, CompletionOptions()).collect { event ->
                                    when (event) {
                                        is CompletionEvent.ContentDelta -> if (answer.length < 10000) answer.append(event.text.take(10000 - answer.length)) else Unit
                                        is CompletionEvent.ToolCallDelta -> calls.add(event)
                                        else -> Unit
                                    }
                                }
                                val actions = calls.calls()
                                if (actions.isEmpty()) {
                                    summary = answer.toString().ifBlank { "No text returned" }
                                    break
                                }
                                turns += ChatTurn(ChatTurn.Role.ASSISTANT, answer.toString(), toolCalls = actions)
                                for (action in actions) {
                                    val result = context.delegate?.invoke(action) ?: ToolOutcome.denied()
                                    turns += ChatTurn(ChatTurn.Role.TOOL, result.content.take(12000), toolCallId = action.id)
                                }
                            }
                            "Agent ${index + 1}: " + summary
                        }
                    } catch (e: TimeoutCancellationException) {
                        "Agent ${index + 1}: timed out"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "Agent ${index + 1}: request failed (${e.javaClass.simpleName})"
                    }
                }
            }.awaitAll()
        }
        return ToolOutcome(results.joinToString("\n\n"))
    }

    companion object { const val NAME = "run_subagents" }
}
