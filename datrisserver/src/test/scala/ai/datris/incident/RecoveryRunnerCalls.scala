package ai.datris.incident

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.JsonObject

/** The REST calls the recovery runner itself sends for an action
  * (`IncidentRunner.RealExecutor.restFor`), for specs outside this package. */
object RecoveryRunnerCalls {

    /** (method, path, body) the runner sends for `tool` with `args`. */
    def of(tool: String, args: JsonObject): Option[(String, String, Option[String])] = IncidentRunner.RealExecutor.restFor(tool, args)

    /** The runner's three mutating calls: save a tap script, run a tap, and
      * regenerate a pipeline's stored AI script. */
    def mutating(tap: String, pipeline: String, kind: String): Seq[(String, (String, String, Option[String]))] = {
        def args(kv: (String, String)*): JsonObject = {
            val o = new JsonObject()
            kv.foreach { case (k, v) => o.addProperty(k, v) }
            o
        }
        Seq(
            "update_tap" -> args("name" -> tap, "script" -> "def fetch(ctx):\n    return []\n"),
            "run_tap" -> args("name" -> tap),
            IncidentRunner.RegenerateScripts -> args("name" -> pipeline, "kind" -> kind)
        ).map { case (tool, a) => tool -> of(tool, a).getOrElse(throw new IllegalStateException("the runner sends no call for " + tool)) }
    }
}
