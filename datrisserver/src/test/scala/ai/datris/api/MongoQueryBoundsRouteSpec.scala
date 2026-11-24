package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.ScopeDenialFixtures
import ai.datris.model.{DatrisEnvironment, DatrisException, MongoDBConfig}
import ai.datris.util.MongoDBQueryUtil
import com.google.gson.JsonParser
import com.mongodb.{MongoExecutionTimeoutException, ServerAddress, ServerCursor}
import com.mongodb.client.{FindIterable, MongoClient, MongoCollection, MongoCursor, MongoDatabase}
import org.bson.Document
import org.bson.conversions.Bson
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{mock, when, RETURNS_SELF}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import java.lang.reflect.{InvocationTargetException, Method}

/** Story: MongoDB query: every query has a time limit, and an unlimited read
  * cannot exhaust the server's memory (plans/stories/mongodb-query-bounds.md),
  * the `QueryAPIController` line of Acceptance: `queryMongoDB` answers a
  * time-limit failure and a too-large result with 400 and the exception's own
  * message; a blocked operator still answers 500 as it does today (the 400
  * branch is not widened to every DatrisException). No MongoDB is reached.
  *
  * `MongoDBQueryUtil.maxResultBytes` does not exist yet, so it is set by
  * reflection; until the story lands the tests that need it fail naming it. */
class MongoQueryBoundsRouteSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val Host = "mongo-7.internal.example"
    private var saved: DatrisEnvironment = _
    private var savedCeiling: Option[AnyRef] = None

    private def accessor(name: String, params: Int): Method = {
        val m = MongoDBQueryUtil.getClass.getDeclaredMethods.toSeq
            .find(m => (m.getName == name || m.getName.endsWith("$$" + name)) && m.getParameterCount == params)
            .getOrElse(throw new AssertionError("MongoDBQueryUtil has no settable member maxResultBytes (added by the story)"))
        m.setAccessible(true)
        m
    }

    private def invoke(m: Method, args: AnyRef*): AnyRef =
        try m.invoke(MongoDBQueryUtil, args: _*)
        catch { case e: InvocationTargetException => throw e.getCause }

    private def setCeiling(bytes: Long): Unit = {
        val m = accessor("maxResultBytes_$eq", 1)
        val arg: AnyRef =
            if (m.getParameterTypes.head == classOf[Int] || m.getParameterTypes.head == classOf[Integer]) Integer.valueOf(bytes.toInt)
            else java.lang.Long.valueOf(bytes)
        invoke(m, arg)
    }

    private final class FakeCursor(docs: Seq[Document], fail: Option[RuntimeException]) extends MongoCursor[Document] {
        private val it = docs.iterator
        override def close(): Unit = ()
        override def hasNext: Boolean = { fail.foreach(e => throw e); it.hasNext }
        override def next(): Document = { fail.foreach(e => throw e); it.next() }
        override def available(): Int = 0
        override def tryNext(): Document = if (hasNext) next() else null
        override def getServerCursor: ServerCursor = null
        override def getServerAddress: ServerAddress = new ServerAddress(Host, 27017)
    }

    private def install(docs: Seq[Document], fail: Option[RuntimeException] = None): Unit =
        MongoDBQueryUtil.clientFactoryOverride = () => {
            val cursor = new FakeCursor(docs, fail)
            val find = mock(classOf[FindIterable[Document]], RETURNS_SELF)
            when(find.iterator()).thenReturn(cursor)
            when(find.cursor()).thenReturn(cursor)
            val coll = mock(classOf[MongoCollection[Document]])
            when(coll.find(any(classOf[Bson]))).thenReturn(find)
            val db = mock(classOf[MongoDatabase])
            when(db.getCollection(anyString())).thenReturn(coll)
            val client = mock(classOf[MongoClient])
            when(client.getDatabase(anyString())).thenReturn(db)
            client
        }

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        savedCeiling = scala.util.Try(invoke(accessor("maxResultBytes", 0))).toOption
        ScopeDenialFixtures.install(
            ai.datris.util.PlatformCollectionFixtures.named(
                ScopeDenialFixtures.auditEnv().copy(
                    environment = "oss",
                    useApiKeys = false,
                    useAuditLog = false,
                    multiTenant = false,
                    mongoDbConfig = MongoDBConfig("mongodb://unused", "datris", "oss")
                )
            )
        )
    }

    override def afterEach(): Unit = {
        MongoDBQueryUtil.clientFactoryOverride = null
        savedCeiling.foreach {
            case n: java.lang.Number => setCeiling(n.longValue)
            case _ => ()
        }
        ScopeDenialFixtures.install(saved)
    }

    private def body(limit: Int, filter: java.util.Map[String, Any] = null): java.util.Map[String, Any] = {
        val m = new java.util.LinkedHashMap[String, Any]()
        m.put("collection", "items")
        m.put("limit", Integer.valueOf(limit))
        if (filter != null) m.put("filter", filter)
        m
    }

    private def errorOf(resp: org.springframework.http.ResponseEntity[String]): String = {
        val json = JsonParser.parseString(resp.getBody).getAsJsonObject
        assert(json.keySet().size() == 1, resp.getBody)
        json.get("error").getAsString
    }

    /** The message `MongoDBQueryUtil.query` itself throws for the same stub. */
    private def utilMessage(limit: Int): String = {
        val e = intercept[Throwable](MongoDBQueryUtil.query("items", null, null, limit, null))
        assert(e.isInstanceOf[DatrisException], "the query utility threw " + e.getClass.getName + ": " + e.getMessage)
        e.getMessage
    }

    test("a time-limit failure and a too-large result answer 400 with their message; a blocked operator still answers as it does today") {
        val c = new QueryAPIController()

        // Time limit, bounded and unlimited.
        for (limit <- Seq(5, -1)) {
            install(Nil, Some(new MongoExecutionTimeoutException(50, "operation exceeded time limit on " + Host + ":27017")))
            val expected = utilMessage(limit)
            val resp = c.queryMongoDB(null, body(limit))
            assert(resp.getStatusCode.value == 400, "time limit, limit " + limit + " -> " + resp.getStatusCode + " " + resp.getBody)
            assert(errorOf(resp) == expected, resp.getBody)
            assert(!resp.getBody.contains(Host), resp.getBody)
        }

        // Too large, unlimited and positive.
        setCeiling(1024L * 1024L)
        val big = (1 to 40).map(i => new Document("_id", Integer.valueOf(i)).append("pad", "x" * 100000))
        for (limit <- Seq(-1, 1000)) {
            install(big)
            val expected = utilMessage(limit)
            assert(expected.contains("larger than 1 MB"), expected)
            val resp = c.queryMongoDB(null, body(limit))
            assert(resp.getStatusCode.value == 400, "too large, limit " + limit + " -> " + resp.getStatusCode)
            assert(errorOf(resp) == expected, resp.getBody)
        }

        // A blocked operator: 500 with its message, exactly as before this story.
        install(Nil)
        val filter = new java.util.LinkedHashMap[String, Any]()
        filter.put("$where", "sleep(100000)")
        val blocked = c.queryMongoDB(null, body(5, filter))
        assert(blocked.getStatusCode.value == 500, blocked.getStatusCode + " " + blocked.getBody)
        assert(errorOf(blocked) == "MongoDB filter contains blocked operator: $where", blocked.getBody)

        // A small read under the ceiling still answers 200 with every document.
        install(big.take(3))
        val ok = c.queryMongoDB(null, body(-1))
        assert(ok.getStatusCode.value == 200, ok.getBody)
        assert(JsonParser.parseString(ok.getBody).getAsJsonObject.get("count").getAsInt == 3)
    }
}
