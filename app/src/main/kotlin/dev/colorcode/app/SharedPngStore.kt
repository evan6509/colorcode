package dev.colorcode.app

import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Immutable, private PNG snapshots. URIs expire after seven days, even if a grant survives. */
internal class SharedPngStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nextToken: () -> String = { UUID.randomUUID().toString().replace("-", "") }
) {
    fun create(write: (OutputStream) -> Unit): String {
        val directory = directory()
        repeat(32) {
            val name = "${nextToken()}.png"
            require(NAME.matches(name))
            val file = File(directory, name)
            val stream = try {
                Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            } catch (_: FileAlreadyExistsException) { return@repeat }
            try {
                stream.use(write)
                check(file.setLastModified(clock())) { "Cannot set share expiry" }
                check(file.setReadOnly()) { "Cannot protect shared PNG" }
                return uri(name)
            } catch (e: Exception) { file.delete(); throw e }
        }
        throw java.io.IOException("Cannot allocate a new shared PNG")
    }

    fun resolve(uri: String): File {
        // Validate the entire raw URI. Encoded separators, dot segments, query/fragment,
        // user info, ports, alternative authorities/schemes and extra segments are rejected.
        if (!URI.matches(uri)) throw FileNotFoundException("Invalid share URI")
        val file = contained(uri.substringAfterLast('/')) ?: throw FileNotFoundException()
        if (expired(file)) throw FileNotFoundException("Shared PNG expired; create a new share")
        return file
    }

    fun cleanup(revoke: (String) -> Unit) {
        if (!root.exists()) return
        directory().listFiles()?.forEach { candidate ->
            val file = contained(candidate.name) ?: return@forEach
            if (expired(file)) {
                // Never remove a live share or reuse/truncate an existing file. A recipient
                // already holding an open descriptor continues to see the original inode.
                revoke(uri(file.name))
                file.delete()
            }
        }
    }

    private fun directory(): File {
        if (Files.isSymbolicLink(root.toPath())) throw FileNotFoundException("Invalid share directory")
        if (!root.isDirectory && !root.mkdirs()) throw FileNotFoundException("Cannot open share directory")
        return root.canonicalFile
    }

    private fun contained(name: String): File? {
        if (!NAME.matches(name)) return null
        val directory = directory()
        val file = File(directory, name)
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.canonicalFile.parentFile != directory) return null
        return file
    }

    private fun expired(file: File): Boolean {
        val now = clock(); val created = file.lastModified()
        return created <= 0 || created > now || now - created >= LIFETIME_MS
    }

    private fun uri(name: String) = "content://$AUTHORITY/$name"

    companion object {
        const val AUTHORITY = "dev.colorcode.app.share"
        const val LIFETIME_MS = 7L * 24 * 60 * 60 * 1000
        private val NAME = Regex("[a-f0-9]{32}\\.png")
        private val URI = Regex("content://dev\\.colorcode\\.app\\.share/[a-f0-9]{32}\\.png")
    }
}
