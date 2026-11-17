package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.PipelineConfig

import java.util.Locale

/** Identity of the structured datasets a pipeline writes (a Postgres, SQL
  * Server, Snowflake or Databricks table, a MongoDB collection, an
  * object-store prefix, a Kafka topic, an ActiveMQ queue), normalised the way
  * each destination resolves the name. Pure; no I/O. Used by the
  * hidden-writer save rule (a caller who cannot see every pipeline may not
  * point a pipeline at a dataset a hidden pipeline writes) and the structured
  * delete guard (a table or collection another pipeline also writes is kept).
  *
  * This is a second key beside `LineageService.DatasetRef.id`, which stays as
  * it is (stored run-lineage docs key on it). Over-matching is the safe
  * direction: the account / credentials secret is not part of the key.
  *
  * Families: `pgtable` (a Postgres destination table and a pgvector table are
  * the same Postgres object when schema and table match and the databases
  * match or the pgvector database is unknown), `mongodb`, `snowflake`,
  * `databricks`, `sqlserver`, `objectstore` (prefix containment on a
  * path-segment boundary, as `ObjectStoreSpark.destinationsOverlap`), `kafka`,
  * `activemq`. Vector stores other than pgvector have no key here (the
  * vector-container rule owns them). */
object SharedDatasets {

    /** What the keys depend on outside the config: the environment's default
      * bucket, the tenant database (multiTenant; replaces `dbName` for
      * Postgres and MongoDB) and the pgvector secret's database (None =
      * unknown, matches any Postgres database). */
    case class Env(defaultBucket: String, tenantDatabase: Option[String], pgvectorDatabase: Option[String])

    /** `family` is the identity space, `kind` the destination kind (the
      * `LineageService` dataset kind, or `scratch`), `parts` the normalised
      * coordinates. */
    case class Key(family: String, kind: String, parts: Seq[String])

    /** Families the delete guard keeps when shared. */
    val StructuredFamilies: Set[String] = Set("pgtable", "mongodb", "snowflake", "databricks", "sqlserver")

    /** Database marker for an unknown pgvector database. */
    private val UnknownDatabase = ""

    private def blank(s: String): Boolean = s == null || s.trim.isEmpty
    private def orEmpty(s: String): String = if (s == null) "" else s
    private def lower(s: String): String = orEmpty(s).toLowerCase(Locale.ROOT)

    /** The database of a JDBC URL (last path segment, query string
      * stripped); None when the URL has no database path. */
    def databaseOfJdbcUrl(jdbcUrl: String): Option[String] =
        Option(jdbcUrl).flatMap { u =>
            val noQuery = u.takeWhile(_ != '?')
            val afterProtocol = noQuery.replaceFirst("^[^:]+:[^:]+://", "")
            val slash = afterProtocol.indexOf('/')
            if (slash < 0) None
            else Option(afterProtocol.substring(slash + 1).split('/').lastOption.getOrElse("")).map(_.trim).filter(_.nonEmpty)
        }

    /** Every shared-dataset key the config's destination names.
      * `withScratch` adds a scratch destination's folder
      * (`_scratch/<name>` in the default bucket); only a hidden pipeline's
      * scratch folder is a dataset to the hidden-writer rule. */
    def keysOf(c: PipelineConfig, env: Env, withScratch: Boolean = false): Seq[Key] = {
        if (c == null || c.destination == null) return Nil
        val d = c.destination
        val out = Seq.newBuilder[Key]
        if (d.database != null) {
            val db = d.database
            if (db.usePostgres && !blank(db.table)) {
                val database = env.tenantDatabase.getOrElse(orEmpty(db.dbName))
                val schema = if (blank(db.schema)) "public" else db.schema
                out += Key("pgtable", "postgres", Seq(database, schema, db.table))
            }
            if (db.useMongoDB && !blank(db.table)) {
                val database = env.tenantDatabase.getOrElse(if (db.dbName == null) "datris" else db.dbName)
                out += Key("mongodb", "mongodb", Seq(database, db.table))
            }
            if (db.useSnowflake && !blank(db.table))
                out += Key(
                    "snowflake",
                    "snowflake",
                    Seq(db.dbName, db.schema, db.table).map(s => SnowflakeConnectionUtil.effectiveName(orEmpty(s)))
                )
            if (db.useDatabricks && !blank(db.table))
                out += Key(
                    "databricks",
                    "databricks",
                    Seq(db.dbName, db.schema, db.table).map(s => DatabricksConnectionUtil.effectiveName(orEmpty(s)))
                )
            if (db.useSqlServer && !blank(db.table)) {
                val schema = if (blank(db.schema)) SqlServerConnectionUtil.DefaultSchema else db.schema.trim
                out += Key("sqlserver", "sqlserver", Seq(lower(db.dbName), lower(schema), lower(db.table)))
            }
        }
        if (d.objectStore != null) {
            val os = d.objectStore
            val bucket = if (os.destinationBucketOverride != null) os.destinationBucketOverride else env.defaultBucket
            out += Key("objectstore", "objectstore", Seq(orEmpty(bucket), ObjectStoreSpark.normalizePrefix(os.prefixKey)))
        }
        if (d.kafka != null && !blank(d.kafka.topic)) out += Key("kafka", "kafka", Seq(d.kafka.topic))
        if (d.activeMQ != null && !blank(d.activeMQ.queueName)) out += Key("activemq", "activemq", Seq(d.activeMQ.queueName))
        if (d.pgvector != null && !blank(d.pgvector.tableName)) {
            val database = env.pgvectorDatabase.getOrElse(UnknownDatabase)
            val schema = if (blank(d.pgvector.schemaName)) "public" else d.pgvector.schemaName
            out += Key("pgtable", "pgvector", Seq(database, schema, d.pgvector.tableName))
        }
        if (withScratch && d.scratch != null && !blank(c.name))
            out += Key("objectstore", "scratch", Seq(orEmpty(env.defaultBucket), "_scratch/" + c.name))
        out.result()
    }

    /** True when `a` and `b` are (or, for object stores, contain) the same data. */
    def overlaps(a: Key, b: Key): Boolean = {
        if (a == null || b == null || a.family != b.family) return false
        a.family match {
            case "objectstore" =>
                if (a.parts.head != b.parts.head) false
                else {
                    val pa = a.parts(1)
                    val pb = b.parts(1)
                    pa.isEmpty || pb.isEmpty || pa == pb || pa.startsWith(pb + "/") || pb.startsWith(pa + "/")
                }
            case "pgtable" =>
                val sameObject = a.parts(1) == b.parts(1) && a.parts(2) == b.parts(2)
                val crossKind = a.kind == "pgvector" || b.kind == "pgvector"
                sameObject && (a.parts.head == b.parts.head ||
                    (crossKind && (pgvectorUnknown(a) || pgvectorUnknown(b))))
            case _ => a.parts == b.parts
        }
    }

    private def pgvectorUnknown(k: Key): Boolean = k.kind == "pgvector" && k.parts.head == UnknownDatabase

    /** Keys the candidate names that its stored config (same name, null for
      * a new pipeline) does not, and that a pipeline with another name for
      * which `visible` is false also writes (its scratch folder included).
      * A dataset unchanged from the stored config never conflicts
      * (grandfathered sharers). */
    def hiddenWriterConflicts(
        candidate: PipelineConfig,
        stored: PipelineConfig,
        others: Seq[PipelineConfig],
        visible: PipelineConfig => Boolean,
        env: Env
    ): Seq[Key] = {
        if (candidate == null) return Nil
        val kept = keysOf(stored, env).toSet
        val fresh = keysOf(candidate, env).filterNot(kept.contains)
        if (fresh.isEmpty) return Nil
        val hidden = others.filter(o => o != null && o.name != candidate.name && !visible(o))
        if (hidden.isEmpty) return Nil
        val hiddenKeys = hidden.flatMap(o => keysOf(o, env, withScratch = true))
        fresh.filter(k => hiddenKeys.exists(h => overlaps(k, h)))
    }

    /** The config's structured keys (tables and collections) that another
      * pipeline also writes, with those pipelines: data a delete must keep.
      * A pgvector-pgvector pair is left to `VectorContainers`. */
    def sharedOnDelete(config: PipelineConfig, others: Seq[PipelineConfig], env: Env): Seq[(Key, Seq[PipelineConfig])] = {
        if (config == null) return Nil
        val own = keysOf(config, env).filter(k => StructuredFamilies.contains(k.family))
        if (own.isEmpty) return Nil
        val rest = others.filter(o => o != null && o.name != config.name)
        own
            .map { k =>
                k -> rest.filter { o =>
                    keysOf(o, env).exists(ok =>
                        StructuredFamilies.contains(ok.family) && !(k.kind == "pgvector" && ok.kind == "pgvector") && overlaps(k, ok)
                    )
                }
            }
            .filter(_._2.nonEmpty)
    }

    /** Human label, e.g. "postgres table datris.public.orders",
      * "mongodb collection datris.events". */
    def label(k: Key): String = {
        val coords = k.parts.filter(_.nonEmpty).mkString(".")
        k.kind match {
            case "pgvector" => "pgvector table " + k.parts.drop(1).mkString(".")
            case "mongodb" => "mongodb collection " + coords
            case "objectstore" | "scratch" => "object-store prefix " + coords
            case "kafka" => "kafka topic " + coords
            case "activemq" => "activemq queue " + coords
            case kind => kind + " table " + coords
        }
    }

    /** Save refusal for a dataset a pipeline hidden from the caller writes.
      * Never names a pipeline or catalog, and is the same whatever the
      * candidate's `authoritative` flag says. */
    def refusalMessage(datasetName: String): String =
        "The destination " + datasetName + " is already in use by another pipeline. Choose another destination."

    /** Delete warning for a kept shared table or collection; names the other
      * pipelines only when given. */
    def keptOnDeleteMessage(label: String, visibleNames: Option[Seq[String]]): String =
        visibleNames.filter(_.nonEmpty) match {
            case Some(ns) => "Kept " + label + ": pipeline(s) " + ns.map("'" + _ + "'").mkString(", ") + " also write to it."
            case None => "Kept " + label + ": another pipeline also writes to it."
        }
}
