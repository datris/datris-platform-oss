package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Database, DatrisException, PipelineConfig}

import java.nio.charset.StandardCharsets
import scala.collection.JavaConverters._

/** The naming rules for a pipeline's `destination.database` block. Pure, no
  * I/O.
  *
  * [[problem]] is the save validator's own `destination.database` checks
  * (required fields, the identifier charset, the `;` rule for `options`,
  * exactly one engine), in the validator's order and with its messages, plus
  * one rule of its own: a Postgres `dbName`, `schema` or `table` is at most
  * [[PostgresMaxNameBytes]] bytes (PostgreSQL cuts longer names). It runs on
  * every pipeline type, so an unstructured pipeline's block is checked too.
  * [[same]] decides whether a stored block is kept unchanged (and so is not
  * checked again). [[postgresDropProblem]] decides whether a delete may drop
  * the Postgres table at all. */
object DatabaseDestinationNames {

    /** PostgreSQL's identifier limit (NAMEDATALEN - 1), in bytes. */
    val PostgresMaxNameBytes: Int = 63

    /** The first rule `db` fails, as the save validator words it; None for a
      * null block or one that passes. */
    def problem(db: Database): Option[String] = {
        if (db == null) return None
        if (db.dbName == null)
            return Some("If the 'destination.database' section is defined, the 'destination.database.dbName' must be defined")
        if (!db.useMongoDB && db.schema == null)
            return Some("If the 'destination.database' section is defined, the 'destination.database.schema' must be defined")
        if (db.table == null)
            return Some("If the 'destination.database' section is defined, the 'destination.database.table' must be defined")

        identifierProblem(db.dbName, "destination.database.dbName").foreach(p => return Some(p))
        if (!db.useMongoDB) {
            identifierProblem(db.schema, "destination.database.schema").foreach(p => return Some(p))
            identifierProblem(db.table, "destination.database.table").foreach(p => return Some(p))
            // Databricks: a blank warehouse means "use the secret's", not an error.
            val blankDatabricksWarehouse = db.useDatabricks && Option(db.warehouse).forall(_.trim.isEmpty)
            if (db.warehouse != null && !blankDatabricksWarehouse)
                identifierProblem(db.warehouse, "destination.database.warehouse").foreach(p => return Some(p))
        }
        if (db.options != null && db.options.asScala.exists(o => o != null && o.contains(";")))
            return Some("destination.database.options entries must not contain ';'")

        val engineFlags = Seq(db.usePostgres, db.useMongoDB, db.useSnowflake, db.useDatabricks, db.useSqlServer).count(identity)
        if (engineFlags != 1)
            return Some(
                "For the 'destination.database' section, you must select exactly one of usePostgres, useMongoDB, useSnowflake, useDatabricks, or useSqlServer" +
                    (if (engineFlags > 1) " — multiple are currently set" else "")
            )

        if (db.usePostgres) {
            Seq(db.dbName -> "destination.database.dbName", db.schema -> "destination.database.schema", db.table -> "destination.database.table")
                .foreach { case (v, label) => lengthProblem(v, label).foreach(p => return Some(p)) }
        }
        None
    }

    /** True when `a` and `b` carry the same `destination.database` block as
      * far as naming goes: identical `dbName`, `schema`, `table`,
      * `warehouse`, `options` and engine flags, nulls included. False when
      * either has no block (or is null). */
    def same(a: PipelineConfig, b: PipelineConfig): Boolean =
        (blockOf(a), blockOf(b)) match {
            case (Some(x), Some(y)) =>
                x.dbName == y.dbName && x.schema == y.schema && x.table == y.table && x.warehouse == y.warehouse &&
                x.options == y.options && x.usePostgres == y.usePostgres && x.useMongoDB == y.useMongoDB &&
                x.useSnowflake == y.useSnowflake && x.useDatabricks == y.useDatabricks && x.useSqlServer == y.useSqlServer
            case _ => false
        }

    /** Why a delete must not connect or drop for this Postgres block, or None
      * when it may. The effective database is `tenantDatabase` when given
      * (multi-tenant), else `dbName`; a null schema is `public`. Schema,
      * table and a `dbName` must pass the identifier charset and the 63-byte
      * limit. A tenant database is percent-encoded into the URL and quoted in
      * SQL, so only what neither can carry (null, empty, NUL) and the
      * 63-byte limit are refused for it. */
    def postgresDropProblem(db: Database, tenantDatabase: Option[String]): Option[String] = {
        if (db == null) return Some("the pipeline has no 'destination.database' section")
        val database = tenantDatabase match {
            case Some(t) =>
                if (t == null || t.isEmpty) Some("the tenant database name is empty")
                else if (t.indexOf('\u0000') >= 0) Some("the tenant database name contains a NUL character")
                else lengthProblem(t, "tenant database")
            case None => nameProblem(db.dbName, "destination.database.dbName")
        }
        database
            .orElse(nameProblem(Option(db.schema).getOrElse("public"), "destination.database.schema"))
            .orElse(nameProblem(db.table, "destination.database.table"))
    }

    private def blockOf(c: PipelineConfig): Option[Database] =
        Option(c).flatMap(x => Option(x.destination)).flatMap(d => Option(d.database))

    /** The validator's identifier message, read from the validator itself. */
    private def identifierProblem(value: String, label: String): Option[String] =
        try {
            PipelineValidatorUtil.validateSqlIdentifier(value, label)
            None
        } catch { case e: DatrisException => Some(e.getMessage) }

    private def lengthProblem(value: String, label: String): Option[String] =
        if (value != null && value.getBytes(StandardCharsets.UTF_8).length > PostgresMaxNameBytes)
            Some("'" + label + "' is longer than " + PostgresMaxNameBytes + " bytes, the PostgreSQL limit for a name")
        else None

    private def nameProblem(value: String, label: String): Option[String] =
        identifierProblem(value, label).orElse(lengthProblem(value, label))
}
