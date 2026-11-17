package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.CatalogGrants
import ai.datris.model.User

/** Catalog-scoped user access: the Assistant prompt block for a restricted
  * session user (plans/stories/catalog-scoped-user-access-ui-assistant.md).
  *
  * Data first (`Catalog access for this user: <name> (<level>), ...`), then
  * the rules. Filing targets are the edit/own catalogs only; a view-only
  * catalog is named in the data line and nowhere else. The block never names
  * the unassigned group: a restricted user cannot see or file into it.
  *
  * Unrestricted users (restricted=false, or any admin) get None so their
  * prompt is byte-for-byte unchanged. The wording carries no example names:
  * the model mimics literal examples. */
object CatalogAccessPrompt {

    def block(user: User): Option[String] = {
        if (user == null || !user.isRestricted) return None
        val grants = CatalogGrants.grantsOf(user.catalogGrants)
        val editable = grants.collect { case (c, l) if l == User.GrantEdit || l == User.GrantOwn => c }
        val data =
            if (grants.isEmpty) "none"
            else grants.map { case (c, l) => c + " (" + l + ")" }.mkString(", ")
        val targets = editable.map(c => "`" + c + "`").mkString(", ")

        val sb = new StringBuilder
        sb.append("Catalog access for this user: ").append(data).append("\n")
        sb.append(
            "- **Work only inside these catalogs.** This user sees only the taps and pipelines in the catalogs listed above, and every list, lookup and search you run on their behalf already reflects that. Every tap or pipeline you create must carry a `catalog` that is one of the filing targets; never leave `catalog` empty and never invent a catalog name.\n"
        )
        editable match {
            case Seq() =>
                sb.append(
                    "- **No filing targets.** This user cannot create, change or move taps or pipelines in any catalog. If they ask for that, say plainly that their catalog access does not allow it and that an administrator can grant edit access; do not attempt the call.\n"
                )
            case Seq(_) =>
                sb.append("- **Filing target: ").append(targets).append(".** ")
                sb.append(
                    "Put every new tap and pipeline in this catalog without asking (pass it as `catalog` on `create_tap` and `create_pipeline`).\n"
                )
            case _ =>
                sb.append("- **Filing targets: ").append(targets).append(".** ")
                sb.append(
                    "Before the first `create_tap` or `create_pipeline` of a request, ask which catalog with one `ask_user` card whose options are exactly these catalogs and nothing else, then pass the answer as `catalog` for every item in that request. Do not ask again for later items unless the user changes the catalog.\n"
                )
        }
        sb.append(
            "- **Moving and deleting.** `set_catalog` may only move an item between filing targets. Deleting a tap or pipeline needs the own level on its catalog. `rename_catalog` needs the own level on that catalog; deleting a catalog is for administrators.\n"
        )
        sb.append(
            "- **Relay refusals verbatim.** When the server refuses a request, quote its error text to the user exactly as returned (for example the list of catalogs to choose from), then follow it. Do not reword it or work around it.\n"
        )
        sb.append(
            "- **Anything else is not available.** A tap, pipeline, catalog or data store the user names that does not appear in your results, or that returns 404, is not available to this user. Say it is not available to you. Do not retry with other spellings or letter case, do not suggest a similar name, and do not guess what it might be.\n"
        )
        sb.append(
            "- **Raw queries and table browsing are not available.** Free-form SQL, MongoDB and natural-language queries and table/collection browsing are refused for this user. Query a pipeline's data through the tools that take a `pipeline` name instead; for this user the warehouse query tools only preview the pipeline's table, so do not pass `sql` (it is refused) and leave it out. `find_data`, `get_lineage` and `get_provenance` work and show only this user's catalogs.\n"
        )
        sb.append(
            "- **Tap secrets are install-wide.** A tap secret you create is visible to everyone on this install, not only to this user's catalogs. Say so when you create one.\n"
        )
        Some(sb.toString)
    }
}
