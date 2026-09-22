package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, DatrisException, TapConfig, TenantContext}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.slf4j.LoggerFactory

import java.nio.file.Files
import java.util.Date
import scala.collection.mutable.ListBuffer

/** Story: tap cron validation (plans/stories/tap-cron-validation.md).
  *
  *  Today `POST /api/v1/tap` accepts a 5-field Unix cron such as
  *  `*&#47;1 * * * *`, stores it, and `TapScheduler` logs
  *  "invalid cron expression for tap" on every 30 s tick forever while the tap
  *  silently never fires. After this story the save is refused before anything
  *  is persisted, and a tap already stored with a bad cron logs once per
  *  distinct bad value instead of once per tick.
  *
  *  Seams assumed (story "Files"):
  *
  *  {{{
  *  object TapCronValidation {
  *      // None for null / blank / parseable (org.quartz.CronExpression, the
  *      // same parser TapScheduler uses); otherwise the refusal message.
  *      def check(name: String, cron: String): Option[String]
  *      // Same message, thrown as ai.datris.model.DatrisException.
  *      def validate(name: String, cron: String): Unit
  *      // 5 whitespace tokens -> the 6-field Quartz equivalent, but only when
  *      // that equivalent itself parses.
  *      def unixHint(cron: String): Option[String]
  *  }
  *  object TapScheduler {
  *      private[util] def checkSchedules(taps: Seq[TapConfig], now: Date): Unit
  *      // Dedup key is (tap name -> last bad cron value); this clears it.
  *      private[util] def resetInvalidCronWarnings(): Unit
  *  }
  *  }}}
  *
  *  Message (story Step 1), asserted here by content, not by equality:
  *  names the tap, quotes the expression, states the 6-field Quartz format
  *  with the `'0 0 * * * ?'` example, and — only for a 5-token input whose
  *  rewrite parses — adds the "5-field" sentence carrying the 6-field form.
  *
  *  Ordering matters in the save path: `TapCronGate.normalizeCron` (blank →
  *  null, v1.34.1) runs first, then this validation, then `TapCronGate.check`
  *  (the test-before-cron 409, whose text is pinned byte-for-byte by
  *  `TapCronGateSpec` and must not move).
  *
  *  The scheduler half drives the pure per-tick seam directly — no Mongo — and
  *  captures log lines off the live SLF4J binding with the `LogCapture` helper
  *  copied from `TapCronNormalizationSpec`.
  */
class TapCronValidationSpec extends AnyFunSuite with BeforeAndAfterAll {

    private val root = Files.createTempDirectory("tap-cron-validation-spec")

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

    private def tap(name: String, cron: String): TapConfig =
        TapConfig(
            name = name,
            description = "spec",
            targetPipeline = "p",
            scriptStorage = "github",
            scriptRepoPath = "taps/" + name + ".py",
            scriptCommitSha = "aaa111",
            cronExpression = cron,
            enabled = true,
            lastRunStatus = null
        )

    // -------------------------------------------------- Acceptance 1 -------

    test("a 5-field Unix cron is refused and the message carries the 6-field equivalent") {
        val msg = TapCronValidation.check("prices", "*/1 * * * *")
        assert(msg.isDefined, "*/1 * * * * is not a Quartz cron and must be refused at save")
        val m = msg.get
        assert(m.contains("prices"), "message must name the tap: " + m)
        assert(m.contains("*/1 * * * *"), "message must quote the submitted expression: " + m)
        assert(m.contains("'0 0 * * * ?'"), "message must show the Quartz format example '0 0 * * * ?': " + m)
        assert(m.contains("5-field"), "message must say the input looks like a 5-field Unix cron: " + m)
        assert(m.contains("'0 */1 * * * ?'"), "message must carry the 6-field equivalent '0 */1 * * * ?': " + m)
    }

    // -------------------------------------------------- Acceptance 2 -------

    test("the 5-field hint puts ? in the day field the Unix form left unspecified") {
        // Acceptance 2 as amended by the lead: a numeric Unix day-of-week is
        // TRANSLATED, not copied — Unix counts 0/7 = Sunday, Quartz 1 = Sunday,
        // so digits carried through would be valid Quartz for the wrong days.
        val weekdays = TapCronValidation.check("prices", "30 5 * * 1-5")
        assert(weekdays.isDefined, "30 5 * * 1-5 must be refused")
        assert(
            weekdays.get.contains("'0 30 5 ? * MON-FRI'"),
            "a set day-of-week means day-of-month becomes ? and 1-5 (Unix Mon-Fri) becomes MON-FRI, " +
                "expected hint '0 30 5 ? * MON-FRI': " + weekdays.get
        )

        // Noon on Sunday, Unix style. (The amendment quoted the input with six
        // tokens; a six-token string is never a 5-field Unix cron, so the case
        // is written with the five-field form that yields the stated hint.)
        val unixSunday = TapCronValidation.check("prices", "0 12 * * 7")
        assert(unixSunday.isDefined, "0 12 * * 7 must be refused")
        assert(
            unixSunday.get.contains("'0 0 12 ? * SUN'"),
            "Unix day-of-week 7 is Sunday (Quartz 7 is Saturday), expected hint '0 0 12 ? * SUN': " + unixSunday.get
        )

        val unixSundayZero = TapCronValidation.check("prices", "0 12 * * 0")
        assert(unixSundayZero.isDefined, "0 12 * * 0 must be refused")
        assert(
            unixSundayZero.get.contains("'0 0 12 ? * SUN'"),
            "Unix day-of-week 0 is Sunday too, expected hint '0 0 12 ? * SUN': " + unixSundayZero.get
        )

        val monthly = TapCronValidation.check("prices", "0 0 1 * *")
        assert(monthly.isDefined, "0 0 1 * * must be refused")
        assert(
            monthly.get.contains("'0 0 0 1 * ?'"),
            "a set day-of-month means day-of-week becomes ?, expected hint '0 0 0 1 * ?': " + monthly.get
        )
    }

    // -------------------------------------------------- Acceptance 3 -------

    test("valid Quartz crons pass, including the 7-field year form") {
        assert(TapCronValidation.check("prices", "0 * * * * ?").isEmpty, "every-minute Quartz cron must be accepted")
        assert(TapCronValidation.check("prices", "0 30 5 ? * MON-FRI").isEmpty, "weekday cron must be accepted")
        assert(TapCronValidation.check("prices", "0 0 12 * * ? 2027").isEmpty, "the optional year field must be accepted")
    }

    // -------------------------------------------------- Acceptance 4 -------

    test("garbage is refused with no 5-field hint") {
        for (bad <- Seq("not a cron", "0 0 * * * *", "0 0 *")) {
            val msg = TapCronValidation.check("prices", bad)
            assert(msg.isDefined, "'" + bad + "' must be refused")
            assert(msg.get.contains(bad), "message must quote the submitted expression: " + msg.get)
            assert(msg.get.contains("'0 0 * * * ?'"), "message must show the Quartz format example: " + msg.get)
            assert(
                !msg.get.contains("5-field"),
                "'" + bad + "' is not a convertible 5-field Unix cron — no hint may be offered: " + msg.get
            )
        }
        // '0 0 * * * *' is 6 tokens but Quartz forbids '*' in BOTH day fields.
        assert(TapCronValidation.unixHint("0 0 * * * *").isEmpty, "a 6-token string is never a 5-field Unix cron")
        assert(TapCronValidation.unixHint("not a cron").isEmpty)
    }

    // -------------------------------------------------- Acceptance 5 -------

    test("null and blank crons never reach the parser") {
        assert(TapCronValidation.check("prices", null).isEmpty, "null cron means unscheduled")
        assert(TapCronValidation.check("prices", "").isEmpty, "\"\" is normalised to unscheduled before validation")
        assert(TapCronValidation.check("prices", "  \t").isEmpty, "whitespace-only is unscheduled, not invalid")
        // And nothing is thrown on the blank lane either.
        TapCronValidation.validate("prices", null)
        TapCronValidation.validate("prices", "")
        TapCronValidation.validate("prices", "  \t")
    }

    // -------------------------------------------------- Acceptance 6 -------

    test("validate throws DatrisException carrying exactly the check message") {
        val expected = TapCronValidation.check("prices", "*/1 * * * *")
        assert(expected.isDefined)
        val thrown = intercept[DatrisException] {
            TapCronValidation.validate("prices", "*/1 * * * *")
        }
        assert(thrown.getMessage == expected.get, "validate must throw the same message check returns: " + thrown.getMessage)
    }

    test("the 400 body is valid JSON even when the cron carries quotes or backslashes") {
        val nasty = "*/1 * \\ \" *"
        val msg = TapCronValidation.check("prices", nasty)
        assert(msg.isDefined, "'" + nasty + "' must be refused")
        val body = TapCronValidation.errorBody(msg.get)
        // A hand-concatenated body would break here, and the MCP server's
        // json.loads fallback would then report a 400 as "created successfully".
        val parsed = com.google.gson.JsonParser.parseString(body).getAsJsonObject
        assert(parsed.get("error").getAsString == msg.get, "the message must survive JSON encoding intact: " + body)
        assert(parsed.get("error").getAsString.contains(nasty), "the body must still quote the submitted expression: " + body)
    }

    // -------------------------------------------------- Acceptance 7 -------

    test("validation runs before the test-before-cron gate and does not change its refusal") {
        val fresh = tap("prices", "0 * * * * ?")
        assert(TapCronValidation.check(fresh.name, fresh.cronExpression).isEmpty, "a valid cron passes validation untouched")
        val gated = TapCronGate.check(null, fresh)
        assert(gated.isDefined, "a valid cron on a never-tested script is still the gate's business")
        assert(
            gated.get == "Tap 'prices' cannot be scheduled: its script has never passed a test run. Remedy: " +
                TapCronGate.Remedy + ".",
            "the 409 text must stay byte-identical: " + gated.get
        )
    }

    // -------------------------------------------------- Acceptance 8 -------

    /** Root-logger capture on whichever SLF4J binding is live (copied from
      *  TapCronNormalizationSpec). */
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
                    app.setName("tap-cron-validation-spec-capture")
                    app.start()
                    rootLogger.addAppender(app)
                    rootLogger.setLevel(ch.qos.logback.classic.Level.TRACE)
                    detach = () => { rootLogger.detachAppender(app); app.stop(); rootLogger.setLevel(prevLevel) }
                case _ =>
                    val ctx = org.apache.logging.log4j.LogManager.getContext(false).asInstanceOf[org.apache.logging.log4j.core.LoggerContext]
                    val rootLogger = ctx.getRootLogger
                    val prevLevel = rootLogger.getLevel
                    val app = new org.apache.logging.log4j.core.appender.AbstractAppender("tap-cron-validation-spec-capture", null, null, true, null) {
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

    test("a stored bad cron logs once per distinct value, not once per tick") {
        TapScheduler.resetInvalidCronWarnings()
        val capture = new LogCapture("TapScheduler")
        capture.start()
        try {
            def badLines(): List[String] =
                capture.lines
                    .synchronized(capture.lines.toList)
                    .collect { case (lvl, msg) if lvl == "ERROR" && msg.contains("invalid cron expression for tap: stale") => msg }

            // Five ticks over the same tap with the same bad value: one line.
            (1 to 5).foreach(_ => TapScheduler.checkSchedules(Seq(tap("stale", "bad one")), new Date()))
            assert(badLines().size == 1, "the upgrade path must log ONE line per distinct bad value, saw: " + badLines())

            // A changed bad value is news again.
            (1 to 3).foreach(_ => TapScheduler.checkSchedules(Seq(tap("stale", "bad two")), new Date()))
            assert(badLines().size == 2, "a changed bad cron must log once more, saw: " + badLines())

            // Fixed, then broken again with the ORIGINAL value: the entry was
            // cleared when the cron parsed, so this logs. The "fixed" value is
            // a valid cron whose next slot is years away, so the tick parses it
            // without firing a real run.
            TapScheduler.checkSchedules(Seq(tap("stale", "0 0 12 * * ? 2099")), new Date())
            assert(badLines().size == 2, "a valid cron must not log an invalid-cron line, saw: " + badLines())
            TapScheduler.checkSchedules(Seq(tap("stale", "bad one")), new Date())
            assert(badLines().size == 3, "a tap that parsed and then broke again must log, saw: " + badLines())

            // Deleted: the name is absent from a tick, so its entry is dropped.
            TapScheduler.checkSchedules(Seq.empty, new Date())
            TapScheduler.checkSchedules(Seq(tap("stale", "bad one")), new Date())
            assert(badLines().size == 4, "a deleted tap must not keep its dedup entry, saw: " + badLines())
        } finally {
            capture.stop()
            TapScheduler.resetInvalidCronWarnings()
        }
    }
}
