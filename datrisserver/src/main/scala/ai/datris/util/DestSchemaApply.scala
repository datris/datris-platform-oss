package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, DatrisException, PipelineConfig, SchemaField, SchemaProperties}
import org.slf4j.{Logger, LoggerFactory}

import java.sql.Statement
import scala.collection.JavaConverters._
import scala.util.Try

/** GET response for /pipeline/dest-types: either a typed proposal inferred
  * on demand from landed rows, or the reason there isn't one. Nothing about a
  * proposal is ever stored — see plans/destination-schema-after-load.md. */
case class DestTypesProposal(
    pipeline: String,
    eligible: Boolean,
    reason: String, // null when eligible; else destination-not-supported | already-typed | no-landed-rows
    message: String,
    fields: java.util.List[InferredDestField],
    sampleRowCount: Int
)

/** POST response for /pipeline/dest-types: what was applied. */
case class DestTypesApplied(
    pipeline: String,
    fields: java.util.List[SchemaField],
    migrated: Boolean, // true when a landed table was retyped (vs. config-only)
    version: Int
)

/** Destination-side typing, pull-based (plans/destination-schema-after-load.md):
  * `propose` samples what actually landed and infers types on demand;
  * `apply` migrates the destination table to the (possibly edited) types and
  * then writes the typed config as a new version. Always destination-first,
  * config-second, so every intermediate state is safe. Any landed value that
  * won't cast fails the whole apply naming the column, with config and data
  * untouched.
  *
  * v1 scope is postgres, snowflake, and databricks; objectstore is deferred
  * (its migration is a non-atomic background rewrite that would need stored
  * status). Mongo is schemaless and vector destinations have no field schema —
  * neither applies. */
object DestSchemaApply {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    private val sampleLimit = 1000

    private val ReasonNotSupported = "destination-not-supported"
    private val ReasonAlreadyTyped = "already-typed"
    private val ReasonNoLandedRows = "no-landed-rows"

    /** Destination kind when this pipeline's dest supports on-demand typing. */
    def inScopeDest(config: PipelineConfig): Option[String] = {
        if (config == null || config.destination == null) None
        else if (config.destination.database != null && config.destination.database.usePostgres) Some("postgres")
        else if (config.destination.database != null && config.destination.database.useSnowflake) Some("snowflake")
        else if (config.destination.database != null && config.destination.database.useDatabricks) Some("databricks")
        else None
    }

    /** Effective dest fields (PipelineConfigIO.read already applies the
      * source-schema fallback, so null-ness is not the test). */
    private def effectiveFields(config: PipelineConfig): java.util.List[SchemaField] =
        if (config.destination.schemaProperties == null) null
        else config.destination.schemaProperties.fields

    // ------------------------------------------------------------------
    // Propose: sample landed rows, infer with evidence. Stateless.
    // ------------------------------------------------------------------

    def propose(pipelineName: String): DestTypesProposal = {
        val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, pipelineName)
        if (config == null)
            throw new DatrisException("Pipeline not found: " + pipelineName)

        val destKind = inScopeDest(config).getOrElse(
            return ineligible(pipelineName, ReasonNotSupported,
                "This pipeline's destination does not support on-demand typing (postgres, snowflake, databricks only)")
        )
        if (!DestTypeInference.allString(effectiveFields(config)))
            return ineligible(pipelineName, ReasonAlreadyTyped, "Destination fields already carry types")

        val rows: Seq[Map[String, Any]] = sampleLanded(config, destKind).getOrElse(
            return ineligible(pipelineName, ReasonNoLandedRows,
                "No landed data to infer from — run the pipeline once first")
        )

        val names = effectiveFields(config).asScala.map(_.name).toList
        val fields = DestTypeInference.inferFieldsFromRecords(names, rows)
        logger.info("Dest-types proposal for pipeline '" + pipelineName + "': inferred " + fields.size +
            " columns from " + rows.size + " landed rows")
        DestTypesProposal(pipelineName, eligible = true, reason = null, message = null, fields, rows.size)
    }

    private def ineligible(pipelineName: String, reason: String, message: String): DestTypesProposal =
        DestTypesProposal(pipelineName, eligible = false, reason, message, fields = null, sampleRowCount = 0)

    /** Up to [[sampleLimit]] landed rows, or None when nothing has landed yet
      * (no table, or an empty one). */
    private def sampleLanded(config: PipelineConfig, destKind: String): Option[Seq[Map[String, Any]]] = {
        val rows: Seq[Map[String, Any]] = destKind match {
            case "postgres" =>
                if (!postgresTableExists(config)) return None
                val db = config.destination.database
                val sql = "SELECT * FROM \"" + db.schema + "\".\"" + db.table + "\""
                PostgresQueryUtil.query(sql, postgresDbName(config), sampleLimit).asScala.map(_.asScala.toMap).toSeq
            case "snowflake" =>
                val exists = SnowflakeConnectionUtil.withConnection(config.destination.database) { conn =>
                    val statement = conn.createStatement()
                    try snowflakeTableExists(statement, config) finally Try(statement.close())
                }
                if (!exists) return None
                SnowflakeQueryUtil.query(config.name, None, sampleLimit).results.asScala.map(_.asScala.toMap).toSeq
            case "databricks" =>
                val exists = DatabricksConnectionUtil.withConnection(config.destination.database) { conn =>
                    val statement = conn.createStatement()
                    try databricksTableExists(statement, config) finally Try(statement.close())
                }
                if (!exists) return None
                DatabricksQueryUtil.query(config.name, None, sampleLimit).results.asScala.map(_.asScala.toMap).toSeq
            case _ => return None
        }
        if (rows.isEmpty) None else Some(rows)
    }

    // ------------------------------------------------------------------
    // Apply: migrate the landed table (if any), then write typed config.
    // ------------------------------------------------------------------

    def apply(pipelineName: String, fields: java.util.List[SchemaField], actor: String): DestTypesApplied = {
        val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, pipelineName)
        if (config == null)
            throw new DatrisException("Pipeline not found: " + pipelineName)

        val destKind = inScopeDest(config).getOrElse(
            throw new DatrisException("Pipeline '" + pipelineName + "' destination does not support on-demand typing (postgres, snowflake, databricks only)")
        )
        if (!DestTypeInference.allString(effectiveFields(config)))
            throw new DatrisException("Destination fields for pipeline '" + pipelineName + "' already carry types — edit the pipeline to change them")

        val resolved = validateFields(config, fields)
        if (!DestTypeInference.hasTypedField(resolved))
            throw new DatrisException("All fields are string — nothing to apply")

        val migrated = destKind match {
            case "postgres" =>
                val exists = postgresTableExists(config)
                if (exists) migratePostgres(config, resolved)
                exists
            case "snowflake" =>
                SnowflakeConnectionUtil.withConnection(config.destination.database) { conn =>
                    val statement = conn.createStatement()
                    try {
                        val exists = snowflakeTableExists(statement, config)
                        if (exists) migrateSnowflake(statement, config, resolved)
                        exists
                    } finally Try(statement.close())
                }
            case "databricks" =>
                DatabricksConnectionUtil.withConnection(config.destination.database) { conn =>
                    val statement = conn.createStatement()
                    try {
                        val exists = databricksTableExists(statement, config)
                        if (exists) migrateDatabricks(statement, config, resolved)
                        exists
                    } finally Try(statement.close())
                }
        }

        // Destination-first ordering succeeded — now the config write.
        val existingProps = config.destination.schemaProperties
        val newProps =
            if (existingProps != null) existingProps.copy(fields = resolved)
            else SchemaProperties(dbName = null, fields = resolved)
        val updated = config.copy(destination = config.destination.copy(schemaProperties = newProps))
        val versioned = PipelineConfigIO.writeVersioned(updated, "dest schema typed", actor)
        logger.info("Dest types applied for pipeline '" + pipelineName + "' by " + actor +
            (if (migrated) " (landed table migrated)" else " (config only — no landed table)"))
        DestTypesApplied(pipelineName, resolved, migrated, versioned.version)
    }

    /** Applied fields must cover exactly the effective dest field names —
      * types are the only thing apply may change. */
    private[util] def validateFields(config: PipelineConfig, fields: java.util.List[SchemaField]): java.util.List[SchemaField] = {
        if (fields == null || fields.isEmpty)
            throw new DatrisException("fields is required: every destination column with its intended type")
        val currentNames = effectiveFields(config).asScala.map(_.name.toLowerCase).toSet
        val appliedNames = fields.asScala.map(_.name.toLowerCase).toSet
        if (currentNames != appliedNames)
            throw new DatrisException("Applied fields must have the same names as the destination columns: expected [" +
                effectiveFields(config).asScala.map(_.name).mkString(", ") + "]")
        val supported = Set("string", "boolean", "int", "bigint", "float", "double", "date", "timestamp")
        fields.asScala.foreach { f =>
            if (f.`type` == null || !supported.contains(f.`type`.toLowerCase))
                throw new DatrisException("Unsupported type '" + f.`type` + "' for field '" + f.name + "'. Supported: " + supported.mkString(", "))
        }
        fields
    }

    // ------------------------------------------------------------------
    // Postgres: in-place ALTER, one transaction.
    // ------------------------------------------------------------------

    private def postgresDbName(config: PipelineConfig): String =
        if (DatrisEnvironment.current.multiTenant) DatrisEnvironment.current.environment
        else config.destination.database.dbName

    private def postgresTableExists(config: PipelineConfig): Boolean = {
        val db = config.destination.database
        withPostgres(config) { statement =>
            val rs = statement.executeQuery(
                "SELECT to_regclass('\"" + db.schema + "\".\"" + db.table + "\"')"
            )
            try { rs.next() && rs.getString(1) != null } finally Try(rs.close())
        }
    }

    private def migratePostgres(config: PipelineConfig, fields: java.util.List[SchemaField]): Unit = {
        val db = config.destination.database
        val typed = fields.asScala.filter(f => !f.`type`.equalsIgnoreCase("string"))
        if (typed.isEmpty) return
        withPostgres(config) { statement =>
            val conn = statement.getConnection
            conn.setAutoCommit(false)
            try {
                typed.foreach { f =>
                    val pgType = postgresType(f.`type`)
                    // NULLIF: empty strings in landed TEXT become NULL, not a cast error.
                    val sql = "ALTER TABLE \"" + db.schema + "\".\"" + db.table + "\" " +
                        "ALTER COLUMN \"" + f.name + "\" TYPE " + pgType +
                        " USING NULLIF(\"" + f.name + "\", '')::" + pgType
                    logger.info("Dest-types migration (postgres): " + sql)
                    statement.execute(sql)
                }
                conn.commit()
            } catch {
                case e: Exception =>
                    Try(conn.rollback())
                    throw new DatrisException("Postgres migration failed — no changes were applied. A landed value would not cast: " + e.getMessage)
            } finally {
                Try(conn.setAutoCommit(true))
            }
        }
    }

    private def withPostgres[T](config: PipelineConfig)(f: Statement => T): T = {
        val secrets = SecretsRetrieverUtil.postgresSecrets()
        Class.forName("org.postgresql.Driver")
        val jdbcUrl = secrets.jdbcUrl + "/" + postgresDbName(config)
        PostgresPool.withConnection(jdbcUrl, secrets.username, secrets.password) { conn =>
            val statement = conn.createStatement()
            try f(statement) finally Try(statement.close())
        }
    }

    private def postgresType(t: String): String = t.toLowerCase match {
        case "tinyint" | "smallint" => "int2"
        case "float" => "float4"
        case "double" => "float8"
        case "string" => "text"
        case other => other // int, bigint, boolean, date, timestamp
    }

    // ------------------------------------------------------------------
    // Snowflake / Databricks: TRY_CAST-validate, then CREATE OR REPLACE
    // TABLE AS SELECT. Neither can retype a landed varchar column in place.
    // The swap drops constraints the loader's CREATE would have added (PK
    // from keyFields) — MERGE upserts key on the configured keyFields, not
    // the constraint, so loads keep working.
    // ------------------------------------------------------------------

    private def sqlString(s: String): String = "'" + s.replace("'", "''") + "'"

    private def snowflakeTableExists(statement: Statement, config: PipelineConfig): Boolean = {
        import SnowflakeConnectionUtil.{effectiveName, ident}
        val db = config.destination.database
        val rs = statement.executeQuery(
            "SELECT COUNT(*) FROM " + ident(db.dbName) + ".INFORMATION_SCHEMA.TABLES" +
                " WHERE TABLE_SCHEMA = " + sqlString(effectiveName(db.schema)) +
                " AND TABLE_NAME = " + sqlString(effectiveName(db.table))
        )
        try { rs.next() && rs.getLong(1) > 0 } finally Try(rs.close())
    }

    private def migrateSnowflake(statement: Statement, config: PipelineConfig, fields: java.util.List[SchemaField]): Unit = {
        import SnowflakeConnectionUtil.ident
        val table = SnowflakeConnectionUtil.qualifiedTable(config.destination.database)
        validateCasts(statement, table, fields, ident, (col, t) => "TRY_CAST(NULLIF(" + ident(col) + ", '') AS " + snowflakeType(t) + ")")

        val selectList = fields.asScala.map { f =>
            if (f.`type`.equalsIgnoreCase("string")) ident(f.name)
            else "CAST(NULLIF(" + ident(f.name) + ", '') AS " + snowflakeType(f.`type`) + ") AS " + ident(f.name)
        }.mkString(", ")
        val sql = "CREATE OR REPLACE TABLE " + table + " COPY GRANTS AS SELECT " + selectList + " FROM " + table
        logger.info("Dest-types migration (snowflake): " + sql)
        statement.execute(sql)
    }

    private def snowflakeType(t: String): String = t.toLowerCase match {
        case "string" => "VARCHAR"
        case "int" | "integer" | "tinyint" | "smallint" | "bigint" => "NUMBER(38,0)"
        case "float" | "double" => "FLOAT"
        case "boolean" => "BOOLEAN"
        case "date" => "DATE"
        case "timestamp" => "TIMESTAMP_NTZ"
        case other => other
    }

    private def databricksTableExists(statement: Statement, config: PipelineConfig): Boolean = {
        import DatabricksConnectionUtil.{effectiveName, ident}
        val db = config.destination.database
        val rs = statement.executeQuery(
            "SELECT COUNT(*) FROM " + ident(db.dbName) + ".information_schema.tables" +
                " WHERE table_schema = " + sqlString(effectiveName(db.schema)) +
                " AND table_name = " + sqlString(effectiveName(db.table))
        )
        try { rs.next() && rs.getLong(1) > 0 } finally Try(rs.close())
    }

    private def migrateDatabricks(statement: Statement, config: PipelineConfig, fields: java.util.List[SchemaField]): Unit = {
        import DatabricksConnectionUtil.ident
        val table = DatabricksConnectionUtil.qualifiedTable(config.destination.database)
        validateCasts(statement, table, fields, ident, (col, t) => "try_cast(nullif(" + ident(col) + ", '') AS " + databricksType(t) + ")")

        val selectList = fields.asScala.map { f =>
            if (f.`type`.equalsIgnoreCase("string")) ident(f.name)
            else "CAST(nullif(" + ident(f.name) + ", '') AS " + databricksType(f.`type`) + ") AS " + ident(f.name)
        }.mkString(", ")
        val sql = "CREATE OR REPLACE TABLE " + table + " AS SELECT " + selectList + " FROM " + table
        logger.info("Dest-types migration (databricks): " + sql)
        statement.execute(sql)
    }

    private def databricksType(t: String): String = t.toLowerCase match {
        case "string" => "STRING"
        case "int" | "integer" | "tinyint" | "smallint" => "INT"
        case "bigint" => "BIGINT"
        case "float" => "FLOAT"
        case "double" => "DOUBLE"
        case "boolean" => "BOOLEAN"
        case "date" => "DATE"
        case "timestamp" => "TIMESTAMP"
        case other => other
    }

    /** One validation query for all typed columns: per column, count non-empty
      * values TRY_CAST rejects. Any nonzero count aborts with the column named.
      * `colRef` renders a column reference in the destination's dialect. */
    private def validateCasts(statement: Statement, table: String, fields: java.util.List[SchemaField], colRef: String => String, tryCastExpr: (String, String) => String): Unit = {
        val typed = fields.asScala.filter(f => !f.`type`.equalsIgnoreCase("string")).toList
        if (typed.isEmpty) return
        val counts = typed.map { f =>
            "SUM(CASE WHEN NULLIF(" + colRef(f.name) + ", '') IS NOT NULL AND " + tryCastExpr(f.name, f.`type`) + " IS NULL THEN 1 ELSE 0 END)"
        }.mkString(", ")
        val rs = statement.executeQuery("SELECT " + counts + " FROM " + table)
        try {
            if (rs.next()) {
                typed.zipWithIndex.foreach { case (f, i) =>
                    val bad = rs.getLong(i + 1)
                    if (bad > 0)
                        throw new DatrisException("Cannot apply type '" + f.`type` + "' for column '" + f.name + "': " +
                            bad + " landed value(s) do not cast. Set the field back to string or clean the data, then apply again. No changes were applied.")
                }
            }
        } finally Try(rs.close())
    }
}
