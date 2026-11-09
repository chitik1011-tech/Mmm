package dev.huecoder.md3agent.conversation

object ReplyRetry {
    data class Plan(val delayMs: Long, val limit: Int)

    fun plan(failure: Throwable, attempt: Int): Plan? =
        if (failure is kotlinx.coroutines.CancellationException || attempt < 1) null else Plan(3_000L, Int.MAX_VALUE)
}
