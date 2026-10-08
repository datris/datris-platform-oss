package ai.datris.controller

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component

/** Stops the Kafka consumer during Spring's context close, before the audit
  * log's shutdown flush ([[ai.datris.audit.AuditLogLifecycle]], phase 0):
  * lifecycles stop in descending phase order, so phase 100 runs first. The
  * consumer then cannot handle (and auto-commit) a record whose audit entry
  * would arrive after the flush has drained the queue. */
@Component
class KafkaConsumerLifecycle extends SmartLifecycle {
    @volatile private var running = false

    override def start(): Unit = running = true

    override def stop(): Unit = {
        try KafkaConsumerRunner.stopActive(10000L)
        finally running = false
    }

    override def isRunning: Boolean = running

    override def getPhase: Int = 100
}
