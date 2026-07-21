package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.slf4j.{Logger, LoggerFactory}

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.collection.JavaConverters._

/** In-memory staging area for files the user drops into the Assistant chat.
  *
  * The Assistant agent loop runs server-side and the model cannot emit a real
  * file's bytes, so the UI uploads the file once to the staging endpoint, gets
  * back a short `attachmentId` + a text sample, and only that handle travels
  * through the chat. When the model later calls a file tool (create_pipeline,
  * upload_data, profile_data) with the `attachmentId`, AgentLoop resolves the
  * id back to the real bytes and substitutes them before dispatching to MCP.
  *
  * Entries are tenant-scoped (resolution checks the tenant that staged them),
  * expire after a TTL so abandoned uploads self-clean, and are held only in
  * heap — they never need to survive a restart. This is the v1 store; a
  * temp-file backing is a later hardening step if large uploads matter.
  */
object AttachmentStore {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /** A staged file plus the metadata the model needs to reason about it. */
    case class Attachment(
        id: String,
        tenantEnv: String,
        filename: String,
        bytes: Array[Byte],
        sample: String,
        detectedType: String,
        createdAt: Long
    )

    /** How long a staged file lives before the sweep evicts it. Long enough
      * to survive a multi-turn conversation where the agent inquires and the
      * user replies, short enough that an abandoned drop doesn't linger. */
    private val TtlMillis: Long = 60L * 60L * 1000L // 1 hour

    /** Soft cap on total entries — a runaway-upload backstop. When exceeded we
      * sweep expired entries first; if still over, the oldest is evicted. */
    private val MaxEntries: Int = 256

    private val store: ConcurrentHashMap[String, Attachment] = new ConcurrentHashMap()
    private val lastSweep: AtomicLong = new AtomicLong(0L)

    /** Stage a file and return its handle. The caller has already extracted
      * the sample + detected type (it knows the file types). */
    def put(tenantEnv: String, filename: String, bytes: Array[Byte], sample: String, detectedType: String): Attachment = {
        val now = System.currentTimeMillis()
        sweep(now)
        if (store.size() >= MaxEntries) evictOldest()

        val att = Attachment(UUID.randomUUID().toString.replace("-", ""), tenantEnv, filename, bytes, sample, detectedType, now)
        store.put(att.id, att)
        logger.info("AttachmentStore: staged " + att.id + " (" + filename + ", " + bytes.length + " bytes) for tenant " + tenantEnv)
        att
    }

    /** Resolve a handle to its staged file, scoped to the requesting tenant.
      * Returns None if unknown, expired, or owned by a different tenant — the
      * tenant check is the multi-tenant isolation boundary. */
    def get(id: String, tenantEnv: String): Option[Attachment] = {
        if (id == null || id.isEmpty) return None
        sweep(System.currentTimeMillis())
        Option(store.get(id)).filter { a =>
            val ok = a.tenantEnv == tenantEnv
            if (!ok) logger.warn("AttachmentStore: tenant mismatch on " + id + " (owner=" + a.tenantEnv + ", requester=" + tenantEnv + ")")
            ok
        }
    }

    /** Drop a handle once its data has been loaded into a pipeline. */
    def remove(id: String): Unit = if (id != null) store.remove(id)

    /** Evict expired entries. Cheap and idempotent; throttled to once per
      * minute so a burst of calls doesn't walk the map repeatedly. */
    private def sweep(now: Long): Unit = {
        val prev = lastSweep.get()
        if (now - prev < 60000L) return
        if (!lastSweep.compareAndSet(prev, now)) return
        val it = store.entrySet().iterator()
        while (it.hasNext) {
            val e = it.next()
            if (now - e.getValue.createdAt > TtlMillis) it.remove()
        }
    }

    private def evictOldest(): Unit = {
        store.entrySet().asScala.toList.sortBy(_.getValue.createdAt).headOption.foreach(e => store.remove(e.getKey))
    }
}
