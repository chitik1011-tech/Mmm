package dev.huecoder.md3agent.tools.system

import dev.huecoder.md3agent.data.settings.ToolPermission
import dev.huecoder.md3agent.data.settings.ToolSettings
import dev.huecoder.md3agent.llm.ToolSpec
import dev.huecoder.md3agent.system.shell.ShellResult
import dev.huecoder.md3agent.system.shell.TermuxShell
import dev.huecoder.md3agent.tools.Tool
import dev.huecoder.md3agent.tools.ToolOutcome
import dev.huecoder.md3agent.tools.integerProperty
import dev.huecoder.md3agent.tools.objectSchema
import dev.huecoder.md3agent.tools.optionalInt
import dev.huecoder.md3agent.tools.requireString
import dev.huecoder.md3agent.tools.stringProperty
import kotlinx.serialization.json.JsonObject

class RunTermuxCommandTool(private val termux: TermuxShell) : Tool {
    override val defaultPermission = ToolPermission.ASK

    override val timeoutMs = MAX_TIMEOUT_S * MS_PER_SECOND + TIMEOUT_GRACE_MS

    override val spec = ToolSpec(
        name = NAME,
        description = "Runs a command directly inside the installed Termux app using Termux RUN_COMMAND. " +
            "Use this for pkg/apt, git, clang, cmake, Gradle, Node, Python, build tools, and files in the Termux home. " +
            "This tool is independent of the selected Android shell mode. Never emulate Termux through root, su, nsenter, /data/data/com.termux, or /data/user/0/com.termux when this tool is available.",
        parameters = objectSchema(required = listOf("command")) {
            stringProperty("command", "Command to execute in Termux bash")
            integerProperty("timeout_seconds", "How long to wait before killing the command, default $DEFAULT_TIMEOUT_S", 1, MAX_TIMEOUT_S.toInt())
        },
    )

    override fun promptNote(settings: ToolSettings): String =
        "$NAME executes bash inside Termux through Termux RunCommandService, regardless of whether the selected shell is Sandbox, Shizuku, or Root. " +
            "For any Termux/package/build/coding task, prefer $NAME. Do not use run_command with su/nsenter to reach Termux. " +
            "Each call is a fresh non-interactive Termux process; chain dependent commands with &&."

    override suspend fun execute(arguments: JsonObject, settings: ToolSettings): ToolOutcome {
        val command = arguments.requireString("command")
        val timeoutSeconds = (arguments.optionalInt("timeout_seconds") ?: DEFAULT_TIMEOUT_S.toInt()).toLong().coerceIn(1, MAX_TIMEOUT_S)
        val result = termux.run(command, timeoutSeconds * MS_PER_SECOND)
        return ToolOutcome(result.describe(timeoutSeconds), isError = result.exitCode != 0)
    }

    private fun ShellResult.describe(timeoutSeconds: Long): String = buildString {
        if (timedOut) appendLine("Killed after $timeoutSeconds s without finishing") else appendLine("Exit code: ${exitCode ?: "unknown"}")
        if (stdout.isNotEmpty()) append("stdout:\n").appendLine(stdout.clipped())
        if (stderr.isNotEmpty()) append("stderr:\n").appendLine(stderr.clipped())
        if (stdout.isEmpty() && stderr.isEmpty()) appendLine("No output")
    }.trimEnd()

    private fun String.clipped(): String {
        if (length <= MAX_STREAM_CHARS) return trimEnd()
        val half = MAX_STREAM_CHARS / 2
        return take(half) + "\n… ${length - MAX_STREAM_CHARS} characters cut …\n" + takeLast(half).trimEnd()
    }

    companion object {
        const val NAME = "run_termux_command"
        private const val DEFAULT_TIMEOUT_S = 60L
        private const val MAX_TIMEOUT_S = 300L
        private const val MS_PER_SECOND = 1_000L
        private const val TIMEOUT_GRACE_MS = 10_000L
        private const val MAX_STREAM_CHARS = 12_000
    }
}
