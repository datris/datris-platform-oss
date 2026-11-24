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

    // ------------------------------------------------------------------------
    // Story: pgvector: two table names that PostgreSQL cuts to the same 63
    // bytes are treated as one table
    // (plans/stories/pgvector-long-names-one-table.md), Acceptance,
    // PipelineDeletePlanSpec (extended). DELETE /pipeline keeps every vector
    // container `VectorContainers.sharedOnDelete(config, others)` returns
    // (`cleanupDestinationData`'s `dropVector` guard drops the pgvector table
    // only when `VectorContainers.key(store, tableName, schemaName)` is not
    // in that set) and reports each with
    // `PipelineAPIController.vectorDeleteWarnings`, naming the sharer only
    // when the caller may see it.

    private def delReq(rk: ai.datris.model.ResolvedKey): jakarta.servlet.http.HttpServletRequest = {
        val r = org.mockito.Mockito.mock(classOf[jakarta.servlet.http.HttpServletRequest])
        org.mockito.Mockito.when(r.getMethod).thenReturn("DELETE")
        org.mockito.Mockito.when(r.getRequestURI).thenReturn("/api/v1/pipeline")
        org.mockito.Mockito.when(r.getRemoteAddr).thenReturn("127.0.0.1")
        org.mockito.Mockito.when(r.getAttribute(ai.datris.config.TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def pgvIn(name: String, catalog: String, table: String, schema: String): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = "someone",
            destination = Destination(pgvector = PGVectorConfig(table, schema, chunking, null, null, null))
        )

    /** What the delete of `config` does with its pgvector table, given the
      * other stored configs: true when the guard keeps it. */
    private def tableKept(config: PipelineConfig, others: Seq[PipelineConfig]): Boolean = {
        val keep = VectorContainers.sharedOnDelete(config, others).map(_._1).toSet
        val p = config.destination.pgvector
        VectorContainers.key("pgvector", p.tableName, Option(p.schemaName)).exists(keep.contains)
    }

    test("deleting one of two pipelines whose pgvector names collide after the cut keeps the table and names the sharer") {
        val p63 = "kd" + "x" * 61
        assert(p63.length == 63)
        val a = pgvIn("kd_long_a", "Sales", p63 + "_one", "public")
        val b = pgvIn("kd_long_b", "HR", p63 + "_two", null)
        val c = pgvIn("kd_long_c", "Sales", "kd" + "x" * 60 + "y_three", "public") // differs within 63 bytes
        val stored = Seq(a, b, c)

        // the guard keeps the table on either delete
        assert(tableKept(a, stored), "delete of " + a.name)
        assert(tableKept(b, stored), "delete of " + b.name)
        val shared = VectorContainers.sharedOnDelete(a, stored)
        assert(shared.size == 1, shared)
        assert(shared.head._2.map(_.name) == Seq("kd_long_b"))

        // the existing Kept warning, sharer named for a caller who may see it
        val full = delReq(ResolvedKey(None, "full", Nil, isLegacyFullAccess = true))
        val warnings = ai.datris.api.PipelineAPIController.vectorDeleteWarnings(full, a, stored)
        assert(warnings.size == 1, warnings)
        assert(warnings.head.startsWith("Kept pgvector table "), warnings.head)
        assert(warnings.head.contains(p63), warnings.head)
        assert(warnings.head.contains("kd_long_b"), warnings.head)
        assert(!warnings.head.contains("kd_long_c"), warnings.head)

        // a restricted session that cannot see the sharer gets the name-free line
        val bob = delReq(
            APIKeyValidator.resolveFromSession(
                User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = InMemoryUserDb.grants("Sales" -> "edit"))
            )
        )
        val hidden = ai.datris.api.PipelineAPIController.vectorDeleteWarnings(bob, a, stored)
        assert(hidden.size == 1, hidden)
        assert(hidden.head.startsWith("Kept pgvector table "), hidden.head)
        assert(!hidden.head.contains("kd_long_b") && !hidden.head.contains("HR"), hidden.head)

        // a two-byte letter straddling byte 63: PostgreSQL keeps the 62-byte prefix
        val p62 = "kd" + "x" * 60
        val s1 = pgvIn("kd_straddle", "Sales", p62 + "é", "public")
        val s2 = pgvIn("kd_exact62", "Sales", p62, "public")
        assert(tableKept(s1, Seq(s1, s2)) && tableKept(s2, Seq(s1, s2)))
        assert(ai.datris.api.PipelineAPIController.vectorDeleteWarnings(full, s1, Seq(s1, s2)).exists(_.contains("kd_exact62")))

        // long schemas collide the same way
        val ls1 = pgvIn("kd_schema_one", "Sales", "kd_docs", "s" * 63 + "_one")
        val ls2 = pgvIn("kd_schema_two", "Sales", "kd_docs", "s" * 63 + "_two")
        assert(tableKept(ls1, Seq(ls1, ls2)))

        // a name that differs within 63 bytes shares nothing: its table is dropped, no warning
        assert(!tableKept(c, stored))
        assert(ai.datris.api.PipelineAPIController.vectorDeleteWarnings(full, c, stored).isEmpty)
        // and a lone long name is dropped as before
        assert(!tableKept(a, Seq(a, c)))
        assert(ai.datris.api.PipelineAPIController.vectorDeleteWarnings(full, a, Seq(a, c)).isEmpty)
    }

    test("a Kept warning and a container refusal for a long pgvector name show the name as configured, not the cut form") {
        val p63 = "kd" + "x" * 61
        val longSchema = "s" * 63 + "_schema"
        val a = pgvIn("kd_long_a", "Sales", p63 + "_one", longSchema)
        val b = pgvIn("kd_long_b", "Sales", p63 + "_two", longSchema)
        val full = delReq(ResolvedKey(None, "full", Nil, isLegacyFullAccess = true))

        val kept = ai.datris.api.PipelineAPIController.vectorDeleteWarnings(full, a, Seq(a, b))
        assert(kept.size == 1, kept)
        assert(kept.head.startsWith("Kept pgvector table " + longSchema + "." + p63 + "_one: "), kept.head)
        assert(kept.head.contains("kd_long_b"), kept.head)

        val refused = ai.datris.api.PipelineAPIController.vectorContainerRefusal(full, b, null, Seq(a))
        assert(refused.isDefined)
        val body = refused.get.getBody
        assert(body.contains("pgvector table " + longSchema + "." + p63 + "_two is already used"), body)
        assert(body.contains("kd_long_a"), body)

        // the display key carries the configured names; the identity key the cut ones
        val k = VectorContainers.keysOf(a).head
        assert(k.name == p63 && k.schema == "s" * 63)
        val shown = VectorContainers.displayKey(k, a)
        assert(shown.name == p63 + "_one" && shown.schema == longSchema)
        assert(VectorContainers.displayKey(k, pgvIn("n", "Sales", p63 + "_one", null)) == k)
        // a null schema shows as public, as before
        val n = pgvIn("kd_null", "Sales", p63 + "_one", null)
        assert(VectorContainers.displayKey(VectorContainers.keysOf(n).head, n).schema == "public")
    }
}
