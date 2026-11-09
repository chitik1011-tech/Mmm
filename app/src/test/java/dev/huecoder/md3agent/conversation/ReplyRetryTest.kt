package dev.huecoder.md3agent.conversation

import dev.huecoder.md3agent.llm.ProviderException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyRetryTest {
    @Test fun `errors retry every three seconds without an attempt limit`() {
        val errors = listOf(ProviderException.Http(401, "unauthorized"), ProviderException.Http(503, "unavailable"), IllegalStateException("failed"))
        for (error in errors) for (attempt in listOf(1, 11, 10_000)) {
            assertEquals(3_000L, ReplyRetry.plan(error, attempt)?.delayMs)
            assertEquals(Int.MAX_VALUE, ReplyRetry.plan(error, attempt)?.limit)
        }
    }

    @Test fun `cancellation and invalid attempts do not retry`() {
        assertNull(ReplyRetry.plan(CancellationException("stopped"), 1))
        assertNull(ReplyRetry.plan(IllegalStateException(), 0))
    }
}
