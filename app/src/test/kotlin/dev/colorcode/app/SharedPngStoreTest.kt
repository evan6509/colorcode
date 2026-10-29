package dev.colorcode.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

class SharedPngStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected FileNotFoundException") } catch (_: FileNotFoundException) {}
    }

    @Test fun firstSharedUriStillReadsFirstBytesAfterSecondShare() {
        val store = SharedPngStore(temporary.newFolder())
        val first = store.create { it.write(byteArrayOf(1, 2, 3)) }
        val second = store.create { it.write(byteArrayOf(4, 5, 6)) }
        assertNotEquals(first, second)
        assertArrayEquals(byteArrayOf(1, 2, 3), store.resolve(first).readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), store.resolve(second).readBytes())
        assertTrue(first.matches(Regex("content://dev\\.colorcode\\.app\\.share/[a-f0-9]{32}\\.png")))
    }

    @Test fun traversalAndUntrustedUriFormsAreRejected() {
        val store = SharedPngStore(temporary.newFolder())
        val good = store.create { it.write(1) }
        val name = good.substringAfterLast('/')
        listOf("../secret", "%2e%2e/secret", "%252e%252e%252fsecret", "/$name", "$name/../$name", "$name%2f..", "..\\secret").forEach {
            rejects { store.resolve("content://${SharedPngStore.AUTHORITY}/$it") }
        }
        listOf(good + "?x=y", good + "#x", good.replace("content:", "file:"),
            good.replace(SharedPngStore.AUTHORITY, "evil.example"),
            good.replace(SharedPngStore.AUTHORITY, "user@${SharedPngStore.AUTHORITY}"),
            good.replace(SharedPngStore.AUTHORITY, "${SharedPngStore.AUTHORITY}:80"),
            good.replace(".png", "%2epng")).forEach { bad -> rejects { store.resolve(bad) } }
    }

    @Test fun symlinkCannotEscapePrivateRoot() {
        val store = SharedPngStore(temporary.newFolder())
        val uri = store.create { it.write(1) }
        val file = store.resolve(uri)
        val secret = temporary.newFile().apply { writeText("private") }
        assertTrue(file.delete())
        Files.createSymbolicLink(file.toPath(), secret.toPath())
        rejects { store.resolve(uri) }
    }

    @Test fun collisionNeverTruncatesPublishedContent() {
        val tokens = ArrayDeque(listOf("a".repeat(32), "a".repeat(32), "b".repeat(32)))
        val store = SharedPngStore(temporary.newFolder(), nextToken = { tokens.removeFirst() })
        val first = store.create { it.write(21) }
        val second = store.create { it.write(42) }
        assertArrayEquals(byteArrayOf(21), store.resolve(first).readBytes())
        assertArrayEquals(byteArrayOf(42), store.resolve(second).readBytes())
    }

    @Test fun cleanupKeepsLiveSharesAndRevokesExpiredUrisBeforeDeleting() {
        var now = 1_000_000L
        val store = SharedPngStore(temporary.newFolder(), clock = { now })
        val first = store.create { it.write(7) }
        val firstFile = store.resolve(first)
        now += SharedPngStore.LIFETIME_MS - 1
        val second = store.create { it.write(8) }
        store.cleanup { fail("Live URI must not be revoked") }
        assertTrue(firstFile.exists())
        now += 2
        rejects { store.resolve(first) }
        val revoked = mutableListOf<String>()
        store.cleanup { uri -> assertTrue(firstFile.exists()); revoked.add(uri) }
        assertEquals(listOf(first), revoked)
        assertFalse(firstFile.exists())
        assertArrayEquals(byteArrayOf(8), store.resolve(second).readBytes())
    }

    @Test fun failedWriteLeavesNoPublishedOrPartialFile() {
        val root = temporary.newFolder()
        val store = SharedPngStore(root)
        try { store.create { it.write(1); throw java.io.IOException("full") }; fail() }
        catch (_: java.io.IOException) {}
        assertEquals(0, root.listFiles()!!.size)
    }

    @Test fun symlinkDirectoryAndMissingOrLegacyUrisAreRejected() {
        val parent = temporary.newFolder()
        val root = File(parent, "shares")
        Files.createSymbolicLink(root.toPath(), temporary.newFolder().toPath())
        val store = SharedPngStore(root)
        rejects { store.create { it.write(1) } }
        rejects { store.resolve("content://${SharedPngStore.AUTHORITY}/${"a".repeat(32)}.png") }
        rejects { store.resolve("content://${SharedPngStore.AUTHORITY}/shared-colorcode.png") }
    }

    @Test fun openRecipientStreamKeepsOriginalBytesThroughExpiryCleanupAndLaterShare() {
        var now = 1_000_000L
        val store = SharedPngStore(temporary.newFolder(), clock = { now })
        val first = store.create { it.write(byteArrayOf(1, 2, 3)) }
        store.resolve(first).inputStream().use { heldByRecipient ->
            now += SharedPngStore.LIFETIME_MS + 1
            store.cleanup {}
            val second = store.create { it.write(byteArrayOf(4, 5, 6)) }
            assertArrayEquals(byteArrayOf(1, 2, 3), heldByRecipient.readBytes())
            assertArrayEquals(byteArrayOf(4, 5, 6), store.resolve(second).readBytes())
            rejects { store.resolve(first) }
        }
    }
}
