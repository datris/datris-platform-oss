package ai.datris.audit

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Bounded FIFO between request threads and the single audit writer thread.
  *
  * On overflow the OLDEST pending entry is dropped and `offer` returns true so
  * the caller can count it. Losing an audit entry is preferable to blocking a
  * user request; the drop counter makes the loss visible. */
class AuditQueue(val capacity: Int) {
    require(capacity > 0, "AuditQueue capacity must be positive")

    private val items = new java.util.ArrayDeque[AuditEntry](capacity)
    private val lock = new ReentrantLock()
    private val notEmpty = lock.newCondition()

    /** Enqueue; returns true if an older entry had to be evicted to make room. */
    def offer(entry: AuditEntry): Boolean = {
        lock.lock()
        try {
            var dropped = false
            if (items.size() >= capacity) {
                items.pollFirst()
                dropped = true
            }
            items.addLast(entry)
            notEmpty.signal()
            dropped
        } finally lock.unlock()
    }

    /** Block up to `timeoutMs` for the next entry; None on timeout. */
    def poll(timeoutMs: Long): Option[AuditEntry] = {
        lock.lock()
        try {
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (items.isEmpty && remaining > 0)
                remaining = notEmpty.awaitNanos(remaining)
            Option(items.pollFirst())
        } finally lock.unlock()
    }

    /** Drain everything currently queued (used by the shutdown flush). */
    def drain(): List[AuditEntry] = {
        lock.lock()
        try {
            val out = List.newBuilder[AuditEntry]
            while (!items.isEmpty) out += items.pollFirst()
            out.result()
        } finally lock.unlock()
    }

    def size: Int = {
        lock.lock()
        try items.size()
        finally lock.unlock()
    }
}
