package ai.datris

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.MultiTenantRetired
import com.google.common.base.Throwables
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.scheduling.annotation.EnableScheduling

object DatrisServiceApplication {
    private val logger: Logger = LoggerFactory.getLogger(this.getClass.getSimpleName)

    def main(args: Array[String]): Unit = {
        try {
            initializeApplication()
            SpringApplication.run(classOf[Application], args: _*)
        } catch {
            case e: Exception =>
                startupExit(e) match {
                    case Some((status, message)) =>
                        logger.error(message)
                        System.exit(status)
                    case None =>
                        logger.error("DatrisServiceApplication startup error: " + Throwables.getStackTraceAsString(e))
                }
        }
    }

    /** The exit status and the last line to log when a startup failure must
      * end the process. Only the multi-tenant refusal does: the process ends
      * with [[MultiTenantRetired.ExitStatus]] and its plain message. Every
      * other startup failure gives None and is handled as before. */
    private[datris] def startupExit(e: Throwable): Option[(Int, String)] =
        MultiTenantRetired.refusal(e).map(m => (MultiTenantRetired.ExitStatus, m))

    def initializeApplication(): Unit = {}
}

@SpringBootApplication
@EnableScheduling
class Application {}
