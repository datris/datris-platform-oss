package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.ScopeDenialFixtures
import ai.datris.model.{DatrisEnvironment, DatrisException, MongoDBConfig}
import com.mongodb.{MongoExecutionTimeoutException, MongoSocketReadTimeoutException, ServerAddress, ServerCursor}
import com.mongodb.client.{FindIterable, MongoClient, MongoCollection, MongoCursor, MongoDatabase}
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.json.{JsonMode, JsonWriterSettings}
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{mock, times, verify, when, RETURNS_SELF}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._

/** Story: MongoDB query: every query has a time limit, and an unlimited read
  * cannot exhaust the server's memory (plans/stories/mongodb-query-bounds.md),
  * the `MongoDBQueryBoundsSpec` half of Acceptance.
  *
  * No MongoDB is reached: `clientFactoryOverride` supplies a stub client whose
  * find returns a fake cursor over documents built here. Members the story adds
  * are reached through [[QuerySafetyReflect]] (or reflection on the setter of
  * `maxResultBytes`), so this file compiles before they exist and each test
  * fails at run time naming what is missing:
  *
  * {{{
  * object MongoDBQueryUtil {
  *     private[util] def timeoutSeconds(limit: Int): Int     // 30 for limit >= 0, 300 for limit < 0
  *     @volatile private[datris] var maxResultBytes: Long    // DATRIS_QUERY_MAX_RESULT_MB, default 256 MB, 0 = none
  *     class ResultTooLargeException ... extends DatrisException
  * }
  * }}}
  *
  * Nothing here waits on a clock: a time-limit failure is simulated by the
  * cursor throwing the driver's exception. */
class MongoDBQueryBoundsSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val Mb: Long = 1024L * 1024L
    private val jsonSettings = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build()
    private val Host = "mongo-7.internal.example"

    private var savedEnv: DatrisEnvironment = _
    private var savedCeiling: Option[Long] = None

    // ------------------------------------------------------------- fakes

    /** A cursor over `docs`, counting reads and closes; `fail` (if given) is
      * thrown by the first hasNext. */
    private final class FakeCursor(docs: Seq[Document], fail: Option[RuntimeException] = None) extends MongoCursor[Document] {
        private val it = docs.iterator
        var nextCalls = 0
        var closeCalls = 0
        override def close(): Unit = closeCalls += 1
        override def hasNext: Boolean = {
            fail.foreach(e => throw e)
            it.hasNext
        }
        override def next(): Document = {
            fail.foreach(e => throw e)
            nextCalls += 1
            it.next()
        }
        override def available(): Int = 0
        override def tryNext(): Document = if (hasNext) next() else null
        override def getServerCursor: ServerCursor = null
        override def getServerAddress: ServerAddress = new ServerAddress(Host, 27017)
    }

    private final case class Stub(client: MongoClient, find: FindIterable[Document], cursor: FakeCursor)

    private def stub(cursor: FakeCursor): Stub = {
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
        Stub(client, find, cursor)
    }

    /** `n` documents of about `padChars` characters of JSON text each. */
    private def docs(n: Int, padChars: Int): Seq[Document] =
        (1 to n).map(i => new Document("_id", Integer.valueOf(i)).append("pad", "x" * padChars))

    // ------------------------------------------------------------- ceiling seam

    private def ceilingGetter() = {
        val m = QuerySafetyReflect.methods(MongoDBQueryUtil, "maxResultBytes").find(_.getParameterCount == 0).getOrElse(
            throw new AssertionError("MongoDBQueryUtil has no member maxResultBytes (added by the story)")
        )
        m.setAccessible(true)
        m
    }

    private def ceiling(): Long =
        QuerySafetyReflect.invoke(MongoDBQueryUtil, ceilingGetter()) match {
            case n: java.lang.Number => n.longValue
            case other => throw new AssertionError("maxResultBytes is not a number: " + other)
        }

    /** Sets `maxResultBytes` (a `var`, Long or Int) to `bytes`. */
    private def setCeiling(bytes: Long): Unit = {
        val setters = QuerySafetyReflect.methods(MongoDBQueryUtil, "maxResultBytes_$eq").filter(_.getParameterCount == 1)
        val m = setters.headOption.getOrElse(
            throw new AssertionError("MongoDBQueryUtil.maxResultBytes cannot be set by a spec (the story makes it a private[datris] var)")
        )
        m.setAccessible(true)
        val arg: AnyRef = m.getParameterTypes.head match {
            case c if c == classOf[Long] || c == classOf[java.lang.Long] => java.lang.Long.valueOf(bytes)
            case c if c == classOf[Int] || c == classOf[Integer] => Integer.valueOf(bytes.toInt)
            case c => throw new AssertionError("maxResultBytes has an unexpected type " + c)
        }
        QuerySafetyReflect.invoke(MongoDBQueryUtil, m, arg)
    }

    override def beforeEach(): Unit = {
        savedEnv = DatrisEnvironment.values
        savedCeiling = scala.util.Try(ceiling()).toOption
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
        savedCeiling.foreach(setCeiling)
        ScopeDenialFixtures.install(savedEnv)
    }

    private def run(limit: Int): Seq[String] = MongoDBQueryUtil.query("items", null, null, limit, null).asScala.toSeq

    // ------------------------------------------------------------- time limit

    test("timeoutSeconds is 30 for a positive limit and for 0, and 300 for a negative limit") {
        for (l <- Seq(1, 5, 20, 250000, Int.MaxValue, 0))
            assert(QuerySafetyReflect.timeoutFor(MongoDBQueryUtil, l) == 30, "limit " + l)
        for (l <- Seq(-1, -50, Int.MinValue))
            assert(QuerySafetyReflect.timeoutFor(MongoDBQueryUtil, l) == 300, "limit " + l)
    }

    test("the find carries maxTime for a bounded and for an unlimited query") {
        for ((limit, seconds) <- Seq(5 -> 30L, 0 -> 30L, -1 -> 300L)) {
            val s = stub(new FakeCursor(docs(2, 10)))
            run(limit)
            val value = ArgumentCaptor.forClass(classOf[Long])
            val unit = ArgumentCaptor.forClass(classOf[TimeUnit])
            verify(s.find, times(1)).maxTime(value.capture(), unit.capture())
            val asSeconds = unit.getValue.toSeconds(value.getValue)
            assert(asSeconds == seconds, "limit " + limit + ": maxTime " + value.getValue + " " + unit.getValue)
        }
    }

    test("a MongoExecutionTimeoutException becomes a DatrisException that states the limit in seconds and names no host") {
        val failures: Seq[() => RuntimeException] = Seq(
            () => new MongoExecutionTimeoutException(50, "Executor error during find command on " + Host + ":27017 :: operation exceeded time limit"),
            () =>
                new MongoSocketReadTimeoutException(
                    "Timeout while receiving message from " + Host + ":27017",
                    new ServerAddress(Host, 27017),
                    new java.net.SocketTimeoutException("Read timed out")
                )
        )
        for (make <- failures; (limit, seconds) <- Seq(5 -> "30", -1 -> "300")) {
            val s = stub(new FakeCursor(Nil, Some(make())))
            val e = intercept[Throwable](run(limit))
            val what = make().getClass.getSimpleName + " limit " + limit
            assert(e.isInstanceOf[DatrisException], what + ": thrown as " + e.getClass.getName + ": " + e.getMessage)
            val msg = e.getMessage
            assert(msg != null && msg.matches("(?s).*\\b" + seconds + "[\\s-]*(s|sec|secs|second|seconds)\\b.*"), what + ": " + msg)
            assert(!msg.contains(Host) && !msg.contains("27017") && !msg.contains("internal.example"), what + " names the host: " + msg)
            verify(s.client, times(1)).close()
        }
    }

    // ------------------------------------------------------------- size ceiling

    test("an unlimited read under the ceiling returns every document in order") {
        setCeiling(Mb)
        val all = docs(5, 100000) // about 500 KB of JSON text, under 1 MB
        val s = stub(new FakeCursor(all))
        val out = run(-1)
        assert(out == all.map(_.toJson(jsonSettings)))
        assert(s.cursor.nextCalls == all.size)
        verify(s.client, times(1)).close()
    }

    test("an unlimited read that passes the ceiling stops reading, closes the cursor and the client, and throws ResultTooLargeException") {
        setCeiling(Mb)
        val all = docs(40, 100000) // about 4 MB of JSON text
        val s = stub(new FakeCursor(all))
        val e = intercept[Throwable](run(-1))
        assert(e.getClass.getName.endsWith("ResultTooLargeException"), "thrown as " + e.getClass.getName + ": " + e.getMessage)
        assert(e.isInstanceOf[DatrisException], e.getClass.getName)
        assert(e.getMessage.contains("larger than 1 MB"), e.getMessage)
        assert(e.getMessage.toLowerCase(java.util.Locale.ROOT).contains("positive limit"), e.getMessage)
        // Stopped at the document that passed 1 MB, not at the end of the collection.
        assert(s.cursor.nextCalls <= 12, "read " + s.cursor.nextCalls + " of " + all.size + " documents")
        assert(s.cursor.closeCalls >= 1, "cursor not closed")
        verify(s.client, times(1)).close()
    }

    test("a positive limit above the ceiling is refused the same way") {
        setCeiling(Mb)
        val all = docs(40, 100000)
        val s = stub(new FakeCursor(all))
        val e = intercept[Throwable](run(1000))
        assert(e.getClass.getName.endsWith("ResultTooLargeException"), "thrown as " + e.getClass.getName + ": " + e.getMessage)
        assert(e.isInstanceOf[DatrisException])
        assert(e.getMessage.contains("larger than 1 MB"), e.getMessage)
        assert(s.cursor.nextCalls <= 12, "read " + s.cursor.nextCalls + " of " + all.size + " documents")
        assert(s.cursor.closeCalls >= 1, "cursor not closed")
        verify(s.client, times(1)).close()
        // The limit itself is still applied as given (Decision 4).
        verify(s.find).limit(1000)
    }

    test("a ceiling of 0 reads everything") {
        setCeiling(0L)
        val all = docs(40, 100000) // about 4 MB, past any ceiling set above
        for (limit <- Seq(-1, 1000)) {
            val s = stub(new FakeCursor(all))
            val out = run(limit)
            assert(out == all.map(_.toJson(jsonSettings)), "limit " + limit)
            assert(s.cursor.nextCalls == all.size, "limit " + limit)
        }
    }

    // Decision 2 (not an Acceptance line of its own): the bundled default.
    test("without DATRIS_QUERY_MAX_RESULT_MB the ceiling is 256 MB of JSON text") {
        assume(sys.env.get("DATRIS_QUERY_MAX_RESULT_MB").isEmpty, "DATRIS_QUERY_MAX_RESULT_MB is set in this shell")
        val c = savedCeiling.getOrElse(ceiling())
        assert(c == 256L * Mb || c == 256L * 1000L * 1000L, "maxResultBytes is " + c)
    }
}
