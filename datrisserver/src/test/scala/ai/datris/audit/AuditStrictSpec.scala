package ai.datris.audit

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, MongoDBConfig, TenantContext}
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.scalatest.concurrent.Eventually
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: strict evidence mode (plans/stories/strict-evidence-mode.md).
  *
  * The real AuditLog queue and writer thread, with the Mongo write replaced by
  * a fake store that can be taken down, fail N times, or block. No Spring, no
  * Mongo.
  *
  * Pinned seam (does not exist on main at 3de8be3; the implementation adds it):
  * {{{
  * // ai.datris.model.DatrisEnvironment — from `${auditLog.strict:false}` (AUDIT_LOG_STRICT)
  * auditLogStrict: Boolean = false
  *
  * // ai.datris.audit.AuditLog
  * def strict: Boolean              // enabled && DatrisEnvironment.values.auditLogStrict
  * def unrecordedCount: Long        // datris_audit_unrecorded_total since startup (status field `unrecorded`)
  * def acceptingWrites: Boolean     // under strict: queueDepth < QueueCapacity (10000) - 1000
  *
  * // Test seam for the persist step of `write`: replaces collectionFor +
  * // insertOne / writeCollapsed (null = Mongo). A throw is a failed persist:
  * // default mode logs and moves on, strict retries the same entry.
  * @volatile private[datris] var persistOverride: AuditEntry => Unit = null
  *
  * // Fixed delay between strict retries for tests; < 0 = the real backoff.
  * @volatile private[datris] var retryDelayMsOverride: Long = -1L
  * }}}
  * `sinkOverride` stays null here so entries go through the bounded queue.
  */
class AuditStrictSpec extends AnyFunSuite with BeforeAndAfterEach with BeforeAndAfterAll with Eventually {

    implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(30, Seconds), interval = Span(20, Millis))

    private val QueueCapacity = 10000
    private val HighWater = QueueCapacity - 1000

    /** Stand-in for the config store. */
    private class FakeStore {
        @volatile var down = false
        @volatile var failNext = 0
        @volatile var gate: CountDownLatch = null
        val attempts = new AtomicInteger(0)
        val persisted: mutable.ArrayBuffer[String] = mutable.ArrayBuffer[String]()

        def persist(e: AuditEntry): Unit = {
            attempts.incrementAndGet()
            val g = gate
            if (g != null) g.await()
            if (down) throw new RuntimeException("config store unavailable")
            synchronized {
                if (failNext > 0) { failNext -= 1; throw new RuntimeException("transient write failure") }
            }
            persisted.synchronized { persisted += e.action; () }
        }
        def actions: List[String] = persisted.synchronized(persisted.toList)
    }

    private def env(strict: Boolean): DatrisEnvironment = DatrisEnvironment(
        initialized = true,
        environment = "test",
        fileNotifierQueue = null,
        ttlFileNotifierQueueMessages = 0,
        pipelineTopic = null,
        pipelineTableName = null,
        archivedMetadataTableName = null,
        pipelineStatusTableName = null,
        fileNotifierMessageTableName = null,
        dataPullTableName = null,
        useApiKeys = false,
        apiKeysSecretName = null,
        postgresSecretName = null,
        mongoDbSecretName = null,
        kafkaProducerSecretName = null,
        kafkaConsumerConfig = null,
        mongoDbConfig = MongoDBConfig("mongodb://unused", "datris", "datris"),
        minIOConfig = null,
        activeMQConfig = null,
        aiConfig = null,
        aiEnabled = false,
        embeddingSecretName = null,
        qdrantSecretName = null,
        weaviateSecretName = null,
        milvusSecretName = null,
        chromaSecretName = null,
        pgvectorSecretName = null,
        multiTenant = false,
        useAuditLog = true,
        auditLogTableName = "test-audit-log",
        auditLogEmitLogLine = false,
        auditLogStrict = strict
    )

    private def entry(action: String) = AuditEntry(
        ts = Instant.now(),
        actor = AuditActorInfo.System,
        category = "test",
        action = action,
        resourceType = None,
        resourceName = None,
        outcome = "success",
        tableName = "test-audit-log"
    )

    private val registry = new SimpleMeterRegistry()
    private def counter(name: String): Double = Option(registry.find(name).counter()).map(_.count()).getOrElse(0.0)

    private var savedValues: DatrisEnvironment = _
    private var store: FakeStore = _
    private var lastSubmitted: String = _

    override def beforeAll(): Unit = Metrics.addRegistry(registry)
    override def afterAll(): Unit = Metrics.removeRegistry(registry)

    private def install(strict: Boolean): Unit = {
        val e = env(strict)
        DatrisEnvironment.values = e
        TenantContext.set(e)
    }

    private def submit(action: String): Boolean = {
        lastSubmitted = action
        AuditLog.submit(entry(action))
    }

    override def beforeEach(): Unit = {
        savedValues = DatrisEnvironment.values
        store = new FakeStore
        lastSubmitted = null
        AuditLog.sinkOverride = null
        AuditLog.persistOverride = (e: AuditEntry) => store.persist(e)
        AuditLog.retryDelayMsOverride = 5L
    }

    override def afterEach(): Unit = {
        // Let the writer finish whatever the test left behind before restoring.
        store.down = false
        store.failNext = 0
        Option(store.gate).foreach(_.countDown())
        try {
            // Queue empty and the writer idle (no new persist attempts), whatever
            // the test left behind; a strict writer finishes its retry first.
            if (lastSubmitted != null) {
                eventually(assert(AuditLog.queueDepth == 0))
                eventually {
                    val before = store.attempts.get
                    Thread.sleep(50)
                    assert(store.attempts.get == before)
                }
            }
        } finally {
            AuditLog.persistOverride = null
            AuditLog.retryDelayMsOverride = -1L
            TenantContext.clear()
            DatrisEnvironment.values = savedValues
        }
    }

    /** Submit one entry and wait until the writer has taken it off the queue
      * and is inside persist (held by the gate or retrying a down store). */
    private def occupyWriter(action: String): Unit = {
        assert(submit(action))
        eventually(assert(store.attempts.get >= 1 && AuditLog.queueDepth == 0))
    }

    test("default mode drops the oldest and counts it") {
        install(strict = false)
        assert(!AuditLog.strict)
        store.gate = new CountDownLatch(1)
        occupyWriter("d-held")
        val droppedBefore = AuditLog.droppedCount
        val metricBefore = counter("datris_audit_dropped_total")
        (1 to QueueCapacity).foreach(i => assert(submit("d" + i)))
        assert(AuditLog.queueDepth == QueueCapacity)
        assert(AuditLog.droppedCount == droppedBefore, "nothing dropped while the queue has room")
        assert(submit("d-overflow"), "default mode never refuses an entry")
        assert(AuditLog.droppedCount == droppedBefore + 1)
        assert(counter("datris_audit_dropped_total") == metricBefore + 1)
        store.gate.countDown()
        eventually(assert(store.actions.contains("d-overflow")))
        val got = store.actions
        assert(!got.contains("d1"), "the OLDEST queued entry was evicted")
        assert(got.contains("d2") && got.head == "d-held", got.take(3))
    }

    test("strict mode drops nothing while the store is down and persists every entry in order once it recovers") {
        install(strict = true)
        assert(AuditLog.strict)
        store.down = true
        val droppedBefore = AuditLog.droppedCount
        val unrecordedBefore = AuditLog.unrecordedCount
        val metricDropped = counter("datris_audit_dropped_total")
        val expected = (0 until 200).map("s" + _).toList
        expected.foreach(a => assert(submit(a), a + " accepted"))
        eventually(assert(store.attempts.get >= 3, "the writer keeps retrying the head entry"))
        assert(store.actions.isEmpty, "nothing persisted while the store is down")
        assert(AuditLog.droppedCount == droppedBefore)
        assert(AuditLog.unrecordedCount == unrecordedBefore)
        assert(counter("datris_audit_dropped_total") == metricDropped, "datris_audit_dropped_total stays put under strict")
        store.down = false
        eventually(assert(store.actions.size == expected.size && AuditLog.queueDepth == 0))
        assert(store.actions == expected, "every entry, once, in submission order")
        assert(AuditLog.droppedCount == droppedBefore)
    }

    test("strict writer retries a failed persist instead of skipping it") {
        install(strict = true)
        store.failNext = 3
        assert(submit("r-1"))
        eventually(assert(store.actions == List("r-1")))
        assert(store.attempts.get == 4, "three failures then one success on the SAME entry: " + store.attempts.get)
        assert(submit("r-2"))
        eventually(assert(store.actions == List("r-1", "r-2")))

        // Contrast: default mode logs the failure and moves on.
        install(strict = false)
        store.failNext = 1
        val attemptsBefore = store.attempts.get
        assert(submit("r-skipped"))
        eventually(assert(store.attempts.get == attemptsBefore + 1))
        assert(submit("r-3"))
        eventually(assert(store.actions.contains("r-3")))
        assert(!store.actions.contains("r-skipped"), "default mode does not retry")
    }

    test("acceptingWrites turns false at the high-water mark and true again after draining") {
        install(strict = true)
        store.down = true
        assert(AuditLog.acceptingWrites, "empty queue under strict accepts")
        occupyWriter("h-held")
        (1 until HighWater).foreach(i => assert(submit("h" + i)))
        assert(AuditLog.queueDepth == HighWater - 1)
        assert(AuditLog.acceptingWrites, "one below the high-water mark still accepts")
        assert(submit("h-last"))
        assert(AuditLog.queueDepth == HighWater)
        assert(!AuditLog.acceptingWrites, "at capacity minus headroom the gate closes")
        store.down = false
        eventually(assert(AuditLog.queueDepth == 0 && store.actions.contains("h-last")))
        assert(AuditLog.acceptingWrites, "drained: the gate reopens")
        assert(store.actions.size == HighWater + 1, "every entry persisted")
    }

    test("strict producer that times out waiting for room counts the entry as unrecorded and logs it at ERROR") {
        install(strict = true)
        store.down = true
        occupyWriter("t-held")
        (1 to QueueCapacity).foreach(i => assert(submit("t" + i)))
        assert(AuditLog.queueDepth == QueueCapacity)
        val unrecordedBefore = AuditLog.unrecordedCount
        val metricBefore = counter("datris_audit_unrecorded_total")
        val droppedBefore = AuditLog.droppedCount
        val auditLogger = org.slf4j.LoggerFactory.getLogger("ai.datris.audit").asInstanceOf[ch.qos.logback.classic.Logger]
        val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]()
        appender.start()
        auditLogger.addAppender(appender)
        AuditLog.strictOfferTimeoutMsOverride = 20L
        try {
            assert(!submit("t-over"), "the entry could not be queued")
            assert(AuditLog.queueDepth == QueueCapacity, "nothing was evicted")
            assert(AuditLog.unrecordedCount == unrecordedBefore + 1)
            assert(counter("datris_audit_unrecorded_total") == metricBefore + 1)
            assert(AuditLog.droppedCount == droppedBefore)
            val errors = appender.list.asScala.filter(_.getLevel == ch.qos.logback.classic.Level.ERROR).map(_.getFormattedMessage)
            assert(errors.exists(_.contains("t-over")), "the whole entry is on the audit stream: " + errors)
        } finally {
            AuditLog.strictOfferTimeoutMsOverride = -1L
            auditLogger.detachAppender(appender)
        }
    }
}
