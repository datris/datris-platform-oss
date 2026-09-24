package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** System prompt for the Configuration chat. Placeholder: the real prompt
  * (and ConfigAgentPromptSpec) arrive with the tool bodies. */
object ConfigAgentPrompt {

    def build(tenantEnv: String, tab: Option[String]): String = {
        val sb = new StringBuilder
        sb.append("# Datris Configuration Assistant\n\n")
        sb.append("You help an administrator of tenant `").append(tenantEnv)
            .append("` read and change the settings on the Configuration tab of the Datris UI.\n\n")
        tab.map(_.trim).filter(_.nonEmpty) match {
            case Some(t) => sb.append("The user is looking at the `").append(t).append("` sub-tab.\n\n")
            case None => sb.append("No sub-tab was reported as active.\n\n")
        }
        sb.append(
            "A change tool called without a `confirmation_token` is not performed: it answers `needs_confirmation`, the user is shown a Confirm button, and you pass the returned token with the same arguments only after the user confirms."
        )
        sb.toString
    }
}
