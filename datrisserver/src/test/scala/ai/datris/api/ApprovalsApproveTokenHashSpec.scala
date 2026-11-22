package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditActorInfo, AuditEntry, AuditLog}
import ai.datris.auth.ScopeDenialFixtures
import ai.datris.config.PolicyInterceptor
import ai.datris.model.{DatrisEnvironment, UserContext}
import ai.datris.policy.{FakePendingActionCollection, PendingAction, PendingActionIO, PolicyReplay}
import ai.datris.util.{MongoDBUtil, TokenHash}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import org.bson.Document
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.slf4j.LoggerFactory

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.JavaConverters._

/** Story: approvals, the one-time token that runs an approved action is
  * stored as a hash and removed once used
  * (plans/stories/approval-replay-token-hashed.md).
  *
  * `ApprovalsAPIController.approve` end to end on the MongoDB branch of
  * PendingActionIO ([[FakePendingActionCollection]] behind a real
  * MongoDBUtil, through the assumed `PendingActionIO.dbOverride` seam). The
  * replayed request goes to a loopback stand-in for this server
  * (`PolicyReplay.port`) that does what PolicyInterceptor does with it: hands
  * the presented token to `PolicyInterceptor.MongoStore.consumeReplay`.
  * It also reads the stored record while the replay is in flight, the one
  * moment the record is approved and not yet used. */
class ApprovalsApproveTokenHashSpec extends AnyFunSuite with BeforeAndAfterEach {

    private var saved: DatrisEnvironment = _
    private var savedPort: Int = 0
    private var fake: FakePendingActionCollection = _
    private var server: HttpServer = _
    private val audit = new ConcurrentLinkedQueue[AuditEntry]()

    /** What the stand-in saw for each replayed request. */
    private case class Replay(header: String, storedDuringReplay: Option[Document], accepted: Boolean)
    private val replays = new ConcurrentLinkedQueue[Replay]()

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        savedPort = PolicyReplay.port
        ScopeDenialFixtures.install(ScopeDenialFixtures.auditEnv().copy(
            useApiKeys = false,
            useAgentPolicy = true,
            pendingActionTableName = "test-pending-action"
        ))
        fake = new FakePendingActionCollection
        PendingActionIO.dbOverride = new MongoDBUtil(fake.database)
        audit.clear()
        replays.clear()
        AuditLog.sinkOverride = (e: AuditEntry) => { audit.add(e); () }
        UserContext.clear()

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
            "/",
            (ex: HttpExchange) => {
                val header = Option(ex.getRequestHeaders.getFirst(PolicyReplay.HeaderApproval)).getOrElse("")
                val dot = header.indexOf('.')
                val (id, token) = if (dot > 0) (header.substring(0, dot), header.substring(dot + 1)) else (header, "")
                val during = fake.doc(id)
                val accepted = PolicyInterceptor.MongoStore.consumeReplay(id, token).isDefined
                replays.add(Replay(header, during, accepted))
                val out = (if (accepted) "handled" else "{\"errorKind\":\"policy_replay_rejected\"}").getBytes(StandardCharsets.UTF_8)
                ex.sendResponseHeaders(if (accepted) 200 else 403, out.length.toLong)
                ex.getResponseBody.write(out)
                ex.close()
            }
        )
        server.start()
        PolicyReplay.port = server.getAddress.getPort
    }

    override def afterEach(): Unit = {
        if (server != null) server.stop(0)
        PolicyReplay.port = savedPort
        AuditLog.sinkOverride = null
        PendingActionIO.dbOverride = null
        UserContext.clear()
        ScopeDenialFixtures.install(saved)
    }

    private def queued(id: String, state: String = PendingAction.Pending): PendingAction = {
        val now = Instant.now()
        PendingAction(
            id = id,
            action = "tap:run",
            resourceType = None,
            resourceName = None,
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
            hashVersion = Some(PendingAction.CurrentHashVersion)
        )
    }

    private def approve(id: String) = {
        val (req, _) = ScopeDenialFixtures.request(null, "POST", "/api/v1/approvals/" + id + "/approve")
        new ApprovalsAPIController().approve(id, req)
    }

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

    private def stored(id: String): Document = fake.doc(id).getOrElse(fail("no record " + id))

    test("an approved record's stored document holds a hash and no clear token") {
        PendingActionIO.insert(queued("pa_a"))
        val res = approve("pa_a")
        assert(res.getStatusCode.value() == 200, res.getBody)
        val r = replays.asScala.toList
        assert(r.size == 1, s"$r")
        val token = r.head.header.stripPrefix("pa_a.")
        assert(token.nonEmpty && token != r.head.header)
        val during = r.head.storedDuringReplay.getOrElse(fail("record missing during the replay"))
        assert(during.getString("state") == PendingAction.Approved)
        assert(during.getString("replayTokenHash") == TokenHash.sha256Hex(token), during.toJson)
        assert(!during.containsKey("replayToken"), during.toJson)
        assert(!during.toJson.contains(token))
    }

    test("the approved action runs once; the record afterwards holds neither the token nor its hash") {
        PendingActionIO.insert(queued("pa_b"))
        val res = approve("pa_b")
        assert(res.getStatusCode.value() == 200, res.getBody)
        val r = replays.asScala.toList
        assert(r.size == 1 && r.head.accepted, s"$r")
        val d = stored("pa_b")
        assert(d.getString("state") == PendingAction.Executed, d.toJson)
        assert(!d.containsKey("replayToken"), d.toJson)
        assert(!d.containsKey("replayTokenHash"), d.toJson)
        assert(d.getString("replayConsumedAt") != null)
        // approving again does not run it again
        val again = approve("pa_b")
        assert(again.getStatusCode.value() == 409, again.getBody)
        assert(replays.size == 1)
    }

    test("a second presentation of the same token is not accepted") {
        PendingActionIO.insert(queued("pa_c"))
        assert(approve("pa_c").getStatusCode.value() == 200)
        val token = replays.asScala.head.header.stripPrefix("pa_c.")
        val after = stored("pa_c")
        assert(PolicyInterceptor.MongoStore.consumeReplay("pa_c", token).isEmpty)
        assert(stored("pa_c") == after)
    }

    test("a record approved before the upgrade and not yet run is not run, stays approved, and nothing errors") {
        val d = queued("pa_old", PendingAction.Approved).copy(decidedBy = Some("todd"), decidedAt = Some(Instant.now())).toDocument
        d.put("replayToken", PendingAction.newToken())
        fake.put(d)
        val before = stored("pa_old")
        val res = approve("pa_old")
        assert(res.getStatusCode.value() == 409, s"${res.getStatusCode} ${res.getBody}")
        assert(replays.isEmpty, "a replay was sent for it")
        assert(PolicyInterceptor.MongoStore.consumeReplay("pa_old", before.getString("replayToken")).isEmpty)
        val after = stored("pa_old")
        assert(after.getString("state") == PendingAction.Approved)
        assert(!after.containsKey("replayConsumedAt") && !after.containsKey("executedAt"), after.toJson)
        // shown like any approved-not-run record; its token is not in what is shown
        assert(!res.getBody.contains(before.getString("replayToken")), res.getBody)
    }

    test("the public JSON of a record never holds a token or a hash") {
        PendingActionIO.insert(queued("pa_d"))
        val res = approve("pa_d")
        val token = replays.asScala.head.header.stripPrefix("pa_d.")
        val hash = TokenHash.sha256Hex(token)
        for (body <- Seq(res.getBody, PendingActionIO.get("pa_d").get.toPublicJson.toString)) {
            assert(!body.contains(token), body)
            assert(!body.contains(hash), body)
            assert(!body.contains("replayToken"), body)
        }
    }

    test("no log line or audit row carries a token or its hash") {
        PendingActionIO.insert(queued("pa_e"))
        val (_, lines) = withLogs(approve("pa_e"))
        val token = replays.asScala.head.header.stripPrefix("pa_e.")
        val hash = TokenHash.sha256Hex(token)
        for (l <- lines) {
            assert(!l.contains(token), l)
            assert(!l.contains(hash), l)
        }
        for (e <- audit.asScala) {
            assert(!e.toString.contains(token), e.toString)
            assert(!e.toString.contains(hash), e.toString)
        }
    }
}
