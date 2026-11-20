package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.ScopeDenialFixtures
import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, MongoDBLoader, MongoDBQueryUtil, MongoReservedNames}
import com.google.gson.JsonParser
import com.mongodb.client.{MongoClient, MongoCollection, MongoCursor, MongoDatabase, MongoIterable}
import jakarta.servlet.http.HttpServletRequest
import org.bson.Document
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.{mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

/** The platform's internal database, MongoDB's system databases and
  * `system.` collections are never a pipeline destination: saves and
  * restores refuse them (an unchanged stored block included), the MongoDB
  * loader fails the run before connecting, truncating or writing, and a
  * delete leaves such a collection alone with a warning line. The metadata
  * listings neither list nor open them. No MongoDB is reached: the client
  * factories are replaced and count what they build. */
class MongoReservedDestinationSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val Internal = "oss"
    private var saved: DatrisEnvironment = _
    private val clients = ArrayBuffer[String]() // one entry per client built
    private val opened = ArrayBuffer[String]() // database names opened
    private val dropped = ArrayBuffer[String]() // db.collection dropped
    private var listed: Seq[String] = Seq.empty

    private def env(multiTenant: Boolean = false, environment: String = "oss"): DatrisEnvironment =
        ScopeDenialFixtures.auditEnv().copy(
            environment = environment,
            useApiKeys = false,
            useAuditLog = false,
            multiTenant = multiTenant,
            postgresDatabase = if (multiTenant) environment else "datris",
            mongoDbConfig = MongoDBConfig("mongodb://unused", "datris", Internal)
        )

    private def cursorOf[T](items: Seq[T]): MongoCursor[T] = {
        val it = items.iterator
        val c = mock(classOf[MongoCursor[T]])
        when(c.hasNext).thenAnswer(_ => it.hasNext)
        when(c.next()).thenAnswer(_ => it.next())
        c
    }

    private def fakeClient(): MongoClient = {
        clients += "client"
        val client = mock(classOf[MongoClient])
        when(client.listDatabaseNames()).thenAnswer { _ =>
            val names = mock(classOf[MongoIterable[String]])
            when(names.iterator()).thenAnswer(_ => cursorOf(listed))
            names
        }
        when(client.getDatabase(anyString())).thenAnswer { i =>
            val name = i.getArgument[String](0)
            opened += name
            val db = mock(classOf[MongoDatabase])
            when(db.getCollection(anyString())).thenAnswer { j =>
                val coll = mock(classOf[MongoCollection[Document]])
                val cn = j.getArgument[String](0)
                org.mockito.Mockito.doAnswer(_ => { dropped += name + "." + cn; null }).when(coll).drop()
                coll
            }
            when(db.listCollectionNames()).thenAnswer { _ =>
                val names = mock(classOf[MongoIterable[String]])
                when(names.iterator()).thenAnswer(_ => cursorOf(Seq("orders")))
                names
            }
            db
        }
        client
    }

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        clients.clear(); opened.clear(); dropped.clear(); listed = Seq.empty
        PipelineAPIController.mongoDropClientOverride = () => fakeClient()
        MetadataAPIController.mongoClientOverride = () => fakeClient()
        MongoDBQueryUtil.clientFactoryOverride = () => fakeClient()
        ScopeDenialFixtures.install(env())
    }

    override def afterEach(): Unit = {
        PipelineAPIController.mongoDropClientOverride = null
        MetadataAPIController.mongoClientOverride = null
        MongoDBQueryUtil.clientFactoryOverride = null
        ScopeDenialFixtures.install(saved)
    }

    // ------------------------------------------------------------ fixtures

    private def variants(name: String): Seq[String] =
        Seq(name, name.toUpperCase, name.capitalize, name.zipWithIndex.map { case (c, i) => if (i % 2 == 0) c.toUpper else c }.mkString)

    private val ReservedDbs: Seq[String] = Seq(Internal, "admin", "local", "config").flatMap(variants)
    private val ReservedCollections: Seq[String] = Seq("system.js", "System.Views", "SYSTEM.profile", "system.buckets.x")

    private def mongoDb(dbName: String = "datris", table: String = "orders", truncate: Boolean = false, tx: Boolean = true): Database =
        Database(dbName = dbName, table = table, useMongoDB = true, truncateBeforeWrite = truncate, useTransaction = tx)

    private def structured(db: Database, name: String = "docs_in"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = "Sales",
            createdByKeyLabel = "someone",
            source = Source(
                schemaProperties = SchemaProperties(null, java.util.Arrays.asList(SchemaField("_json", "string"))),
                fileAttributes = FileAttributes(jsonAttributes = JsonAttributes(everyRowContainsObject = true))
            ),
            destination = Destination(schemaProperties = SchemaProperties(null, java.util.Arrays.asList(SchemaField("_json", "string"))), database = db)
        )

    private def unstructured(db: Database, name: String = "docs_u"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = "Sales",
            createdByKeyLabel = "someone",
            source = Source(fileAttributes = FileAttributes(unstructuredAttributes = UnstructuredAttributes(fileExtension = "pdf"))),
            destination = Destination(objectStore = ObjectStore(prefixKey = "docs"), database = db)
        )

    private def req(rk: ResolvedKey): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("POST")
        when(r.getRequestURI).thenReturn("/api/v1/pipeline/version/restore")
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def session(role: String): ResolvedKey = APIKeyValidator.resolveFromSession(User("u", "h", role, null, null, null))
    private val callers: Seq[(String, HttpServletRequest)] = Seq(
        "admin session" -> req(session(User.RoleAdmin)),
        "editor session" -> req(session(User.RoleEditor)),
        "full-access key" -> req(ScopeDenialFixtures.key("all", "*:*")),
        "legacy full-access key" -> req(ResolvedKey(None, "legacy", Nil, isLegacyFullAccess = true))
    )

    private def errorOf(r: ResponseEntity[String]): String = JsonParser.parseString(r.getBody).getAsJsonObject.get("error").getAsString

    private def assertRefused(r: Option[ResponseEntity[String]], clue: String): Unit = {
        assert(r.isDefined, clue + ": not refused")
        assert(r.get.getStatusCode.value == 400, clue + ": " + r)
        assert(errorOf(r.get) == MongoReservedNames.DestinationRefusal, clue + ": " + errorOf(r.get))
    }

    private def reservedBlocks: Seq[(String, Database)] =
        ReservedDbs.map(d => ("db " + d, mongoDb(dbName = d))) ++ ReservedCollections.map(c => ("collection " + c, mongoDb(table = c)))

    // ------------------------------------------------------------ save and restore

    test("a reserved MongoDB destination is refused on save: new, changed, and an unchanged stored block, on every pipeline type") {
        for (
            (what, db) <- reservedBlocks;
            (kind, mk) <- Seq[(String, Database => PipelineConfig)]("structured" -> (structured(_)), "unstructured" -> (unstructured(_)))
        ) {
            val cfg = mk(db)
            assertRefused(PipelineAPIController.databaseNameRefusal(cfg, null), s"$kind $what new")
            assertRefused(PipelineAPIController.databaseNameRefusal(cfg, mk(mongoDb())), s"$kind $what changed")
            assertRefused(PipelineAPIController.databaseNameRefusal(cfg, cfg), s"$kind $what unchanged re-save")
            // a catalog restore saves the archived config through the same rule, over the live one
            assertRefused(PipelineAPIController.databaseNameRefusal(cfg.copy(version = 4), cfg), s"$kind $what catalog restore")
        }
    }

    test("a version restore of a reserved MongoDB destination is refused for an admin, an editor and full-access keys") {
        for ((what, db) <- reservedBlocks; (who, r) <- callers) {
            val snap = structured(db).copy(version = 2)
            assertRefused(EntityVersionAPIController.pipelineRestoreRefusal(r, snap, null, Nil), s"$what $who deleted")
            assertRefused(EntityVersionAPIController.pipelineRestoreRefusal(r, snap, snap.copy(version = 3), Nil), s"$what $who over identical live")
        }
    }

    test("multi-tenant: a tenant whose own database is reserved cannot save a MongoDB destination") {
        ScopeDenialFixtures.install(env(multiTenant = true, environment = "Admin"))
        assertRefused(PipelineAPIController.databaseNameRefusal(structured(mongoDb(dbName = "acme")), null), "tenant Admin")
        ScopeDenialFixtures.install(env(multiTenant = true, environment = "acme"))
        assert(PipelineAPIController.databaseNameRefusal(structured(mongoDb(dbName = "acme")), null).isEmpty)
    }

    test("ordinary MongoDB destinations and other engines are unaffected") {
        for (db <- Seq(mongoDb(), mongoDb(dbName = "analytics"), mongoDb(table = "a.b"), mongoDb(table = "systemic"), mongoDb(dbName = "localdata")))
            assert(PipelineAPIController.databaseNameRefusal(structured(db), null).isEmpty, db)
        // a Postgres block named like a reserved MongoDB database is not a MongoDB destination
        val pg = Database(dbName = "admin", schema = "public", table = "system", usePostgres = true)
        assert(MongoReservedNames.destinationProblem(pg, DatrisEnvironment.current).isEmpty)
        assert(PipelineAPIController.databaseNameRefusal(structured(pg), null).isEmpty)
        assert(MongoReservedNames.destinationProblem(null, DatrisEnvironment.current).isEmpty)
    }

    test("the internal database name comes from the platform's configuration") {
        ScopeDenialFixtures.install(env().copy(mongoDbConfig = MongoDBConfig("mongodb://unused", "datris", "platformstate")))
        assertRefused(PipelineAPIController.databaseNameRefusal(structured(mongoDb(dbName = "PlatformState")), null), "configured name")
        assert(PipelineAPIController.databaseNameRefusal(structured(mongoDb(dbName = "oss")), null).isEmpty)
    }

    // ------------------------------------------------------------ load and truncate

    private def load(cfg: PipelineConfig): Throwable = {
        val ctx = JobContext(null, null, null, cfg, null, null, null, null)
        intercept[Throwable](new MongoDBLoader(ctx).process())
    }

    test("the loader fails a run with a reserved destination before it connects, truncates or writes") {
        for ((what, db) <- reservedBlocks; truncate <- Seq(false, true); tx <- Seq(true, false)) {
            val e = load(structured(db.copy(truncateBeforeWrite = truncate, useTransaction = tx)))
            assert(e.isInstanceOf[DatrisException] && e.getMessage == MongoReservedNames.DestinationRefusal, s"$what truncate=$truncate tx=$tx: $e")
        }
        // a stored config with a blank database writes to the default one, checked too
        ScopeDenialFixtures.install(env().copy(postgresDatabase = "OSS"))
        assert(load(structured(mongoDb(dbName = ""))).getMessage == MongoReservedNames.DestinationRefusal)
    }

    test("the loader goes past the check for an ordinary destination") {
        // With no status util in the job context, getting past the check fails on it.
        val e = load(structured(mongoDb(truncate = true)))
        assert(e.isInstanceOf[NullPointerException], e)
    }

    // ------------------------------------------------------------ delete

    test("delete with data leaves a reserved collection alone, with one warning line and no client") {
        for ((what, db) <- reservedBlocks) {
            assert(!PipelineAPIController.dropMongoDestination(db), what)
            val w = PipelineAPIController.mongoReservedDeleteWarning(structured(db))
            assert(w.isDefined && !w.get.contains("\n"), what)
        }
        assert(clients.isEmpty && dropped.isEmpty)
    }

    test("multi-tenant delete drops from the tenant's database, and never when that database is reserved") {
        ScopeDenialFixtures.install(env(multiTenant = true, environment = "acme"))
        assert(PipelineAPIController.dropMongoDestination(mongoDb(dbName = "anything")))
        assert(dropped == Seq("acme.orders"))
        ScopeDenialFixtures.install(env(multiTenant = true, environment = "Local"))
        assert(!PipelineAPIController.dropMongoDestination(mongoDb(dbName = "acme")))
        assert(PipelineAPIController.mongoReservedDeleteWarning(structured(mongoDb(dbName = "acme"))).isDefined)
        assert(clients.size == 1)
    }

    test("delete with data drops an ordinary collection with one client, and gives no warning") {
        assert(PipelineAPIController.dropMongoDestination(mongoDb(dbName = "analytics", table = "a.b")))
        assert(dropped == Seq("analytics.a.b") && clients.size == 1)
        assert(PipelineAPIController.mongoReservedDeleteWarning(structured(mongoDb())).isEmpty)
        assert(!PipelineAPIController.dropMongoDestination(Database(dbName = "x", schema = "s", table = "t", usePostgres = true)))
    }

    // ------------------------------------------------------------ metadata

    private def assertNotAvailable(r: ResponseEntity[String], clue: String): Unit = {
        assert(r.getStatusCode.value == 400, clue + ": " + r)
        assert(errorOf(r) == MongoDBQueryUtil.DatabaseNotAvailableMessage, clue)
    }

    test("the collection listing of a reserved database is the query route's 400, before any client") {
        val c = new MetadataAPIController()
        for (d <- ReservedDbs) assertNotAvailable(c.getMongoCollections(null, d), d)
        ScopeDenialFixtures.install(env(multiTenant = true, environment = "Config"))
        assertNotAvailable(c.getMongoCollections(null, null), "tenant Config")
        assert(clients.isEmpty)
    }

    test("listings omit the internal database by its configured name and the system databases") {
        ScopeDenialFixtures.install(env(environment = "dev").copy(mongoDbConfig = MongoDBConfig("mongodb://unused", "datris", "platformstate")))
        listed = Seq("admin", "Config", "local", "platformstate", "PlatformState", "dev", "datris", "analytics")
        val c = new MetadataAPIController()
        val dbs = JsonParser.parseString(c.getMongoDatabases(null).getBody).getAsJsonArray.asScala.map(_.getAsString).toList
        assert(dbs == List("analytics", "datris"), dbs)
        val all = JsonParser.parseString(c.getMongoCollections(null, null).getBody).getAsJsonArray.asScala.map(_.getAsString).toList
        assert(!opened.exists(MongoReservedNames.databaseReserved(_, "platformstate")), opened)
        assert(all.contains("datris.orders") && all.contains("analytics.orders"), all)
        val named = c.getMongoCollections(null, "analytics")
        assert(named.getStatusCode.value == 200 && named.getBody.contains("orders"), named)
    }
}
