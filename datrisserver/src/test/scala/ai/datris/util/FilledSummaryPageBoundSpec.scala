package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, PipelineStatusSummary, PipelineStatusSummaryTable, TenantContext}
import com.google.gson.Gson
import com.mongodb.client.model.Filters
import com.mongodb.client.{FindIterable, MongoCollection, MongoCursor, MongoDatabase}
import org.bson.Document
import org.bson.conversions.Bson
import org.mockito.Answers.RETURNS_SELF
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.{mock, never, verify, verifyNoInteractions, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Story: Four small read leaks for callers limited to a catalog
  * (plans/stories/limited-caller-read-leaks.md), D3 with the review ruling:
  * a limited caller's summary page is one store query filtered to the
  * pipeline names it may see, with the unfiltered read's sort, skip and
  * limit, so no page is bounded and every visible row is reachable. Pinned
  * on the query MongoDBUtil sends to a mocked collection. */
class FilledSummaryPageBoundSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val gson = new Gson

    private def summaryDoc(i: Int, pipeline: String): Document = {
        val at = 1800000000000L - i * 1000L
        val s = PipelineStatusSummary("ts" + i, at, at, pipeline, "tok-" + i, "JobRunner", "s", "e", "1s", "success")
        Document.parse(gson.toJson(PipelineStatusSummaryTable(s.pipelineToken, s, at)))
    }

    /** A database whose collection `table` answers every find with `docs`. */
    private def fixture(table: String, docs: Seq[Document]): (MongoDatabase, MongoCollection[Document], FindIterable[Document]) = {
        val database = mock(classOf[MongoDatabase])
        val coll = mock(classOf[MongoCollection[_]]).asInstanceOf[MongoCollection[Document]]
        val find = mock(classOf[FindIterable[_]], RETURNS_SELF).asInstanceOf[FindIterable[Document]]
        val it = docs.iterator
        val cursor = mock(classOf[MongoCursor[_]]).asInstanceOf[MongoCursor[Document]]
        when(cursor.hasNext).thenAnswer(_ => it.hasNext)
        when(cursor.next()).thenAnswer(_ => it.next())
        when(find.iterator()).thenReturn(cursor)
        when(coll.find(org.mockito.ArgumentMatchers.any(classOf[Bson]))).thenReturn(find)
        when(database.getCollection(table)).thenReturn(coll)
        (database, coll, find)
    }

    private var savedEnv: DatrisEnvironment = _

    override def beforeEach(): Unit = {
        savedEnv = DatrisEnvironment.values
        val e = InMemoryUserDb.env().copy(pipelineStatusTableName = "status")
        DatrisEnvironment.init(e)
        TenantContext.set(e)
    }

    override def afterEach(): Unit = {
        TenantContext.clear()
        DatrisEnvironment.values = savedEnv
    }

    test("the filtered page is one query: pipeline name in the set, newest first, skip of the pages before it, limit of one page") {
        val (database, coll, find) = fixture("status-summary", Seq(summaryDoc(40, "orders"), summaryDoc(41, "orders_old")))
        val util = new MongoDBUtil(database)
        val got = util.getPageOfItemsWhereInAsJSON("status-summary", "json.pipeline", Set("orders_old", "orders"), 2, 20, "created_at")

        val filter = ArgumentCaptor.forClass(classOf[Bson])
        verify(coll).find(filter.capture())
        assert(filter.getValue.toBsonDocument() == Filters.in("json.pipeline", java.util.Arrays.asList("orders", "orders_old")).toBsonDocument())
        val sort = ArgumentCaptor.forClass(classOf[Bson])
        verify(find).sort(sort.capture())
        assert(sort.getValue.toBsonDocument() == (new Document("created_at", -1): Bson).toBsonDocument())
        verify(find).skip(40)
        verify(find).limit(20)
        verify(coll, never()).find()
        assert(got.map(j => gson.fromJson(j, classOf[PipelineStatusSummaryTable]).pipeline_token) == List("tok-40", "tok-41"))
    }

    test("GET /pipeline/status page N for a limited caller sends that query to the summary table: skip 20(N-1), limit 20") {
        for (page <- Seq(1, 2, 40, 500)) {
            val (database, coll, find) = fixture("status-summary", Seq(summaryDoc(0, "orders")))
            val got = PipelineStatusUtil.getVisiblePipelineStatusSummary(Set("orders"), page, new MongoDBUtil(database))
            val filter = ArgumentCaptor.forClass(classOf[Bson])
            verify(coll).find(filter.capture())
            assert(filter.getValue.toBsonDocument() == Filters.in("json.pipeline", java.util.Arrays.asList("orders")).toBsonDocument(), page)
            verify(find).skip((page - 1) * 20)
            verify(find).limit(20)
            assert(got.asScala.map(_.pipelineToken) == Seq("tok-0"), page)
        }
    }

    test("an empty visible set sends no query at all") {
        val (database, _, _) = fixture("status-summary", Nil)
        assert(PipelineStatusUtil.getVisiblePipelineStatusSummary(Set.empty, 1, new MongoDBUtil(database)).isEmpty)
        assert(new MongoDBUtil(database).getPageOfItemsWhereInAsJSON("status-summary", "json.pipeline", Set.empty, 0, 20, "created_at").isEmpty)
        verifyNoInteractions(database)
    }

    test("the store default (no query filter) gives the same rows: filtered on the field, sorted, then paged") {
        val docs = (0 until 300).map(i => summaryDoc(i, if (i % 7 == 0) "orders" else if (i % 7 == 3) "orders_old" else "payroll"))
        val db = new InMemoryUserDb {
            override def getAllItemsAsJSON(tableName: String): List[String] = scala.util.Random.shuffle(docs.map(_.toJson).toList)
        }
        val names = Set("orders", "orders_old")
        val expected =
            docs.map(d => gson.fromJson(d.toJson, classOf[PipelineStatusSummaryTable]).json).filter(s => names.contains(s.pipeline)).map(_.pipelineToken)
        val pages = (0 until 6).map(n =>
            db.getPageOfItemsWhereInAsJSON("t", "json.pipeline", names, n, 20, "created_at").map(j =>
                gson.fromJson(j, classOf[PipelineStatusSummaryTable]).pipeline_token
            )
        )
        assert(pages.map(_.size) == Seq(20, 20, 20, 20, 6, 0), pages)
        assert(pages.flatten == expected)
        // a missing or non-string field never matches
        val odd = new InMemoryUserDb {
            override def getAllItemsAsJSON(tableName: String): List[String] =
                List("""{"json":{"pipeline":null},"created_at":1}""", """{"json":{"pipeline":7},"created_at":2}""", """{"created_at":3}""", "not json")
        }
        assert(odd.getPageOfItemsWhereInAsJSON("t", "json.pipeline", Set("7", "null"), 0, 20, "created_at").isEmpty)
    }
}
