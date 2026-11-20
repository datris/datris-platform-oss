package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.ScopeDenialFixtures
import ai.datris.model.{DatrisEnvironment, DatrisException, MongoDBConfig, ResolvedKey, User}
import ai.datris.util.{APIKeyValidator, MongoDBQueryUtil}
import com.google.gson.JsonParser
import com.mongodb.client.{FindIterable, MongoClient, MongoCollection, MongoCursor, MongoDatabase}
import org.bson.Document
import org.bson.conversions.Bson
import org.mockito.ArgumentMatchers.{any, anyInt, anyString}
import org.mockito.Mockito.{mock, never, verify, when, RETURNS_SELF}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.atomic.AtomicInteger

/** The MongoDB query route reads only the data databases: never the
  * platform's internal database nor MongoDB's own system databases, for any
  * caller and in single- and multi-tenant installs. The decision is made on
  * the database the query runs against, before any client is created. The
  * projection is checked with the filter's operator rule; the limit rule is
  * unchanged. No MongoDB is reached: the client factory is replaced. */
class MongoQueryDatabaseRuleSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val Internal = "oss"
    private val UserDefault = "datris"

    private var saved: DatrisEnvironment = _
    private val created = new AtomicInteger()
    private var lastDatabase: String = _
    private var closed = 0

    private def env(multiTenant: Boolean, environment: String = "oss"): DatrisEnvironment =
        ScopeDenialFixtures.auditEnv().copy(
            environment = environment,
            useApiKeys = false,
            useAuditLog = false,
            multiTenant = multiTenant,
            mongoDbConfig = MongoDBConfig("mongodb://unused", UserDefault, Internal)
        )

    /** A client whose find returns no documents; records the database used. */
    private def fakeClient(): MongoClient = {
        created.incrementAndGet()
        val cursor = mock(classOf[MongoCursor[Document]])
        when(cursor.hasNext).thenReturn(false)
        val find = mock(classOf[FindIterable[Document]], RETURNS_SELF)
        when(find.iterator()).thenReturn(cursor)
        val coll = mock(classOf[MongoCollection[Document]])
        when(coll.find(any(classOf[Bson]))).thenReturn(find)
        val db = mock(classOf[MongoDatabase])
        when(db.getCollection(anyString())).thenReturn(coll)
        val client = mock(classOf[MongoClient])
        when(client.getDatabase(anyString())).thenAnswer { i =>
            lastDatabase = i.getArgument[String](0); db
        }
        org.mockito.Mockito.doAnswer(_ => { closed += 1; null }).when(client).close()
        client
    }

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        created.set(0)
        lastDatabase = null
        closed = 0
        MongoDBQueryUtil.clientFactoryOverride = () => fakeClient()
        ScopeDenialFixtures.install(env(multiTenant = false))
    }

    override def afterEach(): Unit = {
        MongoDBQueryUtil.clientFactoryOverride = null
        ScopeDenialFixtures.install(saved)
    }

    private def caseVariants(name: String): Seq[String] =
        Seq(name, name.toUpperCase, name.capitalize, name.zipWithIndex.map { case (c, i) => if (i % 2 == 0) c.toUpper else c }.mkString, " " + name + " ")

    private val ReservedNames: Seq[String] = Seq(Internal, "admin", "local", "config")

    private def body(database: Any = null, filter: Any = null, projection: Any = null, limit: Any = null): java.util.Map[String, Any] = {
        val m = new java.util.LinkedHashMap[String, Any]()
        m.put("collection", "items")
        if (database != null) m.put("database", database)
        if (filter != null) m.put("filter", filter)
        if (projection != null) m.put("projection", projection)
        if (limit != null) m.put("limit", limit)
        m
    }

    private def jmap(kv: (String, Any)*): java.util.Map[String, Any] = {
        val m = new java.util.LinkedHashMap[String, Any]()
        kv.foreach { case (k, v) => m.put(k, v) }
        m
    }

    private def jlist(vs: Any*): java.util.List[Any] = {
        val l = new java.util.ArrayList[Any]()
        vs.foreach(l.add)
        l
    }

    private def assertRefused(resp: org.springframework.http.ResponseEntity[String], what: String): Unit = {
        assert(resp.getStatusCode.value == 400, what + " -> " + resp.getStatusCode + " " + resp.getBody)
        val json = JsonParser.parseString(resp.getBody).getAsJsonObject
        assert(json.get("error").getAsString == MongoDBQueryUtil.DatabaseNotAvailableMessage, what)
        assert(json.keySet().size() == 1, what)
    }

    // ---------------------------------------------------------- callers

    private def session(role: String): ResolvedKey = APIKeyValidator.resolveFromSession(User("u", "h", role, null, null, null))
    private val callers: Seq[(String, ResolvedKey)] = Seq(
        "viewer session" -> session(User.RoleViewer),
        "admin session" -> session(User.RoleAdmin),
        "query:mongodb key" -> ScopeDenialFixtures.key("mongo-reader", "query:mongodb"),
        "full-access key" -> ScopeDenialFixtures.key("all", "*:*"),
        "legacy full-access key" -> ResolvedKey(None, "legacy", Nil, isLegacyFullAccess = true)
    )

    // The handler takes no identity: the route gate decides who reaches it.
    // Each caller below holds query:mongodb, so the gate admits it, and the
    // handler's answer is then the same for all of them.
    test("every caller tried here holds query:mongodb, so the route gate admits it") {
        callers.foreach { case (n, rk) => assert(rk.grants("query", "mongodb"), n) }
    }

    test("the internal database and each system database are refused in every case variant, for every caller, before any client is created") {
        val c = new QueryAPIController()
        for ((who, _) <- callers; name <- ReservedNames; variant <- caseVariants(name)) {
            val resp = c.queryMongoDB(null, body(database = variant))
            assertRefused(resp, s"$who '$variant'")
        }
        assert(created.get == 0)
    }

    test("the refusal is the same whether or not the database exists, and comes before the filter and projection checks") {
        val c = new QueryAPIController()
        val a = c.queryMongoDB(null, body(database = "admin"))
        val b = c.queryMongoDB(null, body(database = "ADMIN"))
        val d = c.queryMongoDB(null, body(database = "Oss", filter = jmap("$where" -> "x"), projection = jmap("a" -> jmap("$function" -> jmap()))))
        assert(a.getBody == b.getBody && b.getBody == d.getBody)
        assertRefused(d, "with blocked operators")
        assert(created.get == 0)
    }

    test("the internal database name comes from the platform's configuration, not a fixed name") {
        ScopeDenialFixtures.install(env(multiTenant = false).copy(mongoDbConfig = MongoDBConfig("mongodb://unused", UserDefault, "platformstate")))
        val c = new QueryAPIController()
        assertRefused(c.queryMongoDB(null, body(database = "PlatformState")), "configured internal name")
        assert(c.queryMongoDB(null, body(database = "oss")).getStatusCode.value == 200)
        assert(lastDatabase == "oss")
        assert(created.get == 1)
    }

    test("the user-facing default and an ordinary named database are read, with one client that is closed") {
        val c = new QueryAPIController()
        val r1 = c.queryMongoDB(null, body())
        assert(r1.getStatusCode.value == 200, r1.getBody)
        assert(lastDatabase == UserDefault)
        val r2 = c.queryMongoDB(null, body(database = "analytics"))
        assert(r2.getStatusCode.value == 200, r2.getBody)
        assert(lastDatabase == "analytics")
        // A name that merely contains a reserved one is an ordinary database.
        val r3 = c.queryMongoDB(null, body(database = "localdata"))
        assert(r3.getStatusCode.value == 200, r3.getBody)
        assert(created.get == 3 && closed == 3)
    }

    test("multi-tenant: the query runs on the tenant's own database whatever database is named") {
        ScopeDenialFixtures.install(env(multiTenant = true, environment = "acme"))
        val c = new QueryAPIController()
        for (name <- ReservedNames; variant <- caseVariants(name)) {
            val resp = c.queryMongoDB(null, body(database = variant))
            assert(resp.getStatusCode.value == 200, variant + " " + resp.getBody)
            assert(lastDatabase == "acme", variant)
        }
    }

    test("multi-tenant: a tenant whose own database name is the internal or a system database is refused, in every case variant") {
        val c = new QueryAPIController()
        for (name <- ReservedNames; variant <- caseVariants(name).map(_.trim)) {
            ScopeDenialFixtures.install(env(multiTenant = true, environment = variant))
            for ((who, _) <- callers) {
                assertRefused(c.queryMongoDB(null, body()), s"tenant '$variant' $who")
                assertRefused(c.queryMongoDB(null, body(database = UserDefault)), s"tenant '$variant' $who named db")
            }
        }
        assert(created.get == 0)
    }

    test("resolveQueryDatabase and databaseRefused agree with the handler") {
        val single = env(multiTenant = false)
        assert(MongoDBQueryUtil.resolveQueryDatabase(null, single) == UserDefault)
        assert(MongoDBQueryUtil.resolveQueryDatabase("", single) == UserDefault)
        assert(MongoDBQueryUtil.resolveQueryDatabase("x", single) == "x")
        val multi = env(multiTenant = true, environment = "acme")
        assert(MongoDBQueryUtil.resolveQueryDatabase("admin", multi) == "acme")
        for (name <- ReservedNames; v <- caseVariants(name)) assert(MongoDBQueryUtil.databaseRefused(v, Internal), v)
        for (ok <- Seq(UserDefault, "analytics", "localdata", "admins", "oss2")) assert(!MongoDBQueryUtil.databaseRefused(ok, Internal), ok)
        assert(!MongoDBQueryUtil.databaseRefused("oss", null))
        assert(MongoDBQueryUtil.databaseRefused("admin", null))
    }

    // ---------------------------------------------------------- projection

    test("the projection is refused for each operator the filter refuses, at the top, nested and in arrays") {
        for (op <- MongoDBQueryUtil.BLOCKED_OPERATORS.toSeq) {
            val shapes: Seq[java.util.Map[String, Any]] = Seq(
                jmap(op -> "x"),
                jmap("a" -> jmap(op -> jmap("body" -> "x"))),
                jmap("a" -> jmap("$cond" -> jlist(true, jmap(op -> jmap()), 0))),
                jmap("a" -> jlist(jlist(jmap("b" -> jmap(op -> 1)))))
            )
            for (shape <- shapes) {
                val f = intercept[DatrisException](MongoDBQueryUtil.validateOperators(shape, "filter"))
                assert(f.getMessage == "MongoDB filter contains blocked operator: " + op)
                val p = intercept[DatrisException](MongoDBQueryUtil.validateOperators(shape, "projection"))
                assert(p.getMessage == "MongoDB projection contains blocked operator: " + op)
                assert(intercept[DatrisException](MongoDBQueryUtil.query("items", null, shape, 5, null)).getMessage.contains("projection"))
            }
        }
        assert(created.get == 0)
    }

    test("projections in the forms callers use are accepted and passed to the find") {
        val accepted: Seq[java.util.Map[String, Any]] = Seq(
            jmap("name" -> 1, "_id" -> 0),
            jmap("name" -> java.lang.Double.valueOf(1.0), "status" -> true, "secret" -> false),
            jmap("items" -> jmap("$slice" -> 3)),
            jmap("items" -> jmap("$elemMatch" -> jmap("qty" -> jmap("$gt" -> 1)))),
            jmap("score" -> jmap("$meta" -> "textScore")),
            jmap("full" -> jmap("$concat" -> jlist("$first", " ", "$last")))
        )
        accepted.foreach(p => MongoDBQueryUtil.validateOperators(p, "projection"))
        val c = new QueryAPIController()
        accepted.foreach(p => assert(c.queryMongoDB(null, body(projection = p)).getStatusCode.value == 200, p))
        assert(created.get == accepted.size)
    }

    // ---------------------------------------------------------- limit

    test("the limit rule: negative is unlimited, zero is the preview default, a positive value is applied as given") {
        assert(MongoDBQueryUtil.effectiveLimit(-1).isEmpty)
        assert(MongoDBQueryUtil.effectiveLimit(-50).isEmpty)
        assert(MongoDBQueryUtil.effectiveLimit(0).contains(20))
        assert(MongoDBQueryUtil.effectiveLimit(7).contains(7))
        assert(MongoDBQueryUtil.effectiveLimit(250000).contains(250000))
    }

    test("an unlimited query applies no limit to the find; a bounded one applies it") {
        val find = mock(classOf[FindIterable[Document]], RETURNS_SELF)
        val cursor = mock(classOf[MongoCursor[Document]])
        when(find.iterator()).thenReturn(cursor)
        val coll = mock(classOf[MongoCollection[Document]])
        when(coll.find(any(classOf[Bson]))).thenReturn(find)
        val db = mock(classOf[MongoDatabase])
        when(db.getCollection(anyString())).thenReturn(coll)
        val client = mock(classOf[MongoClient])
        when(client.getDatabase(anyString())).thenReturn(db)
        MongoDBQueryUtil.clientFactoryOverride = () => client
        MongoDBQueryUtil.query("items", null, null, -1, null)
        verify(find, never()).limit(anyInt())
        MongoDBQueryUtil.query("items", null, null, 12, null)
        verify(find).limit(12)
        verify(client, org.mockito.Mockito.times(2)).close()
    }
}
