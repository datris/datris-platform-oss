package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ch.qos.logback.classic.{Level, Logger}
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.Marker

/** Drops log events whose exception is a client disconnect — "Broken pipe" or
  * "Connection reset by peer". These are raised by the servlet container when
  * it flushes an SSE frame to a socket the browser already closed (a user
  * navigating away or refreshing mid-stream). They're benign and unactionable,
  * but Tomcat logs them at ERROR with a full stack trace on a container I/O
  * thread — outside our application code's reach, so we can't swallow them at
  * the source.
  *
  * Targeting the THROWABLE (not a logger name) keeps this surgical: a genuine
  * IOException with a different message, or any other error from the same
  * logger, still logs normally. Registered as a logback turbo filter in
  * logback-spring.xml so it sees every event before it's dispatched. */
class ClientAbortLogFilter extends TurboFilter {
    override def decide(marker: Marker,
                        logger: Logger,
                        level: Level,
                        format: String,
                        params: Array[AnyRef],
                        t: Throwable): FilterReply = {
        if (t != null && isClientAbort(t)) FilterReply.DENY else FilterReply.NEUTRAL
    }

    private def isClientAbort(t: Throwable): Boolean = {
        var cur = t
        var depth = 0
        // Walk the cause chain (bounded) — the broken-pipe IOException is often
        // wrapped by the time it reaches the logger.
        while (cur != null && depth < 10) {
            val m = cur.getMessage
            if (m != null) {
                val lm = m.toLowerCase
                if (lm.contains("broken pipe") || lm.contains("connection reset")) return true
            }
            cur = cur.getCause
            depth += 1
        }
        false
    }
}
