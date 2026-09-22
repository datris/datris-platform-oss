package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisEnvironment

/** The tap budgets actually in force on this install, plus where each value
  * came from (plans/stories/tap-sizing-effective-budgets.md).
  *
  * Both agent channels read from here: the in-product Assistant interpolates
  * the values into its sizing rule at request time
  * (`AssistantAPIController.sizingRule`), and an MCP client reads the same
  * numbers off `GET /api/v1/version`. Nothing here changes a default — it only
  * reports the value an install runs with, so a prompt never declares a breach
  * against a documented default that is not in force.
  */
object TapBudgets {

    /** Source of a resolved value: the environment, the deprecated alias, or
      * the documented default. */
    val SourceEnv: String = "env"
    val SourceDeprecatedAlias: String = "deprecated-alias"
    val SourceDefault: String = "default"

    /** Documented defaults, repeated in prose so a prompt can label them as
      * defaults while quoting the value in force. */
    private val DefaultRunTimeoutSeconds: Int = 3600

    case class Effective(
        pipelineMaxPayloadMB: Int,
        pipelineMaxPayloadMBSource: String,
        tapScriptTimeoutSeconds: Int,
        tapScriptTimeoutSecondsSource: String,
        tapRunTimeoutSeconds: Int,
        tapRunTimeoutSecondsSource: String
    )

    /** Resolve the three ceilings for `env`. The disk precedence is the one
      * [[StagingArea.payloadBudgetMB]] applies: an explicit
      * `pipelineMaxPayloadMB` (>= 0) wins, else the deprecated `tapMaxOutputMB`
      * (>= 0), else [[StagingArea.DefaultPayloadBudgetMB]]. `0` is unlimited
      * and is an explicit value, not "unset". */
    def effective(env: DatrisEnvironment): Effective = {
        val (diskMB, diskSource) =
            if (env == null) (StagingArea.DefaultPayloadBudgetMB, SourceDefault)
            else if (env.pipelineMaxPayloadMB >= 0) (env.pipelineMaxPayloadMB, SourceEnv)
            else if (env.tapMaxOutputMB >= 0) (env.tapMaxOutputMB, SourceDeprecatedAlias)
            else (StagingArea.DefaultPayloadBudgetMB, SourceDefault)
        val scriptSeconds = if (env == null) 300 else env.tapScriptTimeoutSeconds
        val scriptSource = if (env != null && env.tapScriptTimeoutSecondsSet) SourceEnv else SourceDefault
        val runSeconds = if (env == null) DefaultRunTimeoutSeconds else env.tapRunTimeoutSeconds
        val runSource = if (env != null && env.tapRunTimeoutSecondsSet) SourceEnv else SourceDefault
        Effective(diskMB, diskSource, scriptSeconds, scriptSource, runSeconds, runSource)
    }

    /** The disk budget in prose, the value in force first and the documented
      * default labelled as a default. */
    def describeDisk(b: Effective): String =
        if (b.pipelineMaxPayloadMB == 0) "currently unlimited (0) on this install (default " + StagingArea.DefaultPayloadBudgetMB + ")"
        else "currently " + b.pipelineMaxPayloadMB + " MB on this install (default " + StagingArea.DefaultPayloadBudgetMB + ", 0 = unlimited)"

    /** The run ceiling in prose, same shape as [[describeDisk]]. */
    def describeRun(b: Effective): String =
        "currently " + b.tapRunTimeoutSeconds + " s on this install (default " + DefaultRunTimeoutSeconds + ")"

    /** The test ceiling in prose, same shape as [[describeDisk]]. */
    def describeScript(b: Effective): String =
        "currently " + b.tapScriptTimeoutSeconds + " s on this install (default 300)"
}
