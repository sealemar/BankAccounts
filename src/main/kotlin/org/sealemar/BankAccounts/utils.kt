package org.sealemar.BankAccounts

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ConcurrentLatch can be opened only once. It should be closed before it can open
 * the next time.
 * Thread-safe
 *
 * Example:
 * val l = ConcurrentLatch()
 *
 * l.open()?.use {
 *     // do thread-safe work here
 * }
 */
class ConcurrentLatch : Closeable {
    private val latch = AtomicBoolean(false)

    /**
     * @return this if latch has been successfully opened or null otherwise
     */
    fun open() = latch.compareAndSet(false, true).takeIf { it }?.let { this }

    override fun close() {
        latch.set(false)
    }
}

/**
 * Repeats 'action' up to 'times' times.
 * @param times if 0 - repeats forever until the condition is met
 * @param action action to repeat
 * @param predicate condition upon which to repeat. If condition is not met, the cycle breaks
 * @return the result of the successful 'action' or null if cycles exhausted
 */
fun <T : Any> repeatIf(times : Int = 0, action : () -> T, predicate : (T) -> Boolean) : T? {
    if(times == 0) {
        while(true) {
            action().takeUnless { predicate(it) }?.let { return it }
        }
    } else {
        repeat(times) {
            action().takeUnless { predicate(it) }?.let { return it }
        }
    }
    return null
}
