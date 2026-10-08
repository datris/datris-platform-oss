package ai.datris.audit

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Bounded FIFO between request threads and the single audit writer thread.
  *
  * Default (best effort): on overflow `offer` drops the OLDEST pending entry
  * and returns true so the caller can count it; a request is never blocked and
  * the drop counter makes the loss visible.
  *
  * Strict (AUDIT_LOG_STRICT): `offerBlocking` never evicts; it waits for room
  * (signalled by `poll` / `drain`) up to a timeout. `hasRoom` lets the request
  * gate refuse new audited requests before the queue is actually full. */
class AuditQueue(val capacity: Int) {
    require(capacity > 0, "AuditQueue capacity must be positive")

    private val items = new java.util.ArrayDeque[AuditEntry](capacity)
    private val lock = new ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val notFull = lock.newCondition()

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

    /** Enqueue without ever evicting: waits for room up to `timeoutMs`. True
      * when enqueued, false on timeout (the queue is unchanged). */
    def offerBlocking(entry: AuditEntry, timeoutMs: Long): Boolean = {
        lock.lockInterruptibly()
        try {
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (items.size() >= capacity && remaining > 0)
                remaining = notFull.awaitNanos(remaining)
            if (items.size() >= capacity) false
            else {
                items.addLast(entry)
                notEmpty.signal()
                true
            }
        } finally lock.unlock()
    }

    /** True while the queue holds fewer than `capacity - headroom` entries. */
    def hasRoom(headroom: Int): Boolean = {
        lock.lock()
        try items.size() < capacity - headroom
        finally lock.unlock()
    }

    /** Block up to `timeoutMs` for the next entry; None on timeout. */
    def poll(timeoutMs: Long): Option[AuditEntry] = {
        lock.lock()
        try {
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (items.isEmpty && remaining > 0)
                remaining = notEmpty.awaitNanos(remaining)
            val head = Option(items.pollFirst())
            if (head.isDefined) notFull.signal()
            head
        } finally lock.unlock()
    }

    /** Drain everything currently queued (used by the shutdown flush). */
    def drain(): List[AuditEntry] = {
        lock.lock()
        try {
            val out = List.newBuilder[AuditEntry]
            while (!items.isEmpty) out += items.pollFirst()
            notFull.signalAll()
            out.result()
        } finally lock.unlock()
    }

    def size: Int = {
        lock.lock()
        try items.size()
        finally lock.unlock()
    }
}
