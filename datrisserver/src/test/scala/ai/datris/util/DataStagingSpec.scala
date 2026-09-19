package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path, Paths}
import scala.collection.JavaConverters._

/** File-backed `Data` (plans/stories/streaming-pipeline.md, Phase 1).
  *
  *  {{{
  *  case class Data(size, header, headerWithSchema, staged: StagedPayload, rawBytes)
  *      def rowCount: Long
  *      def rowIterator(): Iterator[String]
  *      @deprecated def rows: List[String]      // materializes; refuses above the cap
  *      def withRows(rows: List[String]): Data  // writes a NEW staged file
  *  object Data {
  *      // old positional shape, stages its input so existing call sites compile
  *      def apply(size, header, headerWithSchema, rows, rawData, rawBytes = null): Data
  *  }
  *  case class StagedPayload(path, format: StagedFormat, rowCount, bytes)
  *  }}}
  *
  *  The staging root and the materialize cap come from `DatrisEnvironment`
  *  (`tempDir` / `pipelineMaterializeMaxMB`, i.e. `DATRIS_TEMP_DIR` /
  *  `PIPELINE_MATERIALIZE_MAX_MB`). `DatrisEnvironment.current` is null in unit
  *  tests, so each test installs a per-thread environment via `TenantContext`
  *  pointing at a private temp directory.
  */
class DataStagingSpec extends AnyFunSuite with BeforeAndAfterAll {

    private val root: Path = Files.createTempDirectory("data-staging-spec")

    private def env(capMB: Int): DatrisEnvironment = DatrisEnvironment(
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
        tempDir = root.toString,
        pipelineMaterializeMaxMB = capMB
    )

    private def withCap[T](capMB: Int)(body: => T): T = {
        TenantContext.set(env(capMB))
        try body
        finally TenantContext.clear()
    }

    override def afterAll(): Unit = TenantContext.clear()

    private val fields = List(SchemaField("id", "string"), SchemaField("v", "string"))

    private def stagedPath(d: Data): Path = Paths.get(d.staged.path.toString)

    private def delimited(rows: List[String]): Data =
        Data(rows.map(_.length.toLong + 1L).sum, List("id", "v"), fields, rows, null)

    // Acceptance 1
    test("a Data built from 100k rows exposes rowCount and rowIterator without retaining a List[String]") {
        withCap(256) {
            val rows = (1 to 100000).map(i => i + ",v" + i).toList
            val data = delimited(rows)

            assert(data.rowCount == 100000, "rowCount comes from the staged payload")
            assert(data.staged.rowCount == 100000)
            assert(data.rowIterator().length == 100000, "rowIterator streams every staged row")

            // Same rows, same order, header stripped.
            val it = data.rowIterator()
            assert(it.next() == "1,v1")
            assert(data.rowIterator().drop(99999).next() == "100000,v100000")

            // The payload lives on disk under DATRIS_TEMP_DIR, not in the case class.
            assert(Files.isRegularFile(stagedPath(data)), "staged file must exist while the Data is alive")
            assert(stagedPath(data).startsWith(root), s"staged file ${stagedPath(data)} must be under the configured tempDir $root")
            assert(data.staged.bytes > 0L)
            val retainedLists = data.productIterator.collect { case l: List[_] => l.size }.toList
            assert(retainedLists.forall(_ < 100000), s"no field of Data may hold the 100k rows as a List, got list sizes $retainedLists")
        }
    }

    // Acceptance 2
    test("data.rows above pipelineMaterializeMaxMB throws a DatrisException naming the cap env var") {
        withCap(1) {
            // ~2.2 MB of rows, comfortably over a 1 MB cap.
            val rows = (1 to 40000).map(i => i + "," + ("x" * 50)).toList
            val data = delimited(rows)
            assert(data.staged.bytes > 1L * 1024 * 1024, "fixture must exceed the 1 MB cap")

            val e = intercept[DatrisException](data.rows)
            assert(e.getMessage.contains("PIPELINE_MATERIALIZE_MAX_MB"), s"message must name the cap env var, got: ${e.getMessage}")
            // The iterator path is not capped — only whole-payload materialization is.
            assert(data.rowIterator().length == 40000)
        }
    }

    // Acceptance 2
    test("data.rows below pipelineMaterializeMaxMB returns the exact rows") {
        withCap(1) {
            val rows = List("1,a", "2,\"b,c\"", "3,", "4,d")
            val data = delimited(rows)
            assert(data.staged.bytes < 1L * 1024 * 1024)
            assert(data.rows == rows)
            // Materializing does not consume or remove the staged file.
            assert(data.rows == rows)
            assert(Files.isRegularFile(stagedPath(data)))
        }
    }

    // Acceptance 3
    test("Data.withRows writes a new staged file and leaves the previous file on disk") {
        withCap(256) {
            val original = delimited(List("1,a", "2,b", "3,c"))
            val before = stagedPath(original)

            val replaced = original.withRows(List("9,z"))

            assert(stagedPath(replaced) != before, "withRows must stage into a new file")
            assert(Files.isRegularFile(before), "the previous staged file must not be deleted mid-run")
            assert(Files.isRegularFile(stagedPath(replaced)))
            assert(replaced.rowCount == 1)
            assert(replaced.rows == List("9,z"))
            // The original Data is untouched.
            assert(original.rowCount == 3)
            assert(original.rows == List("1,a", "2,b", "3,c"))
            // Non-payload fields carry over.
            assert(replaced.header == original.header)
            assert(replaced.headerWithSchema == original.headerWithSchema)
        }
    }

    // ------------------------------------------------------------------------
    // Phase 2 (plans/stories/streaming-pipeline-phase2.md): rowIterator() is
    // record-aware and closeable.
    //
    //   Data.rowIterator(): CloseableIterator[String]    // ai.datris.util.CloseableIterator
    //   trait CloseableIterator[A] extends Iterator[A] with AutoCloseable
    // ------------------------------------------------------------------------

    /** Stage a CSV exactly the way StreamNotifier.stageData does for a delimited
      * upload: CSVReader.readToWriter into StagingArea.newWriter, then a Data over
      * the resulting StagedPayload with readToWriter's row count. */
    private def stageCsv(csv: String, columns: List[String]): Data = {
        val format = StagedFormat.Delimited(",")
        val (path, writer) = StagingArea.newWriter("spec", format)
        val rowCount =
            try new CSVReader().readToWriter(
                    new java.io.ByteArrayInputStream(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    header = true,
                    delimiter = ",",
                    columnList = columns,
                    columnFilter = columns,
                    removeHeader = true,
                    out = writer
                )
            finally writer.close()
        new Data(csv.length.toLong, columns, columns.map(SchemaField(_, "string")), StagedPayload(path.toString, format, rowCount, Files.size(path)), null)
    }

    // Phase 2 Acceptance 1
    test("rowIterator is record-aware: values holding LF, a lone CR, CRLF and doubled quotes each iterate as one element") {
        withCap(256) {
            // Five data rows. Rows 1-3 carry a line terminator INSIDE a quoted value;
            // row 4 carries doubled quotes; row 5 is plain. readToWriter re-quotes
            // values containing "\n" or a quote; the lone "\r" value is the case the
            // story calls out ("touch readToWriter only if the round-trip is
            // provably lossy") — whether it is re-quoted or not, it must still
            // come back as ONE element that still contains the "\r".
            val csv = "id,v\n" +
                "1,\"a\nb\"\n" +
                "2,\"a\rb\"\n" +
                "3,\"a\r\nb\"\n" +
                "4,\"say \"\"hi\"\"\"\n" +
                "5,plain\n"
            val data = stageCsv(csv, List("id", "v"))
            assert(data.rowCount == 5, "readToWriter counted five data rows")

            val rows = data.rowIterator().toList
            assert(rows.length == data.rowCount, s"rowIterator().length must equal rowCount (5); a line-per-element reader yields ${rows.length}: $rows")

            assert(rows(0) == "1,\"a\nb\"", s"embedded LF stays inside the quoted value, got ${rows(0)}")
            assert(rows(1).startsWith("2,") && rows(1).contains("a\rb"), s"lone CR is data, not a terminator, got ${rows(1)}")
            assert(rows(2) == "3,\"a\r\nb\"", s"embedded CRLF stays inside the quoted value, got ${rows(2)}")
            assert(rows(3) == "4,\"say \"\"hi\"\"\"", s"doubled quotes are untouched, got ${rows(3)}")
            assert(rows(4) == "5,plain")

            // The deprecated whole-payload accessor agrees with the iterator.
            assert(data.rows == rows)
        }
    }

    /** Is `path` open by this JVM? Linux: /proc/self/fd; elsewhere: lsof -p. */
    private def isOpenByThisJvm(path: Path): Option[Boolean] = {
        val target = path.toRealPath().toString
        val procFd = Paths.get("/proc/self/fd")
        if (Files.isDirectory(procFd)) {
            val entries = Files.list(procFd)
            try Some(entries.iterator().asScala.exists(fd => scala.util.Try(Files.readSymbolicLink(fd).toString).toOption.contains(target)))
            finally entries.close()
        } else {
            val pid = java.lang.management.ManagementFactory.getRuntimeMXBean.getName.takeWhile(_ != '@')
            scala.util.Try {
                val proc = new ProcessBuilder("lsof", "-p", pid, "-Fn").redirectErrorStream(true).start()
                val out = scala.io.Source.fromInputStream(proc.getInputStream).getLines().toList
                proc.waitFor()
                out.exists(line => line.startsWith("n") && line.drop(1) == target)
            }.toOption
        }
    }

    // Phase 2 Acceptance 2
    test("rowIterator is closeable: close() after 1 of 1,000 rows releases the file handle") {
        withCap(256) {
            val data = delimited((1 to 1000).map(i => i + ",v" + i).toList)
            val path = stagedPath(data)

            val it: CloseableIterator[String] = data.rowIterator()
            assert(it.next() == "1,v1")

            val openBefore = isOpenByThisJvm(path)
            assume(
                openBefore.contains(true),
                "cannot observe open file handles on this platform (no /proc/self/fd and no lsof); " +
                    "or the iterator did not open the file yet after next()"
            )

            it.close()

            assert(isOpenByThisJvm(path).contains(false), s"the staged file $path is still open after close()")
            assert(!it.hasNext, "a closed iterator is exhausted")
            // The staged file can be deleted on the spot.
            Files.delete(path)
            assert(!Files.exists(path))
            // close() is idempotent.
            it.close()
        }
    }
}
