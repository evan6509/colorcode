package dev.colorcode.app

import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Each capture is a separate private retry input; no later camera attempt can truncate it. */
internal object CameraFiles {
    const val RESULT_NAME = "captureName"
    private val NAME = Regex("[a-f0-9]{32}\\.jpg")

    fun create(filesDir: File, bytes: ByteArray): File {
        val root = directory(filesDir)
        val file = File(root, "${UUID.randomUUID().toString().replace("-", "")}.jpg")
        // CREATE_NEW fails safely on the negligible random collision.
        val stream = Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        try {
            stream.use { it.write(bytes) }
            check(file.setReadOnly()) { "Cannot protect camera input" }
            return file
        } catch (e: Exception) { file.delete(); throw e }
    }

    fun resolve(filesDir: File, name: String): File {
        if (!NAME.matches(name)) throw FileNotFoundException("Invalid camera input")
        val root = directory(filesDir)
        val file = File(root, name)
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.canonicalFile.parentFile != root)
            throw FileNotFoundException("Camera input is unavailable; take another photo")
        return file
    }

    fun delete(filesDir: File, name: String) {
        try { resolve(filesDir, name).delete() } catch (_: FileNotFoundException) {}
    }

    private fun directory(filesDir: File): File {
        val root = File(filesDir, "captures")
        if (Files.isSymbolicLink(root.toPath()) || (!root.isDirectory && !root.mkdirs()))
            throw FileNotFoundException("Invalid camera directory")
        return root.canonicalFile
    }
}
