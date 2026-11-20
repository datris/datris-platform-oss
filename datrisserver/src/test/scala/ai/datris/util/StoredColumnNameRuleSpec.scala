package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.Gson
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

/** A pipeline whose schema gained a column name that is not plain (schema
  * evolution keeps the header cell as written) can be saved again
  * unchanged; a new or changed name still has to be plain.
  *
  * {{{
  * object ColumnNameRule { plain, problem, kept, storedNames, refusal }
  * object PipelineValidatorUtil {
  *     def validate(config: PipelineConfig, stored: () => PipelineConfig): Unit   // stored: the config of this name, read at most once
  * }
  * }}}
  * `savePipelineCore` (POST /pipeline, version restore, catalog restore)
  * passes the stored config of the name it saves; the validator is the one
  * place the rule runs. */
class StoredColumnNameRuleSpec extends AnyFunSuite with BeforeAndAfterAll {

    private val root: Path = Files.createTempDirectory("stored-column-name-rule-spec")

    private def testEnv: DatrisEnvironment = DatrisEnvironment(
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

    override def beforeAll(): Unit = TenantContext.set(testEnv)
    override def afterAll(): Unit = TenantContext.clear()

    private val gson = new Gson

    private class Quiet extends StatusUtil {
        override def info(state: String, description: String): Unit = ()
        override def warn(state: String, description: String): Unit = ()
        override def error(state: String, description: String): Unit = ()
    }

    private def fields(fs: (String, String)*): java.util.List[SchemaField] =
        new java.util.ArrayList[SchemaField](fs.map { case (n, t) => SchemaField(n, t) }.asJava)

    private def csv(name: String, dest: Destination, src: Seq[(String, String)]): PipelineConfig =
        PipelineConfig(
            name = name,
            source = Source(schemaProperties = SchemaProperties("db", fields(src: _*)), fileAttributes = FileAttributes(csvAttributes = CsvAttributes())),
            destination = dest
        )

    private def postgres(src: (String, String)*): PipelineConfig =
        csv(
            "col_pg",
            Destination(
                schemaProperties = SchemaProperties("db", fields(src: _*)),
                database = Database(dbName = "datris", schema = "public", table = "col_pg", usePostgres = true)
            ),
            src
        )

    private def objectStore(fmt: String, src: (String, String)*): PipelineConfig =
        csv(
            "col_os_" + fmt,
            Destination(schemaProperties = SchemaProperties("db", fields(src: _*)), objectStore = ObjectStore(prefixKey = "col", fileFormat = fmt)),
            src
        )

    private val base = Seq("id" -> "string", "amount" -> "string")

    /** The config schema evolution stores when a header adds `cells` (each
      * through validatedCsvHeader, as an upload's header is). */
    private def evolved(plain: PipelineConfig, headerLine: String): PipelineConfig = {
        val header = DataUtil.validatedCsvHeader(headerLine, ",", plain.name)
        val persisted = ListBuffer[PipelineConfig]()
        DataUtil.evolveSchema(header, plain, new Quiet, c => persisted += c)
        assert(persisted.size == 1, "evolution persisted once")
        persisted.head
    }

    private def names(p: SchemaProperties): List[String] = p.fields.asScala.map(_.name).toList

    private def refusal(candidate: PipelineConfig, stored: PipelineConfig): Option[String] =
        try { PipelineValidatorUtil.validate(candidate, () => stored); None }
        catch { case e: DatrisException => Some(e.getMessage) }

    private def withSource(c: PipelineConfig, f: java.util.List[SchemaField] => java.util.List[SchemaField]): PipelineConfig =
        c.copy(source = c.source.copy(schemaProperties = c.source.schemaProperties.copy(fields = f(c.source.schemaProperties.fields))))
    private def withDest(c: PipelineConfig, f: java.util.List[SchemaField] => java.util.List[SchemaField]): PipelineConfig =
        c.copy(destination = c.destination.copy(schemaProperties = c.destination.schemaProperties.copy(fields = f(c.destination.schemaProperties.fields))))
    private def both(c: PipelineConfig, f: java.util.List[SchemaField] => java.util.List[SchemaField]): PipelineConfig = withDest(withSource(c, f), f)
    private def mapNames(m: String => String): java.util.List[SchemaField] => java.util.List[SchemaField] =
        fs => new java.util.ArrayList[SchemaField](fs.asScala.map(f => f.copy(name = m(f.name))).asJava)

    // ------------------------------------------------------------------ pure

    test("ColumnNameRule: plain names pass; a non-plain name is kept only when the stored schema has exactly that name in lower case") {
        assert(ColumnNameRule.plain("Order_Id_2") && !ColumnNameRule.plain("a.b") && !ColumnNameRule.plain("") && !ColumnNameRule.plain(null))
        assert(ColumnNameRule.kept("a.b", Seq("id", "a.b")))
        assert(!ColumnNameRule.kept("a.b", Seq("id")))
        assert(!ColumnNameRule.kept("A.B", Seq("a.b")), "a different case is a different name")
        assert(!ColumnNameRule.kept(" a.b", Seq("a.b")), "different spacing is a different name")
        assert(!ColumnNameRule.kept("A.B", Seq("A.B")), "the save lower-cases names, so an upper-case odd name would be renamed: not kept")
        assert(ColumnNameRule.refusal("a.b", () => Nil) == Some("Column name: a.b is invalid.  Valid characters are a-z, 0-9 and _"))
        var read = 0
        assert(ColumnNameRule.refusal("plain", () => { read += 1; Nil }).isEmpty && read == 0, "a plain name never reads the stored config")
    }

    // ------------------------------------------- GET-then-POST round trip

    private val oddHeader = "id,amount,a.b,unit price,Upper.Case,\"say \"\"hi\"\"\",café-total, padded"
    private val oddNames = List("a.b", "unit price", "upper.case", "say \"hi\"", "café-total", " padded")

    private def destinations: Seq[(String, PipelineConfig)] =
        Seq("postgres" -> postgres(base: _*), "parquet" -> objectStore("parquet", base: _*), "iceberg" -> objectStore("iceberg", base: _*))

    test("an unchanged GET-then-POST of a pipeline with evolved odd names is accepted and modify renames nothing") {
        for ((label, plain) <- destinations) {
            val stored = evolved(plain, oddHeader)
            assert(names(stored.source.schemaProperties) == List("id", "amount") ++ oddNames, label + ": " + names(stored.source.schemaProperties))
            val body = gson.fromJson(gson.toJson(stored), classOf[PipelineConfig]) // GET, then POST the same JSON
            assert(refusal(body, stored).isEmpty, label + ": " + refusal(body, stored))
            val saved = PipelineValidatorUtil.modify(body)
            assert(names(saved.source.schemaProperties) == names(stored.source.schemaProperties), label + ": source renamed")
            assert(names(saved.destination.schemaProperties) == names(stored.destination.schemaProperties), label + ": destination renamed")
            // and the saved config saves again
            assert(refusal(gson.fromJson(gson.toJson(saved), classOf[PipelineConfig]), saved).isEmpty, label + ": second re-save")
        }
    }

    test("a new pipeline with an odd name is still refused with the existing message, in either schema") {
        for ((label, plain) <- destinations) {
            val candidate = evolved(plain, "id,amount,a.b")
            assert(refusal(candidate, null) == Some("Column name: a.b is invalid.  Valid characters are a-z, 0-9 and _"), label)
            assert(refusal(candidate, plain) == Some("Column name: a.b is invalid.  Valid characters are a-z, 0-9 and _"), label + ": stored lacks it")
        }
    }

    test("other edits on a pipeline with a kept name succeed and leave it untouched") {
        for ((label, plain) <- destinations) {
            val stored = evolved(plain, "id,amount,a.b")
            val edits = Seq(
                "catalog" -> stored.copy(catalog = "Sales"),
                "tags" -> stored.copy(tags = java.util.Arrays.asList("x")),
                "another field's type" -> both(
                    stored,
                    fs => new java.util.ArrayList[SchemaField](fs.asScala.map(f => if (f.name == "amount") f.copy(`type` = "int") else f).asJava)
                ),
                "a new plain field" -> both(stored, fs => { val l = new java.util.ArrayList[SchemaField](fs); l.add(SchemaField("note", "string")); l })
            ) ++ (if (stored.destination.objectStore != null && stored.destination.objectStore.fileFormat == "iceberg")
                      Seq("write mode" -> stored.copy(destination =
                          stored.destination.copy(objectStore = stored.destination.objectStore.copy(writeMode = "overwrite"))
                      ))
                  else if (stored.destination.database != null)
                      Seq("truncate" -> stored.copy(destination =
                          stored.destination.copy(database = stored.destination.database.copy(truncateBeforeWrite = true))
                      ))
                  else Nil)
            for ((what, candidate) <- edits) {
                assert(refusal(candidate, stored).isEmpty, label + " " + what + ": " + refusal(candidate, stored))
                val saved = PipelineValidatorUtil.modify(candidate)
                assert(names(saved.source.schemaProperties).contains("a.b") && names(saved.destination.schemaProperties).contains("a.b"), label + " " + what)
            }
        }
    }

    test("removing a kept field or renaming it to a plain name succeeds; renaming it to another odd name, a case or spacing change, is refused") {
        for ((label, plain) <- destinations) {
            val stored = evolved(plain, "id,amount,a.b")
            val removed = both(stored, fs => new java.util.ArrayList[SchemaField](fs.asScala.filterNot(_.name == "a.b").asJava))
            assert(refusal(removed, stored).isEmpty, label + ": remove")
            assert(refusal(both(stored, mapNames(n => if (n == "a.b") "a_b" else n)), stored).isEmpty, label + ": rename to plain")
            for (other <- Seq("a.c", "A.B", " a.b", "a.b "))
                assert(
                    refusal(both(stored, mapNames(n => if (n == "a.b") other else n)), stored) ==
                        Some("Column name: " + other + " is invalid.  Valid characters are a-z, 0-9 and _"),
                    label + ": rename to '" + other + "'"
                )
        }
    }

    test("a name the stored config has only in the other schema is a new name there and is refused") {
        val stored = postgres(base: _*)
        val srcOnly = withSource(stored, fs => { val l = new java.util.ArrayList[SchemaField](fs); l.add(SchemaField("a.b", "string")); l })
        val destToo = withDest(srcOnly, fs => { val l = new java.util.ArrayList[SchemaField](fs); l.add(SchemaField("a.b", "string")); l })
        assert(refusal(destToo, srcOnly) == Some("Column name: a.b is invalid.  Valid characters are a-z, 0-9 and _"), "moved into the destination")
        assert(ColumnNameRule.storedNames(srcOnly, sourceSchema = true).contains("a.b"))
        assert(!ColumnNameRule.storedNames(srcOnly, sourceSchema = false).contains("a.b"))
    }

    test("a kept name may not become a partition field") {
        val stored = evolved(objectStore("parquet", base: _*), "id,amount,a.b")
        val partitioned =
            stored.copy(destination = stored.destination.copy(objectStore = stored.destination.objectStore.copy(partitionBy = java.util.Arrays.asList("a.b"))))
        assert(refusal(partitioned, partitioned) == Some("'partitionBy' field name: a.b is invalid.  Valid characters are a-z, 0-9 and _"))
    }

    test("the stored config is read at most once, and not at all for a Postgres pipeline with plain names") {
        var reads = 0
        val plain = postgres(base: _*)
        PipelineValidatorUtil.validate(plain, () => { reads += 1; null })
        assert(reads == 0)
        val stored = evolved(plain, "id,amount,a.b,c.d")
        PipelineValidatorUtil.validate(stored, () => { reads += 1; stored })
        assert(reads == 1)
    }

    // -------------------------------------------------------------- MongoDB

    test("MongoDB (JSON pipeline): a protected key with an odd name is kept when stored, refused when new") {
        def mongo(protectedKey: String): PipelineConfig = {
            val src = new java.util.ArrayList[SchemaField](
                java.util.Arrays.asList(SchemaField("_json", "string"), SchemaField(protectedKey, "string", ProtectionPolicy(method = "redact")))
            )
            PipelineConfig(
                name = "col_mongo",
                source = Source(
                    schemaProperties = SchemaProperties("db", src),
                    fileAttributes = FileAttributes(jsonAttributes = JsonAttributes(everyRowContainsObject = true))
                ),
                destination = Destination(
                    schemaProperties = SchemaProperties("db", fields("_json" -> "string")),
                    database = Database(dbName = "datris", table = "col_mongo", useMongoDB = true)
                )
            )
        }
        val stored = mongo("a.b")
        assert(refusal(gson.fromJson(gson.toJson(stored), classOf[PipelineConfig]), stored).isEmpty, refusal(stored, stored).toString)
        assert(refusal(stored, null) == Some("Column name: a.b is invalid.  Valid characters are a-z, 0-9 and _"))
        assert(refusal(mongo("a.c"), stored) == Some("Column name: a.c is invalid.  Valid characters are a-z, 0-9 and _"))
    }

    // ------------------------------------------------------ catalog restore

    test("catalog restore: the archived config keeps the odd names, which are kept onto a live pipeline that has them and refused onto one that does not") {
        val live = evolved(postgres(base: _*), "id,amount,a.b")
        val archived = CatalogArchive.stripPipelineForExport(live).copy(catalog = "Sales")
        assert(names(archived.source.schemaProperties).contains("a.b"))
        assert(refusal(archived, live).isEmpty, refusal(archived, live).toString)
        assert(refusal(archived, null) == Some("Column name: a.b is invalid.  Valid characters are a-z, 0-9 and _"))
    }

    // ------------------------------------------- a kept name and a new destination

    private def toPostgres(c: PipelineConfig): PipelineConfig =
        c.copy(destination =
            c.destination.copy(objectStore = null, database = Database(dbName = "datris", schema = "public", table = "col_os", usePostgres = true))
        )
    private def withFormat(c: PipelineConfig, fmt: String): PipelineConfig =
        c.copy(destination = c.destination.copy(objectStore = c.destination.objectStore.copy(fileFormat = fmt)))

    test("a kept name with a backtick cannot be carried from Parquet to ORC; a dotted one can") {
        val stored = evolved(objectStore("parquet", base: _*), "id,amount,x`y")
        assert(refusal(stored, stored).isEmpty, "unchanged destination")
        val flipped = refusal(withFormat(stored, "orc"), stored)
        assert(flipped.exists(m => m.contains("x`y") && m.contains("backtick") && m.contains("cannot be kept for the new destination")), flipped.toString)
        val dotted = evolved(objectStore("parquet", base: _*), "id,amount,a.b")
        assert(refusal(withFormat(dotted, "orc"), dotted).isEmpty)
    }

    test("a kept name longer than Postgres allows cannot be carried from an object store to Postgres; an unchanged destination keeps it") {
        val long = "l" * 70 + ".x"
        val stored = evolved(objectStore("parquet", base: _*), "id,amount," + long)
        assert(refusal(stored, stored).isEmpty, "unchanged destination")
        val moved = refusal(toPostgres(stored), stored)
        assert(moved.exists(m => m.contains("bytes long") && m.contains("63")), moved.toString)
        // a short kept name moves to Postgres
        val short = evolved(objectStore("parquet", base: _*), "id,amount,a.b")
        assert(refusal(toPostgres(short), short).isEmpty)
    }

    // ------------------------------------------- round-two review notes

    test("the destination-change refusal reads as a save message, not a header message") {
        val stored = evolved(objectStore("parquet", base: _*), "id,amount,x`y")
        val m = refusal(withFormat(stored, "orc"), stored).get
        assert(
            m == "Column name: x`y in the source schema cannot be kept for the new destination: it holds a backtick, which ORC files cannot keep in a column name. Rename or remove the field, or keep the destination",
            m
        )
        assert(!m.contains("header"), m)
    }

    test("no fileFormat and an explicit parquet are the same destination") {
        val stored = evolved(objectStore(null, base: _*), "id,amount,x`y")
        assert(stored.destination.objectStore.fileFormat == null)
        assert(refusal(withFormat(stored, "parquet"), stored).isEmpty)
        val explicit = evolved(objectStore("parquet", base: _*), "id,amount,x`y")
        assert(refusal(withFormat(explicit, null), explicit).isEmpty)
    }

    test("adding a second destination checks the kept names against it; removing one does not refuse") {
        val long = "l" * 70 + ".x"
        val stored = evolved(objectStore("parquet", base: _*), "id,amount," + long)
        val added =
            stored.copy(destination = stored.destination.copy(database = Database(dbName = "datris", schema = "public", table = "col_os", usePostgres = true)))
        assert(refusal(added, stored).exists(_.contains("bytes long")), refusal(added, stored).toString)
        val withKafka = stored.copy(destination = stored.destination.copy(kafka = Kafka("t", null, null)))
        assert(refusal(withKafka, stored).isEmpty, "Kafka has no limit")
        assert(refusal(stored, withKafka).isEmpty, "removing Kafka leaves the object store, which keeps the name")
    }

    test("catalog restore over a pipeline whose destination differs checks the kept names against the archived destination") {
        val live = evolved(objectStore("parquet", base: _*), "id,amount,x`y")
        val archived = withFormat(CatalogArchive.stripPipelineForExport(live).copy(catalog = "Sales"), "orc")
        assert(refusal(archived, live).exists(_.contains("cannot be kept for the new destination")), refusal(archived, live).toString)
    }
}
