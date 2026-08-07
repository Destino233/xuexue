package dev.xuexue.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class PdfRenderAdmissionTest {
    @Test
    fun neverRunsMoreThanTheConfiguredNumberOfTasks() =
        runBlocking {
            val admission = PdfRenderAdmission(maxInFlight = 2)
            val active = AtomicInteger()
            val peak = AtomicInteger()
            coroutineScope {
                repeat(8) {
                    launch {
                        admission.withPermit {
                            val current = active.incrementAndGet()
                            peak.updateAndGet { previous -> maxOf(previous, current) }
                            delay(20)
                            active.decrementAndGet()
                        }
                    }
                }
            }
            assertEquals(2, peak.get())
        }

    @Test
    fun cancellingAQueuedTaskReleasesItsAdmissionWait() =
        runBlocking {
            val admission = PdfRenderAdmission(maxInFlight = 1)
            val firstStarted = CompletableDeferred<Unit>()
            val firstRelease = CompletableDeferred<Unit>()
            val first =
                async {
                    admission.withPermit {
                        firstStarted.complete(Unit)
                        firstRelease.await()
                    }
                }
            firstStarted.await()

            val queued =
                async {
                    admission.withPermit { error("The cancelled task must not enter the render block.") }
                }
            queued.cancelAndJoin()
            firstRelease.complete(Unit)
            first.await()

            withTimeout(1_000) {
                admission.withPermit { }
            }
        }
}
