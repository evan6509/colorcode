package dev.colorcode.app

import dev.colorcode.core.ColorCode
import dev.colorcode.core.SquareRenderer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LastCodeStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file() = File(temporary.root, "last-code")

    @Test fun freshStoreRestoresExactPayloadAndGridForPreview() {
        for (grid in ColorCode.grids) {
            val bytes = ByteArray(ColorCode.capacity(grid)) { it.toByte() }
            LastCodeStore(file()).save(bytes, grid)
            val code = LastCodeStore(file()).load()!!
            assertEquals(grid, code.grid)
            assertArrayEquals(bytes, code.bytes)
            val preview = SquareRenderer.render(ColorCode.encode(code.bytes, code.grid), 8)
            assertArrayEquals(bytes, ColorCode.decode(preview).bytes)
        }
    }

    @Test fun replacementAndEmptyPayloadSurviveNewStoreInstances() {
        LastCodeStore(file()).save("previous".toByteArray(), 57)
        LastCodeStore(file()).save(byteArrayOf(), 89)
        val code = LastCodeStore(file()).load()!!
        assertEquals(89, code.grid)
        assertArrayEquals(byteArrayOf(), code.bytes)
        assertEquals(listOf("last-code"), temporary.root.listFiles()!!.map { it.name })
    }

    @Test fun missingTruncatedOrCorruptStateAllowsSampleFallback() {
        assertNull(LastCodeStore(file()).load())
        val bytes = "last created code".toByteArray()
        LastCodeStore(file()).save(bytes, 57)
        val valid = file().readBytes()
        for (length in 0 until valid.size) {
            file().writeBytes(valid.copyOf(length))
            assertNull(LastCodeStore(file()).load())
        }
        val corrupt = valid.copyOf().apply { this[12] = (this[12].toInt() xor 1).toByte() }
        file().writeBytes(corrupt)
        assertNull(LastCodeStore(file()).load())
        file().writeBytes(valid + 0)
        assertNull(LastCodeStore(file()).load())
        for (offset in listOf(0, 4, 8)) {
            file().writeBytes(valid.copyOf().apply { this[offset] = -1 })
            assertNull(LastCodeStore(file()).load())
        }
    }

    @Test fun failedEncodeCannotReplacePreviousCode() {
        val previous = "keep this code".toByteArray()
        LastCodeStore(file()).save(previous, 57)
        try {
            LastCodeStore(file()).save(ByteArray(ColorCode.capacity(57) + 1), 57)
            fail("Expected oversized payload rejection")
        } catch (_: IllegalArgumentException) { }
        assertArrayEquals(previous, LastCodeStore(file()).load()!!.bytes)
    }
}
