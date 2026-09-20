package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import ai.datris.util.StagingArea
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.{GZIPOutputStream, ZipEntry, ZipOutputStream}
import scala.collection.JavaConverters._

/** `UploadStager` (plans/stories/streaming-pipeline-phase5.md, Step 1): the
  *  upload lane stops calling `MultipartFile.getBytes`. The archive walk and the
  *  CSV batch concatenation that lived inline in
  *  `FileUploadAPIController.uploadRawFile` move into a `private[api]` helper
  *  that works on an `InputStream` and writes staged files, so it is
  *  unit-testable without `spring-test` (`MockMultipartFile` is not on the
  *  classpath and adding it is out of scope).
  *
  *  Seam this spec relies on:
  *
  *  {{{
  *  package ai.datris.api
  *
  *  /** One file staged from an upload part: the name the run is filed under
  *    * (`entry.getName.split("/").last` for archive entries, the part's
  *    * filename minus `.gz` for gzip, the part's filename otherwise), the
  *    * staged path (under the CURRENT StagingArea token dir) and its size. */
  *  private[api] case class StagedUpload(name: String, path: java.nio.file.Path, bytes: Long)
  *
  *  private[api] object UploadStager {
  *      /** Stream one upload part into staged files, never `readAllBytes` / `getBytes`:
  *        *  - plain file                       -> one StagedUpload, bytes copied verbatim
  *        *  - `.gz`                            -> one StagedUpload, the decompressed bytes
  *        *  - archive (.zip/.tar/.jar), entries staged one at a time in entry order,
  *        *    skipping directories and names starting with `__MAC`, `META-INF`, `./._`;
  *        *    no usable entry -> DatrisException("No files found in archive: " + filename)
  *        *  - archive on a CSV pipeline with > 1 entry (`csvPipeline`)
  *        *                                     -> ONE StagedUpload named after entry 1:
  *        *    entry 1 whole, then each later entry minus its header line when
  *        *    `csvHeader`, with today's newline fix (a '\n' is inserted when the
  *        *    output so far does not end with one).
  *        *  Bytes written are checked against StagingArea.overBudget; over budget ->
  *        *  DatrisException(StagingArea.budgetExceededMessage(bytes)) and nothing is
  *        *  left under the staging root. Closes `source`. */
  *      def stage(source: InputStream, filename: String, csvPipeline: Boolean, csvHeader: Boolean): List[StagedUpload]
  *  }
  *  }}}
  *
  *  The 250,000-row case is meant to run with `DATRIS_TEST_XMX=512m`
  *  (build.sbt forwards it as -Xmx); it asserts the cap took effect whenever
  *  the variable is set.
  */
class UploadStagingSpec extends AnyFunSuite with BeforeAndAfterAll {

    private val root: Path = Files.createTempDirectory("upload-staging-spec")

    private def env(pipelineMaxPayloadMB: Int = -1): DatrisEnvironment = DatrisEnvironment(
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
        pipelineMaxPayloadMB = pipelineMaxPayloadMB
    )

    // The suite's default env stays bound for the whole run so the token-dir
    // assertions and the per-test StagingArea.delete cleanup resolve against
    // the same temp root the stage call used; withEnv restores it afterwards.
    private def withEnv[T](e: DatrisEnvironment)(body: => T): T = {
        val previous = TenantContext.get()
        TenantContext.set(e)
        try body
        finally previous.fold(TenantContext.clear())(TenantContext.set)
    }

    override def beforeAll(): Unit = TenantContext.set(env())
    override def afterAll(): Unit = TenantContext.clear()

    // ---- helpers -------------------------------------------------------------

    private def bytesOf(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

    private def stream(bytes: Array[Byte]): InputStream = new ByteArrayInputStream(bytes)

    private def text(p: Path): String = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

    /** A zip with the given (entryName, content) pairs in order; a name ending in "/" is a directory entry. */
    private def zip(entries: (String, String)*): Array[Byte] = {
        val buf = new ByteArrayOutputStream()
        val out = new ZipOutputStream(buf)
        entries.foreach { case (name, content) =>
            out.putNextEntry(new ZipEntry(name))
            if (!name.endsWith("/")) out.write(bytesOf(content))
            out.closeEntry()
        }
        out.close()
        buf.toByteArray
    }

    private def gzip(content: Array[Byte]): Array[Byte] = {
        val buf = new ByteArrayOutputStream()
        val out = new GZIPOutputStream(buf)
        out.write(content)
        out.close()
        buf.toByteArray
    }

    private def regularFilesUnder(dir: Path): List[Path] = {
        if (!Files.isDirectory(dir)) return Nil
        val s = Files.walk(dir)
        try s.iterator().asScala.filter(Files.isRegularFile(_)).toList
        finally s.close()
    }

    /** Stage under a fresh upload token, like the controller does with StagingArea.withToken. */
    private def stage(token: String, bytes: Array[Byte], filename: String, csvPipeline: Boolean, csvHeader: Boolean = true): List[StagedUpload] =
        StagingArea.withToken(token)(UploadStager.stage(stream(bytes), filename, csvPipeline, csvHeader))

    private val one = "id,amount\n1,5\n"
    private val two = "id,amount\n2,9" // no trailing newline: exercises today's newline fix
    private val three = "id,amount\n3,11\n"

    // ================================================================
    // Acceptance 1 — a 250,000-row CSV part stages with the same rows getBytes did
    // ================================================================

    test("a 250,000-row CSV part stages byte-for-byte with 250,001 lines under the test heap cap") {
        sys.env.get("DATRIS_TEST_XMX").foreach { xmx =>
            val max = Runtime.getRuntime.maxMemory
            assert(
                max <= 600L * 1024 * 1024,
                s"DATRIS_TEST_XMX=$xmx was set but the forked test JVM has maxMemory=$max — build.sbt must forward it as -Xmx"
            )
        }
        val csv = Files.createTempFile("upload-spec-", ".csv")
        val w = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)
        try {
            w.write("id,amount,note\n")
            var i = 0
            while (i < 250000) {
                w.write(i.toString); w.write(","); w.write((i * 3).toString); w.write(",\"row ")
                w.write(i.toString); w.write(", padded\"\n")
                i += 1
            }
        } finally w.close()
        val expectedBytes = Files.size(csv)

        val staged = withEnv(env())(StagingArea.withToken("upload-250k")(UploadStager.stage(Files.newInputStream(csv), "big.csv", true, true)))
        try {
            assert(staged.size == 1, s"a plain CSV part is one staged file, got ${staged.map(_.name)}")
            val s = staged.head
            assert(s.name == "big.csv")
            assert(s.bytes == expectedBytes)
            assert(Files.size(s.path) == expectedBytes)
            assert(Files.mismatch(csv, s.path) == -1L, "the staged file is exactly what getBytes handed StreamNotifier before")
            assert(Files.lines(s.path, StandardCharsets.UTF_8).count() == 250001L)
            assert(s.path.startsWith(StagingArea.forToken("upload-250k")), s"staged under the upload token dir, got ${s.path}")
        } finally {
            Files.deleteIfExists(csv)
            StagingArea.delete("upload-250k")
        }
    }

    // ================================================================
    // Acceptance 2 — archives and gzip
    // ================================================================

    test("a .zip of 3 CSVs on a CSV pipeline yields one staged file with one header and all rows, named after entry 1") {
        val archive = zip(
            "data/" -> "",
            "__MACOSX/._one.csv" -> "junk",
            "data/one.csv" -> one,
            "META-INF/MANIFEST.MF" -> "junk",
            "data/two.csv" -> two,
            "./._three.csv" -> "junk",
            "data/three.csv" -> three
        )
        val staged = withEnv(env())(stage("upload-zip-csv", archive, "batch.zip", csvPipeline = true))
        try {
            assert(staged.size == 1, s"CSV batch mode concatenates into one staged file, got ${staged.map(_.name)}")
            val s = staged.head
            assert(s.name == "one.csv", "the batch is filed under the first entry's basename, as before")
            // Exactly today's concatenation: entry 1 whole, later entries minus
            // their header, a '\n' inserted only when the output so far lacks one.
            val expected = "id,amount\n1,5\n2,9\n3,11\n"
            assert(text(s.path) == expected, s"got: ${text(s.path)}")
            assert(s.bytes == bytesOf(expected).length.toLong)
            assert(Files.lines(s.path, StandardCharsets.UTF_8).filter(_ == "id,amount").count() == 1L, "one header line")
        } finally StagingArea.delete("upload-zip-csv")
    }

    test("a .zip of 3 CSVs on a CSV pipeline without a header row concatenates every entry whole") {
        val archive = zip("one.csv" -> "1,5\n", "two.csv" -> "2,9", "three.csv" -> "3,11\n")
        val staged = withEnv(env())(stage("upload-zip-noheader", archive, "batch.zip", csvPipeline = true, csvHeader = false))
        try {
            assert(staged.size == 1)
            assert(text(staged.head.path) == "1,5\n2,9\n3,11\n", s"got: ${text(staged.head.path)}")
        } finally StagingArea.delete("upload-zip-noheader")
    }

    test("the same .zip on a non-CSV pipeline yields 3 staged files in entry order, verbatim, junk entries skipped") {
        val archive = zip(
            "__MACOSX/._one.json" -> "junk",
            "one.json" -> "{\"id\":1}",
            "nested/two.json" -> "{\"id\":2}",
            "META-INF/x" -> "junk",
            "dir/" -> "",
            "./._three.json" -> "junk",
            "three.json" -> "{\"id\":3}"
        )
        val staged = withEnv(env())(stage("upload-zip-json", archive, "batch.zip", csvPipeline = false))
        try {
            assert(staged.map(_.name) == List("one.json", "two.json", "three.json"), s"got ${staged.map(_.name)}")
            assert(staged.map(s => text(s.path)) == List("{\"id\":1}", "{\"id\":2}", "{\"id\":3}"))
            assert(staged.map(_.bytes) == List(8L, 8L, 8L))
            assert(staged.map(_.path).distinct.size == 3, "each entry is its own staged file")
            assert(staged.forall(_.path.startsWith(StagingArea.forToken("upload-zip-json"))))
        } finally StagingArea.delete("upload-zip-json")
    }

    test("a single-entry .zip on a CSV pipeline is one file, not batch mode, and keeps its header") {
        val archive = zip("only.csv" -> one)
        val staged = withEnv(env())(stage("upload-zip-single", archive, "only.zip", csvPipeline = true))
        try {
            assert(staged.size == 1)
            assert(staged.head.name == "only.csv")
            assert(text(staged.head.path) == one)
        } finally StagingArea.delete("upload-zip-single")
    }

    test("an archive with no usable entry raises 'No files found in archive'") {
        val archive = zip("__MACOSX/._one.csv" -> "junk", "META-INF/MANIFEST.MF" -> "junk", "empty/" -> "")
        val e = intercept[DatrisException](withEnv(env())(stage("upload-zip-empty", archive, "empty.zip", csvPipeline = true)))
        assert(e.getMessage.contains("No files found in archive: empty.zip"), s"got: ${e.getMessage}")
        StagingArea.delete("upload-zip-empty")
    }

    test("a .gz yields one staged file, decompressed, named without the .gz suffix") {
        val staged = withEnv(env())(stage("upload-gz", gzip(bytesOf(one)), "orders.csv.gz", csvPipeline = true))
        try {
            assert(staged.size == 1)
            assert(staged.head.name == "orders.csv")
            assert(text(staged.head.path) == one)
            assert(staged.head.bytes == bytesOf(one).length.toLong)
        } finally StagingArea.delete("upload-gz")
    }

    test("a plain non-archive part on a non-CSV pipeline is one verbatim staged file") {
        val json = "{\"id\": 1, \"note\": \"<a & b>\"}"
        val staged = withEnv(env())(stage("upload-plain", bytesOf(json), "one.json", csvPipeline = false))
        try {
            assert(staged.size == 1)
            assert(staged.head.name == "one.json")
            assert(text(staged.head.path) == json)
        } finally StagingArea.delete("upload-plain")
    }

    // ================================================================
    // Acceptance 3 — the upload lane honours PIPELINE_MAX_PAYLOAD_MB
    // ================================================================

    test("bytes above PIPELINE_MAX_PAYLOAD_MB fail with the disk-budget message and leave no file under the staging root") {
        // 1 MB budget; a ~1.5 MB plain CSV and a zip whose entries add up to ~1.5 MB.
        val row = "1,\"" + ("x" * 60) + "\"\n"
        val big = new StringBuilder("id,pad\n")
        while (big.length < 1536 * 1024) big.append(row)
        val bigBytes = bytesOf(big.toString)

        withEnv(env(pipelineMaxPayloadMB = 1)) {
            val plain = intercept[DatrisException](stage("upload-budget-plain", bigBytes, "big.csv", csvPipeline = true))
            assert(plain.getMessage.contains(StagingArea.PayloadBudgetEnvVar + " = 1 MB"), s"got: ${plain.getMessage}")
            assert(plain.getMessage.contains("disk budget"), s"got: ${plain.getMessage}")
            assert(regularFilesUnder(root).isEmpty, s"no partial file may remain, found ${regularFilesUnder(root)}")

            val half = big.toString.substring(0, 800 * 1024)
            val archive = zip("a.csv" -> half, "b.csv" -> half)
            val zipped = intercept[DatrisException](stage("upload-budget-zip", archive, "big.zip", csvPipeline = true))
            assert(zipped.getMessage.contains(StagingArea.PayloadBudgetEnvVar), s"got: ${zipped.getMessage}")
            assert(regularFilesUnder(root).isEmpty, s"no partial file may remain, found ${regularFilesUnder(root)}")
        }

        // The same plain part is fine when the budget is unlimited (0) or generous.
        withEnv(env(pipelineMaxPayloadMB = 0)) {
            val ok = stage("upload-budget-unlimited", bigBytes, "big.csv", csvPipeline = true)
            assert(ok.size == 1 && ok.head.bytes == bigBytes.length.toLong)
            StagingArea.delete("upload-budget-unlimited")
        }
    }
}
