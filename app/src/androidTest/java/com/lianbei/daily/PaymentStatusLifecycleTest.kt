package com.lianbei.daily

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PaymentStatusLifecycleTest {
    @Test fun pollingStopsBelowStartedAndReadsImmediatelyOnRestart() = runBlocking {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        val reads = AtomicInteger()
        val updates = Channel<PaymentCaptureStatus>(Channel.UNLIMITED)
        withContext(Dispatchers.Main) { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        val job = launch(Dispatchers.Main) {
            observeCaptureStatus(owner.lifecycle, 30L,
                readStatus = { PaymentCaptureStatus(queued = reads.incrementAndGet()) },
                onStatus = { updates.trySend(it) })
        }
        try {
            delay(150)
            assertEquals(0, reads.get())
            withContext(Dispatchers.Main) { owner.lifecycle.currentState = Lifecycle.State.STARTED }
            withTimeout(3_000) { updates.receive() }
            withContext(Dispatchers.Main) { owner.lifecycle.currentState = Lifecycle.State.CREATED }
            // Let cancellation finish; there must be no reads during the stopped interval.
            yield()
            val stoppedAt = reads.get()
            while (updates.tryReceive().isSuccess) { }
            delay(150)
            assertEquals(stoppedAt, reads.get())
            withContext(Dispatchers.Main) { owner.lifecycle.currentState = Lifecycle.State.STARTED }
            assertTrue(withTimeout(3_000) { updates.receive() }.queued > stoppedAt)
        } finally {
            job.cancelAndJoin()
            withContext(Dispatchers.Main) { owner.lifecycle.currentState = Lifecycle.State.DESTROYED }
            updates.close()
        }
    }

    @Test fun leavingStartedCancelsAnInFlightRead() = runBlocking {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val updates = AtomicInteger()
        withContext(Dispatchers.Main) { owner.lifecycle.currentState = Lifecycle.State.STARTED }
        val job = launch(Dispatchers.Main) {
            observeCaptureStatus(owner.lifecycle, 30L, readStatus = {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }, onStatus = { updates.incrementAndGet() })
        }
        try {
            withTimeout(3_000) { entered.await() }
            withContext(Dispatchers.Main) { owner.lifecycle.currentState = Lifecycle.State.CREATED }
            withTimeout(3_000) { cancelled.await() }
            assertEquals(0, updates.get())
        } finally {
            job.cancelAndJoin()
            withContext(Dispatchers.Main) { owner.lifecycle.currentState = Lifecycle.State.DESTROYED }
        }
    }
}
