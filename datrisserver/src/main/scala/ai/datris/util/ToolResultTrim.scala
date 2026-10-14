package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** Caps a tool result before [[AgentLoop]] feeds it back to the model. The UI
  * gets the full result over SSE; only the round-tripped copy is capped, so a
  * runaway result can't be re-sent as input on every iteration.
  *
  * Code-bearing tools (`get_tap`, `get_tap_version`, any `*_script`) get a
  * higher ceiling so the model can read a whole script. StartupRunner sets
  * both caps from `datris.agent.toolResultMaxChars` and
  * `datris.agent.toolResultMaxCharsScript`. */
object ToolResultTrim {
    val DefaultMaxChars: Int = 32000
    val DefaultScriptMaxChars: Int = 131072

    @volatile private[datris] var maxChars: Int = DefaultMaxChars
    @volatile private[datris] var scriptMaxChars: Int = DefaultScriptMaxChars

    private val ScriptTools: Set[String] = Set("get_tap", "get_tap_version", "get_tap_script")

    private[util] def isScriptTool(toolName: String): Boolean =
        toolName != null && (ScriptTools.contains(toolName) || toolName.endsWith("_script"))

    /** The cap for one tool: the script ceiling for code-bearing tools (never
      * below the general cap), the general cap otherwise. */
    private[util] def capFor(toolName: String, general: Int, script: Int): Int =
        if (isScriptTool(toolName)) math.max(general, script) else general

    private[util] def trailer(removed: Int, total: Int): String =
        "\n\n…[truncated " + removed + " of " + total +
            " chars; the user sees the full result. Ask the user to paste the remainder, or narrow the request]"

    /** Trim `s` to the cap for `toolName`, appending a trailer that tells the
      * model what was cut and what to do about it. */
    private[util] def trim(toolName: String, s: String, general: Int = maxChars, script: Int = scriptMaxChars): String = {
        if (s == null) return ""
        val cap = capFor(toolName, general, script)
        if (s.length <= cap) s
        else s.substring(0, cap) + trailer(s.length - cap, s.length)
    }

    /** Parse a configured cap: Some(n) for a positive integer, None otherwise
      * (the caller keeps the default and warns). */
    private[datris] def parseCap(raw: String): Option[Int] =
        scala.util.Try(Option(raw).getOrElse("").trim.toInt).toOption.filter(_ > 0)
}
