package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.slf4j.LoggerFactory

import java.nio.file.Files
import java.util.Date
import scala.collection.mutable.ListBuffer

/** Story: taps survive large sources (plans/stories/tap-large-sources.md),
  *  Step 7 — a blank cron means "unscheduled", never an error line on every
  *  scheduler tick.
  *
  *  Today the UI (and any REST client) can store `cronExpression: ""`; the
  *  save gate already reads "" as no schedule (`TapCronGate.blankToNull`), but
  *  the value is persisted verbatim and `TapScheduler.checkSchedules` only
  *  skips `null`, so `new CronExpression("")` throws and
  *  `"TapScheduler: invalid cron expression for tap: …"` is logged every tick.
  *
  *  Seams assumed:
  *
  *  {{{
  *  object TapCronGate {
  *      // Blank / whitespace-only cronExpression → null on the config that is
  *      // about to be saved (TapAPIController.saveTap and the version-restore
  *      // path both call it before `check`).
  *      def normalizeCron(tap: TapConfig): TapConfig
  *  }
  *  object TapScheduler {
  *      def checkSchedules(): Unit                                     // unchanged: readAll + delegate
  *      private[util] def checkSchedules(taps: Seq[TapConfig], now: Date): Unit
  *          // per-tap evaluation over a given list, so the tick is testable without Mongo
  *  }
  *  }}}
  *
  *  Log lines are captured off the live SLF4J binding (logback or log4j2 —
  *  both are on the test classpath) at the root logger and filtered by
  *  logger name, and the capture is validated by a tap with a genuinely
  *  invalid cron, which MUST log.
  */
class TapCronNormalizationSpec extends AnyFunSuite with BeforeAndAfterAll {

    private val root = Files.createTempDirectory("tap-cron-normalization-spec")

    private val env: DatrisEnvironment = DatrisEnvironment(
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
        mongoDbConfig = null,
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
        tapTableName = "test-tap",
        tempDir = root.toString
    )

    override def beforeAll(): Unit = TenantContext.set(env)
    override def afterAll(): Unit = TenantContext.clear()

    private def tap(name: String, cron: String, enabled: Boolean = true): TapConfig =
        TapConfig(
            name = name,
            description = "spec",
            targetPipeline = "p",
            scriptStorage = "github",
            scriptRepoPath = "taps/" + name + ".py",
            scriptCommitSha = "aaa111",
            cronExpression = cron,
            enabled = enabled,
            // No anchor date → epoch fallback; only matters for a VALID cron, which
            // this spec never passes (it would fire a real run).
            lastRunStatus = null
        )

    // ------------------------------------------------------------- save ---

    test("a save with cronExpression \"\" stores null and is not treated as setting a schedule") {
        val blank = TapCronGate.normalizeCron(tap("prices", ""))
        assert(blank.cronExpression == null, "\"\" must be stored as null, got: " + String.valueOf(blank.cronExpression))
        val ws = TapCronGate.normalizeCron(tap("prices", "   \t"))
        assert(ws.cronExpression == null, "whitespace-only must be stored as null, got: " + String.valueOf(ws.cronExpression))
        // Everything else on the config survives untouched.
        assert(blank.name == "prices" && blank.scriptRepoPath == "taps/prices.py" && blank.enabled)

        // A real cron is left alone; null stays null.
        assert(TapCronGate.normalizeCron(tap("prices", "0 0 3 * * ?")).cronExpression == "0 0 3 * * ?")
        assert(TapCronGate.normalizeCron(tap("prices", null)).cronExpression == null)

        // Not a schedule: the test-before-cron gate lets a never-tested script
        // through when the (normalised) cron is null — both on create and on an
        // edit of a stored-but-untested tap.
        assert(TapCronGate.check(null, blank).isEmpty, "a new tap saved with \"\" must not be gated as scheduled")
        assert(TapCronGate.check(tap("prices", null), blank).isEmpty)
        // Clearing a stored cron with "" is allowed and lands as null.
        val cleared = TapCronGate.normalizeCron(tap("prices", ""))
        assert(TapCronGate.check(tap("prices", "0 0 3 * * ?"), cleared).isEmpty && cleared.cronExpression == null)
    }

    // -------------------------------------------------------- scheduler ---

    /** Root-logger capture on whichever SLF4J binding is live. */
    private final class LogCapture(loggerNameFilter: String) {
        val lines: ListBuffer[(String, String)] = ListBuffer.empty // (level, message)
        private var detach: () => Unit = () => ()

        def start(): Unit = {
            val factory = LoggerFactory.getILoggerFactory
            factory match {
                case ctx: ch.qos.logback.classic.LoggerContext =>
                    val rootLogger = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
                    val prevLevel = rootLogger.getLevel
                    val app = new ch.qos.logback.core.AppenderBase[ch.qos.logback.classic.spi.ILoggingEvent] {
                        override def append(e: ch.qos.logback.classic.spi.ILoggingEvent): Unit =
                            if (e.getLoggerName.contains(loggerNameFilter)) lines.synchronized { lines += ((e.getLevel.toString, e.getFormattedMessage)) }
                    }
                    app.setContext(ctx)
                    app.setName("tap-cron-spec-capture")
                    app.start()
                    rootLogger.addAppender(app)
                    rootLogger.setLevel(ch.qos.logback.classic.Level.TRACE)
                    detach = () => { rootLogger.detachAppender(app); app.stop(); rootLogger.setLevel(prevLevel) }
                case _ =>
                    val ctx = org.apache.logging.log4j.LogManager.getContext(false).asInstanceOf[org.apache.logging.log4j.core.LoggerContext]
                    val rootLogger = ctx.getRootLogger
                    val prevLevel = rootLogger.getLevel
                    val app = new org.apache.logging.log4j.core.appender.AbstractAppender("tap-cron-spec-capture", null, null, true, null) {
                        override def append(e: org.apache.logging.log4j.core.LogEvent): Unit =
                            if (e.getLoggerName.contains(loggerNameFilter))
                                lines.synchronized { lines += ((e.getLevel.toString, e.getMessage.getFormattedMessage)) }
                    }
                    app.start()
                    rootLogger.addAppender(app)
                    rootLogger.setLevel(org.apache.logging.log4j.Level.ALL)
                    ctx.updateLoggers()
                    detach = () => { rootLogger.removeAppender(app); app.stop(); rootLogger.setLevel(prevLevel); ctx.updateLoggers() }
            }
        }

        def stop(): Unit = detach()
    }

    test("TapScheduler logs nothing for a tap stored with \"\" (and nothing for whitespace or null); an invalid cron still logs") {
        val capture = new LogCapture("TapScheduler")
        capture.start()
        try {
            // Sanity: the capture sees the scheduler's error line for a bad cron.
            TapScheduler.checkSchedules(Seq(tap("broken", "not a cron")), new Date())
            val sanity = capture.lines.synchronized(capture.lines.toList)
            assert(
                sanity.exists { case (lvl, msg) => lvl == "ERROR" && msg.contains("invalid cron expression") && msg.contains("broken") },
                "capture sanity check: an invalid cron must produce the existing error line, saw: " + sanity
            )
            capture.lines.synchronized(capture.lines.clear())

            // The upgrade path: taps already stored with "" (or whitespace) and null.
            TapScheduler.checkSchedules(Seq(tap("blank", ""), tap("spaces", "   "), tap("none", null), tap("blank-disabled", "", enabled = false)), new Date())
            // Two ticks, like the live scheduler.
            TapScheduler.checkSchedules(Seq(tap("blank", "")), new Date())
            val seen = capture.lines.synchronized(capture.lines.toList)
            assert(seen.isEmpty, "a blank cron is unscheduled — no log line of any level on a tick, saw: " + seen)
        } finally capture.stop()
    }
}
