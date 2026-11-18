package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.PipelineConfig

import java.util.Locale

/** The save-time "new destinations only" rule for restricted users' sessions
  * (a logged-in user limited to catalog grants). Pure; no I/O.
  *
  * A restricted user may not aim a pipeline at a warehouse table or
  * object-store prefix that already holds data, then read it through the
  * pipeline preview with the platform's stored credentials. Each fresh
  * target of a candidate in [[ProbedFamilies]] (the destinations a preview
  * reads back) is checked with [[DestinationProbe]]; a target that exists
  * refuses the save.
  *
  * A target is the `SharedDatasets` key plus the connection that reaches it
  * (`credentialsSecret`, `warehouse`, `role` for a database; `provider`,
  * `endpoint`, `credentialsSecret` for an object store): the same key behind
  * another secret is another account's data. Fresh = the stored config of the
  * same name does not have the same target. A target equal to (object store:
  * equal to or under the prefix of) a target of another pipeline the caller
  * can see is not probed: the caller can already preview it. */
object DestinationTargets {

    case class Target(key: SharedDatasets.Key, connection: Seq[String])

    /** Destination families a pipeline preview reads back. */
    val ProbedFamilies: Set[String] = Set("snowflake", "sqlserver", "databricks", "objectstore")

    private def norm(s: String): String = if (s == null) "" else s.trim

    /** The candidate's targets in [[ProbedFamilies]], in `SharedDatasets.keysOf` order. */
    def targetsOf(c: PipelineConfig, env: SharedDatasets.Env): Seq[Target] = {
        if (c == null || c.destination == null) return Nil
        SharedDatasets.keysOf(c, env).filter(k => ProbedFamilies.contains(k.family)).map { k =>
            val connection =
                if (k.family == "objectstore") {
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
            Target(k, connection)
        }
    }

    /** The candidate's targets its stored config (same name; null for a new
      * pipeline) does not have. */
    def freshTargets(candidate: PipelineConfig, stored: PipelineConfig, env: SharedDatasets.Env): Seq[Target] = {
        val kept = targetsOf(stored, env).toSet
        targetsOf(candidate, env).filterNot(kept.contains)
    }

    /** True when a caller who can preview `other` can already read `t`: a
      * table with the same key and connection; an object-store prefix in the
      * same bucket with the same connection, equal to or under `other`'s
      * prefix on a path-segment boundary. */
    def coveredBy(t: Target, other: Target): Boolean = {
        if (t == null || other == null || t.key.family != other.key.family || t.connection != other.connection) return false
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
        val fresh = freshTargets(candidate, stored, env)
        if (fresh.isEmpty) return Nil
        val name = if (candidate == null) null else candidate.name
        val visibleTargets = Option(visibleOthers).getOrElse(Nil).filter(o => o != null && o.name != name).flatMap(o => targetsOf(o, env))
        fresh.filterNot(t => visibleTargets.exists(v => coveredBy(t, v)))
    }

    /** 400 refusal text; `label` is `SharedDatasets.label` of the caller's
      * own target, so it names nothing the caller did not type. */
    def ExistsRefusal(label: String): String =
        "The destination " + label + " already exists. Choose a table or prefix that does not exist yet, " +
            "or ask a user with install-wide access to save this destination."

    /** 500 text when the probe failed (fail closed). */
    val ProbeUnavailable: String = "Could not check whether the destination already exists; nothing was saved. Try again."
}
