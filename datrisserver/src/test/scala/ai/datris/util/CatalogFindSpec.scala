package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

class CatalogFindSpec extends AnyFunSuite {

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

    test("weaviate hint uses class_name, matching the search tool's schema") {
        val w = CatalogFind.howToQuery("p", LineageService.DatasetRef("weaviate", List("collection" -> "Documents")))
        assert(w.get("tool").getAsString == "search_weaviate")
        assert(w.getAsJsonObject("args").get("class_name").getAsString == "Documents")
    }
}
