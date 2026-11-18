package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

/** Story: Catalog-scoped user access: a restricted user cannot point a
  * pipeline at a table or prefix that already exists (and then preview it)
  * (plans/stories/catalog-scoped-user-access-warehouse-preview-target.md),
  * Acceptance, DestinationTargetsSpec.
  *
  * Pure rule; no I/O. Assumed:
  * {{{
  * object DestinationTargets {
  *   case class Target(key: SharedDatasets.Key, connection: Seq[String])
  *   val ProbedFamilies: Set[String]   // snowflake, sqlserver, databricks, objectstore
  *   def targetsOf(c: PipelineConfig, env: SharedDatasets.Env): Seq[Target]
  *   def freshTargets(candidate: PipelineConfig, stored: PipelineConfig, env: SharedDatasets.Env): Seq[Target]
  *   def coveredBy(t: Target, other: Target): Boolean
  *   def toProbe(candidate: PipelineConfig, stored: PipelineConfig, visibleOthers: Seq[PipelineConfig],
  *               env: SharedDatasets.Env): Seq[Target]
  *   def ExistsRefusal(label: String): String
  *   val ProbeUnavailable: String
  * }
  * }}}
  * A target's key is the `SharedDatasets.keysOf` key; its connection holds
  * `credentialsSecret`, `warehouse`, `role` (database) or `provider`,
  * `endpoint`, `credentialsSecret` (object store).
  */
class DestinationTargetsSpec extends AnyFunSuite {

    private val env = SharedDatasets.Env(defaultBucket = "dev-data", tenantDatabase = None, pgvectorDatabase = Some("datris"))

    private def snow(
        name: String,
        db: String = "analytics",
        schema: String = "public",
        table: String = "orders",
        secret: String = "sf-main",
        warehouse: String = "WH1",
        role: String = null,
        catalog: String = "Sales"
    ): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            destination = Destination(database =
                Database(dbName = db, schema = schema, table = table, useSnowflake = true, credentialsSecret = secret, warehouse = warehouse, role = role)
            )
        )

    private def sqls(name: String, db: String = "sales", schema: String = "dbo", table: String = "orders", secret: String = "mssql-main"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = "Sales",
            destination = Destination(database = Database(dbName = db, schema = schema, table = table, useSqlServer = true, credentialsSecret = secret))
        )

    private def dbx(
        name: String,
        db: String = "main",
        schema: String = "default",
        table: String = "orders",
        secret: String = "dbx-main",
        warehouse: String = "abc123"
    ): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = "Sales",
            destination =
                Destination(database =
                    Database(dbName = db, schema = schema, table = table, useDatabricks = true, credentialsSecret = secret, warehouse = warehouse)
                )
        )

    private def os(
        name: String,
        prefix: String,
        bucket: String = null,
        provider: String = "minio",
        endpoint: String = null,
        secret: String = null,
        catalog: String = "Sales"
    ): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            destination = Destination(objectStore =
                ObjectStore(
                    prefixKey = prefix,
                    destinationBucketOverride = bucket,
                    fileFormat = "parquet",
                    provider = provider,
                    endpoint = endpoint,
                    credentialsSecret = secret
                )
            )
        )

    private def families(ts: Seq[DestinationTargets.Target]): Set[String] = ts.map(_.key.family).toSet

    // ------------------------------------------------------------ targetsOf

    test("targetsOf lists snowflake, sqlserver, databricks and objectstore targets and nothing for postgres, mongodb, kafka, activemq, pgvector or scratch") {
        assert(DestinationTargets.ProbedFamilies == Set("snowflake", "sqlserver", "databricks", "objectstore"))

        for ((c, fam) <- Seq(snow("s") -> "snowflake", sqls("q") -> "sqlserver", dbx("d") -> "databricks", os("o", "o/p") -> "objectstore")) {
            val ts = DestinationTargets.targetsOf(c, env)
            assert(ts.size == 1, s"$fam: $ts")
            assert(ts.head.key.family == fam, ts)
            // the key is the shared-dataset key, unchanged
            assert(ts.head.key == SharedDatasets.keysOf(c, env).head, ts)
        }

        val pg = PipelineConfig(
            name = "p",
            destination = Destination(database = Database(dbName = "datris", schema = "public", table = "t", usePostgres = true))
        )
        val mongo = PipelineConfig(name = "m", destination = Destination(database = Database(dbName = "datris", table = "t", useMongoDB = true)))
        val kafka = PipelineConfig(name = "k", destination = Destination(kafka = Kafka("topic1", null, null)))
        val amq = PipelineConfig(name = "a", destination = Destination(activeMQ = ActiveMQ("q1")))
        val pgv = PipelineConfig(name = "v", destination = Destination(pgvector = PGVectorConfig("vt", "public", ChunkingConfig(), null, null, null)))
        val scratch = PipelineConfig(name = "sc", destination = Destination(scratch = ScratchConfig()))
        for (c <- Seq(pg, mongo, kafka, amq, pgv, scratch))
            assert(DestinationTargets.targetsOf(c, env).isEmpty, c.name)

        // several destinations: only the probed families
        val multi = PipelineConfig(
            name = "multi",
            destination = Destination(
                database =
                    Database(dbName = "analytics", schema = "public", table = "orders", useSnowflake = true, usePostgres = true, credentialsSecret = "sf"),
                objectStore = ObjectStore(prefixKey = "multi"),
                kafka = Kafka("topic1", null, null)
            )
        )
        assert(families(DestinationTargets.targetsOf(multi, env)) == Set("snowflake", "objectstore"))

        assert(DestinationTargets.targetsOf(null, env).isEmpty)
        assert(DestinationTargets.targetsOf(PipelineConfig(name = "none"), env).isEmpty)
    }

    // ------------------------------------------------------------ freshTargets

    test("freshTargets is empty when the destination is unchanged, and when only schema fields, tags, keyFields or truncateBeforeWrite change") {
        val stored = snow("p")
        assert(DestinationTargets.freshTargets(stored, stored, env).isEmpty)
        assert(DestinationTargets.freshTargets(stored.copy(), stored, env).isEmpty)

        val fields = new java.util.ArrayList[SchemaField]()
        fields.add(SchemaField("id", "int"))
        fields.add(SchemaField("amount", "double"))
        val withSchema = stored.copy(destination = stored.destination.copy(schemaProperties = SchemaProperties("analytics", fields)))
        val tagged = stored.copy(tags = java.util.Arrays.asList("changed"), version = 7)
        val keyed = stored.copy(destination =
            stored.destination.copy(database = stored.destination.database.copy(keyFields = java.util.Arrays.asList("id")))
        )
        val truncating = stored.copy(destination =
            stored.destination.copy(database = stored.destination.database.copy(truncateBeforeWrite = true))
        )
        for (c <- Seq(withSchema, tagged, keyed, truncating))
            assert(DestinationTargets.freshTargets(c, stored, env).isEmpty, c)

        val o = os("o", "a/b")
        val oKeyed = o.copy(destination =
            o.destination.copy(objectStore = o.destination.objectStore.copy(keyFields = java.util.Arrays.asList("id"), writeMode = "merge"))
        )
        assert(DestinationTargets.freshTargets(o.copy(tags = java.util.Arrays.asList("x")), o, env).isEmpty)
        assert(DestinationTargets.freshTargets(oKeyed, o, env).isEmpty)
        // a prefix written with surrounding slashes is the same prefix
        assert(DestinationTargets.freshTargets(os("o", "/a/b/"), o, env).isEmpty)
    }

    test("freshTargets holds the target when table, schema, dbName, prefixKey or bucket changes, in any letter case the key normalises") {
        val stored = snow("p")
        for (c <- Seq(snow("p", table = "orders2"), snow("p", schema = "other"), snow("p", db = "other_db"))) {
            val fresh = DestinationTargets.freshTargets(c, stored, env)
            assert(fresh.size == 1, c)
            assert(fresh.head.key == SharedDatasets.keysOf(c, env).head)
        }
        // Snowflake folds simple identifiers to upper case: a letter-case-only change is not fresh
        assert(DestinationTargets.freshTargets(snow("p", db = "ANALYTICS", schema = "PUBLIC", table = "ORDERS"), stored, env).isEmpty)

        val sStored = sqls("q")
        assert(DestinationTargets.freshTargets(sqls("q", table = "orders2"), sStored, env).size == 1)
        assert(DestinationTargets.freshTargets(sqls("q", schema = "sales"), sStored, env).size == 1)
        assert(DestinationTargets.freshTargets(sqls("q", db = "other"), sStored, env).size == 1)
        assert(DestinationTargets.freshTargets(sqls("q", db = "SALES", schema = "DBO", table = "Orders"), sStored, env).isEmpty)

        val dStored = dbx("d")
        assert(DestinationTargets.freshTargets(dbx("d", table = "orders2"), dStored, env).size == 1)
        assert(DestinationTargets.freshTargets(dbx("d", schema = "other"), dStored, env).size == 1)
        assert(DestinationTargets.freshTargets(dbx("d", db = "other"), dStored, env).size == 1)
        assert(DestinationTargets.freshTargets(dbx("d", db = "MAIN", schema = "Default", table = "ORDERS"), dStored, env).isEmpty)

        val oStored = os("o", "a/b")
        assert(DestinationTargets.freshTargets(os("o", "a/c"), oStored, env).size == 1)
        assert(DestinationTargets.freshTargets(os("o", "a/b/c"), oStored, env).size == 1)
        assert(DestinationTargets.freshTargets(os("o", "a/b", bucket = "other-bucket"), oStored, env).size == 1)
        // the default bucket named explicitly is the same bucket
        assert(DestinationTargets.freshTargets(os("o", "a/b", bucket = "dev-data"), oStored, env).isEmpty)
    }

    test("a changed credentialsSecret, warehouse, role, provider or endpoint makes an unchanged key fresh") {
        val stored = snow("p", role = "LOADER")
        for (c <- Seq(snow("p", role = "LOADER", secret = "sf-other"), snow("p", role = "LOADER", warehouse = "WH2"), snow("p", role = "ADMIN"))) {
            val fresh = DestinationTargets.freshTargets(c, stored, env)
            assert(fresh.size == 1, c)
            // same key, other connection
            assert(fresh.head.key == SharedDatasets.keysOf(stored, env).head)
        }
        assert(DestinationTargets.freshTargets(sqls("q", secret = "mssql-other"), sqls("q"), env).size == 1)
        assert(DestinationTargets.freshTargets(dbx("d", secret = "dbx-other"), dbx("d"), env).size == 1)
        assert(DestinationTargets.freshTargets(dbx("d", warehouse = "zzz999"), dbx("d"), env).size == 1)

        val oStored = os("o", "a/b", bucket = "b1", provider = "s3", endpoint = "https://s3.one.example", secret = "aws-1")
        for (
            c <- Seq(
                os("o", "a/b", bucket = "b1", provider = "minio", endpoint = "https://s3.one.example", secret = "aws-1"),
                os("o", "a/b", bucket = "b1", provider = "s3", endpoint = "https://s3.two.example", secret = "aws-1"),
                os("o", "a/b", bucket = "b1", provider = "s3", endpoint = "https://s3.one.example", secret = "aws-2")
            )
        ) assert(DestinationTargets.freshTargets(c, oStored, env).size == 1, c)
        assert(
            DestinationTargets.freshTargets(os("o", "a/b", bucket = "b1", provider = "s3", endpoint = "https://s3.one.example", secret = "aws-1"), oStored, env).isEmpty
        )
    }

    // ------------------------------------------------------------ toProbe / coveredBy

    test("toProbe skips a table a visible pipeline names with the same connection, and probes it when the connection differs") {
        val visible = snow("sales_a", role = "LOADER")
        assert(DestinationTargets.toProbe(snow("bob_p", role = "LOADER"), null, Seq(visible), env).isEmpty)
        for (c <- Seq(snow("bob_p", role = "LOADER", secret = "sf-other"), snow("bob_p", role = "LOADER", warehouse = "WH2"), snow("bob_p", role = "OTHER")))
            assert(DestinationTargets.toProbe(c, null, Seq(visible), env).size == 1, c)
        // another table: probed
        assert(DestinationTargets.toProbe(snow("bob_p", table = "other", role = "LOADER"), null, Seq(visible), env).size == 1)

        assert(DestinationTargets.toProbe(sqls("bob_q"), null, Seq(sqls("sales_q")), env).isEmpty)
        assert(DestinationTargets.toProbe(sqls("bob_q", secret = "mssql-other"), null, Seq(sqls("sales_q")), env).size == 1)
        assert(DestinationTargets.toProbe(dbx("bob_d"), null, Seq(dbx("sales_d")), env).isEmpty)
        assert(DestinationTargets.toProbe(dbx("bob_d", secret = "dbx-other"), null, Seq(dbx("sales_d")), env).size == 1)

        // coveredBy directly
        val t = DestinationTargets.targetsOf(snow("x", role = "LOADER"), env).head
        assert(DestinationTargets.coveredBy(t, DestinationTargets.targetsOf(visible, env).head))
        assert(!DestinationTargets.coveredBy(t, DestinationTargets.targetsOf(snow("y", role = "LOADER", secret = "sf-other"), env).head))
        assert(!DestinationTargets.coveredBy(t, DestinationTargets.targetsOf(snow("y", table = "t2", role = "LOADER"), env).head))
    }

    test(
        "toProbe skips a prefix equal to or under a visible pipeline's prefix, and probes a parent prefix and a sibling sharing a name prefix (`a/b` vs `a/bc`)"
    ) {
        val visible = Seq(os("sales_o", "a/b"))
        assert(DestinationTargets.toProbe(os("bob_o", "a/b"), null, visible, env).isEmpty)
        assert(DestinationTargets.toProbe(os("bob_o", "a/b/c"), null, visible, env).isEmpty)
        assert(DestinationTargets.toProbe(os("bob_o", "/a/b/c/"), null, visible, env).isEmpty)
        assert(DestinationTargets.toProbe(os("bob_o", "a"), null, visible, env).size == 1)
        assert(DestinationTargets.toProbe(os("bob_o", ""), null, visible, env).size == 1)
        assert(DestinationTargets.toProbe(os("bob_o", "a/bc"), null, visible, env).size == 1)
        assert(DestinationTargets.toProbe(os("bob_o", "a/bc/d"), null, visible, env).size == 1)
        // same prefix, other bucket or other connection: probed
        assert(DestinationTargets.toProbe(os("bob_o", "a/b", bucket = "other-bucket"), null, visible, env).size == 1)
        assert(DestinationTargets.toProbe(os("bob_o", "a/b", provider = "s3", secret = "aws-1"), null, visible, env).size == 1)

        def t(prefix: String) = DestinationTargets.targetsOf(os("x", prefix), env).head
        assert(DestinationTargets.coveredBy(t("a/b"), t("a/b")))
        assert(DestinationTargets.coveredBy(t("a/b/c"), t("a/b")))
        assert(!DestinationTargets.coveredBy(t("a"), t("a/b")))
        assert(!DestinationTargets.coveredBy(t("a/bc"), t("a/b")))
    }

    test("a null stored config makes every probed-family target fresh") {
        val multi = PipelineConfig(
            name = "multi",
            destination = Destination(
                database =
                    Database(dbName = "analytics", schema = "public", table = "orders", useSnowflake = true, usePostgres = true, credentialsSecret = "sf"),
                objectStore = ObjectStore(prefixKey = "multi")
            )
        )
        val fresh = DestinationTargets.freshTargets(multi, null, env)
        assert(families(fresh) == Set("snowflake", "objectstore"), fresh)
        assert(fresh.toSet == DestinationTargets.targetsOf(multi, env).toSet)
        for (c <- Seq(snow("s"), sqls("q"), dbx("d"), os("o", "o")))
            assert(DestinationTargets.freshTargets(c, null, env) == DestinationTargets.targetsOf(c, env), c)
        // and with no visible pipeline, every fresh target is probed
        assert(DestinationTargets.toProbe(multi, null, Nil, env).toSet == fresh.toSet)
    }
}
