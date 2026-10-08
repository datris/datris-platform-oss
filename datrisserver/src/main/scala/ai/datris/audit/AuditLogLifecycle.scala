package ai.datris.audit

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component

/** Runs the audit log's shutdown flush inside Spring's context close.
  *
  * Phase 0 stops after the embedded web server (graceful-shutdown and
  * web-server lifecycles sit just below Integer.MAX_VALUE and stop first), so
  * requests have stopped arriving and their entries are queued; and it runs
  * before Spring Boot shuts logging down, which happens only after the
  * context has closed. A plain JVM shutdown hook would race both, since JVM
  * hooks run concurrently. While this bean is running, AuditLog's own JVM
  * hook stands aside; it still covers a process with no Spring context. */
@Component
class AuditLogLifecycle extends SmartLifecycle {
    @volatile private var running = false

    override def start(): Unit = {
        AuditLog.lifecycleManaged = true
        running = true
    }

    override def stop(): Unit = {
        try AuditLog.shutdownFlush()
        finally running = false
    }

    override def isRunning: Boolean = running

    override def getPhase: Int = 0
}
