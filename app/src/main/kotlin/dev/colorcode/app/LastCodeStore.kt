package dev.colorcode.app

import dev.colorcode.core.ColorCode
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32

/** The last successfully created code, kept in private storage across fresh launches. */
internal class LastCodeStore(private val file: File) {
    data class Code(val bytes: ByteArray, val grid: Int)

    fun load(): Code? = try {
        DataInputStream(file.inputStream()).use { input ->
            require(input.readInt() == MAGIC)
            val grid = input.readInt()
            val length = input.readInt()
            require(length in 0..ColorCode.capacity(grid))
            val bytes = ByteArray(length).also { input.readFully(it) }
            require(input.readLong() == CRC32().apply { update(bytes) }.value)
            require(input.read() == -1)
            Code(bytes, grid)
        }
    } catch (_: IOException) { null }
      catch (_: IllegalArgumentException) { null }

    fun save(bytes: ByteArray, grid: Int) {
        require(bytes.size <= ColorCode.capacity(grid))
        val temporary = File.createTempFile("last-code-", ".tmp", file.parentFile)
        try {
            FileOutputStream(temporary).use { stream ->
                val output = DataOutputStream(stream)
                output.writeInt(MAGIC)
                output.writeInt(grid)
                output.writeInt(bytes.size)
                output.write(bytes)
                output.writeLong(CRC32().apply { update(bytes) }.value)
                output.flush()
                stream.fd.sync()
            }
            // Same-directory atomic replacement keeps the previous code if writing fails.
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    private companion object { const val MAGIC = 0x43434C31 }
}
