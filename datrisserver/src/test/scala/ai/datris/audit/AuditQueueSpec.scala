package ai.datris.audit

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant

class AuditQueueSpec extends AnyFunSuite {

    private def entry(n: Int) = AuditEntry(
        ts = Instant.ofEpochMilli(n.toLong),
        actor = AuditActorInfo.System,
        category = "test",
        action = "n" + n,
        resourceType = None,
        resourceName = None,
        outcome = "success"
    )

    test("offer reports no drop while under capacity") {
        val q = new AuditQueue(3)
        assert(!q.offer(entry(1)))
        assert(!q.offer(entry(2)))
        assert(!q.offer(entry(3)))
        assert(q.size == 3)
    }

    test("overflow drops the OLDEST entry and reports it") {
        val q = new AuditQueue(2)
        q.offer(entry(1)); q.offer(entry(2))
        assert(q.offer(entry(3)))
        assert(q.size == 2)
        assert(q.poll(10).map(_.action).contains("n2"))
        assert(q.poll(10).map(_.action).contains("n3"))
        assert(q.poll(10).isEmpty)
    }

    test("poll is FIFO and times out empty") {
        val q = new AuditQueue(10)
        q.offer(entry(1)); q.offer(entry(2))
        assert(q.poll(10).map(_.action).contains("n1"))
        assert(q.poll(10).map(_.action).contains("n2"))
        val t0 = System.nanoTime()
        assert(q.poll(30).isEmpty)
        assert((System.nanoTime() - t0) / 1000000L >= 25)
    }

    test("drain empties the queue in order") {
        val q = new AuditQueue(10)
        (1 to 5).foreach(i => q.offer(entry(i)))
        assert(q.drain().map(_.action) == List("n1", "n2", "n3", "n4", "n5"))
        assert(q.size == 0)
    }

    test("submit is a no-op when the environment is not initialized / audit is off") {
        // DatrisEnvironment.values is null in unit tests → enabled must be false
        // and submit must return false without touching any store.
        assert(!AuditLog.enabled)
        assert(!AuditLog.submit(entry(1)))
        assert(AuditLog.queueDepth == 0)
        AuditLog.system("system", "start") // must not throw
    }

    test("collapse key folds actor + category + action + resource") {
        val e = entry(1).copy(category = "document", action = "upload", resourceName = Some("docs"))
        assert(e.collapseKey == "system|document|upload|docs")
        assert(e.copy(resourceName = None).collapseKey == "system|document|upload|")
    }

    test("toDocument carries a BSON Date for the TTL index and the ISO ts") {
        val e = entry(1000).copy(resourceType = Some("tap"), resourceName = Some("x"), httpStatus = Some(200))
        val d = e.toDocument
        assert(d.get("tsDate").isInstanceOf[java.util.Date])
        assert(d.getString("ts") == "1970-01-01T00:00:01Z")
        assert(d.get("resource", classOf[org.bson.Document]).getString("name") == "x")
        assert(d.getInteger("httpStatus") == 200)
    }

    // --- Strict evidence mode (plans/stories/strict-evidence-mode.md) ---------
    //
    // Pinned seam (does not exist on main at 3de8be3; the implementation adds it):
    // {{{
    // // ai.datris.audit.AuditQueue
    // /** Enqueue without ever evicting: waits on a `notFull` condition (signalled
    //   * by poll / drain) up to timeoutMs; true when enqueued, false on timeout. */
    // def offerBlocking(entry: AuditEntry, timeoutMs: Long): Boolean
    // /** true while size < capacity - headroom (AuditLog.acceptingWrites uses
    //   * headroom 1000: accepting below depth 9000, refusing at 9000). */
    // def hasRoom(headroom: Int): Boolean
    // }}}
    // `offer` is unchanged (the tests above).

    test("offerBlocking never evicts") {
        val q = new AuditQueue(2)
        assert(q.offerBlocking(entry(1), 10))
        assert(q.offerBlocking(entry(2), 10))
        assert(!q.offerBlocking(entry(3), 10), "a full queue refuses instead of evicting")
        assert(q.size == 2)
        assert(q.drain().map(_.action) == List("n1", "n2"), "the oldest entries are still there")
    }

    test("offerBlocking waits for room and succeeds after a poll") {
        val q = new AuditQueue(1)
        assert(q.offerBlocking(entry(1), 10))
        val result = new java.util.concurrent.atomic.AtomicReference[Option[Boolean]](None)
        val started = new java.util.concurrent.CountDownLatch(1)
        val t = new Thread(() => {
            started.countDown()
            result.set(Some(q.offerBlocking(entry(2), 5000)))
        })
        t.start()
        started.await()
        Thread.sleep(100)
        assert(result.get.isEmpty, "the producer is waiting, not returned")
        assert(q.size == 1)
        assert(q.poll(10).map(_.action).contains("n1"))
        t.join(5000)
        assert(result.get.contains(true), "woken by poll and enqueued")
        assert(q.poll(10).map(_.action).contains("n2"))
    }

    test("offerBlocking returns false on timeout") {
        val q = new AuditQueue(1)
        q.offer(entry(1))
        val t0 = System.nanoTime()
        assert(!q.offerBlocking(entry(2), 50))
        val waitedMs = (System.nanoTime() - t0) / 1000000L
        assert(waitedMs >= 45, "it waited for the timeout: " + waitedMs + "ms")
        assert(q.size == 1)
        assert(q.poll(10).map(_.action).contains("n1"), "nothing was evicted")
    }
}
