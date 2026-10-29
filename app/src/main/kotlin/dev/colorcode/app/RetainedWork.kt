package dev.colorcode.app

import java.util.ArrayDeque

/**
 * Retained across configuration changes. Tasks must use immutable input/application context,
 * never an Activity or its views. Receiver callbacks use the currently resumed owner.
 * All methods except the supplied task run on the owner/dispatch thread.
 */
internal class RetainedWork<O : Any>(
    private val execute: (() -> Unit) -> Unit,
    private val dispatch: (() -> Unit) -> Unit,
    private val changed: O.(String?) -> Unit,
    private val stop: () -> Unit = {}
) {
    private interface Delivery<O> { fun deliver(owner: O); fun discard() }
    private var owner: O? = null
    @Volatile private var closed = false
    private val messages = ArrayDeque<String>()
    private val ready = ArrayDeque<Delivery<O>>()

    fun attach(value: O) {
        check(!closed)
        owner = value
        while (ready.isNotEmpty()) finish(ready.removeFirst())
        changed(value, messages.peekFirst())
    }

    fun detach(value: O) { if (owner === value) owner = null }

    fun <T> submit(message: String, task: () -> T, done: O.(T) -> Unit,
                   failed: O.(Exception) -> Unit, discard: (T) -> Unit = {}) {
        check(!closed)
        messages.addLast(message)
        owner?.let { changed(it, messages.peekFirst()) }
        execute {
            if (!closed) {
                val delivery = try {
                    val value = task()
                    object : Delivery<O> {
                        override fun deliver(owner: O) = done(owner, value)
                        override fun discard() = discard(value)
                    }
                } catch (e: Exception) {
                    object : Delivery<O> {
                        override fun deliver(owner: O) = failed(owner, e)
                        override fun discard() {}
                    }
                }
                dispatch {
                    if (closed) delivery.discard()
                    else if (owner == null) ready.addLast(delivery)
                    else finish(delivery)
                }
            }
        }
    }

    private fun finish(delivery: Delivery<O>) {
        messages.pollFirst()
        val target = owner
        if (target != null) {
            delivery.deliver(target)
            changed(target, messages.peekFirst())
        } else delivery.discard()
    }

    fun close() {
        closed = true; owner = null
        while (ready.isNotEmpty()) ready.removeFirst().discard()
        messages.clear()
        stop()
    }
}
