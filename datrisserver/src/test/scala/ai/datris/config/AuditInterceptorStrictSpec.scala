package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditActorInfo, AuditEntry, AuditLog}
import ai.datris.model.{DatrisEnvironment, MongoDBConfig, TenantContext}
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.mockito.ArgumentMatchers.{anyInt, anyString, eq => eqTo}
import org.mockito.Mockito.{atLeastOnce, mock, never, verify, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.time.{Millis, Seconds, Span}

import java.io.{PrintWriter, StringWriter}
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** Story: strict evidence mode (plans/stories/strict-evidence-mode.md).
  *
  * The interceptor gate, called the way Spring calls it: preHandle with a
  * mocked request/response. "Not accepting" is produced for real: the
  * AuditLog writer is held on a store that is down (strict) or blocked
  * (default) while the queue fills to the high-water mark (capacity 10000
  * minus headroom 1000).
  *
  * Pinned seam (does not exist on main at 3de8be3; the implementation adds it):
  * {{{
  * // ai.datris.model.DatrisEnvironment — from `${auditLog.strict:false}` (AUDIT_LOG_STRICT)
  * auditLogStrict: Boolean = false
  *
  * // ai.datris.audit.AuditLog
  * def strict: Boolean
  * def acceptingWrites: Boolean   // under strict: queueDepth < 10000 - 1000
  * @volatile private[datris] var persistOverride: AuditEntry => Unit = null
  * @volatile private[datris] var retryDelayMsOverride: Long = -1L
  *
  * // ai.datris.config.AuditInterceptor.preHandle — under strict, when
  * // AuditClassifier.classify(method, uri, auditLogLogReads) is defined and
  * // !AuditLog.acceptingWrites: response.setStatus(503), a Retry-After header,
  * // a JSON body written through response.getWriter (as CapabilityInterceptor
  * // does) whose message names AUDIT_LOG_STRICT, and `false` is returned.
  * }}}
  */
class AuditInterceptorStrictSpec extends AnyFunSuite with BeforeAndAfterEach with Eventually {

    implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(30, Seconds), interval = Span(20, Millis))

    private val HighWater = 10000 - 1000

    @volatile private var down = false
    @volatile private var gate: CountDownLatch = null
    private val attempts = new AtomicInteger(0)
    private var lastSubmitted: String = null
    private var savedValues: DatrisEnvironment = _

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
        auditLogLogReads = false,
        auditLogStrict = strict
    )

    private def install(strict: Boolean): Unit = {
        val e = env(strict)
        DatrisEnvironment.values = e
        TenantContext.set(e)
    }

    private def submit(action: String): Unit = {
        lastSubmitted = action
        assert(
            AuditLog.submit(AuditEntry(Instant.now(), AuditActorInfo.System, "test", action, None, None, "success", tableName = "test-audit-log"))
        )
    }

    /** Hold the writer on one entry, then queue `HighWater` more. */
    private def fillToHighWater(prefix: String): Unit = {
        submit(prefix + "-held")
        eventually(assert(attempts.get >= 1 && AuditLog.queueDepth == 0))
        (1 to HighWater).foreach(i => submit(prefix + i))
        assert(AuditLog.queueDepth == HighWater)
    }

    override def beforeEach(): Unit = {
        savedValues = DatrisEnvironment.values
        down = false
        gate = null
        attempts.set(0)
        lastSubmitted = null
        AuditLog.sinkOverride = null
        AuditLog.retryDelayMsOverride = 5L
        AuditLog.persistOverride = (e: AuditEntry) => {
            attempts.incrementAndGet()
            val g = gate
            if (g != null) g.await()
            if (down) throw new RuntimeException("config store unavailable")
        }
    }

    override def afterEach(): Unit = {
        down = false
        Option(gate).foreach(_.countDown())
        try {
            // Queue empty and the writer idle, whatever the test left behind.
            if (lastSubmitted != null) {
                eventually(assert(AuditLog.queueDepth == 0))
                eventually {
                    val before = attempts.get
                    Thread.sleep(50)
                    assert(attempts.get == before)
                }
            }
        } finally {
            AuditLog.persistOverride = null
            AuditLog.retryDelayMsOverride = -1L
            TenantContext.clear()
            DatrisEnvironment.values = savedValues
        }
    }

    private def request(method: String, uri: String): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn(method)
        when(r.getRequestURI).thenReturn(uri)
        r
    }

    private def response(): (HttpServletResponse, StringWriter) = {
        val body = new StringWriter()
        val r = mock(classOf[HttpServletResponse])
        when(r.getWriter).thenReturn(new PrintWriter(body, true))
        (r, body)
    }

    private val interceptor = new AuditInterceptor

    test("an audited request is refused 503 before the handler when strict and not accepting") {
        install(strict = true)
        down = true
        fillToHighWater("a")
        assert(AuditLog.strict && !AuditLog.acceptingWrites)

        val (resp, body) = response()
        val proceed = interceptor.preHandle(request("POST", "/api/v1/pipeline"), resp, new Object)
        assert(!proceed, "preHandle returns false so the handler never runs")
        verify(resp).setStatus(503)
        verify(resp).setHeader(eqTo("Retry-After"), anyString())
        assert(body.toString.contains("AUDIT_LOG_STRICT"), "the message names the switch: " + body)
        assert(com.google.gson.JsonParser.parseString(body.toString).isJsonObject, "JSON body: " + body)

        // Recovered: the same request goes through.
        down = false
        eventually(assert(AuditLog.queueDepth == 0 && AuditLog.acceptingWrites))
        val (resp2, _) = response()
        assert(interceptor.preHandle(request("POST", "/api/v1/pipeline"), resp2, new Object))
        verify(resp2, never()).setStatus(anyInt())
    }

    test("an unaudited read passes") {
        install(strict = true)
        down = true
        fillToHighWater("u")
        assert(!AuditLog.acceptingWrites)
        val (resp, body) = response()
        assert(interceptor.preHandle(request("GET", "/api/v1/pipelines"), resp, new Object), "reads are not audited (AUDIT_LOG_READS off)")
        verify(resp, never()).setStatus(anyInt())
        assert(body.toString.isEmpty)
        // The always-audited read (secrets) is gated like a write.
        val (resp2, _) = response()
        assert(!interceptor.preHandle(request("GET", "/api/v1/secrets"), resp2, new Object))
        verify(resp2, atLeastOnce()).setStatus(503)
    }

    test("default mode never refuses") {
        install(strict = false)
        assert(!AuditLog.strict)
        gate = new CountDownLatch(1)
        fillToHighWater("n")
        submit("n-more")
        Seq("POST" -> "/api/v1/pipeline", "DELETE" -> "/api/v1/pipeline", "GET" -> "/api/v1/secrets", "GET" -> "/api/v1/pipelines").foreach {
            case (m, u) =>
                val (resp, body) = response()
                assert(interceptor.preHandle(request(m, u), resp, new Object), m + " " + u + " refused in default mode")
                verify(resp, never()).setStatus(anyInt())
                assert(body.toString.isEmpty)
        }
        gate.countDown()
    }
}
