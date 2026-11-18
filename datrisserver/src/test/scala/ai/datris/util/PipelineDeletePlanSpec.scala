package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

/** Story: Pipeline delete: an explicit `deleteData=false` keeps the
  * destination data (plans/stories/pipeline-delete-keep-data.md), Acceptance,
  * PipelineDeletePlanSpec.
  *
  * Pure flag table and keep-mode rules (no I/O). Signatures assumed:
  * {{{
  * object PipelineDeletePlan {
  *   case class Plan(deleteConfig: Boolean, deleteData: Boolean, keepData: Boolean)
  *   def of(deleteData: String, deleteConfig: String): Plan
  *   val KeepDataVectorRefusal: String
  *   def refusal(plan: Plan, config: PipelineConfig): Option[String]
  *   def keptLabels(config: PipelineConfig, env: SharedDatasets.Env): Seq[String]
  * }
  * }}}
  */
class PipelineDeletePlanSpec extends AnyFunSuite {
    import PipelineDeletePlan.Plan

    private val env = SharedDatasets.Env(defaultBucket = "dev-data", tenantDatabase = None, pgvectorDatabase = Some("datris"))

    private val FullDelete = Plan(deleteConfig = true, deleteData = true, keepData = false)
    private val KeepData = Plan(deleteConfig = true, deleteData = false, keepData = true)
    private val Reset = Plan(deleteConfig = false, deleteData = true, keepData = false)
    private val NoDelete = Plan(deleteConfig = false, deleteData = false, keepData = false)

    private val chunking = ChunkingConfig()

    private def p(name: String, d: Destination): PipelineConfig =
        PipelineConfig(name = name, catalog = "Sales", destination = d, createdByKeyLabel = "someone")

    private def db(name: String, database: Database): PipelineConfig = p(name, Destination(database = database))
    private def pgT(name: String, table: String): PipelineConfig =
        db(name, Database(dbName = "datris", schema = "public", table = table, usePostgres = true))
    private def mongo(name: String, coll: String): PipelineConfig =
        db(name, Database(dbName = "datris", table = coll, useMongoDB = true))
    private def snow(name: String, table: String): PipelineConfig =
        db(name, Database(dbName = "ANALYTICS", schema = "PUBLIC", table = table, useSnowflake = true))
    private def dbx(name: String, table: String): PipelineConfig =
        db(name, Database(dbName = "main", schema = "sales", table = table, useDatabricks = true))
    private def mssql(name: String, table: String): PipelineConfig =
        db(name, Database(dbName = "sales", schema = "dbo", table = table, useSqlServer = true))
    private def os(name: String, prefix: String): PipelineConfig =
        p(name, Destination(objectStore = ObjectStore(prefixKey = prefix, fileFormat = "parquet")))

    private def pgv(name: String, table: String): PipelineConfig =
        p(name, Destination(pgvector = PGVectorConfig(table, "public", chunking, null, null, null)))
    private def qd(name: String, coll: String): PipelineConfig =
        p(name, Destination(qdrant = QdrantConfig(coll, chunking, null, null, null)))
    private def wv(name: String, cls: String): PipelineConfig =
        p(name, Destination(weaviate = WeaviateConfig(cls, chunking, null, null, null)))
    private def mv(name: String, coll: String): PipelineConfig =
        p(name, Destination(milvus = MilvusConfig(coll, chunking, null, null, null)))
    private def ch(name: String, coll: String): PipelineConfig =
        p(name, Destination(chroma = ChromaConfig(coll, chunking, null, null, null)))

    private def scratch(name: String): PipelineConfig = p(name, Destination(scratch = ScratchConfig()))
    private def kafka(name: String): PipelineConfig = p(name, Destination(kafka = Kafka("orders_topic", null, null)))
    private def activemq(name: String): PipelineConfig = p(name, Destination(activeMQ = ActiveMQ("orders_queue")))
    private def rest(name: String): PipelineConfig = p(name, Destination(restEndpoint = RestEndpoint("http://localhost:9/hook")))

    private val VectorText =
        "This pipeline writes to a vector store; its configuration cannot be deleted while keeping that data. " +
            "Delete it with its data, or keep the pipeline."

    test("no flags, and true/true, delete config and data and do not keep") {
        // "absent" is the Spring default "true".
        assert(PipelineDeletePlan.of("true", "true") == FullDelete)
        assert(PipelineDeletePlan.of("TRUE", "True") == FullDelete)
    }

    test("deleteData=false with the config delete keeps data, in any letter case and with surrounding spaces") {
        Seq("false", "FALSE", "False", " false", "false ", "  fAlSe  ", "\tfalse\n").foreach { v =>
            assert(PipelineDeletePlan.of(v, "true") == KeepData, "deleteData='" + v + "'")
        }
    }

    test("deleteConfig=false with deleteData true or absent is the data-only reset") {
        assert(PipelineDeletePlan.of("true", "false") == Reset)
        assert(PipelineDeletePlan.of("TRUE", "FALSE") == Reset)
    }

    test("false/false deletes nothing") {
        assert(PipelineDeletePlan.of("false", "false") == NoDelete)
        assert(PipelineDeletePlan.of("FALSE", "False") == NoDelete)
        // Any deleteConfig text other than true is deleteConfig=false.
        assert(PipelineDeletePlan.of("false", "no") == NoDelete)
        assert(PipelineDeletePlan.of("no", "false") == NoDelete)
        assert(PipelineDeletePlan.of("true", "nope") == Reset)
    }

    test("any deleteData text other than false still deletes data with the config") {
        Seq("no", "0", "", " ", "FALSEY", "f", "off").foreach { v =>
            assert(PipelineDeletePlan.of(v, "true") == FullDelete, "deleteData='" + v + "'")
        }
    }

    test(
        "refusal is the vector text for a keep plan on a pgvector, qdrant, weaviate, milvus or chroma pipeline, also when it has a table too, and None for every other plan"
    ) {
        assert(PipelineDeletePlan.KeepDataVectorRefusal == VectorText)
        val vectors = Seq(
            pgv("kd_pgv", "kd_chunks"),
            qd("kd_qd", "kd_coll"),
            wv("kd_wv", "KdClass"),
            mv("kd_mv", "kd_coll"),
            ch("kd_ch", "kd_coll"),
            p(
                "kd_mixed",
                Destination(
                    database = Database(dbName = "datris", schema = "public", table = "kd_rows", usePostgres = true),
                    pgvector = PGVectorConfig("kd_chunks", "public", chunking, null, null, null)
                )
            ),
            p(
                "kd_mixed_os",
                Destination(
                    objectStore = ObjectStore(prefixKey = "kd_os", fileFormat = "parquet"),
                    qdrant = QdrantConfig("kd_coll", chunking, null, null, null)
                )
            )
        )
        vectors.foreach { c =>
            assert(PipelineDeletePlan.refusal(KeepData, c).contains(VectorText), c.name)
            assert(PipelineDeletePlan.refusal(PipelineDeletePlan.of(" FALSE ", "true"), c).contains(VectorText), c.name)
            // Every other plan on a vector pipeline: no refusal.
            Seq(FullDelete, Reset, NoDelete, PipelineDeletePlan.of("no", "true")).foreach { plan =>
                assert(PipelineDeletePlan.refusal(plan, c).isEmpty, c.name + " " + plan)
            }
        }
        // A keep plan on a pipeline with no vector destination: no refusal.
        Seq(
            pgT("kd_pg", "kd_pg"),
            mongo("kd_mg", "kd_mg"),
            snow("kd_sf", "KD_SF"),
            dbx("kd_dbx", "kd_dbx"),
            mssql("kd_ms", "kd_ms"),
            os("kd_os", "kd_os"),
            scratch("kd_scratch"),
            kafka("kd_kafka"),
            activemq("kd_amq"),
            rest("kd_rest"),
            p("kd_none", null)
        ).foreach { c =>
            assert(PipelineDeletePlan.refusal(KeepData, c).isEmpty, c.name)
        }
    }

    test("refusal text names no pipeline, catalog or container") {
        val text = PipelineDeletePlan.refusal(KeepData, qd("kd_secret_pipe", "kd_secret_coll")).get
        Seq("kd_secret_pipe", "kd_secret_coll", "Sales", "qdrant").foreach(s => assert(!text.contains(s), s + " in " + text))
    }

    test("keptLabels lists the table, collection and prefix labels and nothing for scratch, kafka, activemq, rest or a null destination") {
        def labelsOf(c: PipelineConfig): Seq[String] = SharedDatasets.keysOf(c, env).map(SharedDatasets.label)

        val pg = pgT("kd_pg", "kd_pg")
        assert(PipelineDeletePlan.keptLabels(pg, env) == labelsOf(pg))
        assert(PipelineDeletePlan.keptLabels(pg, env) == Seq("postgres table datris.public.kd_pg"))

        val mg = mongo("kd_mg", "kd_events")
        assert(PipelineDeletePlan.keptLabels(mg, env) == Seq("mongodb collection datris.kd_events"))

        val prefix = os("kd_os", "kd_os")
        val prefixLabels = PipelineDeletePlan.keptLabels(prefix, env)
        assert(prefixLabels == labelsOf(prefix))
        assert(prefixLabels.size == 1 && prefixLabels.head.startsWith("object-store prefix ") && prefixLabels.head.contains("kd_os"), prefixLabels)

        Seq(snow("kd_sf", "KD_SF"), dbx("kd_dbx", "kd_dbx"), mssql("kd_ms", "kd_ms")).foreach { c =>
            val ls = PipelineDeletePlan.keptLabels(c, env)
            assert(ls == labelsOf(c), c.name)
            assert(ls.size == 1 && ls.head.contains(" table "), ls)
        }

        // Table plus prefix: both, in keysOf order.
        val both = p(
            "kd_both",
            Destination(
                database = Database(dbName = "datris", schema = "public", table = "kd_rows", usePostgres = true),
                objectStore = ObjectStore(prefixKey = "kd_both", fileFormat = "parquet")
            )
        )
        assert(PipelineDeletePlan.keptLabels(both, env) == labelsOf(both))
        assert(PipelineDeletePlan.keptLabels(both, env).size == 2)

        // Nothing keepable.
        Seq(scratch("kd_scratch"), kafka("kd_kafka"), activemq("kd_amq"), rest("kd_rest"), p("kd_none", null)).foreach { c =>
            assert(PipelineDeletePlan.keptLabels(c, env).isEmpty, c.name + ": " + PipelineDeletePlan.keptLabels(c, env))
        }

        // No vector label, even next to a table.
        val mixed = p(
            "kd_mixed",
            Destination(
                database = Database(dbName = "datris", schema = "public", table = "kd_rows", usePostgres = true),
                pgvector = PGVectorConfig("kd_chunks", "public", chunking, null, null, null)
            )
        )
        assert(PipelineDeletePlan.keptLabels(mixed, env) == Seq("postgres table datris.public.kd_rows"))
        assert(PipelineDeletePlan.keptLabels(pgv("kd_pgv", "kd_chunks"), env).isEmpty)
    }
}
