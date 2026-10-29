package dev.colorcode.app

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class RetainedWorkTest {
    private class Screen { val results = mutableListOf<String>(); var busy: String? = null }
    private val worker = ArrayDeque<() -> Unit>()
    private val main = ArrayDeque<() -> Unit>()
    private val work = RetainedWork<Screen>({ worker.add(it) }, { main.add(it) }, { busy = it })
    private fun complete() { while (worker.isNotEmpty()) worker.removeFirst()(); while (main.isNotEmpty()) main.removeFirst()() }

    @Test fun encodingCompletionDuringRotationReachesNewScreenExactlyOnce() {
        val old = Screen(); work.attach(old)
        work.submit("Encoding", { "encoded input snapshot" }, { results.add(it) }, { throw it })
        work.detach(old)
        complete()
        val replacement = Screen(); work.attach(replacement)
        assertEquals(listOf("encoded input snapshot"), replacement.results)
        assertTrue(old.results.isEmpty())
        work.detach(replacement); work.attach(replacement)
        assertEquals(1, replacement.results.size)
        assertNull(replacement.busy)
    }

    @Test fun queuedImportAndDecodeSurviveRotationBeforeWorkStarts() {
        val old = Screen(); work.attach(old)
        work.submit("Import", { "exact file bytes" }, { results.add(it) }, { throw it })
        work.submit("Decode", { "verified payload" }, { results.add(it) }, { throw it })
        work.detach(old)
        val next = Screen(); work.attach(next)
        assertEquals("Import", next.busy)
        complete()
        assertEquals(listOf("exact file bytes", "verified payload"), next.results)
        assertTrue(old.results.isEmpty())
        assertNull(next.busy)
    }

    @Test fun cameraInputDeletedOnlyAfterSuccessfulDeliveryToResumedScreen() {
        val file = java.nio.file.Files.createTempFile("capture-test", ".jpg").toFile().apply { writeText("retry input") }
        try {
            val old = Screen(); work.attach(old)
            work.submit("Camera", { file.readText() }, { results.add(it); file.delete() }, { throw it })
            work.detach(old); complete()
            assertTrue(file.exists())
            val next = Screen(); work.attach(next)
            assertEquals(listOf("retry input"), next.results)
            assertFalse(file.exists())
        } finally { file.delete() }
    }

    @Test fun failureSurvivesRotationAndPreservesRetryInput() {
        val old = Screen(); work.attach(old)
        work.submit("Reading", { throw java.io.IOException("retry this input") }, { _: Nothing -> fail() }, { results.add(it.message!!) })
        work.detach(old); complete()
        val next = Screen(); work.attach(next)
        assertEquals(listOf("retry this input"), next.results)
    }

    @Test fun finishingDiscardsUndeliveredResources() {
        var discarded = 0
        work.attach(Screen())
        work.submit("Bitmap", { 1 }, { fail("Finished owner must not receive work") }, { throw it }, { discarded += it })
        worker.removeFirst()()
        work.close()
        main.removeFirst()()
        assertEquals(1, discarded)
    }

    @Test fun staleDetachCannotRemoveNewOwnerAndRepeatedRotationsKeepQueuedResult() {
        val first = Screen(); val second = Screen(); val third = Screen()
        work.attach(first)
        work.submit("Decode", { "verified" }, { results.add(it) }, { throw it })
        work.detach(first); work.attach(second); work.detach(second)
        complete()
        work.attach(third); work.detach(first)
        work.submit("Import", { "binary" }, { results.add(it) }, { throw it })
        complete()
        assertEquals(listOf("verified", "binary"), third.results)
        assertTrue(first.results.isEmpty()); assertTrue(second.results.isEmpty())
    }

    @Test fun closingWhileDetachedDisposesBufferedBitmapResultOnce() {
        var disposed = 0
        val old = Screen(); work.attach(old)
        work.submit("Bitmap", { 1 }, { fail() }, { throw it }, { disposed += it })
        work.detach(old); complete()
        assertEquals(0, disposed)
        work.close()
        assertEquals(1, disposed)
    }
}
