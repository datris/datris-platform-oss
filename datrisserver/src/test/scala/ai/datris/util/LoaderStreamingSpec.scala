package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.controller.RestEndpointRunner
import ai.datris.model._
import com.google.gson.{Gson, JsonParser}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

/** Loader-side streaming (plans/stories/streaming-pipeline-phase2.md).
  *
  *  Loaders read the Phase 1 staged file through `Data.rowIterator()` /
  *  `recordIterator()` in bounded batches instead of the deprecated
  *  whole-payload `rows` / `rawData` accessors. Seams this spec relies on:
  *
  *  {{{
  *  trait CloseableIterator[A] extends Iterator[A] with AutoCloseable        // ai.datris.util
  *  case class Data { def rowIterator(): CloseableIterator[String]; def recordIterator(): CloseableIterator[String] }
  *  object DestSchemaProjector { def project(jobContext: JobContext, rows: Iterator[String]): Iterator[String] }
  *  object MongoDBLoader { val BatchSize: Int = 1000 }
  *  class MongoDBLoader(jobContext) {
  *      private[util] def loadJsonDocuments(dbUtil: NoSQLDbUtility, collectionName: String, everyRowContainsObject: Boolean): Long
  *  }
  *  case class RestEndpoint(..., batchSize: Int = 0)
  *  class RestEndpointRunner(jobContext: JobContext, restEndpointConfig: RestEndpoint, destination: Boolean = false) {
  *      private[datris] def requestBodies(): Iterator[String]   // one JSON body per HTTP call, in call order
  *  }
  *  }}}
  *
  *  `SpyData` extends `Data` and counts every record pulled through the
  *  iterators and every call that would materialize the payload (the calls
  *  that log "staged payload materialized by ..."). That is how batching and
  *  "no whole-payload read" are observed without a database.
  *
  *  The 1,000,000-row case is meant to run with `DATRIS_TEST_XMX=512m`
  *  (build.sbt forwards it as -Xmx to the forked test JVM); it asserts the cap
  *  took effect whenever the variable is set.
  */
class LoaderStreamingSpec extends AnyFunSuite with BeforeAndAfterAll {

    private val root: Path = Files.createTempDirectory("loader-streaming-spec")
    private val gson = new Gson
    private val TOKEN = "job-token-1"

    private def env: DatrisEnvironment = DatrisEnvironment(
        initialized = true,
        environment = "test",
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
        tempDir = root.toString
    )

    override def beforeAll(): Unit = TenantContext.set(env)
    override def afterAll(): Unit = TenantContext.clear()

    // ---- fixtures ------------------------------------------------------------

    private class RecordingStatusUtil extends StatusUtil {
        val messages = new ListBuffer[(String, String, String)]()
        var result: ScratchResult = _
        override def overrideProcessName(processName: String): Unit = ()
        override def info(state: String, description: String): Unit = messages += (("info", state, description))
        override def warn(state: String, description: String): Unit = messages += (("warning", state, description))
        override def error(state: String, description: String): Unit = messages += (("error", state, description))
        override def scratchResult(result: ScratchResult): Unit = this.result = result
    }

    /** A Data that observes how it is read. `pulled` counts records taken through
      * rowIterator()/recordIterator(); `materialized` records every accessor call
      * that would read the whole payload into heap (and log the Phase 1 warning). */
    private class SpyData(base: Data) extends Data(base.size, base.header, base.headerWithSchema, base.staged, base.rawBytes) {
        var pulled = 0L
        val materialized = new ListBuffer[String]()

        private def counting(it: CloseableIterator[String]): CloseableIterator[String] = new CloseableIterator[String] {
            override def hasNext: Boolean = it.hasNext
            override def next(): String = { pulled += 1; it.next() }
            override def close(): Unit = it.close()
        }
        override def rowIterator(): CloseableIterator[String] = counting(super.rowIterator())
        override def recordIterator(): CloseableIterator[String] = counting(super.recordIterator())
        override def rows: List[String] = {
            staged.format match {
                case StagedFormat.Delimited(_) if !staged.isEmpty => materialized += "rows"
                case _ => ()
            }
            super.rows
        }
        override def rawData: String = {
            staged.format match {
                case StagedFormat.NdJson | StagedFormat.Xml | StagedFormat.Text if !staged.isEmpty => materialized += "rawData"
                case _ => ()
            }
            super.rawData
        }
    }

    private def fields(names: String*): java.util.List[SchemaField] =
        new java.util.ArrayList[SchemaField](names.map(n => SchemaField(n, "string")).asJava)

    private def delimitedData(rows: List[String], header: List[String]): Data =
        Data(rows.map(_.length.toLong + 1L).sum, header, header.map(SchemaField(_, "string")), rows, null)

    private def jsonData(raw: String): Data = Data(raw.length.toLong, null, null, null, raw)

    private def ctx(cfg: PipelineConfig, data: Data, status: StatusUtil): JobContext =
        JobContext(
            pipelineToken = TOKEN,
            metadata = PipelineMetadata(cfg.name, "rows.csv", "/tmp/rows.csv", "pub-1", bulkUpload = false),
            data = data,
            config = cfg,
            pipelineProperties = null,
            state = null,
            thread = null,
            statusUtil = status
        )

    // ---- Acceptance 4: 1,000,000 rows, bounded memory -------------------------

    test("a 1,000,000-row staged file projects and batches end to end without holding more than one batch") {
        val rowCount = 1000000L
        val pad = "x" * 80
        val format = StagedFormat.Delimited(",")
        val (path, writer) = StagingArea.newWriter("million", format)
        try {
            var i = 1L
            while (i <= rowCount) {
                if (i > 1) writer.write("\n")
                writer.write(i.toString); writer.write(","); writer.write(pad); writer.write(","); writer.write(pad); writer.write(","); writer.write(pad)
                i += 1
            }
        } finally writer.close()
        val bytes = Files.size(path)
        assert(bytes > 200L * 1024 * 1024, s"fixture must be large enough that materializing it twice cannot fit in 512 MB, got $bytes bytes")

        try {
            // A materializing implementation dies here under DATRIS_TEST_XMX=512m:
            // the story's Verify block runs this spec with that cap.
            sys.env.get("DATRIS_TEST_XMX").foreach { xmx =>
                val max = Runtime.getRuntime.maxMemory
                assert(
                    max <= 600L * 1024 * 1024,
                    s"DATRIS_TEST_XMX=$xmx was set but the forked test JVM has maxMemory=$max — build.sbt must forward it as -Xmx"
                )
            }

            val cfg = PipelineConfig(
                name = "million",
                source = Source(
                    schemaProperties = SchemaProperties("db", fields("id", "a", "b", "c")),
                    fileAttributes = FileAttributes(csvAttributes = CsvAttributes())
                ),
                destination =
                    Destination(schemaProperties = SchemaProperties("db", fields("id", "c", "a")), database = Database(usePostgres = true, table = "t"))
            )
            val header = List("id", "a", "b", "c")
            val base = new Data(bytes, header, header.map(SchemaField(_, "string")), StagedPayload(path.toString, format, rowCount, bytes), null)
            val data = new SpyData(base)
            val jc = ctx(cfg, data, new RecordingStatusUtil)

            val it = data.rowIterator()
            var batches = 0L
            var rowsSeen = 0L
            var maxBatch = 0
            var first: String = null
            var last: String = null
            try
                DestSchemaProjector.project(jc, it).grouped(1000).foreach { batch =>
                    batches += 1
                    rowsSeen += batch.size
                    maxBatch = math.max(maxBatch, batch.size)
                    if (first == null) first = batch.head
                    last = batch.last
                    // Never more than one batch ahead of what has been consumed.
                    assert(data.pulled <= rowsSeen + 1000, s"iterator ran ${data.pulled - rowsSeen} rows ahead of the consumer")
                }
            finally it.close()

            assert(rowsSeen == rowCount)
            assert(data.pulled == rowCount)
            assert(batches == 1000)
            assert(maxBatch == 1000)
            assert(first == "1," + pad + "," + pad, "projection reorders to the destination schema and drops b")
            assert(last == rowCount + "," + pad + "," + pad)
            assert(data.materialized.isEmpty, s"the payload was materialized via ${data.materialized}")
        } finally Files.deleteIfExists(path)
    }

    // ---- Acceptance 5: Mongo batching ------------------------------------------

    private class FakeMongo extends NoSQLDbUtility {
        val inserted = new ListBuffer[String]()
        val upserted = new ListBuffer[(java.util.List[String], String)]()

        /** (k, pulled-at-the-time) for the k-th write, 1-based. */
        val pulledAtWrite = new ListBuffer[(Long, Long)]()
        var spy: SpyData = _
        private def record(): Unit = pulledAtWrite += ((pulledAtWrite.size + 1L, spy.pulled))
        override def insertJSON(tableName: String, json: String): Unit = { record(); inserted += json }
        override def upsertJSON(tableName: String, keyFields: java.util.List[String], json: String): Unit = { record(); upserted += ((keyFields, json)) }
        override def deleteAll(tableName: String): Long = 0L
        override def getItemsKeysByKeyName(tableName: String, keyName: String): List[String] = Nil
        override def getItemAttribute[T](tableName: String, keyName: String, key: String, attributeName: String): T = null.asInstanceOf[T]
        override def setItemNameValue(tableName: String, keyName: String, key: String, valueName: String, value: String): Unit = ()
        override def getItemJSON(tableName: String, keyName: String, key: String, valueName: String): Option[String] = None
        override def putItemJSON(
            tableName: String,
            keyName: String,
            key: String,
            valueName: String,
            value: String,
            sortKeyName: String,
            sortKeyValue: Number,
            extraFields: java.util.Map[String, AnyRef]
        ): Unit = ()
        override def updateItemJSON(
            tableName: String,
            keyName: String,
            key: String,
            valueName: String,
            value: String,
            sortKeyName: String,
            sortKeyValue: Number
        ): Unit = ()
        override def deleteItemJSON(tableName: String, keyName: String, key: String, sortKeyName: String, sortKeyValue: Number): Unit = ()
        override def queryJSONItemsByKey(tableName: String, keyName: String, key: String): List[String] = Nil
        override def getAllItemsAsJSON(tableName: String): List[String] = Nil
        override def getPageOfItemsAsJSON(tableName: String, pageNbr: Int, maxPageSize: Int, sortField: String, sortDescending: Boolean): List[String] = Nil
        override def getItemsSinceAsJSON(tableName: String, sortField: String, sinceEpochMs: Long, maxItems: Int): List[String] = Nil
    }

    private def mongoConfig(keyFields: List[String], everyRowContainsObject: Boolean): PipelineConfig =
        PipelineConfig(
            name = "mongo_pipe",
            source = Source(
                schemaProperties = SchemaProperties("db", fields("_json")),
                fileAttributes = FileAttributes(jsonAttributes = JsonAttributes(everyRowContainsObject = everyRowContainsObject))
            ),
            destination = Destination(
                schemaProperties = SchemaProperties("db", fields("_json")),
                database = Database(dbName = "db", table = "coll", useMongoDB = true, keyFields = if (keyFields == null) null else keyFields.asJava)
            )
        )

    /** Every write k must see exactly min(ceil(k / 1000) * 1000, total) records
      * pulled: a full batch is read before its first write, and the final
      * partial batch is read to exhaustion before its first write. */
    private def assertBatched(fake: FakeMongo, total: Long): Unit = {
        assert(MongoDBLoader.BatchSize == 1000)
        assert(fake.pulledAtWrite.size == total)
        fake.pulledAtWrite.foreach { case (k, pulled) =>
            val expected = math.min(((k + 999) / 1000) * 1000, total)
            assert(pulled == expected, s"at write #$k the loader had pulled $pulled records; batches of 1000 would have pulled $expected")
        }
        assert(fake.spy.pulled == total)
    }

    test("Mongo with no keyFields inserts in 1,000-record batches, then the final partial batch, and returns the total") {
        val total = 2500
        val ndjson = (1 to total).map(i => s"""{"id":$i,"v":"row $i"}""").mkString("\n")
        val spy = new SpyData(jsonData(ndjson))
        assert(spy.staged.format == StagedFormat.NdJson && spy.rowCount == total)
        val fake = new FakeMongo
        fake.spy = spy
        val cfg = mongoConfig(keyFields = null, everyRowContainsObject = true)
        val loader = new MongoDBLoader(ctx(cfg, spy, new RecordingStatusUtil))

        val count = loader.loadJsonDocuments(fake, "coll", everyRowContainsObject = true)

        assert(count == total)
        assert(fake.inserted.size == total && fake.upserted.isEmpty, "no keyFields → insertJSON only")
        assert(
            fake.inserted.map(j => JsonParser.parseString(j).getAsJsonObject.get("id").getAsInt).toList == (1 to total).toList,
            "records land in source order"
        )
        assertBatched(fake, total)
        assert(spy.materialized.isEmpty, s"MongoDBLoader materialized the payload via ${spy.materialized}")
    }

    test("Mongo with keyFields upserts in 1,000-record batches with the same total, honouring everyRowContainsObject=false on an array") {
        val total = 2500
        val array = (1 to total).map(i => s"""{"id":$i,"v":"row $i"}""").mkString("[", ",", "]")
        val spy = new SpyData(jsonData(array))
        assert(spy.staged.format == StagedFormat.NdJson && spy.rowCount == total, "an array stages as one NDJSON line per element")
        val fake = new FakeMongo
        fake.spy = spy
        val keys = List("id")
        val cfg = mongoConfig(keyFields = keys, everyRowContainsObject = false)
        val loader = new MongoDBLoader(ctx(cfg, spy, new RecordingStatusUtil))

        val count = loader.loadJsonDocuments(fake, "coll", everyRowContainsObject = false)

        assert(count == total)
        assert(fake.upserted.size == total && fake.inserted.isEmpty, "keyFields → upsertJSON only")
        assert(fake.upserted.forall(_._1.asScala.toList == keys))
        assert(fake.upserted.map(u => JsonParser.parseString(u._2).getAsJsonObject.get("id").getAsInt).toList == (1 to total).toList)
        assertBatched(fake, total)
        assert(spy.materialized.isEmpty, s"MongoDBLoader materialized the payload via ${spy.materialized}")
    }

    test("Mongo: exactly 1,000 records is one full batch and no partial batch; a single object is one record") {
        val ndjson = (1 to 1000).map(i => s"""{"id":$i}""").mkString("\n")
        val spy = new SpyData(jsonData(ndjson))
        val fake = new FakeMongo
        fake.spy = spy
        val count = new MongoDBLoader(ctx(mongoConfig(null, everyRowContainsObject = true), spy, new RecordingStatusUtil))
            .loadJsonDocuments(fake, "coll", everyRowContainsObject = true)
        assert(count == 1000 && fake.inserted.size == 1000)
        assertBatched(fake, 1000)

        val one = new SpyData(jsonData("""{"only":"one"}"""))
        val fakeOne = new FakeMongo
        fakeOne.spy = one
        val countOne = new MongoDBLoader(ctx(mongoConfig(null, everyRowContainsObject = false), one, new RecordingStatusUtil))
            .loadJsonDocuments(fakeOne, "coll", everyRowContainsObject = false)
        assert(countOne == 1 && fakeOne.inserted.size == 1)
        assert(JsonParser.parseString(fakeOne.inserted.head).getAsJsonObject.get("only").getAsString == "one")
    }

    // ---- Acceptance 6: RestEndpointRunner batching ------------------------------

    private def restConfig(endpoint: RestEndpoint): PipelineConfig =
        PipelineConfig(
            name = "rest_pipe",
            source = Source(schemaProperties = SchemaProperties("db", fields("id", "name")), fileAttributes = FileAttributes(csvAttributes = CsvAttributes())),
            destination = Destination(schemaProperties = SchemaProperties("db", fields("id", "name")), restEndpoint = endpoint)
        )

    private val restRows: List[String] = (1 to 600).map(i => s"$i,name$i").toList

    /** Today's body, built exactly as RestEndpointRunner.buildRequestBody does. */
    private def legacyBody(data: Data): com.google.gson.JsonElement = {
        val map = new java.util.HashMap[String, AnyRef]()
        map.put("size", java.lang.Long.valueOf(data.size))
        map.put("header", data.header.asJava)
        map.put("rows", data.rows.asJava)
        val payload = new java.util.HashMap[String, AnyRef]()
        payload.put("pipelineToken", TOKEN)
        payload.put("pipelineName", "rest_pipe")
        payload.put("data", map)
        JsonParser.parseString(gson.toJson(payload))
    }

    test("RestEndpointRunner destination with batchSize = 0 produces today's identical single body") {
        val data = delimitedData(restRows, List("id", "name"))
        val endpoint = RestEndpoint(endpoint = "http://rest.invalid/hook", batchSize = 0)
        val runner = new RestEndpointRunner(ctx(restConfig(endpoint), data, new RecordingStatusUtil), endpoint, destination = true)

        val bodies = runner.requestBodies().toList
        assert(bodies.size == 1, s"batchSize = 0 must be exactly one call, got ${bodies.size}")
        val body = JsonParser.parseString(bodies.head)
        assert(body == legacyBody(data), s"body differs from today's envelope:\n${bodies.head}")
        val obj = body.getAsJsonObject
        assert(!obj.has("batch") && !obj.has("ofBatches"), "the single-call envelope carries no batch fields")
        assert(obj.getAsJsonObject("data").getAsJsonArray("rows").size() == 600)
    }

    test("RestEndpointRunner destination with batchSize = 250 over 600 rows produces 3 bodies, batch 1..3 of 3") {
        val data = new SpyData(delimitedData(restRows, List("id", "name")))
        val endpoint = RestEndpoint(endpoint = "http://rest.invalid/hook", batchSize = 250)
        val runner = new RestEndpointRunner(ctx(restConfig(endpoint), data, new RecordingStatusUtil), endpoint, destination = true)

        val bodies = runner.requestBodies().map(b => JsonParser.parseString(b).getAsJsonObject).toList
        assert(bodies.size == 3, s"600 rows / 250 = 3 calls, got ${bodies.size}")

        assert(bodies.map(_.get("batch").getAsInt) == List(1, 2, 3))
        assert(bodies.forall(_.get("ofBatches").getAsInt == 3))
        val rowsPerBody = bodies.map(_.getAsJsonObject("data").getAsJsonArray("rows").size())
        assert(rowsPerBody == List(250, 250, 100), s"got $rowsPerBody")
        // Same envelope: token, name, header on every call; rows partitioned in order.
        bodies.foreach { b =>
            assert(b.get("pipelineToken").getAsString == TOKEN)
            assert(b.get("pipelineName").getAsString == "rest_pipe")
            assert(b.getAsJsonObject("data").getAsJsonArray("header").asScala.map(_.getAsString).toList == List("id", "name"))
        }
        val allRows = bodies.flatMap(_.getAsJsonObject("data").getAsJsonArray("rows").asScala.map(_.getAsString))
        assert(allRows == restRows)
        assert(data.materialized.isEmpty, s"batched mode must stream rowIterator(), not materialize via ${data.materialized}")
    }

    test("RestEndpointRunner preprocessor role ignores batchSize and keeps today's single body") {
        val data = delimitedData(restRows, List("id", "name"))
        val endpoint = RestEndpoint(endpoint = "http://rest.invalid/hook", batchSize = 250)
        val cfg = restConfig(endpoint).copy(preprocessor = endpoint)
        val runner = new RestEndpointRunner(ctx(cfg, data, new RecordingStatusUtil), endpoint)

        val bodies = runner.requestBodies().toList
        assert(bodies.size == 1, "the preprocessor path is Phase 3 and stays one call")
        assert(JsonParser.parseString(bodies.head) == legacyBody(data))
    }

    // ---- Unit-level proxy for "the materialized-payload log names no loader":
    // ScratchLoader is the one destination that runs without a live service.

    private def scratchConfig(source: String): PipelineConfig =
        gson.fromJson(
            s"""{"name":"scratch_pipe",
               |"source":{"fileAttributes":{$source},"schemaProperties":{"fields":[{"name":"id","type":"string"},{"name":"name","type":"string"}]}},
               |"destination":{"scratch":{}}}""".stripMargin,
            classOf[PipelineConfig]
        )

    private def scratchLines(scratchRoot: Path): List[String] = {
        val f = scratchRoot.resolve("_scratch").resolve("scratch_pipe").resolve(TOKEN + ".jsonl")
        assert(Files.exists(f), s"expected the scratch object at $f")
        Files.readAllLines(f, StandardCharsets.UTF_8).asScala.toList.filter(_.trim.nonEmpty)
    }

    test("ScratchLoader streams CSV rows through rowIterator() and never materializes the payload") {
        val scratchRoot = Files.createTempDirectory("loader-streaming-scratch")
        val spy = new SpyData(delimitedData(List("1,alice", "2,bob", "3,carol"), List("id", "name")))
        val status = new RecordingStatusUtil
        val cfg = scratchConfig(""""csvAttributes":{"delimiter":","}""")

        new ScratchLoader(ctx(cfg, spy, status), ScratchLoader.Settings(scratchRoot.toUri.toString.stripSuffix("/"), 200, 24)).process()

        val lines = scratchLines(scratchRoot)
        assert(lines.size == 3)
        assert(lines.map(l => JsonParser.parseString(l).getAsJsonObject.get("name").getAsString) == List("alice", "bob", "carol"))
        assert(status.result != null && status.result.resultRowCount == 3L)
        assert(spy.pulled == 3, "rows must come through rowIterator()")
        assert(spy.materialized.isEmpty, s"ScratchLoader materialized the payload via ${spy.materialized}")
    }

    test("ScratchLoader streams JSON records through recordIterator() and never materializes the payload") {
        val scratchRoot = Files.createTempDirectory("loader-streaming-scratch")
        val spy = new SpyData(jsonData("""[{"a":1},{"a":2},{"a":3}]"""))
        val status = new RecordingStatusUtil
        val cfg = scratchConfig(""""jsonAttributes":{"everyRowContainsObject":false}""")

        new ScratchLoader(ctx(cfg, spy, status), ScratchLoader.Settings(scratchRoot.toUri.toString.stripSuffix("/"), 200, 24)).process()

        val lines = scratchLines(scratchRoot)
        assert(lines.map(l => JsonParser.parseString(l).getAsJsonObject.get("a").getAsInt) == List(1, 2, 3))
        assert(status.result != null && status.result.resultRowCount == 3L)
        assert(spy.pulled == 3, "records must come through recordIterator()")
        assert(spy.materialized.isEmpty, s"ScratchLoader materialized the payload via ${spy.materialized}")
    }
}
