package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.JsonObject
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

class CatalogFindSpec extends AnyFunSuite with BeforeAndAfterAll {

    private def tags(values: String*): java.util.List[String] =
        new java.util.ArrayList[String](values.asJava)

    private def pipeline(name: String, catalog: String = null, tagList: java.util.List[String] = null): PipelineConfig =
        PipelineConfig(name = name, catalog = catalog, tags = tagList)

    private def tap(name: String, target: String, description: String): TapConfig =
        TapConfig(name = name, description = description, targetPipeline = target)

    test("tokenize lowercases and splits on non-alphanumerics, dropping single chars") {
        assert(CatalogFind.tokenize("Customer-Orders_2026 landed!") == List("customer", "orders", "2026", "landed"))
        assert(CatalogFind.tokenize(null) == Nil)
    }

    test("scoring ranks the better lexical match first, deterministically") {
        val orders = pipeline("orders", catalog = "commerce", tagList = tags("sales"))
        val weather = pipeline("weather", catalog = "science")
        val q = CatalogFind.tokenize("customer orders this week")
        val sOrders = CatalogFind.score(q, orders, Some(tap("orders-export", "orders", "Daily order export")))
        val sWeather = CatalogFind.score(q, weather, None)
        assert(sOrders > sWeather)
    }

    test("tag matches count toward the score") {
        val tagged = pipeline("p1", tagList = tags("sales"))
        val untagged = pipeline("p2")
        val q = CatalogFind.tokenize("sales")
        assert(CatalogFind.score(q, tagged, None) > CatalogFind.score(q, untagged, None))
    }

    test("howToQuery names the existing tool with pre-filled arguments per kind") {
        def ref(kind: String, coords: (String, String)*) = LineageService.DatasetRef(kind, coords.toList)

        val pg = CatalogFind.howToQuery("p", ref("postgres", "database" -> "datris", "schema" -> "public", "table" -> "orders"))
        assert(pg.get("tool").getAsString == "query_postgres")
        assert(pg.getAsJsonObject("args").get("sql").getAsString == "SELECT * FROM \"public\".\"orders\" LIMIT 100")

        val mongo = CatalogFind.howToQuery("p", ref("mongodb", "database" -> "datris", "collection" -> "orders"))
        assert(mongo.get("tool").getAsString == "query_mongodb")
        assert(mongo.getAsJsonObject("args").get("collection").getAsString == "orders")

        val sf = CatalogFind.howToQuery("p", ref("snowflake", "table" -> "t"))
        assert(sf.get("tool").getAsString == "query_snowflake")
        assert(sf.getAsJsonObject("args").get("pipeline").getAsString == "p")

        val qd = CatalogFind.howToQuery("p", ref("qdrant", "collection" -> "chunks"))
        assert(qd.get("tool").getAsString == "search_qdrant")
        assert(qd.getAsJsonObject("args").get("collection").getAsString == "chunks")

        val pgv = CatalogFind.howToQuery("p", ref("pgvector", "schema" -> "public", "table" -> "docs"))
        assert(pgv.get("tool").getAsString == "search_pgvector")
        assert(pgv.getAsJsonObject("args").get("table").getAsString == "docs")

        // Destinations without a query tool return no hint.
        assert(CatalogFind.howToQuery("p", ref("kafka", "topic" -> "events")) == null)
        assert(CatalogFind.howToQuery("p", ref("activemq", "queue" -> "q")) == null)
    }

    test("a tag shared by pipeline and tap shows once in a hit") {
        val p = pipeline("orders", tagList = tags("provenance-test", "orders"))
        val t = tap("orders-export", "orders", "d").copy(tags = tags("provenance-test"))
        assert(CatalogFind.mergedTags(p, Some(t)) == List("provenance-test", "orders"))
        assert(CatalogFind.mergedTags(p, None) == List("provenance-test", "orders"))
        assert(CatalogFind.mergedTags(pipeline("bare"), None) == Nil)
    }

    test("weaviate hint uses class_name, matching the search tool's schema") {
        val w = CatalogFind.howToQuery("p", LineageService.DatasetRef("weaviate", List("collection" -> "Documents")))
        assert(w.get("tool").getAsString == "search_weaviate")
        assert(w.getAsJsonObject("args").get("class_name").getAsString == "Documents")
    }

    // ---- scratch destination (story: scratch-destination-server) ----

    test("a scratch pipeline has no howToQuery hint and is not returned as a queryable dataset") {
        assert(CatalogFind.howToQuery("p", LineageService.DatasetRef("scratch", Nil)) == null)
        assert(CatalogFind.howToQuery("p", LineageService.DatasetRef("scratch", List("bucket" -> "unit-data", "prefix" -> "_scratch/p/"))) == null)
        // renderHit derives locations/howToQuery from LineageService.datasets, so
        // a scratch-only pipeline must surface no location at all.
        val scratchOnly = PipelineConfig(name = "p", destination = Destination(scratch = ScratchConfig()))
        assert(LineageService.datasets(scratchOnly).isEmpty)
    }

    // ---- Unity Catalog 2: discovery (plans/stories/unity-catalog-2-discovery.md) ----
    //
    // Seams pinned here (story "Files", CatalogFind.scala + UnityCatalogDiscovery.scala):
    //
    //   // top level in package ai.datris.util (UnityCatalogDiscovery.scala)
    //   case class UcHit(secret: String, catalog: String, schema: String, table: String,
    //                    comment: String /* nullable */, matchedColumns: List[String],
    //                    tags: Map[String, String])
    //   case class UnityCatalogResults(searched: List[String],
    //                                  skipped: List[(String, String)] /* (secret, reason) */,
    //                                  hits: List[UcHit])
    //
    //   CatalogFind.find(query, limit, ai, visible, taps,
    //                    unityCatalog: Option[UnityCatalogResults] = None): JsonObject
    //
    // find() renders Datris hits through LineageService.freshness /
    // authorityOfDataset, which read DatrisEnvironment.current. A test env with
    // no Mongo config makes every Mongo read fail fast and be swallowed there,
    // so hits render with empty freshness.

    private val ucEnv: DatrisEnvironment = DatrisEnvironment(
        initialized = true,
        environment = "catalog-find-spec",
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
        tapTableName = null
    )

    override def beforeAll(): Unit = TenantContext.set(ucEnv)
    override def afterAll(): Unit = TenantContext.clear()

    private def dbxPipeline(name: String, cat: String, sch: String, tbl: String): PipelineConfig =
        PipelineConfig(
            name = name,
            destination = Destination(database =
                Database(dbName = cat, schema = sch, table = tbl, useDatabricks = true, credentialsSecret = "dbx", warehouse = "abc123")
            )
        )

    private def ucHit(cat: String, sch: String, tbl: String, matched: List[String] = List("order_id"), tagMap: Map[String, String] = Map.empty) =
        UcHit(secret = "dbx", catalog = cat, schema = sch, table = tbl, comment = null, matchedColumns = matched, tags = tagMap)

    private def ucResults(hits: UcHit*) =
        UnityCatalogResults(searched = List("dbx"), skipped = List("dbx-broken" -> "no warehouse"), hits = hits.toList)

    private def resultList(out: JsonObject): List[JsonObject] =
        out.getAsJsonArray("results").asScala.map(_.getAsJsonObject).toList

    private def names(out: JsonObject): List[String] = resultList(out).map(_.get("name").getAsString)

    private def byName(out: JsonObject, name: String): JsonObject =
        resultList(out).find(_.get("name").getAsString == name).getOrElse(fail("no hit named " + name + " in " + out))

    // "orders" matches the query by name; "ingest_feed" does not (score 0), so it
    // is visible but never a Datris hit.
    private val ordersPipeline = pipeline("orders")
    private val ingestFeed = dbxPipeline("ingest_feed", "main", "sales", "orders_gold")

    test("find without unityCatalog produces no source key and no unityCatalog block") {
        val implicitNone = CatalogFind.find("orders", 5, ai = false, visible = List(ordersPipeline, ingestFeed), taps = Nil)
        val explicitNone = CatalogFind.find("orders", 5, ai = false, visible = List(ordersPipeline, ingestFeed), taps = Nil, unityCatalog = None)
        Seq(implicitNone, explicitNone).foreach { out =>
            assert(names(out) == List("orders"), out)
            assert(!out.has("unityCatalog"), out)
            resultList(out).foreach(h => assert(!h.has("source"), h))
        }
        assert(implicitNone.toString == explicitNone.toString)
    }

    test("with unityCatalog, Datris hits carry source=datris and UC hits follow with source=unity-catalog") {
        val out = CatalogFind.find(
            "orders",
            5,
            ai = false,
            visible = List(ordersPipeline, ingestFeed),
            taps = Nil,
            unityCatalog = Some(ucResults(ucHit("main", "raw", "orders_raw")))
        )
        val hits = resultList(out)
        assert(names(out) == List("orders", "main.raw.orders_raw"), out)
        assert(hits.head.get("source").getAsString == "datris")
        val uc = hits(1)
        assert(uc.get("source").getAsString == "unity-catalog")
        assert(uc.get("secret").getAsString == "dbx")
        assert(uc.getAsJsonArray("matchedColumns").asScala.map(_.getAsString).toList == List("order_id"))
        val loc = uc.getAsJsonObject("location")
        assert(loc.get("kind").getAsString == "databricks")

        val block = out.getAsJsonObject("unityCatalog")
        assert(block != null, out)
        assert(block.getAsJsonArray("searched").asScala.map(_.getAsString).toList == List("dbx"))
        val skipped = block.getAsJsonArray("skipped").asScala.map(_.getAsJsonObject).toList
        assert(skipped.size == 1)
        assert(skipped.head.get("secret").getAsString == "dbx-broken")
        assert(skipped.head.get("reason").getAsString == "no warehouse")
    }

    test("a UC table owned by a visible Databricks pipeline gets howToQuery query_databricks with that pipeline") {
        val tagged = dbxPipeline("tagged_loader", "other", "place", "elsewhere")
        val out = CatalogFind.find(
            "orders",
            5,
            ai = false,
            visible = List(ordersPipeline, ingestFeed, tagged),
            taps = Nil,
            unityCatalog = Some(ucResults(
                // owned by coords: equals ingest_feed's dbName/schema/table
                ucHit("main", "sales", "orders_gold"),
                // owned by the datris_pipeline tag story 1 writes
                ucHit("main", "sales", "orders_tagged", tagMap = Map("datris_pipeline" -> "tagged_loader"))
            ))
        )
        val byCoords = byName(out, "main.sales.orders_gold")
        assert(byCoords.get("ownedByPipeline").getAsString == "ingest_feed")
        assert(byCoords.getAsJsonObject("howToQuery").get("tool").getAsString == "query_databricks")
        assert(byCoords.getAsJsonObject("howToQuery").getAsJsonObject("args").get("pipeline").getAsString == "ingest_feed")

        val byTag = byName(out, "main.sales.orders_tagged")
        assert(byTag.get("ownedByPipeline").getAsString == "tagged_loader")
        assert(byTag.getAsJsonObject("howToQuery").get("tool").getAsString == "query_databricks")
        assert(byTag.getAsJsonObject("howToQuery").getAsJsonObject("args").get("pipeline").getAsString == "tagged_loader")
        assert(byTag.getAsJsonObject("tags").get("datris_pipeline").getAsString == "tagged_loader")
    }

    test("an unowned UC table gets the plain SQL hint") {
        // A datris_pipeline tag naming a pipeline the caller cannot see does not make it owned.
        val out = CatalogFind.find(
            "orders",
            5,
            ai = false,
            visible = List(ordersPipeline, ingestFeed),
            taps = Nil,
            unityCatalog = Some(ucResults(
                ucHit("main", "raw", "orders_raw"),
                ucHit("main", "raw", "orders_hidden", tagMap = Map("datris_pipeline" -> "not_visible_to_caller"))
            ))
        )
        Seq("main.raw.orders_raw", "main.raw.orders_hidden").foreach { n =>
            val h = byName(out, n)
            assert(!h.has("ownedByPipeline"), h)
            val htq = h.getAsJsonObject("howToQuery")
            assert(htq.get("tool").getAsString == "sql", htq)
            assert(htq.getAsJsonObject("args").get("sql").getAsString == "SELECT * FROM " + n + " LIMIT 100", htq)
            assert(htq.get("note").getAsString.toLowerCase.contains("no datris pipeline"), htq)
        }
    }

    test("an owned table whose pipeline is already a hit is not duplicated") {
        val ordersDbx = dbxPipeline("orders", "main", "sales", "orders")
        val out = CatalogFind.find(
            "orders",
            5,
            ai = false,
            visible = List(ordersDbx),
            taps = Nil,
            unityCatalog = Some(ucResults(
                ucHit("main", "sales", "orders"),
                ucHit("main", "sales", "orders_copy", tagMap = Map("datris_pipeline" -> "orders"))
            ))
        )
        assert(names(out) == List("orders"), out)
        assert(resultList(out).head.get("source").getAsString == "datris")
    }

    test("owned UC hits rank before unowned") {
        val out = CatalogFind.find(
            "orders",
            5,
            ai = false,
            visible = List(ordersPipeline, ingestFeed),
            taps = Nil,
            unityCatalog = Some(ucResults(
                ucHit("main", "raw", "orders_many", matched = List("order_id", "order_date", "order_total")),
                ucHit("main", "raw", "orders_few", matched = List("order_id", "order_date")),
                ucHit("main", "sales", "orders_gold", matched = List("order_id"))
            ))
        )
        // Datris hits first, then owned UC, then unowned by column-match count.
        assert(names(out) == List("orders", "main.sales.orders_gold", "main.raw.orders_many", "main.raw.orders_few"), out)
    }

    test("count and totalMatches include UC hits") {
        val without = CatalogFind.find("orders", 5, ai = false, visible = List(ordersPipeline, ingestFeed), taps = Nil)
        assert(without.get("count").getAsInt == 1)
        assert(without.get("totalMatches").getAsInt == 1)

        val out = CatalogFind.find(
            "orders",
            5,
            ai = false,
            visible = List(ordersPipeline, ingestFeed),
            taps = Nil,
            unityCatalog = Some(ucResults(ucHit("main", "raw", "orders_raw"), ucHit("main", "sales", "orders_gold")))
        )
        assert(resultList(out).size == 3, out)
        assert(out.get("count").getAsInt == 3, out)
        assert(out.get("totalMatches").getAsInt == 3, out)
    }
}
