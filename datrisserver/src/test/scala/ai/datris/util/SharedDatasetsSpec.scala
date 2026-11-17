package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

/** Story: Hidden dataset writes
  * (plans/stories/restricted-hidden-dataset-writes.md), Acceptance, SharedDatasetsSpec.
  *
  * Pure identity rules for structured datasets (no I/O). Signatures assumed:
  * {{{
  * object SharedDatasets {
  *   case class Env(defaultBucket: String, tenantDatabase: Option[String], pgvectorDatabase: Option[String])
  *   case class Key(family: String, kind: String, parts: Seq[String])
  *   def keysOf(c: PipelineConfig, env: Env, withScratch: Boolean = false): Seq[Key]
  *   def overlaps(a: Key, b: Key): Boolean
  *   def hiddenWriterConflicts(candidate: PipelineConfig, stored: PipelineConfig, others: Seq[PipelineConfig],
  *                             visible: PipelineConfig => Boolean, env: Env): Seq[Key]
  *   def sharedOnDelete(config: PipelineConfig, others: Seq[PipelineConfig], env: Env): Seq[(Key, Seq[PipelineConfig])]
  *   def refusalMessage(datasetName: String): String
  *   def keptOnDeleteMessage(label: String, visibleNames: Option[Seq[String]]): String
  * }
  * }}}
  * Identity is asserted through `overlaps` (and `family` where the story
  * names it), not through the layout of `parts`.
  */
class SharedDatasetsSpec extends AnyFunSuite {
    import SharedDatasets.{Env, Key}

    private val env = Env(defaultBucket = "dev-data", tenantDatabase = None, pgvectorDatabase = Some("datris"))

    private def p(name: String, d: Destination, catalog: String = "Sales"): PipelineConfig =
        PipelineConfig(name = name, destination = d, catalog = catalog, createdByKeyLabel = "someone")

    private def pgT(name: String, db: String, schema: String, table: String): PipelineConfig =
        p(name, Destination(database = Database(dbName = db, schema = schema, table = table, usePostgres = true)))
    private def mongo(name: String, db: String, coll: String): PipelineConfig =
        p(name, Destination(database = Database(dbName = db, table = coll, useMongoDB = true)))
    private def snow(name: String, db: String, schema: String, table: String): PipelineConfig =
        p(name, Destination(database = Database(dbName = db, schema = schema, table = table, useSnowflake = true)))
    private def dbx(name: String, db: String, schema: String, table: String): PipelineConfig =
        p(name, Destination(database = Database(dbName = db, schema = schema, table = table, useDatabricks = true)))
    private def mssql(name: String, db: String, schema: String, table: String): PipelineConfig =
        p(name, Destination(database = Database(dbName = db, schema = schema, table = table, useSqlServer = true)))
    private def os(name: String, prefix: String, bucket: String = null): PipelineConfig =
        p(name, Destination(objectStore = ObjectStore(prefixKey = prefix, destinationBucketOverride = bucket)))
    private def pgv(name: String, table: String, schema: String): PipelineConfig =
        p(name, Destination(pgvector = PGVectorConfig(table, schema, ChunkingConfig(), null, null, null)))
    private def qd(name: String, coll: String): PipelineConfig =
        p(name, Destination(qdrant = QdrantConfig(coll, ChunkingConfig(), null, null, null)))
    private def scratch(name: String): PipelineConfig = p(name, Destination(scratch = ScratchConfig()))

    private def keys(c: PipelineConfig, e: Env = env, withScratch: Boolean = false): Seq[Key] =
        SharedDatasets.keysOf(c, e, withScratch)

    /** True when some key of `a` overlaps some key of `b`. */
    private def same(a: PipelineConfig, b: PipelineConfig, e: Env = env): Boolean = {
        val ka = keys(a, e)
        val kb = keys(b, e)
        assert(ka.nonEmpty, "no key for " + a.name)
        assert(kb.nonEmpty, "no key for " + b.name)
        ka.exists(x => kb.exists(y => SharedDatasets.overlaps(x, y)))
    }

    private def onlyKey(c: PipelineConfig, e: Env = env): Key = {
        val ks = keys(c, e)
        assert(ks.size == 1, ks)
        ks.head
    }

    test("postgres keys compare database, schema and table exactly and default a null or blank schema to public") {
        val base = pgT("a", "datris", "public", "orders")
        assert(onlyKey(base).family == "pgtable")
        assert(same(base, pgT("b", "datris", "public", "orders")))
        assert(same(base, pgT("b", "datris", null, "orders")))
        assert(same(base, pgT("b", "datris", "", "orders")))
        assert(same(base, pgT("b", "datris", "  ", "orders")))
        // exact: identifiers are double-quoted
        assert(!same(base, pgT("b", "datris", "public", "ORDERS")))
        assert(!same(base, pgT("b", "datris", "Public", "orders")))
        assert(!same(base, pgT("b", "Datris", "public", "orders")))
        assert(!same(base, pgT("b", "other", "public", "orders")))
        assert(!same(base, pgT("b", "datris", "staging", "orders")))
        assert(!same(base, pgT("b", "datris", "public", "orders2")))
        // multiTenant: the tenant database replaces dbName
        val mt = env.copy(tenantDatabase = Some("tenant_db"))
        assert(same(pgT("a", "x", "public", "orders"), pgT("b", "y", "public", "orders"), mt))
        // keys are deterministic: equal configs give equal keys
        assert(keys(base) == keys(pgT("z", "datris", null, "orders")))
    }

    test("snowflake simple identifiers match across case and quoted-style names do not") {
        val base = snow("a", "ANALYTICS", "PUBLIC", "ORDERS")
        assert(onlyKey(base).family == "snowflake")
        assert(same(base, snow("b", "analytics", "public", "orders")))
        assert(same(base, snow("b", "Analytics", "Public", "Orders")))
        // non-simple identifiers are quoted and keep their case
        assert(!same(snow("a", "ANALYTICS", "PUBLIC", "My Orders"), snow("b", "ANALYTICS", "PUBLIC", "my orders")))
        assert(same(snow("a", "ANALYTICS", "PUBLIC", "My Orders"), snow("b", "analytics", "public", "My Orders")))
        assert(!same(snow("a", "ANALYTICS", "PUBLIC", "ord-ers"), snow("b", "ANALYTICS", "PUBLIC", "ORD-ERS")))
        // a different table is a different dataset
        assert(!same(base, snow("b", "ANALYTICS", "PUBLIC", "ORDERS2")))
        // the same names in another warehouse kind are not the same dataset
        assert(!same(base, pgT("b", "ANALYTICS", "PUBLIC", "ORDERS")))
    }

    test("databricks and sqlserver names match across case, and a blank sqlserver schema is dbo") {
        val d = dbx("a", "main", "sales", "orders")
        assert(onlyKey(d).family == "databricks")
        assert(same(d, dbx("b", "MAIN", "Sales", "ORDERS")))
        assert(!same(d, dbx("b", "main", "sales", "orders_v2")))

        val s = mssql("a", "Shop", "dbo", "Orders")
        assert(onlyKey(s).family == "sqlserver")
        assert(same(s, mssql("b", "shop", "DBO", "orders")))
        assert(same(s, mssql("b", "SHOP", null, "ORDERS")))
        assert(same(s, mssql("b", "shop", "", "orders")))
        assert(same(s, mssql("b", "shop", "  ", "orders")))
        assert(!same(s, mssql("b", "shop", "sales", "orders")))
        assert(!same(s, mssql("b", "other", "dbo", "orders")))

        // families never cross
        assert(!same(d, mssql("b", "main", "sales", "orders")))
    }

    test("an explicit default-bucket override and no override are the same object-store bucket") {
        assert(same(os("a", "hr"), os("b", "hr", bucket = "dev-data")))
        assert(same(os("a", "hr", bucket = "dev-data"), os("b", "hr")))
        assert(!same(os("a", "hr"), os("b", "hr", bucket = "other-bucket")))
        assert(same(os("a", "hr", bucket = "other-bucket"), os("b", "hr", bucket = "other-bucket")))
        // the default comes from Env, not from the running environment
        assert(same(os("a", "hr"), os("b", "hr", bucket = "qa-data"), env.copy(defaultBucket = "qa-data")))
        assert(!same(os("a", "hr"), os("b", "hr", bucket = "dev-data"), env.copy(defaultBucket = "qa-data")))
    }

    test("object-store prefixes match with or without slashes and overlap when one contains the other on a segment boundary, not on a shared name stem") {
        val hr = os("a", "hr")
        assert(same(hr, os("b", "hr/")))
        assert(same(hr, os("b", "/hr")))
        assert(same(hr, os("b", "/hr/")))
        assert(same(hr, os("b", " hr ")))
        assert(same(hr, os("b", "hr/2026")))
        assert(same(os("a", "hr/2026/q1"), os("b", "/hr/")))
        assert(!same(hr, os("b", "hr-archive")))
        assert(!same(hr, os("b", "hrx/2026")))
        assert(!same(os("a", "city"), os("b", "city-forecasts")))
        assert(!same(os("a", "hr/2026"), os("b", "hr/2027")))
        // keys are normalised: three spellings are one key
        assert(keys(os("a", "hr/")) == keys(os("b", "/hr")))
        assert(keys(os("a", "hr")) == keys(os("b", "/hr/")))
    }

    test("an empty object-store prefix overlaps every prefix in its bucket") {
        val root = os("a", "")
        assert(same(root, os("b", "hr")))
        assert(same(root, os("b", "deep/nested/prefix")))
        assert(same(os("a", "/"), os("b", "hr")))
        assert(same(os("a", "hr"), os("b", "/")))
        assert(!same(root, os("b", "hr", bucket = "other-bucket")))
    }

    test(
        "a pgvector table and a postgres table with the same schema and table are one object when the databases match or the pgvector database is unknown, and distinct when they differ"
    ) {
        val vec = pgv("v", "docs", "public")
        assert(onlyKey(vec).family == "pgtable")
        val table = pgT("t", "datris", "public", "docs")
        assert(same(vec, table))
        assert(same(table, vec))
        assert(same(pgv("v", "docs", null), table))
        assert(same(pgv("v", "docs", ""), table))
        // unknown pgvector database: matches any postgres database
        val unknown = env.copy(pgvectorDatabase = None)
        assert(same(vec, pgT("t", "anything", "public", "docs"), unknown))
        assert(same(pgT("t", "anything", "public", "docs"), vec, unknown))
        // databases differ
        val other = env.copy(pgvectorDatabase = Some("vectors"))
        assert(!same(vec, table, other))
        assert(!same(table, vec, other))
        // schema or table differ (exact case)
        assert(!same(vec, pgT("t", "datris", "other", "docs")))
        assert(!same(vec, pgT("t", "datris", "public", "DOCS")))
        assert(!same(pgv("v", "Docs", "public"), table))
        // multiTenant: the postgres side uses the tenant database
        val mt = Env("dev-data", Some("tenant_db"), Some("tenant_db"))
        assert(same(vec, pgT("t", "ignored", "public", "docs"), mt))
    }

    test("a hidden scratch pipeline is hit by an object-store prefix covering its scratch folder; a candidate's own scratch destination never conflicts") {
        val hiddenScratch = scratch("tmp_fetch")
        val hidden: PipelineConfig => Boolean = c => c.name != "tmp_fetch" && c.name != "hidden_root"

        // the scratch folder is only a key on the hidden side
        assert(keys(hiddenScratch).isEmpty)
        assert(keys(hiddenScratch, withScratch = true).nonEmpty)

        for (prefix <- Seq("_scratch", "_scratch/", "/_scratch/tmp_fetch", "_scratch/tmp_fetch/part-0", ""))
            assert(
                SharedDatasets.hiddenWriterConflicts(os("probe", prefix), null, Seq(hiddenScratch), hidden, env).nonEmpty,
                prefix
            )
        // explicit default-bucket override is the same bucket
        assert(SharedDatasets.hiddenWriterConflicts(os("probe", "_scratch", "dev-data"), null, Seq(hiddenScratch), hidden, env).nonEmpty)
        // not covering it
        for (prefix <- Seq("_scratch/other", "_scratch/tmp_fetch_2", "scratch", "tmp_fetch"))
            assert(SharedDatasets.hiddenWriterConflicts(os("probe", prefix), null, Seq(hiddenScratch), hidden, env).isEmpty, prefix)
        assert(SharedDatasets.hiddenWriterConflicts(os("probe", "_scratch", "other-bucket"), null, Seq(hiddenScratch), hidden, env).isEmpty)

        // the candidate's own scratch destination is not a key, even under a hidden whole-bucket writer
        val hiddenRoot = os("hidden_root", "")
        assert(SharedDatasets.hiddenWriterConflicts(scratch("my_fetch"), null, Seq(hiddenRoot, hiddenScratch), hidden, env).isEmpty)
    }

    test("hiddenWriterConflicts is empty when every writer is visible, and when the stored config already writes the dataset") {
        val others = Seq(pgT("mkt_p", "datris", "public", "mkt_tbl"), os("mkt_os", "mkt"))
        val candidate = pgT("bob_p", "datris", "public", "mkt_tbl")
        val allVisible: PipelineConfig => Boolean = _ => true
        val noneVisible: PipelineConfig => Boolean = _ => false

        assert(SharedDatasets.hiddenWriterConflicts(candidate, null, others, allVisible, env).isEmpty)
        assert(SharedDatasets.hiddenWriterConflicts(os("bob_os", "mkt/2026"), null, others, allVisible, env).isEmpty)

        // grandfathered: the stored config already writes it (same dataset, other spelling)
        val stored = pgT("bob_p", "datris", null, "mkt_tbl")
        assert(SharedDatasets.hiddenWriterConflicts(candidate, stored, others :+ stored, noneVisible, env).isEmpty)
        val storedOs = os("bob_os", "/mkt/")
        assert(SharedDatasets.hiddenWriterConflicts(os("bob_os", "mkt"), storedOs, others :+ storedOs, noneVisible, env).isEmpty)

        // a destination with no shared-dataset key never conflicts
        assert(SharedDatasets.hiddenWriterConflicts(qd("bob_q", "mkt_tbl"), null, others, noneVisible, env).isEmpty)
        assert(SharedDatasets.hiddenWriterConflicts(p("bob_none", Destination()), null, others, noneVisible, env).isEmpty)
        assert(SharedDatasets.hiddenWriterConflicts(p("bob_null", null), null, others, noneVisible, env).isEmpty)
    }

    test("hiddenWriterConflicts lists a new or changed dataset that a hidden pipeline writes, ignoring the candidate's own name") {
        val hiddenPg = pgT("mkt_p", "datris", "public", "mkt_tbl")
        val hiddenOs = os("mkt_os", "mkt")
        val visibleOs = os("sales_os", "sales")
        val visible: PipelineConfig => Boolean = c => c.catalog == "Sales"
        def hide(c: PipelineConfig) = c.copy(catalog = "Marketing")
        val hOthers = Seq(hide(hiddenPg), hide(hiddenOs), visibleOs)

        // new
        val candidate = pgT("bob_p", "datris", "public", "mkt_tbl")
        val c1 = SharedDatasets.hiddenWriterConflicts(candidate, null, hOthers, visible, env)
        assert(c1.nonEmpty)
        assert(c1.forall(k => keys(candidate).contains(k)), c1)
        assert(SharedDatasets.hiddenWriterConflicts(os("bob_os", "mkt/sub"), null, hOthers, visible, env).nonEmpty)
        // visible writer: no conflict
        assert(SharedDatasets.hiddenWriterConflicts(os("bob_os2", "sales/sub"), null, hOthers, visible, env).isEmpty)
        // one visible and one hidden writer of the same dataset: conflict
        val mixed = Seq(hide(hiddenPg), pgT("sales_p", "datris", "public", "mkt_tbl"))
        assert(SharedDatasets.hiddenWriterConflicts(candidate, null, mixed, visible, env).nonEmpty)

        // changed: the stored config wrote another table
        val stored = pgT("bob_p", "datris", "public", "bob_tbl")
        assert(SharedDatasets.hiddenWriterConflicts(candidate, stored, hOthers :+ stored, visible, env).nonEmpty)

        // a config with the candidate's own name is never "another pipeline", even if not visible
        val selfCopy = hide(pgT("bob_p", "datris", "public", "mkt_tbl"))
        assert(SharedDatasets.hiddenWriterConflicts(candidate, null, Seq(selfCopy), visible, env).isEmpty)

        // only the conflicting candidate keys are listed
        val two = p(
            "bob_two",
            Destination(
                database = Database(dbName = "datris", schema = "public", table = "free_tbl", usePostgres = true),
                objectStore = ObjectStore(prefixKey = "mkt")
            )
        )
        val c2 = SharedDatasets.hiddenWriterConflicts(two, null, hOthers, visible, env)
        assert(c2.size == 1, c2)
        assert(c2.head.family != "pgtable", c2)
        assert(keys(two).contains(c2.head), c2)
    }

    test("sharedOnDelete lists structured and cross-kind sharers and leaves a pgvector pair to the vector rule") {
        def names(r: Seq[(Key, Seq[PipelineConfig])]): Set[String] = r.flatMap(_._2.map(_.name)).toSet

        val a = pgT("a", "datris", "public", "orders")
        val others = Seq(a, pgT("b", "datris", null, "orders"), pgT("c", "datris", "public", "ORDERS"), pgT("d", "other", "public", "orders"))
        val r = SharedDatasets.sharedOnDelete(a, others, env)
        assert(r.size == 1, r)
        assert(names(r) == Set("b"))
        assert(r.head._1.family == "pgtable")

        // the config's own name is not a sharer; an unshared table lists nothing
        assert(SharedDatasets.sharedOnDelete(a, Seq(a), env).isEmpty)
        assert(SharedDatasets.sharedOnDelete(pgT("z", "datris", "public", "lonely"), others, env).isEmpty)

        // mongodb: null database is datris
        val m = mongo("m1", null, "events")
        assert(names(SharedDatasets.sharedOnDelete(m, Seq(m, mongo("m2", "datris", "events"), mongo("m3", "datris", "Events")), env)) == Set("m2"))
        // snowflake / databricks / sqlserver with their own folding
        assert(names(SharedDatasets.sharedOnDelete(snow("s1", "DB", "PUBLIC", "T"), Seq(snow("s2", "db", "public", "t")), env)) == Set("s2"))
        assert(names(SharedDatasets.sharedOnDelete(dbx("d1", "main", "s", "t"), Seq(dbx("d2", "MAIN", "S", "T")), env)) == Set("d2"))
        assert(names(SharedDatasets.sharedOnDelete(mssql("q1", "shop", null, "t"), Seq(mssql("q2", "SHOP", "dbo", "T")), env)) == Set("q2"))

        // cross-kind, both directions
        val vec = pgv("vec", "orders", "public")
        assert(names(SharedDatasets.sharedOnDelete(a, Seq(a, vec), env)) == Set("vec"))
        assert(names(SharedDatasets.sharedOnDelete(vec, Seq(vec, a), env)) == Set("a"))

        // a pgvector pair is the vector rule's (VectorContainers.sharedOnDelete)
        assert(SharedDatasets.sharedOnDelete(vec, Seq(vec, pgv("vec2", "orders", null)), env).isEmpty)
        // vector stores have no shared-dataset key
        assert(keys(qd("q", "orders")).isEmpty)
        assert(SharedDatasets.sharedOnDelete(qd("q", "orders"), Seq(qd("q2", "orders")), env).isEmpty)
        // object stores, kafka and activemq are not guarded here
        assert(SharedDatasets.sharedOnDelete(os("o1", "hr"), Seq(os("o2", "hr")), env).isEmpty)
        val k1 = p("k1", Destination(kafka = Kafka("topic_x", null, null)))
        assert(SharedDatasets.sharedOnDelete(k1, Seq(p("k2", Destination(kafka = Kafka("topic_x", null, null)))), env).isEmpty)
        // a null config lists nothing
        assert(SharedDatasets.sharedOnDelete(null, others, env).isEmpty)
    }
}
