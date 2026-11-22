package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditClassifier, AuditEntry, AuditLog}
import ai.datris.auth.{CapabilityScopes, RequestPaths, ScopeDenialFixtures}
import ai.datris.model.{Capability, DatrisEnvironment, ResolvedKey, TenantContext, User, UserContext}
import ai.datris.policy.PolicyRoutes
import ai.datris.util.APIKeyValidator
import ai.datris.util.InMemoryUserDb.grants
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.apache.catalina.startup.Tomcat
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.web.bind.annotation._
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.{DispatcherServlet, HandlerInterceptor}
import org.springframework.web.servlet.config.annotation.{EnableWebMvc, InterceptorRegistry, WebMvcConfigurer}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._
import scala.collection.mutable

/** Access decisions use the route the dispatcher matched, never the raw
  * request URI: a path parameter, a percent-encoded character or HEAD for
  * GET does not change any decision.
  *
  * Runs a real embedded Tomcat (loopback, ephemeral port) with a real Spring
  * MVC DispatcherServlet, the real [[CapabilityInterceptor]] and
  * [[RoleEnforcementInterceptor]], and a stand-in controller whose mappings
  * are the application's own (checked against the real controllers'
  * annotations below), so each request is presented to the interceptors
  * exactly as in production. A handler that runs answers 200 `handled`; it
  * touches nothing else. Identities are attached by a test interceptor in
  * place of TenantInterceptor / SessionAuthenticator. A recording interceptor
  * captures what the agent policy and the audit classifier decide for the
  * same dispatcher-presented request. */
class RequestRouteMatchingSpec extends AnyFunSuite with BeforeAndAfterAll {

    private var tomcat: Tomcat = _
    private var port: Int = 0
    private var savedEnv: DatrisEnvironment = _
    private val client = HttpClient.newHttpClient()

    override def beforeAll(): Unit = {
        assume(!sys.env.get("CAPABILITY_ENFORCEMENT").exists(_.equalsIgnoreCase("log-only")), "log-only set in this environment")
        savedEnv = DatrisEnvironment.values
        RequestRouteMatchingSpec.env = ScopeDenialFixtures.auditEnv().copy(useUserAuth = true, useApiKeys = true)
        ScopeDenialFixtures.install(RequestRouteMatchingSpec.env)
        AuditLog.sinkOverride = (_: AuditEntry) => ()
        sys.props -= CapabilityScopes.InertScopesProperty

        val base = Files.createTempDirectory("route-matching").toFile
        tomcat = new Tomcat()
        tomcat.setBaseDir(base.getAbsolutePath)
        tomcat.setPort(0)
        tomcat.getConnector.setProperty("address", "127.0.0.1")
        val ctx = tomcat.addContext("", base.getAbsolutePath)
        val app = new AnnotationConfigWebApplicationContext()
        app.register(classOf[RouteMatchingMvcConfig], classOf[RouteMatchingController], classOf[RouteMatchingUndecidedController])
        Tomcat.addServlet(ctx, "dispatcher", new DispatcherServlet(app)).setLoadOnStartup(1)
        ctx.addServletMappingDecoded("/", "dispatcher")
        tomcat.start()
        port = tomcat.getConnector.getLocalPort
    }

    override def afterAll(): Unit = {
        if (tomcat != null) { tomcat.stop(); tomcat.destroy() }
        AuditLog.sinkOverride = null
        ScopeDenialFixtures.install(savedEnv)
    }

    // ------------------------------------------------------------ identities

    private def stored(label: String, raw: String*): ResolvedKey = {
        val (kept, inert) = CapabilityScopes.partition(Capability.parseList(raw))
        ResolvedKey(None, label, kept, isLegacyFullAccess = false, inertCapabilities = inert)
    }

    private val PartCCaps = Seq("metadata:read", "query:postgres", "query:mongodb", "query:natural")

    private val limitedKey = stored("limited", Seq("pipeline:read:catalog=Sales", "tap:read:catalog=Sales", "tap:run", "pipeline:delete") ++ PartCCaps: _*)
    private val lackingKey = stored("lacking", "pipeline:read:catalog=Sales")
    private val unscopedKey = stored("unscoped", Seq("pipeline:read", "tap:read", "tap:run", "pipeline:delete") ++ PartCCaps: _*)
    private val restricted = User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "own"))
    private val viewer = User("vic", "h", User.RoleViewer, null, null, null)
    private val editor = User("ed", "h", User.RoleEditor, null, null, null)

    private def asKey(id: String, rk: ResolvedKey): String = { RequestRouteMatchingSpec.keys.put(id, rk); id }
    private def asUser(id: String, u: User): String = { RequestRouteMatchingSpec.users.put(id, u); id }

    private lazy val callers: Seq[String] = Seq(
        asKey("limited", limitedKey),
        asKey("lacking", lackingKey),
        asKey("unscoped", unscopedKey),
        asUser("restricted", restricted),
        asUser("viewer", viewer),
        asUser("editor", editor)
    )

    // ------------------------------------------------------------ requests

    private def send(caller: String, method: String, rawPath: String): (Int, String) = {
        val b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + rawPath)).header("x-test-identity", caller)
        if (RequestRouteMatchingSpec.keys.containsKey(caller)) b.header("x-api-key", "test-key-value")
        val req =
            if (method == "GET" || method == "HEAD") b.method(method, HttpRequest.BodyPublishers.noBody()).build()
            else b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString("{\"name\":\"x\"}")).build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        (resp.statusCode(), Option(resp.body()).getOrElse(""))
    }

    /** Spellings of `path` the dispatcher routes to the same handler: a path
      * parameter on the last and on a middle segment, a percent-encoded
      * letter, and both together. */
    private def variants(path: String): Seq[String] = {
        val segs = path.split("/").toSeq // "", "api", "v1", ...
        val last = segs.length - 1
        val mid = 3
        def withParam(i: Int) = segs.updated(i, segs(i) + ";x=1").mkString("/")
        def encodeLetter(i: Int) = {
            val s = segs(i)
            val idx = s.indexWhere(_.isLetter)
            segs.updated(i, s.take(idx) + "%%%02x".format(s(idx).toInt) + s.drop(idx + 1)).mkString("/")
        }
        val both = {
            val s = segs(last)
            val idx = s.indexWhere(_.isLetter)
            segs.updated(last, s.take(idx) + "%%%02x".format(s(idx).toInt) + s.drop(idx + 1) + ";y=2").updated(mid, segs(mid) + ";z").mkString("/")
        }
        Seq(withParam(last), withParam(mid), encodeLetter(last), encodeLetter(mid), both)
    }

    /** The routes exercised: the five Part C patterns, admin-gated routes, a
      * policy-gated and audited mutation, and a route with a name segment. */
    private val Routes: Seq[(String, String)] = Seq(
        "GET" -> "/api/v1/metadata/postgres/tables",
        "GET" -> "/api/v1/metadata/mongodb/collections",
        "GET" -> "/api/v1/unity-catalog/browse",
        "POST" -> "/api/v1/query/postgres",
        "POST" -> "/api/v1/query/mongodb",
        "POST" -> "/api/v1/query/natural",
        "GET" -> "/api/v1/keys",
        "GET" -> "/api/v1/auth/users",
        "POST" -> "/api/v1/tap/run",
        "POST" -> "/api/v1/pipelines/SalesOrders/rename"
    )

    private def decision(r: (Int, String)): String = r._1 match {
        case 200 if r._2 == "handled" || r._2.isEmpty => "handled"
        case s => "refused " + s
    }

    test("the stand-in controller's mappings are the application's own") {
        val real = RouteMatchingController.realMappings
        val mine = RouteMatchingController.mappingsOf(classOf[RouteMatchingController])
        assert(mine.nonEmpty)
        mine.foreach(m => assert(real.contains(m), s"$m is not a mapping of a real controller"))
    }

    test("the canonical routes decide as the access rules say") {
        callers
        assert(decision(send("limited", "POST", "/api/v1/query/postgres")) == "refused 403")
        assert(decision(send("limited", "GET", "/api/v1/metadata/postgres/tables")) == "refused 403")
        assert(decision(send("unscoped", "POST", "/api/v1/query/postgres")) == "handled")
        assert(decision(send("lacking", "POST", "/api/v1/query/postgres")) == "refused 403")
        assert(decision(send("restricted", "GET", "/api/v1/unity-catalog/browse")) == "refused 403")
        assert(decision(send("viewer", "POST", "/api/v1/tap/run")) == "refused 403")
        assert(decision(send("editor", "POST", "/api/v1/tap/run")) == "handled")
        assert(decision(send("editor", "GET", "/api/v1/keys")) == "refused 403")
        assert(decision(send("editor", "GET", "/api/v1/auth/users")) == "refused 403")
        assert(decision(send("editor", "POST", "/api/v1/pipelines/SalesOrders/rename")) == "handled")
    }

    test("a path parameter or a percent-encoded character does not change the access decision") {
        for (caller <- callers; (method, path) <- Routes) {
            val expected = decision(send(caller, method, path))
            for (v <- variants(path)) {
                val got = decision(send(caller, method, v))
                assert(got == expected, s"$caller $method $v -> $got, but $path -> $expected")
            }
        }
    }

    test("HEAD on a GET route gets the decision GET gets") {
        for (caller <- callers; (method, path) <- Routes if method == "GET"; p <- path +: variants(path)) {
            val expected = decision(send(caller, "GET", path))
            val got = decision(send(caller, "HEAD", p))
            assert(got == expected, s"$caller HEAD $p -> $got, but GET $path -> $expected")
        }
    }

    test("the agent policy action and the audit classification of a request are those of its canonical route") {
        for ((method, path) <- Routes; v <- path +: variants(path); m <- if (method == "GET") Seq("GET", "HEAD") else Seq(method)) {
            RequestRouteMatchingSpec.recorded.clear()
            send("unscoped", m, v)
            val seen = RequestRouteMatchingSpec.recorded.asScala.toList
            assert(seen.nonEmpty, s"$m $v reached no interceptor")
            val (path0, action, audit) = seen.head
            assert(path0 == path, s"$m $v matched as $path0")
            assert(action == PolicyRoutes.actionKey(method, path), s"$m $v policy action $action")
            assert(audit == AuditClassifier.classify(method, path, logReads = true), s"$m $v audit $audit")
        }
    }

    test("before dispatch (the body-caching filters) the request path is read the same way") {
        for ((method, path) <- Routes; v <- path +: variants(path)) {
            val r = ScopeDenialFixtures.request(null, method, v)._1
            assert(RequestPaths.path(r) == path, v)
        }
        assert(RequestPaths.path(
            ScopeDenialFixtures.request(null, "POST", "/api/v1/pipelines/Sales%20Orders%3BQ1/rename")._1
        ) == "/api/v1/pipelines/Sales Orders;Q1/rename")
        assert(RequestPaths.path(null) == null)
        assert(RequestPaths.method("head") == "GET" && RequestPaths.method("OPTIONS") == "OPTIONS" && RequestPaths.method(null: String) == null)
    }

    test("a controller method with no access decision is refused to everyone but full access") {
        callers
        val full = asKey("full", ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true))
        val (status, body) = send("unscoped", "GET", "/api/v1/route-without-decision")
        assert(status == 403, body)
        assert(body.contains("\"required\":\"*:*\""), body)
        assert(decision(send("limited", "GET", "/api/v1/route-without-decision;x=1")) == "refused 403")
        assert(decision(send("editor", "GET", "/api/v1/route-without-decision")) == "refused 403")
        assert(decision(send(full, "GET", "/api/v1/route-without-decision")) == "handled")
        assert(decision(send(full, "HEAD", "/api/v1/route-without-decision")) == "handled")
    }

    test("OPTIONS is answered by the dispatcher without reaching a controller method, for every caller") {
        for (caller <- callers; (_, path) <- Routes) {
            val (status, body) = send(caller, "OPTIONS", path)
            assert(status == 200 && body != "handled", s"$caller OPTIONS $path -> $status $body")
        }
    }

    test("a name segment keeps its letter case and characters") {
        RequestRouteMatchingSpec.recorded.clear()
        send("unscoped", "POST", "/api/v1/pipelines/Sales%20Orders%3BQ1/rename")
        assert(RequestRouteMatchingSpec.recorded.asScala.head._1 == "/api/v1/pipelines/Sales Orders;Q1/rename")
        assert(decision(send("unscoped", "POST", "/api/v1/pipelines/Sales%20Orders%3BQ1/rename")) == "handled")
        assert(decision(send("editor", "POST", "/api/v1/pipelines/MixedCase_Name-2/rename")) == "handled")
    }
}

object RequestRouteMatchingSpec {
    @volatile var env: DatrisEnvironment = _
    val keys = new ConcurrentHashMap[String, ResolvedKey]()
    val users = new ConcurrentHashMap[String, User]()
    val recorded = new java.util.concurrent.ConcurrentLinkedQueue[(String, Option[String], Option[ai.datris.audit.AuditRoute])]()
}

/** Attaches the test identity the way TenantInterceptor (a key) and
  * SessionAuthenticator (a session) do. */
class RouteMatchingIdentity extends HandlerInterceptor {
    override def preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean = {
        TenantContext.set(RequestRouteMatchingSpec.env)
        val id = request.getHeader("x-test-identity")
        Option(RequestRouteMatchingSpec.keys.get(id)).foreach(rk => request.setAttribute(TenantInterceptor.ResolvedKeyAttr, rk))
        Option(RequestRouteMatchingSpec.users.get(id)).foreach { u =>
            UserContext.set(u)
            request.setAttribute(TenantInterceptor.ResolvedKeyAttr, APIKeyValidator.resolveFromSession(u))
        }
        true
    }
    override def afterCompletion(request: HttpServletRequest, response: HttpServletResponse, handler: Any, ex: Exception): Unit = {
        UserContext.clear()
        TenantContext.clear()
    }
}

/** Records what the policy and audit decisions see for the request. */
class RouteMatchingRecorder extends HandlerInterceptor {
    override def preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean = {
        val m = RequestPaths.method(request)
        val p = RequestPaths.path(request)
        RequestRouteMatchingSpec.recorded.add((p, PolicyRoutes.actionKey(m, p), AuditClassifier.classify(m, p, logReads = true)))
        true
    }
}

@Configuration
@EnableWebMvc
class RouteMatchingMvcConfig extends WebMvcConfigurer {
    override def addInterceptors(registry: InterceptorRegistry): Unit = {
        registry.addInterceptor(new RouteMatchingRecorder).addPathPatterns("/api/**")
        registry.addInterceptor(new RouteMatchingIdentity).addPathPatterns("/api/**")
        registry.addInterceptor(new CapabilityInterceptor).addPathPatterns("/api/**")
        registry.addInterceptor(new RoleEnforcementInterceptor).addPathPatterns("/api/**")
    }
}

/** Stand-in handlers with the application's own mappings (and role gates). */
@RestController
@RequestMapping(Array("/api/v1"))
class RouteMatchingController {
    @GetMapping(path = Array("/metadata/postgres/tables")) def pgTables(): String = "handled"
    @GetMapping(path = Array("/metadata/mongodb/collections")) def mongoCollections(): String = "handled"
    @GetMapping(path = Array("/unity-catalog/browse")) def browse(): String = "handled"
    @PostMapping(path = Array("/query/postgres")) def postgres(@RequestBody b: String): String = "handled"
    @PostMapping(path = Array("/query/mongodb")) def mongo(@RequestBody b: String): String = "handled"
    @PostMapping(path = Array("/query/natural")) def natural(@RequestBody b: String): String = "handled"
    @GetMapping(path = Array("/keys")) @RequiresRole(Array("admin")) def keys(): String = "handled"
    @GetMapping(path = Array("/auth/users")) @RequiresRole(Array("admin")) def users(): String = "handled"
    @PostMapping(path = Array("/tap/run")) def tapRun(@RequestBody b: String): String = "handled"
    @PostMapping(path = Array("/pipelines/{name}/rename")) def rename(@PathVariable("name") n: String): String = "handled"
}

/** A handler with no capability row or skip pattern (only in this spec). */
@RestController
class RouteMatchingUndecidedController {
    @GetMapping(path = Array("/api/v1/route-without-decision")) def undecided(): String = "handled"
}

object RouteMatchingController {

    /** (method, full pattern) for every mapping on `cls`. */
    def mappingsOf(cls: Class[_]): Set[(String, String)] = {
        val prefix = Option(AnnotatedElementUtils.findMergedAnnotation(cls, classOf[RequestMapping]))
            .flatMap(a => (a.path().toSeq ++ a.value().toSeq).headOption).getOrElse("")
        cls.getDeclaredMethods.toSeq.flatMap { m =>
            Option(AnnotatedElementUtils.findMergedAnnotation(m, classOf[RequestMapping])).toSeq.flatMap { a =>
                val paths = (a.path().toSeq ++ a.value().toSeq).distinct match { case Seq() => Seq(""); case ps => ps }
                for (meth <- a.method().toSeq.map(_.name()); p <- paths) yield (meth, prefix + p)
            }
        }.toSet
    }

    /** The mappings of the application's controllers. */
    lazy val realMappings: Set[(String, String)] = {
        val scanner = new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(new org.springframework.core.`type`.filter.AnnotationTypeFilter(classOf[org.springframework.stereotype.Controller]))
        scanner.findCandidateComponents("ai.datris.api").asScala.toSet[org.springframework.beans.factory.config.BeanDefinition]
            .flatMap(bd => mappingsOf(Class.forName(bd.getBeanClassName)))
    }
}
