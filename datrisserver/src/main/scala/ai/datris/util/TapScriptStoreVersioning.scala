package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.TapConfig
import com.google.gson.{Gson, JsonObject}

/** Version-history decisions for the two tap write lanes, kept pure so they
  * are unit-testable without Spring or a database.
  *
  *  - [[decide]]: the script store lane (`POST /tap/script`) repoints a stored
  *    tap at new script bytes. When that replaces the script of an EXISTING
  *    tap it is a definition edit and must land as a new version; the first
  *    upload for a not-yet-saved tap, an identical re-store, and a write that
  *    only moves the test stamp stay plain writes.
  *  - [[sameDefinition]]: the config lane (`POST /tap`) skips minting a version
  *    when the incoming config equals the stored one, so a store followed by
  *    an unchanged config save (MCP `create_tap` over an existing tap) records
  *    one version, not two. */
object TapScriptStoreVersioning {

    val ScriptReplaced = "script replaced"

    /** Some(change note) when `repointed` replaces the script of `existing`
      * and must be written with `TapConfigIO.writeVersioned`; None for a plain
      * write. `identity` names the bytes a tap runs (TapScriptIdentity.of);
      * two references naming the same readable bytes are the same script. */
    def decide(
        existing: TapConfig,
        repointed: TapConfig,
        incidentId: Option[String],
        identity: TapConfig => Option[String]
    ): Option[String] = {
        if (existing == null || repointed == null) None
        else if (sameReference(existing, repointed)) None
        else {
            val before = safeIdentity(existing, identity)
            val after = safeIdentity(repointed, identity)
            if (before.isDefined && before == after) None
            else Some(ScriptReplaced + incidentId.map(_.trim).filter(_.nonEmpty).map(i => " (incident " + i + ")").getOrElse(""))
        }
    }

    /** True when the two configs differ only in server-managed churn (version,
      * timestamps, owner label, run and test-run status, retry counter). */
    def sameDefinition(stored: TapConfig, incoming: TapConfig): Boolean =
        stored != null && incoming != null &&
            VersionDiff.configDiff(definitionJson(stored), definitionJson(incoming)).isEmpty

    private val VolatileFields = Set("version", "createdAt", "updatedAt", "createdByKeyLabel", "retryCount")

    private def definitionJson(t: TapConfig): String = {
        val o: JsonObject = new Gson().toJsonTree(t).getAsJsonObject
        val drop = o.keySet().toArray(new Array[String](0)).filter(k => VolatileFields(k) || k.startsWith("lastRun") || k.startsWith("lastTestRun"))
        drop.foreach(o.remove)
        o.toString
    }

    private def sameReference(a: TapConfig, b: TapConfig): Boolean =
        a.scriptStorage == b.scriptStorage && a.scriptPath == b.scriptPath &&
            a.scriptRepoPath == b.scriptRepoPath && a.scriptCommitSha == b.scriptCommitSha

    private def safeIdentity(t: TapConfig, identity: TapConfig => Option[String]): Option[String] =
        try identity(t)
        catch { case e: Exception if scala.util.control.NonFatal(e) => None }
}
