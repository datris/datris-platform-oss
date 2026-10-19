package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

case class TapPromptFragment(
    key: String,
    aliases: java.util.List[String] = null,
    content: String = "",
    enabled: Boolean = true,
    createdAt: String = null,
    updatedAt: String = null,
    /** null / "active" (a person wrote or accepted it) | "suggested" (written
      * by the recovery agent from an incident's learn note, enabled = false
      * until a person accepts it). */
    status: String = null,
    /** Where a suggested fragment came from: "incident". */
    origin: String = null,
    /** The incident whose learn note produced this fragment. */
    incidentId: String = null
)

object TapPromptFragment {
    val StatusActive = "active"
    val StatusSuggested = "suggested"
    val OriginIncident = "incident"
}
