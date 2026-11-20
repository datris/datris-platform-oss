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
    // collections each database lists (default: one data collection)
    private var collectionsOf: String => Seq[String] = (_: String) => Seq("orders")

    private def env(multiTenant: Boolean = false, environment: String = "oss"): DatrisEnvironment =
        ai.datris.util.PlatformCollectionFixtures.named(
            ScopeDenialFixtures.auditEnv().copy(
                environment = environment,
                useApiKeys = false,
                useAuditLog = false,
                multiTenant = multiTenant,
                postgresDatabase = if (multiTenant) environment else "datris",
                mongoDbConfig = MongoDBConfig("mongodb://unused", "datris", Internal)
            )
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
                when(names.iterator()).thenAnswer(_ => cursorOf(collectionsOf(name)))
                names
            }
            db
        }
        client
    }

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        clients.clear(); opened.clear(); dropped.clear(); listed = Seq.empty; collectionsOf = (_: String) => Seq("orders")
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

    private val ReservedDbs: Seq[String] = Seq("admin", "local", "config").flatMap(variants)
    private val ReservedCollections: Seq[String] = Seq("system.js", "System.Views", "SYSTEM.profile", "system.buckets.x")

    /** Platform collections of this (single-tenant) environment, current and legacy. */
    private def platformCollections: Seq[String] =
        env().platformCollectionNames ++ env().legacyPlatformCollectionNames

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

    /** Protected destinations: a system database, a system. collection, a
      * platform collection in the internal database (any case of its name). */
    private def reservedBlocks: Seq[(String, Database)] =
        ReservedDbs.map(d => ("db " + d, mongoDb(dbName = d))) ++ ReservedCollections.map(c => ("collection " + c, mongoDb(table = c))) ++
            (for (d <- variants(Internal); c <- platformCollections) yield ("platform " + d + "." + c, mongoDb(dbName = d, table = c)))

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
        assert(MongoReservedNames.destinationProblem(pg, DatrisEnvironment.current, null).isEmpty)
        assert(PipelineAPIController.databaseNameRefusal(structured(pg), null).isEmpty)
        assert(MongoReservedNames.destinationProblem(null, DatrisEnvironment.current, null).isEmpty)
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
        assert(load(structured(mongoDb(dbName = "", table = "oss-tap"))).getMessage == MongoReservedNames.DestinationRefusal)
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

    test("listings omit the system databases, and the internal database (by its configured name) when it holds only platform collections") {
        val e = env(environment = "dev").copy(mongoDbConfig = MongoDBConfig("mongodb://unused", "datris", "platformstate"))
        ScopeDenialFixtures.install(e)
        listed = Seq("admin", "Config", "local", "platformstate", "datris", "analytics")
        collectionsOf = db => if (db == "platformstate") e.platformCollectionNames :+ "system.js" else Seq("orders")
        val c = new MetadataAPIController()
        val dbs = JsonParser.parseString(c.getMongoDatabases(null).getBody).getAsJsonArray.asScala.map(_.getAsString).toList
        assert(dbs == List("analytics", "datris"), dbs)
        val all = JsonParser.parseString(c.getMongoCollections(null, null).getBody).getAsJsonArray.asScala.map(_.getAsString).toList
        assert(!opened.exists(MongoReservedNames.systemDatabase), opened)
        assert(all.sorted == List("analytics.orders", "datris.orders"), all)
        val named = c.getMongoCollections(null, "analytics")
        assert(named.getStatusCode.value == 200 && named.getBody.contains("orders"), named)
    }

    // ------------------------------------------------------------ data collections in the internal database

    private val internalData = mongoDb(dbName = Internal, table = "legacy_orders")

    test(
        "a stored pipeline with a data collection in the internal database saves unchanged, restores over the same live destination, loads and is dropped on delete"
    ) {
        for ((kind, mk) <- Seq[(String, Database => PipelineConfig)]("structured" -> (structured(_)), "unstructured" -> (unstructured(_)))) {
            val stored = mk(internalData)
            assert(PipelineAPIController.databaseNameRefusal(stored, stored).isEmpty, kind + " unchanged re-save")
            assert(
                PipelineAPIController.databaseNameRefusal(stored.copy(version = 9, tags = java.util.Arrays.asList("x")), stored).isEmpty,
                kind + " other edits"
            )
            for ((who, r) <- callers)
                assert(EntityVersionAPIController.pipelineRestoreRefusal(r, stored.copy(version = 2), stored, Nil).isEmpty, s"$kind $who restore")
        }
        for (truncate <- Seq(false, true); tx <- Seq(true, false))
            assert(load(structured(internalData.copy(truncateBeforeWrite = truncate, useTransaction = tx))).isInstanceOf[NullPointerException])
        assert(PipelineAPIController.mongoReservedDeleteWarning(structured(internalData)).isEmpty)
        assert(PipelineAPIController.dropMongoDestination(internalData))
        assert(dropped == Seq("oss.legacy_orders"))
    }

    test("a new pipeline, a changed destination, or a restore onto a different live destination may not use the internal database") {
        for (d <- variants(Internal)) {
            val cfg = structured(mongoDb(dbName = d, table = "legacy_orders"))
            assertRefused(PipelineAPIController.databaseNameRefusal(cfg, null), d + " new")
            assertRefused(PipelineAPIController.databaseNameRefusal(cfg, structured(mongoDb())), d + " moved in")
            assertRefused(PipelineAPIController.databaseNameRefusal(structured(mongoDb(dbName = d, table = "other")), cfg), d + " other collection")
            for ((who, r) <- callers) {
                assertRefused(EntityVersionAPIController.pipelineRestoreRefusal(r, cfg.copy(version = 2), null, Nil), s"$d $who restore of deleted")
                assertRefused(
                    EntityVersionAPIController.pipelineRestoreRefusal(r, cfg.copy(version = 2), structured(mongoDb()), Nil),
                    s"$d $who restore over other"
                )
            }
        }
    }

    test("an install whose data database is the internal database keeps using it for data collections, never platform ones") {
        ScopeDenialFixtures.install(env().copy(mongoDbConfig = MongoDBConfig("mongodb://unused", Internal, Internal)))
        val cfg = structured(mongoDb(dbName = Internal, table = "orders"))
        assert(PipelineAPIController.databaseNameRefusal(cfg, null).isEmpty)
        assert(PipelineAPIController.databaseNameRefusal(structured(mongoDb(dbName = Internal, table = "other")), cfg).isEmpty)
        for (c <- platformCollections) assertRefused(PipelineAPIController.databaseNameRefusal(structured(mongoDb(dbName = Internal, table = c)), null), c)
        val q = new QueryAPIController()
        assert(q.queryMongoDB(null, { val m = new java.util.HashMap[String, Any](); m.put("collection", "orders"); m }).getStatusCode.value != 400)
        assert(opened.last == Internal)
        assert(MongoReservedNames.sharedDatabaseWarning(MongoDBConfig("x", Internal, Internal)).isDefined)
        assert(MongoReservedNames.sharedDatabaseWarning(MongoDBConfig("x", "OSS ", Internal)).isDefined)
        assert(MongoReservedNames.sharedDatabaseWarning(MongoDBConfig("x", "datris", Internal)).isEmpty)
    }

    test("platform collections are refused for every caller in the current environment and another tenant's, also multi-tenant") {
        ScopeDenialFixtures.install(env(multiTenant = true, environment = "acme"))
        val mt = env(multiTenant = true, environment = "acme")
        for (c <- mt.platformCollectionNames ++ env(environment = "tenant-b").platformCollectionNames; (who, r) <- callers) {
            val cfg = structured(mongoDb(dbName = Internal, table = c))
            assertRefused(PipelineAPIController.databaseNameRefusal(cfg, cfg), s"$c unchanged")
            assertRefused(EntityVersionAPIController.pipelineRestoreRefusal(r, cfg.copy(version = 2), cfg, Nil), s"$c $who")
            assert(load(cfg).getMessage == MongoReservedNames.DestinationRefusal, c)
        }
        assert(clients.isEmpty)
    }

    test("listings of the internal database show its data collections and never platform or system. ones") {
        collectionsOf =
            db => if (db.equalsIgnoreCase(Internal)) env().platformCollectionNames ++ Seq("system.views", "legacy_orders", "acme-tap-log") else Seq("orders")
        listed = Seq(Internal, "datris")
        val c = new MetadataAPIController()
        val named = JsonParser.parseString(c.getMongoCollections(null, "OSS").getBody).getAsJsonArray.asScala.map(_.getAsString).toList
        assert(named == List("acme-tap-log", "legacy_orders"), named)
        val dbs = JsonParser.parseString(c.getMongoDatabases(null).getBody).getAsJsonArray.asScala.map(_.getAsString).toList
        assert(dbs == List("datris", Internal), dbs)
        val all = JsonParser.parseString(c.getMongoCollections(null, null).getBody).getAsJsonArray.asScala.map(_.getAsString).toList
        assert(all.sorted == List("datris.orders", "oss.acme-tap-log", "oss.legacy_orders"), all)
    }

    test("collection names are matched exactly: a differently-cased platform name is a data collection") {
        val e = env()
        assert(MongoReservedNames.platformCollection("oss-user", e))
        assert(!MongoReservedNames.platformCollection("OSS-USER", e))
        assert(!MongoReservedNames.platformCollection("oss-user ", e))
        assert(MongoReservedNames.platformCollection("x-y-pipeline-status-summary", e.copy(multiTenant = true)))
        assert(!MongoReservedNames.platformCollection("x-y-pipeline-status-summary", e))
        assert(!MongoReservedNames.platformCollection("pipeline", e))
    }

    // ------------------------------------------------------------ wiring

    test("delete with data reaches the guarded drop: an ordinary collection is dropped through it, a protected one is not") {
        val ctl = new PipelineAPIController()
        assert(!ctl.cleanupDestinationData(structured(mongoDb(dbName = "analytics")), Set.empty, Set.empty, None))
        assert(dropped == Seq("analytics.orders"), dropped)
        dropped.clear(); clients.clear()
        ctl.cleanupDestinationData(structured(mongoDb(dbName = Internal, table = "oss-user")), Set.empty, Set.empty, None)
        ctl.cleanupDestinationData(structured(mongoDb(dbName = "admin")), Set.empty, Set.empty, None)
        assert(dropped.isEmpty && clients.isEmpty)
    }

    private def source(rel: String): String =
        new String(
            java.nio.file.Files.readAllBytes(
                Seq(java.nio.file.Paths.get("src/main/scala/" + rel), java.nio.file.Paths.get("datrisserver/src/main/scala/" + rel))
                    .find(java.nio.file.Files.exists(_))
                    .get
            ),
            java.nio.charset.StandardCharsets.UTF_8
        )

    test("the delete adds the warning line inside the data-delete block (source pin: the delete needs a store)") {
        val src = source("ai/datris/api/PipelineAPIController.scala")
        val start = src.indexOf("if (deleteDataBool && config.destination != null) {")
        val end = src.indexOf("cleanupDocumentTapLedgers(pipeline)", start)
        val w = src.indexOf("warnings ++= PipelineAPIController.mongoReservedDeleteWarning(config)", start)
        assert(start > 0 && w > start && w < end, s"start=$start warning=$w end=$end")
    }

    test("catalog restore and POST /pipeline save through savePipelineCore, which applies databaseNameRefusal (source pin)") {
        val pc = source("ai/datris/api/PipelineAPIController.scala")
        val core = pc.indexOf("private def savePipelineCore(")
        assert(core > 0 && pc.indexOf("PipelineAPIController.databaseNameRefusal(preserved, existing)", core) > core)
        assert(pc.indexOf("savePipelineCore(config, changeNote, request, None)") > 0)
        val cat = source("ai/datris/api/CatalogAPIController.scala")
        assert(cat.contains("pipelineController.savePipelineInternal(toSave, note, request)"))
    }

    // ------------------------------------------------------------ single-tenant exact names

    private val suffixLike = Seq("orders-pipeline", "daily-tap", "app-user", "web-incident")

    test("single-tenant: internal-database data collections named like platform suffixes work on every path") {
        val q = new QueryAPIController()
        for (coll <- suffixLike) {
            val db = mongoDb(dbName = Internal, table = coll)
            val stored = structured(db)
            assert(PipelineAPIController.databaseNameRefusal(stored, stored).isEmpty, coll + " unchanged save")
            for ((who, r) <- callers)
                assert(EntityVersionAPIController.pipelineRestoreRefusal(r, stored.copy(version = 2), stored, Nil).isEmpty, s"$coll $who restore")
            for (truncate <- Seq(false, true))
                assert(load(structured(db.copy(truncateBeforeWrite = truncate))).isInstanceOf[NullPointerException], coll + " load")
            assert(PipelineAPIController.mongoReservedDeleteWarning(stored).isEmpty, coll)
            assert(PipelineAPIController.dropMongoDestination(db), coll + " drop")
            val m = new java.util.HashMap[String, Any](); m.put("collection", coll); m.put("database", Internal)
            assert(q.queryMongoDB(null, m).getStatusCode.value != 400, coll + " query")
        }
        assert(dropped == suffixLike.map("oss." + _))
        collectionsOf = db => if (db.equalsIgnoreCase(Internal)) suffixLike ++ env().platformCollectionNames else Seq("orders")
        val c = new MetadataAPIController()
        val named = JsonParser.parseString(c.getMongoCollections(null, Internal).getBody).getAsJsonArray.asScala.map(_.getAsString).toList
        assert(named == suffixLike.sorted, named)
        // each exact platform name is still refused
        for (p <- platformCollections) assertRefused(PipelineAPIController.databaseNameRefusal(structured(mongoDb(dbName = Internal, table = p)), null), p)
    }

    test("multi-tenant: the suffix match still refuses another tenant's collections") {
        ScopeDenialFixtures.install(env(multiTenant = true, environment = "acme"))
        for (coll <- suffixLike :+ "tenant-b-user-session" :+ "x-dataset") {
            val cfg = structured(mongoDb(dbName = Internal, table = coll))
            assertRefused(PipelineAPIController.databaseNameRefusal(cfg, cfg), coll)
            assert(load(cfg).getMessage == MongoReservedNames.DestinationRefusal, coll)
            assert(!PipelineAPIController.dropMongoDestination(cfg.destination.database), coll)
        }
        assert(clients.isEmpty)
    }

    test("the listings agree: the internal database is listed exactly when its collection listing is non-empty") {
        val c = new MetadataAPIController()
        def dbs = JsonParser.parseString(c.getMongoDatabases(null).getBody).getAsJsonArray.asScala.map(_.getAsString).toList
        def colls = JsonParser.parseString(c.getMongoCollections(null, Internal).getBody).getAsJsonArray.asScala.map(_.getAsString).toList
        listed = Seq(Internal, "datris")
        for (
            inInternal <- Seq(Seq("oss-foo"), env().platformCollectionNames, env().platformCollectionNames :+ "oss-foo", Seq("system.js"), Seq.empty[String])
        ) {
            collectionsOf = db => if (db.equalsIgnoreCase(Internal)) inInternal else Seq("orders")
            assert(dbs.contains(Internal) == colls.nonEmpty, inInternal)
        }
        // a data collection carrying the environment prefix is listed, as the query route reads it
        collectionsOf = db => if (db.equalsIgnoreCase(Internal)) Seq("oss-foo") else Seq("orders")
        assert(colls == List("oss-foo"))
    }

    // ------------------------------------------------------------ unchanged destination

    test("an edit that keeps the engine, database and collection is unchanged for the internal-database rule") {
        val stored = structured(mongoDb(dbName = Internal, table = "legacy_orders"))
        val edits = Seq(
            stored.destination.database.copy(options = new java.util.ArrayList[String]()),
            stored.destination.database.copy(schema = "anything"),
            stored.destination.database.copy(warehouse = "w"),
            stored.destination.database.copy(keyFields = java.util.Arrays.asList("id"))
        )
        for (db <- edits) assert(PipelineAPIController.databaseNameRefusal(structured(db), stored).isEmpty, db)
        // a blank or placeholder name resolving to the same database is the same destination
        ScopeDenialFixtures.install(env().copy(postgresDatabase = Internal))
        for (blank <- Seq("", "DATABASE_NAME", "OSS"))
            assert(
                MongoReservedNames.destinationProblem(mongoDb(dbName = blank, table = "legacy_orders"), DatrisEnvironment.current, stored.destination.database).isEmpty,
                blank
            )
    }

    test("a real change in the internal database is still refused: another collection or another database moving in") {
        val stored = structured(mongoDb(dbName = Internal, table = "legacy_orders"))
        assertRefused(PipelineAPIController.databaseNameRefusal(structured(mongoDb(dbName = Internal, table = "legacy_orders2")), stored), "other collection")
        assertRefused(PipelineAPIController.databaseNameRefusal(stored, structured(mongoDb(dbName = "datris", table = "legacy_orders"))), "other database")
        assertRefused(
            PipelineAPIController.databaseNameRefusal(
                stored,
                structured(Database(dbName = Internal, schema = "public", table = "legacy_orders", usePostgres = true))
            ),
            "other engine"
        )
    }
}
