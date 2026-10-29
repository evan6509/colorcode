package dev.colorcode.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileNotFoundException
import java.nio.file.Files

class CameraFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected FileNotFoundException") } catch (_: FileNotFoundException) {}
    }

    @Test fun retakeCannotOverwriteEarlierRetryInput() {
        val directory = temporary.newFolder()
        val first = CameraFiles.create(directory, byteArrayOf(1, 2, 3))
        val second = CameraFiles.create(directory, byteArrayOf(4, 5, 6))
        assertNotEquals(first.name, second.name)
        assertArrayEquals(byteArrayOf(1, 2, 3), CameraFiles.resolve(directory, first.name).readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), CameraFiles.resolve(directory, second.name).readBytes())
    }

    @Test fun cameraResultNamesCannotTraverseOrEscapeViaSymlink() {
        val directory = temporary.newFolder()
        val file = CameraFiles.create(directory, byteArrayOf(7))
        listOf("../${file.name}", "/${file.name}", "%2e%2e%2f${file.name}", "..\\${file.name}", "capture.jpg").forEach {
            rejects { CameraFiles.resolve(directory, it) }
        }
        assertTrue(file.delete())
        val outside = temporary.newFile().apply { writeText("secret") }
        Files.createSymbolicLink(file.toPath(), outside.toPath())
        rejects { CameraFiles.resolve(directory, file.name) }
    }

    @Test fun failedCameraDecodeRetainsExactInputAcrossDetachAndRetry() {
        class Screen { var error: String? = null; var decoded: ByteArray? = null }
        val directory = temporary.newFolder()
        val file = CameraFiles.create(directory, byteArrayOf(1, 0, -1))
        val worker = java.util.ArrayDeque<() -> Unit>()
        val main = java.util.ArrayDeque<() -> Unit>()
        val work = RetainedWork<Screen>({ worker.add(it) }, { main.add(it) }, {})
        val old = Screen(); work.attach(old)
        work.submit("Decode", {
            assertArrayEquals(byteArrayOf(1, 0, -1), CameraFiles.resolve(directory, file.name).readBytes())
            throw java.io.IOException("No verified code")
        }, { _: Nothing -> fail() }, { error = it.message })
        work.detach(old); worker.removeFirst()(); main.removeFirst()()
        assertTrue(file.exists())
        val replacement = Screen(); work.attach(replacement)
        assertEquals("No verified code", replacement.error)
        assertNull(old.error)
        work.submit("Retry", { CameraFiles.resolve(directory, file.name).readBytes() }, {
            decoded = it; CameraFiles.delete(directory, file.name)
        }, { throw it })
        worker.removeFirst()(); main.removeFirst()()
        assertArrayEquals(byteArrayOf(1, 0, -1), replacement.decoded)
        assertFalse(file.exists())
    }
}
