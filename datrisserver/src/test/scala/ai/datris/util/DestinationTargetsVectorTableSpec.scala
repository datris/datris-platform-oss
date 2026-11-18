package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

/** Restricted sessions, pgvector tables (review finding: a kept Postgres
  * table dropped through a pgvector pipeline): the pgvector table is a
  * target on its own path, beside `targetsOf` (which still lists nothing for
  * pgvector). Pure target, freshness, cover and name-safety logic. */
class DestinationTargetsVectorTableSpec extends AnyFunSuite {

    private val env = SharedDatasets.Env(defaultBucket = "dev-data", tenantDatabase = None, pgvectorDatabase = Some("datris"))

    private def pgv(name: String, table: String, schema: String = "public", catalog: String = "Sales"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            destination = Destination(pgvector = PGVectorConfig(table, schema, ChunkingConfig(), null, null, null))
        )

    private def pg(name: String, table: String): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = "Sales",
            destination = Destination(database = Database(dbName = "datris", schema = "public", table = table, usePostgres = true))
        )

    test("vectorTableTargets: the shared-dataset pgvector key with the schema and table as the loader and the DROP address them") {
        val ts = DestinationTargets.vectorTableTargets(pgv("v", "Orders", schema = "Vec"), env)
        assert(ts.size == 1, ts)
        assert(ts.head.key == SharedDatasets.Key("pgtable", "pgvector", Seq("datris", "Vec", "Orders")))
        assert(ts.head.connection == Nil)
        assert(ts.head.exact == Seq("Vec", "Orders"))
        // a null schema is public, as the loader and the delete
        assert(DestinationTargets.vectorTableTargets(pgv("v", "orders", schema = null), env).head.exact == Seq("public", "orders"))
        // a blank (non-null) schema is kept as written: the DROP quotes it as is
        assert(DestinationTargets.vectorTableTargets(pgv("v", "orders", schema = " "), env).head.exact == Seq(" ", "orders"))
        // the database part follows the environment (tenant database in multi-tenant mode)
        val tenantEnv = env.copy(tenantDatabase = Some("t1"), pgvectorDatabase = Some("t1"))
        assert(DestinationTargets.vectorTableTargets(pgv("v", "orders"), tenantEnv).head.key.parts.head == "t1")
    }

    test("vectorTableTargets: nothing for a non-pgvector or empty destination; targetsOf still lists nothing for pgvector") {
        assert(DestinationTargets.vectorTableTargets(null, env).isEmpty)
        assert(DestinationTargets.vectorTableTargets(PipelineConfig(name = "x"), env).isEmpty)
        assert(DestinationTargets.vectorTableTargets(pg("p", "orders"), env).isEmpty)
        assert(DestinationTargets.vectorTableTargets(pgv("v", "  "), env).isEmpty)
        assert(DestinationTargets.vectorTableTargets(pgv("v", null), env).isEmpty)
        assert(DestinationTargets.targetsOf(pgv("v", "orders"), env).isEmpty)
        assert(!DestinationTargets.ProbedFamilies.contains("pgvector"))
    }

    test("freshVectorTables: a new pipeline's table is fresh; an unchanged one is not; a changed table, schema or letter case is") {
        assert(DestinationTargets.freshVectorTables(pgv("v", "orders"), null, env).size == 1)
        assert(DestinationTargets.freshVectorTables(pgv("v", "orders"), pgv("v", "orders"), env).isEmpty)
        assert(DestinationTargets.freshVectorTables(pgv("v", "orders"), pgv("v", "orders", schema = null), env).isEmpty)
        assert(DestinationTargets.freshVectorTables(pgv("v", "orders"), pgv("v", "chunks"), env).size == 1)
        assert(DestinationTargets.freshVectorTables(pgv("v", "orders", schema = "vec"), pgv("v", "orders"), env).size == 1)
        assert(DestinationTargets.freshVectorTables(pgv("v", "Orders"), pgv("v", "orders"), env).size == 1)
        // the stored config was postgres: the pgvector table is fresh
        assert(DestinationTargets.freshVectorTables(pgv("v", "orders"), pg("v", "orders"), env).size == 1)
    }

    test("vectorTablesToProbe: a visible pgvector pipeline naming the same table covers it; other names, schemas or kinds do not") {
        val cand = pgv("bob_v", "orders")
        assert(DestinationTargets.vectorTablesToProbe(cand, null, Seq(pgv("sales_v", "orders")), env).isEmpty)
        assert(DestinationTargets.vectorTablesToProbe(cand, null, Seq(pgv("sales_v", "orders", schema = null)), env).isEmpty)
        assert(DestinationTargets.vectorTablesToProbe(cand, null, Seq(pgv("sales_v", "Orders")), env).size == 1)
        assert(DestinationTargets.vectorTablesToProbe(cand, null, Seq(pgv("sales_v", "orders", schema = "vec")), env).size == 1)
        // a visible postgres destination table at the same place covers nothing here
        assert(DestinationTargets.vectorTablesToProbe(cand, null, Seq(pg("sales_p", "orders")), env).size == 1)
        // the candidate's own name in the list is not a cover
        assert(DestinationTargets.vectorTablesToProbe(cand, null, Seq(pgv("bob_v", "orders")), env).size == 1)
        // unchanged: never probed, whatever the others
        assert(DestinationTargets.vectorTablesToProbe(cand, pgv("bob_v", "orders"), Nil, env).isEmpty)
        assert(DestinationTargets.vectorTablesToProbe(cand, null, null, env).size == 1)
        assert(DestinationTargets.vectorTablesToProbe(null, null, Nil, env).isEmpty)
    }

    test("unsafeVectorTableName: a double quote or NUL in the schema or table, or an empty schema") {
        assert(!DestinationTargets.unsafeVectorTableName(pgv("v", "orders")))
        assert(!DestinationTargets.unsafeVectorTableName(pgv("v", "Orders Table", schema = null)))
        assert(!DestinationTargets.unsafeVectorTableName(pg("p", "orders")))
        assert(!DestinationTargets.unsafeVectorTableName(null))
        assert(DestinationTargets.unsafeVectorTableName(pgv("v", "x\"; DROP TABLE \"orders")))
        assert(DestinationTargets.unsafeVectorTableName(pgv("v", "orders", schema = "pub\"lic")))
        assert(DestinationTargets.unsafeVectorTableName(pgv("v", "ord\u0000ers")))
        assert(DestinationTargets.unsafeVectorTableName(pgv("v", "orders", schema = "")))
        assert(!DestinationTargets.UnsafeVectorTableRefusal.contains("orders"))
    }
}
