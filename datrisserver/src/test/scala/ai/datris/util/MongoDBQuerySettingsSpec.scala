package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.ScopeDenialFixtures
import ai.datris.model.{DatrisEnvironment, MongoDBConfig}
import com.mongodb.{ServerAddress, ServerCursor}
import com.mongodb.client.{FindIterable, MongoClient, MongoCollection, MongoCursor, MongoDatabase}
import org.bson.Document
import org.bson.conversions.Bson
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{mock, when, RETURNS_SELF}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._

/** The parts of the MongoDB query bounds that the client-factory seam in
  * [[MongoDBQueryBoundsSpec]] does not reach: the socket read timeout on a
  * real client's settings, the parser for `DATRIS_QUERY_MAX_RESULT_MB`, and a
  * cursor whose close fails. No MongoDB is reached. */
class MongoDBQuerySettingsSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val Mb: Long = 1024L * 1024L
    private var savedEnv: DatrisEnvironment = _
    private var savedCeiling: Long = 0L

    override def beforeEach(): Unit = {
        savedEnv = DatrisEnvironment.values
        savedCeiling = MongoDBQueryUtil.maxResultBytes
        ScopeDenialFixtures.install(
            PlatformCollectionFixtures.named(
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
        MongoDBQueryUtil.maxResultBytes = savedCeiling
        ScopeDenialFixtures.install(savedEnv)
    }

    // ------------------------------------------------------------- socket read timeout

    test("a query's client reads with a socket timeout of its time limit plus a few seconds") {
        for ((limit, expected) <- Seq(5 -> 35, 0 -> 35, -1 -> 305)) {
            val settings = MongoDBQueryUtil.clientSettings("mongodb://localhost:27017", MongoDBQueryUtil.timeoutSeconds(limit))
            assert(settings.getSocketSettings.getReadTimeout(TimeUnit.SECONDS) == expected, "limit " + limit)
        }
    }

    test("the client settings keep the connection string") {
        val settings = MongoDBQueryUtil.clientSettings("mongodb://db-a:27018,db-b:27019/?replicaSet=rs0", 30)
        val hosts = settings.getClusterSettings.getHosts.asScala.map(_.toString)
        assert(hosts == Seq("db-a:27018", "db-b:27019"))
        assert(settings.getClusterSettings.getRequiredReplicaSetName == "rs0")
    }

    // ------------------------------------------------------------- DATRIS_QUERY_MAX_RESULT_MB

    test("unset gives the default of 32 MB") {
        assert(MongoDBQueryUtil.parseMaxResultMb(None) == 32L)
    }

    test("a whole number is used as given, spaces ignored, and 0 means no ceiling") {
        assert(MongoDBQueryUtil.parseMaxResultMb(Some("5")) == 5L)
        assert(MongoDBQueryUtil.parseMaxResultMb(Some(" 5 ")) == 5L)
        assert(MongoDBQueryUtil.parseMaxResultMb(Some("0")) == 0L)
        assert(MongoDBQueryUtil.parseMaxResultMb(Some("1024")) == 1024L)
    }

    test("a blank, negative, non-numeric or fractional value falls back to the default") {
        for (raw <- Seq("", "   ", "-1", "abc", "1.5", "32MB"))
            assert(MongoDBQueryUtil.parseMaxResultMb(Some(raw)) == 32L, "value \"" + raw + "\"")
    }

    test("a huge value is clamped so the ceiling in bytes cannot overflow") {
        val most = Long.MaxValue / Mb
        for (raw <- Seq(Long.MaxValue.toString, "99999999999999999999999")) {
            val mb = MongoDBQueryUtil.parseMaxResultMb(Some(raw))
            assert(mb == most, "value " + raw)
            assert(mb * Mb > 0L, "value " + raw)
        }
        assert(MongoDBQueryUtil.parseMaxResultMb(Some(most.toString)) == most)
    }

    // ------------------------------------------------------------- close failure

    /** A cursor over `docs` whose close throws. */
    private final class FailingCloseCursor(docs: Seq[Document], failOnNext: Option[RuntimeException]) extends MongoCursor[Document] {
        private val it = docs.iterator
        override def close(): Unit = throw new IllegalStateException("close failed")
        override def hasNext: Boolean = it.hasNext
        override def next(): Document = {
            failOnNext.foreach(e => throw e)
            it.next()
        }
        override def available(): Int = 0
        override def tryNext(): Document = if (hasNext) next() else null
        override def getServerCursor: ServerCursor = null
        override def getServerAddress: ServerAddress = new ServerAddress("localhost", 27017)
    }

    private def install(cursor: MongoCursor[Document]): MongoClient = {
        val find = mock(classOf[FindIterable[Document]], RETURNS_SELF)
        when(find.iterator()).thenReturn(cursor)
        when(find.cursor()).thenReturn(cursor)
        val coll = mock(classOf[MongoCollection[Document]])
        when(coll.find(any(classOf[Bson]))).thenReturn(find)
        val db = mock(classOf[MongoDatabase])
        when(db.getCollection(anyString())).thenReturn(coll)
        val client = mock(classOf[MongoClient])
        when(client.getDatabase(anyString())).thenReturn(db)
        MongoDBQueryUtil.clientFactoryOverride = () => client
        client
    }

    test("a range filter written in extended JSON reaches the find as an ObjectId and a date") {
        val find = mock(classOf[FindIterable[Document]], RETURNS_SELF)
        when(find.iterator()).thenReturn(new FailingCloseCursor(Nil, None))
        val coll = mock(classOf[MongoCollection[Document]])
        when(coll.find(any(classOf[Bson]))).thenReturn(find)
        val db = mock(classOf[MongoDatabase])
        when(db.getCollection(anyString())).thenReturn(coll)
        val client = mock(classOf[MongoClient])
        when(client.getDatabase(anyString())).thenReturn(db)
        MongoDBQueryUtil.clientFactoryOverride = () => client

        def obj(k: String, v: Any): java.util.Map[String, Any] = {
            val m = new java.util.LinkedHashMap[String, Any]()
            m.put(k, v)
            m
        }
        val filter = new java.util.LinkedHashMap[String, Any]()
        filter.put("_id", obj("$gt", obj("$oid", "0123456789abcdef01234567")))
        filter.put("at", obj("$lt", obj("$date", "2026-01-01T00:00:00Z")))
        MongoDBQueryUtil.query("items", filter, null, -1, null)

        val captor = org.mockito.ArgumentCaptor.forClass(classOf[Bson])
        org.mockito.Mockito.verify(coll).find(captor.capture())
        val parsed = captor.getValue.asInstanceOf[Document]
        val id = parsed.get("_id", classOf[Document]).get("$gt")
        assert(id == new org.bson.types.ObjectId("0123456789abcdef01234567"), "parsed as " + id)
        val at = parsed.get("at", classOf[Document]).get("$lt")
        assert(at == java.util.Date.from(java.time.Instant.parse("2026-01-01T00:00:00Z")), "parsed as " + at)
    }

    private def docs(n: Int, padChars: Int): Seq[Document] =
        (1 to n).map(i => new Document("_id", Integer.valueOf(i)).append("pad", "x" * padChars))

    test("a cursor whose close fails does not replace the too-large refusal") {
        MongoDBQueryUtil.maxResultBytes = Mb
        install(new FailingCloseCursor(docs(40, 100000), None))
        val e = intercept[MongoDBQueryUtil.ResultTooLargeException](MongoDBQueryUtil.query("items", null, null, -1, null))
        assert(e.getMessage.contains("larger than 1 MB"), e.getMessage)
        assert(e.getSuppressed.exists(_.getMessage == "close failed"), "close failure not kept as suppressed")
    }

    test("a cursor whose close fails does not replace the time-limit refusal") {
        MongoDBQueryUtil.maxResultBytes = Mb
        install(new FailingCloseCursor(docs(3, 10), Some(new com.mongodb.MongoExecutionTimeoutException(50, "exceeded"))))
        val e = intercept[MongoDBQueryUtil.QueryTimeLimitException](MongoDBQueryUtil.query("items", null, null, 5, null))
        assert(e.getMessage.contains("30 seconds"), e.getMessage)
    }

    test("a cursor whose close fails after a complete read still returns every document") {
        MongoDBQueryUtil.maxResultBytes = Mb
        install(new FailingCloseCursor(docs(3, 10), None))
        val out = MongoDBQueryUtil.query("items", null, null, -1, null).asScala
        assert(out.size == 3)
    }
}
