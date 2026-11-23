package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import org.slf4j.{Logger, LoggerFactory}

/** Multi-tenant mode is retired. A server started with the setting on stops
  * before it reads or writes anything: [[refuseIfOn]] is the first statement
  * of `StartupRunner.run`, and `DatrisServiceApplication.main` turns the
  * refusal into a non-zero exit with [[Message]] as the last line logged. */
object MultiTenantRetired {
    private val logger: Logger = LoggerFactory.getLogger("ai.datris.StartupRunner")

    /** Exit status of a server that refused to start because of the setting. */
    val ExitStatus: Int = 1

    val Message: String =
        "The MULTI_TENANT setting is on: this install is set to multi-tenant mode, which this version of Datris no longer supports. " +
            "The server has not started and nothing was changed: no database, collection, bucket or stored setting was created, written or removed. " +
            "To keep running as you are, go back to the previous version (the image tag you ran before this upgrade); it works exactly as before. " +
            "To run this version, remove MULTI_TENANT from your .env file (or set it to false) and start the server again: it then serves one install, " +
            "and anything kept for other tenants stays where it is but is not served. " +
            "See https://docs.datris.ai/production/upgrades (Multi-tenant mode retired)."

    /** None when the setting is off; the operator-facing message when it is on. */
    def problem(flagOn: Boolean): Option[String] = if (flagOn) Some(Message) else None

    /** Logs the message at ERROR and stops startup when the setting is on. */
    def refuseIfOn(flagOn: Boolean): Unit =
        problem(flagOn).foreach { m =>
            logger.error(m)
            throw new DatrisException(m)
        }

    /** The message when `e`, or any exception that caused it, is this refusal.
      * Spring wraps an exception thrown by a startup runner, so the whole
      * cause chain is searched. Any other startup failure gives None. */
    def refusal(e: Throwable): Option[String] = {
        var t = e
        var depth = 0
        while (t != null && depth < 32) {
            t match {
                case d: DatrisException if d.getMessage == Message => return Some(Message)
                case _ =>
            }
            t = t.getCause
            depth += 1
        }
        None
    }
}
