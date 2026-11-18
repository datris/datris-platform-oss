package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

/** Story: Catalog-scoped user access: a restricted user cannot point a
  * pipeline at a table or prefix that already exists (and then preview it),
  * review findings 2 and 4: object-store paths that are not plain prefixes,
  * and SQL Server letter-case changes under a case-sensitive collation. */
class DestinationTargetsHardeningSpec extends AnyFunSuite {

    private val env = SharedDatasets.Env(defaultBucket = "dev-data", tenantDatabase = None, pgvectorDatabase = Some("datris"))

    private def os(prefix: String, bucket: String = null): ObjectStore =
        ObjectStore(prefixKey = prefix, destinationBucketOverride = bucket, fileFormat = "parquet")

    private def sqls(name: String, db: String = "sales", schema: String = "dbo", table: String = "Customers", secret: String = "mssql-main"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = "Sales",
            destination = Destination(database = Database(dbName = db, schema = schema, table = table, useSqlServer = true, credentialsSecret = secret))
        )

    test("unsafeObjectStorePath refuses globs, escapes and empty, '.' or '..' segments, and accepts plain prefixes") {
        for (bad <- Seq("a/../b", "../b", "a/..", "a/./b", "a//b", "a*", "pt_orph*", "[a]b", "{a}", "a?", "a\\b", "a/b*/c"))
            assert(DestinationTargets.unsafeObjectStorePath(os(bad)), bad)
        for (bad <- Seq("b*", "[b]", "b/c", "b{1}"))
            assert(DestinationTargets.unsafeObjectStorePath(os("a", bucket = bad)), bad)
        for (bad <- Seq(" reports", "reports ", " a/b ", "\treports"))
            assert(DestinationTargets.unsafeObjectStorePath(os(bad)), "'" + bad + "'")
        assert(DestinationTargets.unsafeObjectStorePath(os("a", bucket = " dev-data")))
        assert(DestinationTargets.unsafeObjectStorePath(os("a", bucket = "dev-data ")))
        for (ok <- Seq("a", "a/b", "/a/b/", "a/b/c", "", null, "a.b", "a..b", "a-b_c/2026=1", "a/.hidden"))
            assert(!DestinationTargets.unsafeObjectStorePath(os(ok)), String.valueOf(ok))
        assert(!DestinationTargets.unsafeObjectStorePath(os("a", bucket = "my.bucket-1")))
        assert(!DestinationTargets.unsafeObjectStorePath(null))
    }

    test("a SQL Server letter-case change is not fresh but is probed, and a cover needs the same letter case") {
        val stored = sqls("q", table = "customers")
        val recased = sqls("q", table = "Customers")
        assert(DestinationTargets.freshTargets(recased, stored, env).isEmpty)
        assert(DestinationTargets.recasedTargets(recased, stored, env).size == 1)
        assert(DestinationTargets.toProbe(recased, stored, Nil, env).size == 1)
        assert(DestinationTargets.toProbe(sqls("q", schema = "DBO", table = "customers"), stored, Nil, env).size == 1)
        assert(DestinationTargets.toProbe(sqls("q", db = "SALES", table = "customers"), stored, Nil, env).size == 1)
        // unchanged: nothing
        assert(DestinationTargets.toProbe(stored, stored, Nil, env).isEmpty)
        assert(DestinationTargets.recasedTargets(stored, stored, env).isEmpty)

        // a visible pipeline with the other letter case does not cover
        val visibleLower = sqls("sales_q", table = "customers")
        assert(DestinationTargets.toProbe(sqls("bob_q", table = "Customers"), null, Seq(visibleLower), env).size == 1)
        assert(DestinationTargets.toProbe(sqls("bob_q", table = "customers"), null, Seq(visibleLower), env).isEmpty)
        // and a recase over a visible pipeline with the new case is covered
        assert(DestinationTargets.toProbe(recased, stored, Seq(sqls("sales_q", table = "Customers")), env).isEmpty)

        // other families keep the key's normalisation
        val snow = PipelineConfig(
            name = "s",
            destination = Destination(database =
                Database(dbName = "analytics", schema = "public", table = "orders", useSnowflake = true, credentialsSecret = "sf", warehouse = "WH")
            )
        )
        assert(DestinationTargets.targetsOf(snow, env).head.exact.isEmpty)
    }

    test("sqlServerTempTable flags # and ## names only, for SQL Server only") {
        for (t <- Seq("#t", "##t", " ##t")) assert(DestinationTargets.sqlServerTempTable(sqls("q", table = t)), t)
        for (t <- Seq("t", "t#", "a#b", null)) assert(!DestinationTargets.sqlServerTempTable(sqls("q", table = t)), String.valueOf(t))
        val snow = PipelineConfig(
            name = "s",
            destination = Destination(database = Database(dbName = "a", schema = "p", table = "#t", useSnowflake = true, credentialsSecret = "sf"))
        )
        assert(!DestinationTargets.sqlServerTempTable(snow))
        assert(!DestinationTargets.sqlServerTempTable(null))
    }
}
