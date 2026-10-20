package com.lianbei.daily

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class CaptureStatusFailureTest {
    @Test fun statusReadFailureIsReportedAndPollingRecovers() = runBlocking {
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        val updates = Channel<PaymentCaptureStatus>(Channel.UNLIMITED)
        var attempts = 0
        withContext(Dispatchers.Main) { owner.lifecycle.currentState = Lifecycle.State.STARTED }
        val job = launch(Dispatchers.Main) {
            observeCaptureStatus(owner.lifecycle, 30, readStatus = {
                if (attempts++ == 0) throw java.io.IOException("private-source-content")
                PaymentCaptureStatus(queued = 2)
            }, onStatus = { updates.trySend(it) })
        }
        try {
            val failure = withTimeout(3_000) { updates.receive() }
            assertTrue(failure.readError.isNotEmpty())
            assertFalse(failure.readError.contains("private-source-content"))
            assertEquals(2, withTimeout(3_000) { updates.receive() }.queued)
        } finally {
            job.cancelAndJoin()
            withContext(Dispatchers.Main) { owner.lifecycle.currentState = Lifecycle.State.DESTROYED }
            updates.close()
        }
    }
}
