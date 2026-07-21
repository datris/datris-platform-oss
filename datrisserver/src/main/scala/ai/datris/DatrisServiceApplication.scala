package ai.datris

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

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
                logger.error("DatrisServiceApplication startup error: " + Throwables.getStackTraceAsString(e))
        }
    }

    def initializeApplication(): Unit = {}
}

@SpringBootApplication
@EnableScheduling
class Application {}
