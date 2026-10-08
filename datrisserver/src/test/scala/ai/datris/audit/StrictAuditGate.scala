package ai.datris.audit

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, MongoDBConfig, TenantContext}

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Test helper (strict evidence mode): closes the AUDIT_LOG_STRICT gate for
  * real, the way AuditStrictSpec does it: the writer is held retrying a store
  * that is down while the queue fills to the high-water mark (10000 - 1000).
  * `reopen()` brings the store back, waits for the queue to drain and restores
  * the environment and the AuditLog seams. */
class StrictAuditGate {
    private val HighWater = 10000 - 1000
    @volatile private var down = false
    private val attempts = new AtomicInteger(0)
    private var saved: DatrisEnvironment = _

    def env(strict: Boolean): DatrisEnvironment = DatrisEnvironment(
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

    private def await(what: String)(cond: => Boolean): Unit = {
        val deadline = System.currentTimeMillis() + 30000L
        while (!cond) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("timed out waiting for " + what)
            Thread.sleep(10)
        }
    }

    private def submit(action: String): Unit =
        assert(AuditLog.submit(AuditEntry(Instant.now(), AuditActorInfo.System, "test", action, None, None, "success", tableName = "test-audit-log")))

    /** Strict on, store down, queue at the high-water mark: acceptingWrites false. */
    def close(): Unit = {
        saved = DatrisEnvironment.values
        val e = env(strict = true)
        DatrisEnvironment.values = e
        TenantContext.set(e)
        down = true
        attempts.set(0)
        AuditLog.sinkOverride = null
        AuditLog.retryDelayMsOverride = 5L
        AuditLog.persistOverride = (_: AuditEntry) => {
            attempts.incrementAndGet()
            if (down) throw new RuntimeException("config store unavailable")
        }
        submit("gate-held")
        await("the writer to hold an entry")(attempts.get >= 1 && AuditLog.queueDepth == 0)
        (1 to HighWater).foreach(i => submit("gate" + i))
        assert(!AuditLog.acceptingWrites)
    }

    /** Store back, queue drained, writer idle, environment and seams restored. */
    def reopen(): Unit = {
        down = false
        try {
            await("the queue to drain")(AuditLog.queueDepth == 0)
            var before = -1
            await("the writer to go idle") {
                val now = attempts.get
                val idle = now == before
                before = now
                if (!idle) Thread.sleep(50)
                idle
            }
        } finally {
            AuditLog.persistOverride = null
            AuditLog.retryDelayMsOverride = -1L
            TenantContext.clear()
            DatrisEnvironment.values = saved
        }
    }
}
