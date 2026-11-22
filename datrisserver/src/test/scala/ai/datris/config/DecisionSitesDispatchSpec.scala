package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditActorInfo, AuditEntry, AuditLog}
import ai.datris.auth.{CapabilityScopes, RequestPaths, ScopeDenialFixtures}
import ai.datris.model.{Capability, DatrisEnvironment, ResolvedKey, TenantContext, UserContext}
import ai.datris.policy.{AgentPolicy, PendingAction, PolicyGate, PolicyIO, PolicyMode, PolicyReplay}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.google.gson.JsonParser
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.apache.catalina.startup.Tomcat
import org.apache.tomcat.util.descriptor.web.{FilterDef, FilterMap}
import org.scalatest.concurrent.Eventually
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation._
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.{DispatcherServlet, HandlerInterceptor}
import org.springframework.web.servlet.config.annotation.{EnableWebMvc, InterceptorRegistry, WebMvcConfigurer}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
import scala.collection.JavaConverters._

/** Every access-decision site reads the route the dispatcher matched, not the
  * raw request URI, so a request spelled differently from its canonical path
  * gets the same capability, policy, role and audit decision. One test per
  * site; each fails when that site alone reads the raw URI again.
  *
  * Runs the production chain on an embedded Tomcat (loopback, ephemeral port):
  * the two body-caching filters, a real DispatcherServlet and the real
  * CapabilityInterceptor, PolicyInterceptor, RoleEnforcementInterceptor and
  * AuditInterceptor in WebMvcConfig's order, with a test interceptor in place
  * of TenantInterceptor / SessionAuthenticator. Stand-in handlers carry the
  * application's own mappings (checked below) and touch nothing. The audit
  * log writes to a test sink, the agent policy and the pending-action store
  * are in memory, and nothing reaches a database. Also covers: approval
  * replays (a token is honoured only on the approved request), queueing (only
  * routed requests; one action per route however it is spelled; approvals
  * pending at upgrade), and log lines (one line per request). */
class DecisionSitesDispatchSpec extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach with Eventually {

    implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(5, Seconds), interval = Span(20, Millis))

    private var tomcat: Tomcat = _
    private var port: Int = 0
    private var savedEnv: DatrisEnvironment = _
    private val client = HttpClient.newHttpClient()
    private val entries = new ConcurrentLinkedQueue[AuditEntry]()
    private val store = new DecisionSitesStore

    private def baseEnv: DatrisEnvironment =
        ScopeDenialFixtures.auditEnv(logReads = true).copy(useUserAuth = true, useApiKeys = true, useAgentPolicy = true)

    private def install(e: DatrisEnvironment): Unit = {
        DecisionSitesDispatchSpec.env = e
        ScopeDenialFixtures.install(e)
    }

    override def beforeAll(): Unit = {
        assume(!sys.env.get("CAPABILITY_ENFORCEMENT").exists(_.equalsIgnoreCase("log-only")), "log-only set in this environment")
        assume(sys.env.get(CapabilityScopes.InertScopesEnv).isEmpty, CapabilityScopes.InertScopesEnv + " is set in this environment")
        savedEnv = DatrisEnvironment.values
        install(baseEnv)
        AuditLog.sinkOverride = (e: AuditEntry) => { entries.add(e); () }
        PolicyInterceptor.store = store

        val base = Files.createTempDirectory("decision-sites").toFile
        tomcat = new Tomcat()
        tomcat.setBaseDir(base.getAbsolutePath)
        tomcat.setPort(0)
        tomcat.getConnector.setProperty("address", "127.0.0.1")
        val ctx = tomcat.addContext("", base.getAbsolutePath)
        Seq("policyBody" -> new PolicyBodyCacheFilter, "auditBody" -> new AuditBodyCacheFilter).foreach { case (name, f) =>
            val fd = new FilterDef()
            fd.setFilterName(name)
            fd.setFilter(f)
            ctx.addFilterDef(fd)
            val fm = new FilterMap()
            fm.setFilterName(name)
            fm.addURLPattern("/*")
            ctx.addFilterMap(fm)
        }
        val app = new AnnotationConfigWebApplicationContext()
        app.register(classOf[DecisionSitesMvcConfig], classOf[DecisionSitesController])
        Tomcat.addServlet(ctx, "dispatcher", new DispatcherServlet(app)).setLoadOnStartup(1)
        ctx.addServletMappingDecoded("/", "dispatcher")
        tomcat.start()
        port = tomcat.getConnector.getLocalPort
    }

    override def afterAll(): Unit = {
        if (tomcat != null) { tomcat.stop(); tomcat.destroy() }
        AuditLog.sinkOverride = null
        AuditLog.acceptingWritesOverride = null
        PolicyIO.currentOverride = null
        PolicyInterceptor.store = PolicyInterceptor.MongoStore
        ScopeDenialFixtures.install(savedEnv)
    }

    override def beforeEach(): Unit = {
        install(baseEnv)
        entries.clear()
        store.clear()
        AuditLog.acceptingWritesOverride = null
        PolicyIO.currentOverride = AgentPolicy()
        sys.props -= CapabilityScopes.InertScopesProperty
    }

    // ------------------------------------------------------------ identities

    private def stored(label: String, raw: String*): ResolvedKey = {
        val (kept, inert) = CapabilityScopes.partition(Capability.parseList(raw))
        ResolvedKey(None, label, kept, isLegacyFullAccess = false, inertCapabilities = inert)
    }

    private def asKey(rk: ResolvedKey): String = { DecisionSitesDispatchSpec.keys.put(rk.label, rk); rk.label }

    private lazy val agent = asKey(stored("agent-key", "pipeline:read", "tap:read", "tap:run", "tap:delete", "pipeline:delete", "audit:read", "query:postgres"))
    private lazy val limited = asKey(stored("limited-key", "pipeline:read:catalog=Sales", "tap:read:catalog=Sales", "query:postgres"))
    private lazy val inertJob = asKey(stored("inert-job", "job:read:owner=self"))
    private lazy val decider = asKey(stored("decider-key", "approval:decide"))

    // ------------------------------------------------------------ requests

    private case class Resp(status: Int, body: String)

    private def send(caller: Option[String], method: String, rawPath: String, body: Option[String] = None, headers: Seq[(String, String)] = Nil): Resp = {
        val b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + rawPath))
        caller.foreach { c => b.header("x-test-identity", c); b.header("x-api-key", "test-key-value") }
        headers.foreach { case (k, v) => b.header(k, v) }
        val req = body match {
            case Some(s) => b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(s)).build()
            case None => b.method(method, HttpRequest.BodyPublishers.noBody()).build()
        }
        val r = client.send(req, HttpResponse.BodyHandlers.ofString())
        Resp(r.statusCode(), Option(r.body()).getOrElse(""))
    }

    /** Status of a request written on a socket (for a path java.net.URI refuses). */
    private def rawStatus(method: String, rawPath: String, body: String): Int = {
        val sock = new java.net.Socket("127.0.0.1", port)
        try {
            val bytes = body.getBytes("UTF-8")
            val head = method + " " + rawPath + " HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length +
                "\r\nx-test-identity: " + agent + "\r\nx-api-key: test-key-value\r\nConnection: close\r\n\r\n"
            val out = sock.getOutputStream
            out.write(head.getBytes("ISO-8859-1"))
            out.write(bytes)
            out.flush()
            val line = new java.io.BufferedReader(new java.io.InputStreamReader(sock.getInputStream, "ISO-8859-1")).readLine()
            line.split(" ")(1).toInt
        } finally sock.close()
    }

    private def letter(seg: String): String = {
        val i = seg.indexWhere(_.isLetter)
        seg.take(i) + "%%%02x".format(seg(i).toInt) + seg.drop(i + 1)
    }

    /** Spellings the dispatcher routes like `path`: a path parameter and a
      * percent-encoded letter, on the first, a middle and the last segment. */
    private def spellings(path: String): Seq[String] = {
        val segs = path.split("/").toSeq
        val idx = Seq(1, 3, segs.length - 1).distinct
        idx.flatMap(i => Seq(segs.updated(i, segs(i) + ";x=1").mkString("/"), segs.updated(i, letter(segs(i))).mkString("/")))
    }

    /** Spellings that change the last segment's text (a prefix test still sees the canonical prefix otherwise). */
    private def lastSegmentSpellings(path: String): Seq[String] = {
        val segs = path.split("/").toSeq
        val l = segs.length - 1
        Seq(segs.updated(l, letter(segs(l))).mkString("/"), segs.updated(l, letter(segs(l)) + ";x=1").mkString("/"))
    }

    private def firstSegmentSpellings(path: String): Seq[String] = {
        val segs = path.split("/").toSeq
        Seq(segs.updated(1, letter(segs(1))).mkString("/"), segs.updated(1, segs(1) + ";x=1").mkString("/"))
    }

    private def json(s: String) = JsonParser.parseString(s).getAsJsonObject

    private def auditFor(category: String, action: String): List[AuditEntry] = entries.asScala.toList.filter(e => e.category == category && e.action == action)

    // ================================================================

    test("the stand-in handlers' mappings are the application's own") {
        val real = RouteMatchingController.realMappings
        val mine = RouteMatchingController.mappingsOf(classOf[DecisionSitesController])
        assert(mine.nonEmpty)
        mine.foreach(m => assert(real.contains(m), s"$m is not a mapping of a real controller"))
    }

    test("policy: the agent policy decides on the matched route, however the request is spelled") {
        PolicyIO.currentOverride = AgentPolicy(actions = Map("tap:run" -> PolicyMode.Deny))
        for (p <- "/api/v1/tap/run" +: spellings("/api/v1/tap/run")) {
            val r = send(Some(agent), "POST", p, Some("{\"name\":\"t1\"}"))
            assert(r.status == 403 && json(r.body).get("errorKind").getAsString == "policy_denied", s"$p -> ${r.status} ${r.body}")
        }
    }

    test("audit strict gate: an audited request is refused while the log cannot take entries, however it is spelled") {
        install(baseEnv.copy(auditLogStrict = true))
        AuditLog.acceptingWritesOverride = java.lang.Boolean.FALSE
        for (p <- "/api/v1/pipelines" +: spellings("/api/v1/pipelines")) {
            val r = send(Some(agent), "GET", p)
            assert(r.status == 503, s"$p -> ${r.status} ${r.body}")
        }
    }

    test("audit strict gate: the audit-log status read stays open while the log cannot take entries, however it is spelled") {
        install(baseEnv.copy(auditLogStrict = true))
        AuditLog.acceptingWritesOverride = java.lang.Boolean.FALSE
        for (p <- "/api/v1/audit-log/status" +: (spellings("/api/v1/audit-log/status") ++ lastSegmentSpellings("/api/v1/audit-log/status"))) {
            val r = send(Some(agent), "GET", p)
            assert(r.status == 200, s"$p -> ${r.status} ${r.body}")
        }
    }

    test("audit record: a request is recorded under its matched route, with the resource named in its body") {
        for (p <- "/api/v1/tap/run" +: spellings("/api/v1/tap/run")) {
            entries.clear()
            assert(send(Some(agent), "POST", p, Some("{\"name\":\"t1\"}")).status == 200, p)
            eventually(assert(auditFor("tap", "run").nonEmpty, s"$p: ${entries.asScala.map(e => e.category + "/" + e.action)}"))
            assert(auditFor("tap", "run").head.resourceName.contains("t1"), s"$p: the body's name is read")
            assert(entries.asScala.forall(_.category != "unmapped"), p)
        }
    }

    test("audit denied row: a refused request's row names its matched route") {
        for (p <- "/api/v1/query/postgres" +: spellings("/api/v1/query/postgres")) {
            entries.clear()
            assert(send(Some(limited), "POST", p, Some("{\"sql\":\"select 1\"}")).status == 403, p)
            eventually(assert(entries.asScala.exists(e => e.category == "security" && e.action == "denied"), p))
            val d = entries.asScala.find(e => e.category == "security" && e.action == "denied").get
            val md = d.metadata.get
            assert(md.get("category").getAsString == "query" && md.get("action").getAsString == "postgres", s"$p: $md")
        }
    }

    test("audit inert-scope-used row: names its matched route") {
        sys.props(CapabilityScopes.InertScopesProperty) = "allow"
        try
            for (p <- "/api/v1/pipeline/status" +: spellings("/api/v1/pipeline/status")) {
                entries.clear()
                assert(send(Some(inertJob), "GET", p).status == 200, p)
                eventually(assert(entries.asScala.exists(_.action == "inert-scope-used"), p))
                val md = entries.asScala.find(_.action == "inert-scope-used").get.metadata.get
                assert(md.get("category").getAsString == "job" && md.get("action").getAsString == "read", s"$p: $md")
            }
        finally sys.props -= CapabilityScopes.InertScopesProperty
    }

    test("policy body filter: a request is cached for approval, however its first segment is spelled") {
        PolicyIO.currentOverride = AgentPolicy(actions = Map("tap:run" -> PolicyMode.Approve))
        for (p <- "/api/v1/tap/run" +: firstSegmentSpellings("/api/v1/tap/run")) {
            store.clear()
            val r = send(Some(agent), "POST", p, Some("{\"name\":\"t1\"}"))
            assert(r.status == 202, s"$p -> ${r.status} ${r.body}")
            assert(json(r.body).get("status").getAsString == "pending_approval", r.body)
        }
    }

    test("audit body filter: a request body is visible to the audit record, however its first segment is spelled") {
        for (p <- firstSegmentSpellings("/api/v1/tap/run")) {
            entries.clear()
            assert(send(Some(agent), "POST", p, Some("{\"name\":\"t2\"}")).status == 200, p)
            eventually(assert(auditFor("tap", "run").nonEmpty, p))
            assert(auditFor("tap", "run").head.resourceName.contains("t2"), s"$p: ${auditFor("tap", "run").head}")
        }
    }

    test("role: the version route is open without a session or key, however it is spelled") {
        for (p <- "/api/v1/version" +: (spellings("/api/v1/version") ++ lastSegmentSpellings("/api/v1/version"))) {
            val r = send(None, "GET", p)
            assert(r.status == 200, s"$p -> ${r.status} ${r.body}")
        }
    }

    test("role: a key holding a role-gated route's capability passes the role gate, however the route is spelled") {
        install(baseEnv.copy(useAgentPolicy = false))
        for (p <- "/api/v1/approvals/pa_1/approve" +: spellings("/api/v1/approvals/pa_1/approve")) {
            val r = send(Some(decider), "POST", p, Some("{}"))
            assert(r.status == 200, s"$p -> ${r.status} ${r.body}")
        }
        // and one without it is still refused
        assert(send(Some(agent), "POST", "/api/v1/approvals/pa_1/approve", Some("{}")).status == 403)
    }

    // ------------------------------------------------------------ approval replays

    private def approved(method: String, rawPath: String, body: String, name: Option[String], token: String = "tok123"): PendingAction = {
        val now = Instant.now()
        val pa = PendingAction(
            id = "pa_" + Math.abs(rawPath.hashCode) + "_" + method,
            action = "tap:run",
            resourceType = Some("tap"),
            resourceName = name,
            resourceVersion = None,
            actor = AuditActorInfo("api-key", agent),
            reason = None,
            agentSession = None,
            method = method,
            path = rawPath,
            query = None,
            contentType = Some("application/json"),
            body = Some(body),
            bodyHash = "unused",
            createdAt = now,
            expiresAt = now.plus(1, ChronoUnit.HOURS),
            state = PendingAction.Approved,
            replayTokenHash = Some(ai.datris.util.TokenHash.sha256Hex(token))
        )
        tokens.put(pa.id, token)
        store.put(pa)
        pa
    }

    /** The clear token of each approval built here, kept beside the record
      * (which holds only its hash), as the approving request holds it. */
    private val tokens = scala.collection.concurrent.TrieMap[String, String]()
    private def tokenOf(pa: PendingAction): String = tokens(pa.id)

    private def approvalHeader(pa: PendingAction) = Seq(PolicyReplay.HeaderApproval -> (pa.id + "." + tokenOf(pa)))

    test("replay: the approved request skips the gate once, and its audit row carries the approval") {
        PolicyIO.currentOverride = AgentPolicy(actions = Map("tap:run" -> PolicyMode.Deny))
        val pa = approved("POST", "/api/v1/tap/run", "{\"name\":\"t1\"}", Some("t1"))
        val r = send(Some(agent), "POST", "/api/v1/tap/run", Some("{\"name\":\"t1\"}"), approvalHeader(pa))
        assert(r.status == 200, r.body)
        assert(store.consumed.contains(pa.id))
        eventually(assert(auditFor("tap", "run").exists(_.metadata.exists(m => m.has("approvalId") && m.get("approvalId").getAsString == pa.id))))
        // single use
        val again = send(Some(agent), "POST", "/api/v1/tap/run", Some("{\"name\":\"t1\"}"), approvalHeader(pa))
        assert(again.status == 403 && json(again.body).get("errorKind").getAsString == "policy_replay_rejected", again.body)
    }

    test("replay: a valid approval on another route, method or resource is gated as if it carried none") {
        PolicyIO.currentOverride =
            AgentPolicy(actions = Map("tap:run" -> PolicyMode.Deny, "pipeline:delete" -> PolicyMode.Deny, "tap:delete" -> PolicyMode.Deny))
        val pa = approved("POST", "/api/v1/tap/run", "{\"name\":\"t1\"}", Some("t1"))
        val others = Seq(
            ("POST", "/api/v1/pipelines/p1/rename", "{}"),
            ("DELETE", "/api/v1/tap?name=t1", "{}"),
            ("POST", "/api/v1/tap/run", "{\"name\":\"t9\"}")
        )
        for ((m, p, b) <- others) {
            entries.clear()
            val r = send(Some(agent), m, p, Some(b), approvalHeader(pa))
            assert(r.status == 403 && json(r.body).get("errorKind").getAsString == "policy_denied", s"$m $p -> ${r.status} ${r.body}")
            assert(!store.consumed.contains(pa.id), s"$m $p consumed the approval")
            Thread.sleep(50)
            assert(!entries.asScala.exists(_.metadata.exists(_.has("approvalId"))), s"$m $p stamped the approval")
        }
        // the approved request itself still replays
        assert(send(Some(agent), "POST", "/api/v1/tap/run", Some("{\"name\":\"t1\"}"), approvalHeader(pa)).status == 200)
    }

    test("replay: the replay client's request matches its approval for every spelling that can have been queued") {
        install(baseEnv.copy(useApiKeys = false))
        PolicyReplay.port = port
        for (raw <- "/api/v1/tap/run" +: spellings("/api/v1/tap/run")) {
            val pa = approved("POST", raw, "{\"name\":\"t1\"}", Some("t1"), token = "tok" + Math.abs(raw.hashCode))
            val res = PolicyReplay.execute(pa, tokenOf(pa), None)
            assert(res.status == 200, s"$raw -> ${res.status} ${res.body}")
            assert(store.consumed.contains(pa.id), raw)
        }
    }

    test("replay: an approval is honoured on any spelling of the approved request, and only once") {
        PolicyIO.currentOverride = AgentPolicy(actions = Map("tap:run" -> PolicyMode.Deny))
        for (p <- spellings("/api/v1/tap/run")) {
            val pa = approved("POST", "/api/v1/tap/run", "{\"name\":\"t1\"}", Some("t1"), token = "tok" + Math.abs(p.hashCode))
            val r = send(Some(agent), "POST", p, Some("{\"name\":\"t1\"}"), approvalHeader(pa))
            assert(r.status == 200, s"$p -> ${r.status} ${r.body}")
            assert(store.consumed.contains(pa.id), p)
        }
    }

    // ------------------------------------------------------------ queueing

    test("queue: two spellings of one request are one pending action") {
        PolicyIO.currentOverride = AgentPolicy(actions = Map("tap:run" -> PolicyMode.Approve))
        val first = json(send(Some(agent), "POST", "/api/v1/tap/run", Some("{\"name\":\"t1\"}")).body)
        assert(first.get("status").getAsString == "pending_approval" && !first.get("duplicate").getAsBoolean)
        for (p <- spellings("/api/v1/tap/run")) {
            val o = json(send(Some(agent), "POST", p, Some("{\"name\":\"t1\"}")).body)
            assert(o.get("approvalId").getAsString == first.get("approvalId").getAsString && o.get("duplicate").getAsBoolean, s"$p: $o")
        }
        assert(store.pendingCount == 1)
    }

    test("queue: an approval pending at upgrade (hashed the earlier way) is found, not queued again") {
        PolicyIO.currentOverride = AgentPolicy(actions = Map("tap:run" -> PolicyMode.Approve))
        val raw = lastSegmentSpellings("/api/v1/tap/run").head
        val body = "{\"name\":\"t1\"}"
        val now = Instant.now()
        val legacy = PendingAction(
            id = "pa_legacy",
            action = "tap:run",
            resourceType = Some("tap"),
            resourceName = Some("t1"),
            resourceVersion = None,
            actor = AuditActorInfo("api-key", agent),
            reason = None,
            agentSession = None,
            method = "POST",
            path = raw,
            query = None,
            contentType = Some("application/json"),
            body = Some(body),
            bodyHash = PendingAction.hashOf(agent, "POST", raw, None, Some(body)),
            createdAt = now,
            expiresAt = now.plus(1, ChronoUnit.HOURS),
            state = PendingAction.Pending
        )
        store.put(legacy)
        val o = json(send(Some(agent), "POST", raw, Some(body)).body)
        assert(o.get("approvalId").getAsString == "pa_legacy" && o.get("duplicate").getAsBoolean, o.toString)
        assert(store.pendingCount == 1)
        // every other spelling of the same request finds it too
        for (p <- "/api/v1/tap/run" +: spellings("/api/v1/tap/run")) {
            val o2 = json(send(Some(agent), "POST", p, Some(body)).body)
            assert(o2.get("approvalId").getAsString == "pa_legacy" && o2.get("duplicate").getAsBoolean, s"$p: $o2")
        }
        assert(store.pendingCount == 1)
    }

    test("queue: a new pending action is stored with the current hash version and its delimited hash") {
        PolicyIO.currentOverride = AgentPolicy(actions = Map("tap:run" -> PolicyMode.Approve))
        val body = "{\"name\":\"t1\"}"
        val o = json(send(Some(agent), "POST", "/api/v1/tap/run", Some(body)).body)
        val pa = store.get(o.get("approvalId").getAsString).get
        assert(pa.hashVersion.contains(PendingAction.CurrentHashVersion))
        assert(pa.bodyHash == PendingAction.delimitedHashOf(agent, "POST", "/api/v1/tap/run", None, Some(body)))
    }

    test("queue: an expired pending action is not handed back as a duplicate; a fresh approval is queued") {
        PolicyIO.currentOverride = AgentPolicy(actions = Map("tap:run" -> PolicyMode.Approve))
        val body = "{\"name\":\"t1\"}"
        val past = Instant.now().minus(2, ChronoUnit.HOURS)
        def stale(id: String, hashVersion: Option[Int]) = PendingAction(
            id = id,
            action = "tap:run",
            resourceType = Some("tap"),
            resourceName = Some("t1"),
            resourceVersion = None,
            actor = AuditActorInfo("api-key", agent),
            reason = None,
            agentSession = None,
            method = "POST",
            path = "/api/v1/tap/run",
            query = None,
            contentType = Some("application/json"),
            body = Some(body),
            bodyHash =
                if (hashVersion.isDefined) PendingAction.delimitedHashOf(agent, "POST", "/api/v1/tap/run", None, Some(body))
                else PendingAction.hashOf(agent, "POST", "/api/v1/tap/run", None, Some(body)),
            createdAt = past,
            // past its expiry, not yet removed by the store's TTL monitor
            expiresAt = past.plus(1, ChronoUnit.HOURS),
            state = PendingAction.Pending,
            hashVersion = hashVersion
        )
        for (hv <- Seq(Some(PendingAction.CurrentHashVersion), None)) {
            store.clear()
            store.put(stale("pa_stale", hv))
            val o = json(send(Some(agent), "POST", "/api/v1/tap/run", Some(body)).body)
            assert(o.get("status").getAsString == "pending_approval" && !o.get("duplicate").getAsBoolean, s"$hv: $o")
            assert(o.get("approvalId").getAsString != "pa_stale", s"$hv: $o")
            assert(Instant.parse(o.get("expiresAt").getAsString).isAfter(Instant.now()), s"$hv: $o")
        }
    }

    test("replay: the replay client replays a path-named approval once, with encoded characters in the name") {
        install(baseEnv.copy(useApiKeys = false))
        PolicyReplay.port = port
        val pa = approved("POST", "/api/v1/pipelines/a%20b%2Bc/rename", "{}", Some("a b+c"), token = "tokpath")
        val res = PolicyReplay.execute(pa, tokenOf(pa), None)
        assert(res.status == 200 && res.body == "handled", s"${res.status} ${res.body}")
        assert(store.consumed.contains(pa.id))
        val again = PolicyReplay.execute(pa, tokenOf(pa), None)
        assert(again.status == 403 && json(again.body).get("errorKind").getAsString == "policy_replay_rejected", again.body)
    }

    test("replay: the replay client replays a query-named approval once") {
        install(baseEnv.copy(useApiKeys = false))
        PolicyReplay.port = port
        val pa = approved("DELETE", "/api/v1/tap", "{}", Some("t1"), token = "tokquery").copy(query = Some("name=t1"), contentType = None, body = None)
        store.put(pa)
        val res = PolicyReplay.execute(pa, tokenOf(pa), None)
        assert(res.status == 200 && res.body == "handled", s"${res.status} ${res.body}")
        assert(store.consumed.contains(pa.id))
        val again = PolicyReplay.execute(pa, tokenOf(pa), None)
        assert(again.status == 403 && json(again.body).get("errorKind").getAsString == "policy_replay_rejected", again.body)
    }

    test("queue: a request the dispatcher does not route to a handler is not an action: it is not queued and gets the not-found answer") {
        PolicyIO.currentOverride = AgentPolicy(actions = Map("tap:run" -> PolicyMode.Approve))
        val r = send(Some(agent), "POST", "/api/v1/tap/run/", Some("{\"name\":\"t1\"}"))
        assert(r.status == 404, s"${r.status} ${r.body}")
        assert(store.pendingCount == 0)
    }

    test("queue: a request with an empty path segment is not routed, so it is neither gated nor queued") {
        PolicyIO.currentOverride = AgentPolicy(actions = Map("tap:run" -> PolicyMode.Approve))
        val segs = "/api/v1/tap/run".split("/").toSeq
        for (i <- 1 until segs.length) {
            val p = (segs.take(i + 1) ++ ("" +: segs.drop(i + 1))).mkString("/")
            store.clear()
            val r = send(Some(agent), "POST", p, Some("{\"name\":\"t1\"}"))
            assert(r.status == 404, s"$p -> ${r.status} ${r.body}")
            assert(store.pendingCount == 0, p)
        }
    }

    // ------------------------------------------------------------ log lines

    test("a request path carrying an encoded line break produces one capability log line, with no line break in it") {
        val logger = LoggerFactory.getLogger(classOf[CapabilityInterceptor]).asInstanceOf[ch.qos.logback.classic.Logger]
        val appender = new ListAppender[ILoggingEvent]()
        appender.start()
        logger.addAppender(appender)
        try {
            for ((m, p) <- Seq("POST" -> "/api/v1/pipelines/a%0Aline-two-marker/rename", "POST" -> "/api/v1/pipelines/b%0D%0Aline-two-marker/rename")) {
                appender.list.clear()
                send(Some(agent), m, p, if (m == "POST") Some("{}") else None)
                val mine = appender.list.asScala.toList.filter(_.getFormattedMessage.contains("line-two-marker"))
                assert(mine.size == 1, s"$p: ${mine.map(_.getFormattedMessage)}")
                val msg = mine.head.getFormattedMessage
                assert(!msg.contains("\n") && !msg.contains("\r"), msg)
                assert(msg.contains(p), s"logged as sent: $msg")
            }
        } finally logger.detachAppender(appender)
    }

    test("a path that cannot be decoded is left to the dispatcher by the body-caching filters") {
        val raw = "/api/v1/tap/run;x=%zz"
        val r = ScopeDenialFixtures.request(null, "POST", raw)._1
        org.mockito.Mockito.when(r.getContentType).thenReturn("application/json")
        org.mockito.Mockito.when(r.getContentLengthLong).thenReturn(10L)
        assert(RequestPaths.path(r) == null)
        assert(DecisionSitesFilters.policySkips(r))
        assert(DecisionSitesFilters.auditSkips(r))
        // through the server: an answer comes back (the dispatcher's), not a filter failure
        val status = rawStatus("POST", raw, "{\"name\":\"t1\"}")
        assert(status >= 400 && status != 403, s"$status")
    }
}

object DecisionSitesDispatchSpec {
    @volatile var env: DatrisEnvironment = _
    val keys = new ConcurrentHashMap[String, ResolvedKey]()
}

/** Calls the filters' protected `shouldNotFilter`. */
object DecisionSitesFilters {
    private class P extends PolicyBodyCacheFilter { def skip(r: HttpServletRequest): Boolean = shouldNotFilter(r) }
    private class A extends AuditBodyCacheFilter { def skip(r: HttpServletRequest): Boolean = shouldNotFilter(r) }
    def policySkips(r: HttpServletRequest): Boolean = new P().skip(r)
    def auditSkips(r: HttpServletRequest): Boolean = new A().skip(r)
}

/** In-memory pending actions. */
class DecisionSitesStore extends PolicyInterceptor.Store {
    private val m = new ConcurrentHashMap[String, PendingAction]()
    val consumed = new java.util.concurrent.ConcurrentSkipListSet[String]()
    def clear(): Unit = { m.clear(); consumed.clear() }
    def put(pa: PendingAction): Unit = m.put(pa.id, pa)
    def pendingCount: Int = m.values().asScala.count(_.state == PendingAction.Pending)
    def get(id: String): Option[PendingAction] = Option(m.get(id))
    def consumeReplay(id: String, token: String): Option[PendingAction] = synchronized {
        Option(m.get(id)).filter(pa => PolicyInterceptor.replayable(pa, token)).map { pa =>
            val c = pa.copy(replayConsumedAt = Some(Instant.now()))
            m.put(id, c)
            consumed.add(id)
            c
        }
    }
    def findPendingDuplicate(hash: String, actorLabel: String): Option[PendingAction] =
        m.values().asScala.find(pa => pa.state == PendingAction.Pending && pa.bodyHash == hash && pa.actor.label == actorLabel)
    def countPending(actorLabel: String): Long = m.values().asScala.count(pa => pa.state == PendingAction.Pending && pa.actor.label == actorLabel).toLong
    def listPending(actorLabel: String): List[PendingAction] =
        m.values().asScala.filter(pa => pa.state == PendingAction.Pending && pa.actor.label == actorLabel).toList
    def insert(pa: PendingAction): Unit = m.put(pa.id, pa)
    def currentVersion(resourceType: String, name: String): Option[Int] = None
}

/** Attaches the test identity the way TenantInterceptor does for a key. */
class DecisionSitesIdentity extends HandlerInterceptor {
    override def preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean = {
        TenantContext.set(DecisionSitesDispatchSpec.env)
        Option(request.getHeader("x-test-identity")).flatMap(id => Option(DecisionSitesDispatchSpec.keys.get(id))).foreach { rk =>
            request.setAttribute(TenantInterceptor.ResolvedKeyAttr, rk)
        }
        true
    }
    override def afterCompletion(request: HttpServletRequest, response: HttpServletResponse, handler: Any, ex: Exception): Unit = {
        UserContext.clear()
        TenantContext.clear()
    }
}

@Configuration
@EnableWebMvc
class DecisionSitesMvcConfig extends WebMvcConfigurer {
    // As Spring Boot does: every unmatched path goes to the resource handler
    // (here with nothing to serve), so it answers 404 after the interceptors.
    override def addResourceHandlers(registry: org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry): Unit =
        registry.addResourceHandler("/**").addResourceLocations("classpath:/decision-sites-no-static/")

    override def addInterceptors(registry: InterceptorRegistry): Unit = {
        registry.addInterceptor(new DecisionSitesIdentity).addPathPatterns("/api/**")
        registry.addInterceptor(new CapabilityInterceptor).addPathPatterns("/api/**")
        registry.addInterceptor(new PolicyInterceptor).addPathPatterns("/api/**")
        registry.addInterceptor(new RoleEnforcementInterceptor).addPathPatterns("/api/**")
        registry.addInterceptor(new AuditInterceptor).addPathPatterns("/api/**")
    }
}

/** Stand-in handlers with the application's own mappings and role gates. */
@RestController
@RequestMapping(Array("/api/v1"))
class DecisionSitesController {
    @PostMapping(path = Array("/tap/run")) def tapRun(@RequestBody b: String): String = "handled"
    @PostMapping(path = Array("/pipelines/{name}/rename")) def rename(@PathVariable("name") n: String): String = "handled"
    @DeleteMapping(path = Array("/tap")) def deleteTap(): String = "handled"
    @GetMapping(path = Array("/pipelines")) def pipelines(): String = "handled"
    @GetMapping(path = Array("/pipeline/status")) def status(): String = "handled"
    @GetMapping(path = Array("/audit-log/status")) def auditStatus(): String = "handled"
    @GetMapping(path = Array("/version")) def version(): String = "handled"
    @PostMapping(path = Array("/approvals/{id}/approve")) @RequiresRole(Array("admin", "editor")) def approve(@PathVariable("id") id: String): String =
        "handled"
    @PostMapping(path = Array("/query/postgres")) def postgres(@RequestBody b: String): String = "handled"
}
