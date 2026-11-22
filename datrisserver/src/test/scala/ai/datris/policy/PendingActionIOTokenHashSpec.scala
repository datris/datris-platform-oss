package ai.datris.policy

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditActorInfo
import ai.datris.auth.ScopeDenialFixtures
import ai.datris.model.DatrisEnvironment
import ai.datris.util.{MongoDBUtil, TokenHash}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.bson.Document
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import scala.collection.JavaConverters._

/** Story: approvals, the one-time token that runs an approved action is
  * stored as a hash and removed once used
  * (plans/stories/approval-replay-token-hashed.md).
  *
  * PendingActionIO against MongoDB is the only branch production runs
  * (`collection()` requires MongoDBUtil). These specs run that branch: a
  * real [[MongoDBUtil]] over [[FakePendingActionCollection]], which
  * evaluates the store's own Filters and Updates. Assumed seam, the same
  * pattern as `SessionStore.dbOverride`:
  * {{{
  * // in object PendingActionIO; null = the package NoSQLDbUtil
  * @volatile private[datris] var dbOverride: ai.datris.util.NoSQLDbUtility = null
  * }}}
  * and the startup cleanup of Decision 4:
  * {{{
  * // unsets `replayToken` on every record; returns the number of records changed
  * def removeClearReplayTokens(): Long
  * }}} */
class PendingActionIOTokenHashSpec extends AnyFunSuite with BeforeAndAfterEach {

    private var saved: DatrisEnvironment = _
    private var fake: FakePendingActionCollection = _
    private val now = Instant.now()

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        ScopeDenialFixtures.install(ScopeDenialFixtures.auditEnv().copy(
            useApiKeys = false,
            useAgentPolicy = true,
            pendingActionTableName = "test-pending-action"
        ))
        fake = new FakePendingActionCollection
        PendingActionIO.dbOverride = new MongoDBUtil(fake.database)
    }

    override def afterEach(): Unit = {
        PendingActionIO.dbOverride = null
        ScopeDenialFixtures.install(saved)
    }

    private def record(id: String, state: String, tokenHash: Option[String]) = PendingAction(
        id = id,
        action = "tap:run",
        resourceType = Some("tap"),
        resourceName = Some("t1"),
        resourceVersion = None,
        actor = AuditActorInfo("api-key", "research-agent"),
        reason = Some("nightly"),
        agentSession = None,
        method = "POST",
        path = "/api/v1/tap/run",
        query = None,
        contentType = Some("application/json"),
        body = Some("{\"name\":\"t1\"}"),
        bodyHash = "h-" + id,
        createdAt = now,
        expiresAt = now.plus(1, ChronoUnit.HOURS),
        state = state,
        decidedBy = if (state == PendingAction.Pending) None else Some("todd"),
        decidedAt = if (state == PendingAction.Pending) None else Some(now),
        replayTokenHash = tokenHash,
        hashVersion = Some(PendingAction.CurrentHashVersion)
    )

    /** An approved record stored by this version, and its token. */
    private def approvedNow(id: String = "pa_new"): String = {
        val token = PendingAction.newToken()
        PendingActionIO.insert(record(id, PendingAction.Approved, Some(TokenHash.sha256Hex(token))))
        token
    }

    /** A record stored by an earlier version: the clear token field, no hash. */
    private def legacy(id: String, state: String, token: String): Document = {
        val d = record(id, state, None).toDocument
        d.remove("replayTokenHash")
        d.put("replayToken", token)
        if (state == PendingAction.Executed) {
            d.put("replayConsumedAt", now.toString)
            d.put("executedAt", now.toString)
            d.put("resultStatus", 200: java.lang.Integer)
        }
        fake.put(d)
        d
    }

    private def stored(id: String): Document = fake.doc(id).getOrElse(fail("no record " + id))

    private def withLogs[T](f: => T): (T, List[String]) = {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[ch.qos.logback.classic.Logger]
        val appender = new ListAppender[ILoggingEvent]()
        appender.start()
        root.addAppender(appender)
        try {
            val r = f
            val lines = appender.list.asScala.toList.map { e =>
                e.getFormattedMessage + Option(e.getThrowableProxy).map(t => " " + t.getClassName + ": " + t.getMessage).getOrElse("")
            }
            (r, lines)
        } finally root.detachAppender(appender)
    }

    test("an approved record's stored document holds a hash and no clear token") {
        val token = approvedNow()
        val d = stored("pa_new")
        assert(d.getString("replayTokenHash") == TokenHash.sha256Hex(token))
        assert(!d.containsKey("replayToken"), d.toJson)
        assert(!d.toJson.contains(token))
    }

    test("the approved action runs once; the record afterwards holds neither the token nor its hash") {
        val token = approvedNow()
        val hash = TokenHash.sha256Hex(token)
        fake.clearRecords()
        val got = PendingActionIO.consumeReplay("pa_new", token)
        assert(got.map(_.id).contains("pa_new"))
        assert(got.exists(_.replayConsumedAt.isDefined))
        assert(got.exists(_.replayTokenHash.isEmpty), s"$got")
        val d = stored("pa_new")
        assert(!d.containsKey("replayTokenHash"), d.toJson)
        assert(!d.containsKey("replayToken"), d.toJson)
        assert(d.getString("replayConsumedAt") != null)
        // one update did both: set the use time and removed the hash
        val consuming = fake.updates.filter(u => u.containsKey("$set") && u.getDocument("$set").containsKey("replayConsumedAt")).toList
        assert(consuming.size == 1, s"${fake.updates}")
        assert(consuming.head.containsKey("$unset") && consuming.head.getDocument("$unset").containsKey("replayTokenHash"), consuming.head.toJson)
        // the database match is on the hash, never on the presented value
        assert(fake.filters.exists(_.toJson.contains(hash)), s"${fake.filters}")
        assert(!fake.filters.exists(_.toJson.contains(token)), s"${fake.filters}")
    }

    test("a second presentation of the same token is not accepted") {
        val token = approvedNow()
        assert(PendingActionIO.consumeReplay("pa_new", token).isDefined)
        val after = stored("pa_new")
        assert(PendingActionIO.consumeReplay("pa_new", token).isEmpty)
        assert(stored("pa_new") == after, "the second presentation changed the record")
    }

    test("two concurrent attempts to run one approved action: exactly one is accepted") {
        for (round <- 1 to 20) {
            val id = "pa_race_" + round
            val token = approvedNow(id)
            val n = 8
            val pool = Executors.newFixedThreadPool(n)
            val start = new CountDownLatch(1)
            try {
                val futures = (1 to n).map(_ =>
                    pool.submit(new java.util.concurrent.Callable[Option[PendingAction]] {
                        def call(): Option[PendingAction] = {
                            start.await()
                            PendingActionIO.consumeReplay(id, token)
                        }
                    })
                )
                start.countDown()
                val results = futures.map(_.get(10, TimeUnit.SECONDS))
                assert(results.count(_.isDefined) == 1, s"round $round: $results")
            } finally pool.shutdownNow()
            assert(!stored(id).containsKey("replayTokenHash"))
        }
    }

    test("a value that is not the token is not accepted, and neither is the stored hash itself") {
        val token = approvedNow()
        val hash = TokenHash.sha256Hex(token)
        val before = stored("pa_new")
        for (v <- Seq(hash, PendingAction.newToken(), token.toUpperCase, token + "0", "", null))
            assert(PendingActionIO.consumeReplay("pa_new", v).isEmpty, s"accepted: $v")
        assert(PendingActionIO.consumeReplay("pa_other", token).isEmpty)
        assert(stored("pa_new") == before, "a refused value changed the record")
        // the token itself still works afterwards
        assert(PendingActionIO.consumeReplay("pa_new", token).isDefined)
    }

    test("a record that holds only a clear token from an earlier version is not accepted") {
        val token = PendingAction.newToken()
        legacy("pa_old", PendingAction.Approved, token)
        val before = stored("pa_old")
        val got = PendingActionIO.consumeReplay("pa_old", token)
        assert(got.isEmpty)
        // nor is its token's hash
        assert(PendingActionIO.consumeReplay("pa_old", TokenHash.sha256Hex(token)).isEmpty)
        // it stays approved and not run, and nothing about it changed
        assert(stored("pa_old") == before, s"${before.toJson}\n${stored("pa_old").toJson}")
        val pa = PendingActionIO.get("pa_old")
        assert(pa.map(_.state).contains(PendingAction.Approved))
        assert(pa.exists(p => p.replayConsumedAt.isEmpty && p.executedAt.isEmpty && p.replayTokenHash.isEmpty), s"$pa")
        assert(PendingActionIO.list(Some(PendingAction.Approved), None, 10).map(_.id) == List("pa_old"))
    }

    test("the startup cleanup removes clear tokens from every record and changes nothing else; a second run changes nothing") {
        val oldRun = PendingAction.newToken()
        val oldOpen = PendingAction.newToken()
        legacy("pa_old_executed", PendingAction.Executed, oldRun)
        legacy("pa_old_approved", PendingAction.Approved, oldOpen)
        val fresh = approvedNow("pa_fresh")
        PendingActionIO.insert(record("pa_pending", PendingAction.Pending, None))
        val before = fake.all.map(d => d.getString("_id") -> d).toMap

        val (changed, lines) = withLogs(PendingActionIO.removeClearReplayTokens())
        assert(changed == 2L)
        val after = fake.all.map(d => d.getString("_id") -> d).toMap
        assert(after.keySet == before.keySet, "records were added or removed")
        after.values.foreach(d => assert(!d.containsKey("replayToken"), d.toJson))
        for ((id, b) <- before) {
            val expected = new Document(b)
            expected.remove("replayToken")
            assert(after(id) == expected, s"$id changed beyond the clear token:\n${b.toJson}\n${after(id).toJson}")
        }
        // the fresh approval still runs; the old open one still does not
        assert(after("pa_fresh").getString("replayTokenHash") == TokenHash.sha256Hex(fresh))
        assert(after("pa_old_approved").getString("state") == PendingAction.Approved)
        for (t <- Seq(oldRun, oldOpen)) assert(!lines.exists(_.contains(t)), lines.mkString("\n"))

        val (again, _) = withLogs(PendingActionIO.removeClearReplayTokens())
        assert(again == 0L)
        assert(fake.all.map(d => d.getString("_id") -> d).toMap == after, "the second run changed a record")
        assert(PendingActionIO.consumeReplay("pa_fresh", fresh).isDefined)
        assert(PendingActionIO.consumeReplay("pa_old_approved", oldOpen).isEmpty)
    }

    test("the startup cleanup on an empty collection changes nothing and does not fail") {
        assert(PendingActionIO.removeClearReplayTokens() == 0L)
        assert(fake.all.isEmpty)
    }

    test("the server runs the startup cleanup") {
        val rel = "datrisserver/src/main/scala/ai/datris/StartupRunner.scala"
        val file = Seq(Paths.get(rel), Paths.get(rel.stripPrefix("datrisserver/"))).find(Files.isRegularFile(_)).getOrElse(fail(rel + " not found"))
        val src = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        assert(src.contains("PendingActionIO.removeClearReplayTokens()"))
    }

    test("no log line carries a token or its hash") {
        val token = PendingAction.newToken()
        val hash = TokenHash.sha256Hex(token)
        val (_, lines) = withLogs {
            PendingActionIO.insert(record("pa_log", PendingAction.Approved, Some(hash)))
            PendingActionIO.consumeReplay("pa_log", "not-the-token")
            PendingActionIO.consumeReplay("pa_log", hash)
            PendingActionIO.consumeReplay("pa_log", token)
            PendingActionIO.consumeReplay("pa_log", token)
            legacy("pa_log_old", PendingAction.Approved, token + "1")
            PendingActionIO.consumeReplay("pa_log_old", token + "1")
            PendingActionIO.removeClearReplayTokens()
        }
        for (l <- lines) {
            assert(!l.contains(token), l)
            assert(!l.contains(hash), l)
        }
    }
}
