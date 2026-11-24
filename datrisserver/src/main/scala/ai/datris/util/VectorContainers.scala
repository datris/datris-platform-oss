package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.PipelineConfig

import java.util.Locale

/** One vector container (qdrant collection, weaviate class, milvus
  * collection, chroma collection, pgvector schema + table) belongs to one
  * pipeline. Pure identity rules shared by the save refusal, the delete
  * guard and the search visibility check; no I/O.
  *
  * Identity: qdrant, milvus and chroma names compare exactly (loaders pass
  * them through verbatim); weaviate class names case-insensitively (the
  * store capitalises them); pgvector by case-sensitive table plus schema
  * (null or blank = "public"; identifiers are double-quoted), each compared
  * as PostgreSQL keeps it: its first 63 UTF-8 bytes, cut on a character
  * boundary ([[PgIdentifier.kept]]), so two names PostgreSQL cuts to one
  * table are one container. Messages show the names as configured
  * ([[displayKey]]), never the cut form. Different
  * stores never collide. A null or blank name has no key. Secret names are
  * not part of the key: there is one store instance per store per
  * environment. */
object VectorContainers {

    case class Key(store: String, schema: String, name: String)

    val Stores: Seq[String] = Seq("qdrant", "weaviate", "milvus", "chroma", "pgvector")

    private def blank(s: String): Boolean = s == null || s.trim.isEmpty

    /** The container key for `name` in `store` (`schema` applies to pgvector
      * only); None for a null or blank name or an unknown store. */
    def key(store: String, name: String, schema: Option[String]): Option[Key] =
        if (blank(name)) None
        else
            store match {
                case "qdrant" | "milvus" | "chroma" => Some(Key(store, "", name))
                case "weaviate" => Some(Key(store, "", name.toLowerCase(Locale.ROOT)))
                case "pgvector" =>
                    val s = schema.filter(!blank(_)).getOrElse("public")
                    Some(Key(store, PgIdentifier.kept(s), PgIdentifier.kept(name)))
                case _ => None
            }

    /** Every vector container the pipeline's destination names. */
    def keysOf(c: PipelineConfig): Seq[Key] = {
        val d = Option(c).flatMap(x => Option(x.destination)).orNull
        if (d == null) Nil
        else
            Seq(
                Option(d.qdrant).flatMap(q => key("qdrant", q.collectionName, None)),
                Option(d.weaviate).flatMap(w => key("weaviate", w.className, None)),
                Option(d.milvus).flatMap(m => key("milvus", m.collectionName, None)),
                Option(d.chroma).flatMap(ch => key("chroma", ch.collectionName, None)),
                Option(d.pgvector).flatMap(p => key("pgvector", p.tableName, Option(p.schemaName)))
            ).flatten
    }

    /** The configs (nulls dropped) that name `k`. */
    def namers(k: Key, configs: Seq[PipelineConfig]): Seq[PipelineConfig] =
        configs.filter(c => c != null && keysOf(c).contains(k))

    private def namedByOthers(self: String, k: Key, others: Seq[PipelineConfig]): Seq[PipelineConfig] =
        namers(k, others.filter(o => o != null && o.name != self))

    /** Keys the candidate names that its stored config of the same name
      * (null for a new pipeline) does not, and which a pipeline with another
      * name already holds, with those pipelines. A container unchanged from
      * the stored config is never a conflict (grandfathered sharers). */
    def saveConflicts(candidate: PipelineConfig, stored: PipelineConfig, others: Seq[PipelineConfig]): Seq[(Key, Seq[PipelineConfig])] = {
        if (candidate == null) return Nil
        val kept = keysOf(stored).toSet
        keysOf(candidate)
            .filterNot(kept.contains)
            .map(k => k -> namedByOthers(candidate.name, k, others))
            .filter(_._2.nonEmpty)
    }

    /** The config's keys that another pipeline also names, with those
      * pipelines: containers a delete must keep. */
    def sharedOnDelete(config: PipelineConfig, others: Seq[PipelineConfig]): Seq[(Key, Seq[PipelineConfig])] =
        if (config == null) Nil
        else
            keysOf(config)
                .map(k => k -> namedByOthers(config.name, k, others))
                .filter(_._2.nonEmpty)

    /** `k` for messages only: a weaviate key carries the class name as `c`
      * wrote it (the key itself is lowercased for identity); a pgvector key
      * carries the schema and table as `c` wrote them (the key holds what
      * PostgreSQL keeps of each, which differs for a name over 63 bytes;
      * a null or blank schema shows as "public"). Other keys and a config
      * without that container are returned unchanged. */
    def displayKey(k: Key, c: PipelineConfig): Key = {
        val d = Option(c).flatMap(x => Option(x.destination))
        k.store match {
            case "weaviate" =>
                d.flatMap(d => Option(d.weaviate))
                    .map(_.className)
                    .filter(n => key("weaviate", n, None).contains(k))
                    .map(n => k.copy(name = n))
                    .getOrElse(k)
            case "pgvector" =>
                d.flatMap(d => Option(d.pgvector))
                    .filter(p => key("pgvector", p.tableName, Option(p.schemaName)).contains(k))
                    .map(p => k.copy(schema = Option(p.schemaName).filter(!blank(_)).getOrElse("public"), name = p.tableName))
                    .getOrElse(k)
            case _ => k
        }
    }

    /** Human label, e.g. "pgvector table public.docs", "qdrant collection docs". */
    def label(k: Key): String = k.store match {
        case "pgvector" => "pgvector table " + k.schema + "." + k.name
        case "weaviate" => "weaviate class " + k.name
        case s => s + " collection " + k.name
    }

    private def quoted(names: Seq[String]): String = names.map("'" + _ + "'").mkString(", ")

    /** Save refusal text; names the other pipelines only when given. */
    def refusalMessage(k: Key, visibleNames: Option[Seq[String]]): String =
        visibleNames.filter(_.nonEmpty) match {
            case Some(ns) =>
                "The " + label(k) + " is already used by pipeline(s) " + quoted(ns) +
                    ". A vector collection, class or table belongs to one pipeline; choose another name."
            case None =>
                "The " + label(k) + " is already in use by another pipeline. " +
                    "A vector collection, class or table belongs to one pipeline; choose another name."
        }

    /** Delete warning for a kept shared container; names the other pipelines only when given. */
    def keptOnDeleteMessage(k: Key, visibleNames: Option[Seq[String]]): String =
        visibleNames.filter(_.nonEmpty) match {
            case Some(ns) => "Kept " + label(k) + ": pipeline(s) " + quoted(ns) + " also write to it."
            case None => "Kept " + label(k) + ": another pipeline also writes to it."
        }
}
