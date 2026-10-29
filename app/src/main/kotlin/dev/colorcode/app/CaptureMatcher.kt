package dev.colorcode.app

/** Pairs request callbacks and JPEGs independently of their callback order. Camera-thread confined. */
internal class CaptureMatcher<T> {
    data class Match<T>(val request: Long, val value: T)
    private var sequence = 0L
    private var active: Long? = null
    private var timestamp: Long? = null
    // Copy bytes out of ImageReader immediately; never hold an Image open while waiting.
    // Bounded buffering handles JPEG-before-metadata without unbounded camera memory.
    private val early = linkedMapOf<Long, T>()
    val pendingRequest: Long? get() = active

    fun begin(): Long {
        check(active == null)
        timestamp = null; early.clear()
        return (++sequence).also { active = it }
    }
    fun isPending(request: Long) = active == request
    fun cancel(request: Long): Boolean {
        if (!isPending(request)) return false
        active = null; timestamp = null; early.clear()
        return true
    }
    fun started(request: Long, timestamp: Long): Match<T>? {
        if (!isPending(request) || timestamp <= 0) return null
        this.timestamp = timestamp
        val value = early.remove(timestamp) ?: return null
        return accept(request, value)
    }
    fun frame(timestamp: Long, value: T): Match<T>? {
        val request = active ?: return null
        if (timestamp <= 0) return null
        if (this.timestamp == timestamp) return accept(request, value)
        if (this.timestamp == null) {
            early[timestamp] = value
            while (early.size > 2) early.remove(early.keys.first())
        }
        return null
    }

    private fun accept(request: Long, value: T): Match<T> {
        cancel(request)
        return Match(request, value)
    }
}
