package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{ObjectStore, PipelineConfig}

import java.util.Locale

/** The save-time "new destinations only" rule for restricted users' sessions
  * (a logged-in user limited to catalog grants). Pure; no I/O.
  *
  * A restricted user may not aim a pipeline at a table, collection or
  * object-store prefix that already holds data, then read it (a preview, the
  * destination-type sample), overwrite it or drop it through the pipeline
  * with the platform's stored credentials. Each fresh target of a candidate
  * in [[ProbedFamilies]] is checked with [[DestinationProbe]]; a target that
  * exists refuses the save. A pgvector table (family `pgtable`, kind
  * `pgvector`) is not a target of [[targetsOf]]: it is checked on its own
  * path ([[vectorTableTargets]], [[vectorTablesToProbe]],
  * `DestinationProbe.vectorTableExists`), beside the one-container rule.
  *
  * A target is the `SharedDatasets` key plus the connection that reaches it
  * (`credentialsSecret`, `warehouse`, `role` for a warehouse; `provider`,
  * `endpoint`, `credentialsSecret` for an object store; nothing for Postgres
  * and MongoDB, which are always reached with the platform's own
  * connection): the same key behind another secret is another account's data. Fresh = the stored config of the
  * same name does not have the same target. A target equal to (object store:
  * equal to or under the prefix of) a target of another pipeline the caller
  * can see is not probed: the caller can already preview it.
  *
  * `exact` carries coordinates the key normalises away but the destination
  * may not: for SQL Server the raw database, effective schema and table, since
  * a case-sensitive collation tells `Customers` from `customers`. A change
  * there alone keeps the target out of [[freshTargets]] (the shared-dataset
  * key is unchanged) but puts it in [[toProbe]], and a cover needs it equal. */
object DestinationTargets {

    case class Target(key: SharedDatasets.Key, connection: Seq[String], exact: Seq[String] = Nil)

    /** Destination families checked: the ones a pipeline preview or the
      * destination-type sample reads back, and the platform's Postgres tables
      * and MongoDB collections. */
    val ProbedFamilies: Set[String] = Set("snowflake", "sqlserver", "databricks", "objectstore", "pgtable", "mongodb")

    /** Families reached with the platform's own connection (no per-pipeline
      * credentials): their targets carry no connection. */
    private val PlatformFamilies: Set[String] = Set("pgtable", "mongodb")

    private def norm(s: String): String = if (s == null) "" else s.trim

    /** The candidate's targets in [[ProbedFamilies]], in `SharedDatasets.keysOf` order. */
    def targetsOf(c: PipelineConfig, env: SharedDatasets.Env): Seq[Target] = {
        if (c == null || c.destination == null) return Nil
        SharedDatasets.keysOf(c, env).filter(k => ProbedFamilies.contains(k.family) && k.kind != "pgvector").map { k =>
            val connection =
                if (PlatformFamilies.contains(k.family)) Nil
                else if (k.family == "objectstore") {
                    val os = c.destination.objectStore
                    Seq(
                        Option(os.provider).map(_.trim.toLowerCase(Locale.ROOT)).filter(_.nonEmpty).getOrElse("minio"),
                        norm(os.endpoint),
                        norm(os.credentialsSecret)
                    )
                } else {
                    val db = c.destination.database
                    Seq(norm(db.credentialsSecret), norm(db.warehouse), norm(db.role))
                }
            val exact =
                if (k.family == "sqlserver") {
                    val db = c.destination.database
                    Seq(norm(db.dbName), SqlServerConnectionUtil.schemaName(db), Option(db.table).getOrElse(""))
                } else Nil
            Target(k, connection, exact)
        }
    }

    /** The candidate's targets its stored config (same name; null for a new
      * pipeline) does not have. */
    def freshTargets(candidate: PipelineConfig, stored: PipelineConfig, env: SharedDatasets.Env): Seq[Target] = {
        val kept = targetsOf(stored, env)
        targetsOf(candidate, env).filterNot(t => kept.exists(k => sameTarget(t, k)))
    }

    /** Same key and connection (`exact` aside). */
    private def sameTarget(a: Target, b: Target): Boolean = a.key == b.key && a.connection == b.connection

    /** The candidate's targets the stored config has with the same key and
      * connection but other exact coordinates (a SQL Server letter-case
      * change): not fresh, still probed. */
    def recasedTargets(candidate: PipelineConfig, stored: PipelineConfig, env: SharedDatasets.Env): Seq[Target] = {
        val kept = targetsOf(stored, env)
        targetsOf(candidate, env).filter(t => kept.exists(k => sameTarget(t, k)) && !kept.exists(k => sameTarget(t, k) && k.exact == t.exact))
    }

    /** True when a caller who can preview `other` can already read `t`: a
      * table with the same key and connection; an object-store prefix in the
      * same bucket with the same connection, equal to or under `other`'s
      * prefix on a path-segment boundary. */
    def coveredBy(t: Target, other: Target): Boolean = {
        if (t == null || other == null || t.key.family != other.key.family || t.connection != other.connection || t.exact != other.exact)
            return false
        if (t.key.family == "objectstore") {
            if (t.key.parts.head != other.key.parts.head) false
            else {
                val pt = t.key.parts(1)
                val po = other.key.parts(1)
                // A preview never reads an empty prefix (the whole bucket),
                // so an empty prefix covers only itself.
                pt == po || (po.nonEmpty && pt.startsWith(po + "/"))
            }
        } else t.key == other.key
    }

    /** The fresh targets of `candidate` that no pipeline in `visibleOthers`
      * (another name, one the caller can see) covers: the ones to probe. */
    def toProbe(candidate: PipelineConfig, stored: PipelineConfig, visibleOthers: Seq[PipelineConfig], env: SharedDatasets.Env): Seq[Target] = {
        val fresh = freshTargets(candidate, stored, env) ++ recasedTargets(candidate, stored, env)
        if (fresh.isEmpty) return Nil
        val name = if (candidate == null) null else candidate.name
        val visibleTargets = Option(visibleOthers).getOrElse(Nil).filter(o => o != null && o.name != name).flatMap(o => targetsOf(o, env))
        fresh.filterNot(t => visibleTargets.exists(v => coveredBy(t, v)))
    }

    // ------------------------------------------------------------ pgvector tables

    /** The candidate's pgvector table, on a path separate from [[targetsOf]]
      * (which leaves pgvector to the one-container rule): a pgvector
      * pipeline's delete drops `"schema"."table"` in the pgvector secret's
      * database whatever the table holds, so a restricted user's new or
      * changed pgvector table must not exist yet either. The key is the
      * `SharedDatasets` pgvector key; `exact` is the schema and table as the
      * loader and the delete's DROP address them: the schema as written
      * (`public` only when null), the table as written, each one quoted
      * identifier (case kept). No connection: there is one pgvector secret
      * per environment. */
    def vectorTableTargets(c: PipelineConfig, env: SharedDatasets.Env): Seq[Target] = {
        if (c == null || c.destination == null || c.destination.pgvector == null) return Nil
        val p = c.destination.pgvector
        SharedDatasets.keysOf(c, env).filter(k => k.family == "pgtable" && k.kind == "pgvector").map { k =>
            Target(k, Nil, Seq(Option(p.schemaName).getOrElse("public"), p.tableName))
        }
    }

    /** The candidate's pgvector tables its stored config (same name; null
      * for a new pipeline) does not have with the same key and exact names. */
    def freshVectorTables(candidate: PipelineConfig, stored: PipelineConfig, env: SharedDatasets.Env): Seq[Target] = {
        val kept = vectorTableTargets(stored, env)
        vectorTableTargets(candidate, env).filterNot(kept.contains)
    }

    /** The fresh pgvector tables of `candidate` that no pgvector pipeline in
      * `visibleOthers` (another name, one the caller can see) names with the
      * same key and exact names: the ones to probe. A visible Postgres
      * destination table at the same place covers nothing here. */
    def vectorTablesToProbe(
        candidate: PipelineConfig,
        stored: PipelineConfig,
        visibleOthers: Seq[PipelineConfig],
        env: SharedDatasets.Env
    ): Seq[Target] = {
        val fresh = freshVectorTables(candidate, stored, env)
        if (fresh.isEmpty) return Nil
        val name = if (candidate == null) null else candidate.name
        val visibleTables =
            Option(visibleOthers).getOrElse(Nil).filter(o => o != null && o.name != name).flatMap(o => vectorTableTargets(o, env))
        fresh.filterNot(visibleTables.contains)
    }

    /** True when the pgvector schema or table cannot be addressed as one
      * quoted identifier the way the loader and the delete's DROP build it
      * (`"` + name + `"`, nothing escaped): a double quote or NUL in either,
      * or an empty (not null) schema. No probe can answer for such a name. */
    def unsafeVectorTableName(c: PipelineConfig): Boolean = {
        if (c == null || c.destination == null || c.destination.pgvector == null) return false
        val p = c.destination.pgvector
        def bad(s: String): Boolean = s != null && (s.contains('"') || s.contains('\u0000'))
        bad(p.schemaName) || bad(p.tableName) || (p.schemaName != null && p.schemaName.isEmpty)
    }

    /** 400 refusal text for [[unsafeVectorTableName]]; names nothing. */
    val UnsafeVectorTableRefusal: String =
        "The pgvector schema and table names must not contain double quotes, and the schema must not be empty. Choose plain names."

    /** Characters a Hadoop / Spark path read expands as a glob or escape. */
    val GlobCharacters: Set[Char] = Set('*', '?', '[', ']', '{', '}', '\\')

    /** True when an object-store destination's path is not a plain prefix: a
      * glob or escape character in `prefixKey` or `destinationBucketOverride`,
      * a `/` in the bucket, leading or trailing whitespace on either, or an
      * empty, `.` or `..` segment in the prefix
      * (leading and trailing slashes aside). Such a path is read (globbed,
      * normalised) somewhere other than the key and the probe address. */
    def unsafeObjectStorePath(os: ObjectStore): Boolean = {
        if (os == null) return false
        def globbed(s: String): Boolean = s != null && s.exists(GlobCharacters.contains)
        val prefix = ObjectStoreSpark.normalizePrefix(os.prefixKey)
        val badSegment = prefix.nonEmpty && prefix.split("/", -1).exists(seg => seg.isEmpty || seg == "." || seg == "..")
        def padded(s: String): Boolean = s != null && s != s.trim
        // The key and the probe trim; the read does not.
        padded(os.prefixKey) || padded(os.destinationBucketOverride) ||
        globbed(os.prefixKey) || globbed(os.destinationBucketOverride) ||
        (os.destinationBucketOverride != null && os.destinationBucketOverride.contains("/")) || badSegment
    }

    /** 400 refusal text for [[unsafeObjectStorePath]]; names nothing. */
    val UnsafePathRefusal: String =
        "The object-store prefix and bucket must be plain names: no wildcard or escape characters (* ? [ ] { } \\), " +
            "no leading or trailing spaces and no empty, '.' or '..' path segments. Choose a plain prefix."

    /** True when the config's SQL Server table is a temporary one (`#name`,
      * `##name`): SQL Server resolves it in tempdb whatever the schema, so
      * neither the probe nor the key addresses what a preview would read. */
    def sqlServerTempTable(c: PipelineConfig): Boolean =
        c != null && c.destination != null && c.destination.database != null && c.destination.database.useSqlServer &&
            Option(c.destination.database.table).exists(_.trim.startsWith("#"))

    /** 400 refusal text for [[sqlServerTempTable]]; names nothing. */
    val TempTableRefusal: String =
        "A SQL Server temporary table (a name starting with #) cannot be a pipeline destination. Choose another table name."

    /** 400 refusal text when Unity Catalog state kept for the pipeline's
      * name records a catalog commit: the remedy is another name, not
      * another prefix. Names nothing. */
    val NameCommittedRefusal: String =
        "A pipeline of this name has an Iceberg table committed through Unity Catalog on record. " +
            "Choose another pipeline name, or ask a user with install-wide access to save this destination."

    /** 400 refusal text; `label` is `SharedDatasets.label` of the caller's
      * own target, so it names nothing the caller did not type. */
    def ExistsRefusal(label: String): String =
        "The destination " + label + " already exists. Choose a table or prefix that does not exist yet, " +
            "or ask a user with install-wide access to save this destination."

    /** 500 text when the probe failed (fail closed). */
    val ProbeUnavailable: String = "Could not check whether the destination already exists; nothing was saved. Try again."

    /** 429 text when the caller already has `DestinationProbe.MaxPerOwner`
      * checks running: their own limit, nothing about the destination. */
    val TooManyChecksRefusal: String =
        "Another destination check of yours is still running; nothing was saved. Wait a moment and try again."

    /** `Retry-After` (seconds) of the 429. */
    val RetryAfterSeconds: String = "5"
}
