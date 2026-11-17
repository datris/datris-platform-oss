package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.TapConfig
import ai.datris.util.CatalogOps
import com.google.gson.JsonObject
import jakarta.servlet.http.HttpServletRequest

/** Catalog-scoped user access: a visible pipeline can be fed by a tap filed in
  * a catalog the restricted user cannot read. The pipeline's run records still
  * name that tap (run input, provenance tap-run block, the `tap-<name>.csv`
  * filename a tap feed lands under). These helpers scrub the hidden tap's
  * identity from those records for a restricted user's session only; every
  * other caller gets the records unchanged. */
object HiddenTapScrub {

    /** What a scrubbed tap name reads as in free text and filenames. */
    val Placeholder = "[hidden]"

    /** Run-input fields that identify the feeding tap. */
    val TapIdentityFields: Seq[String] = Seq("tapName", "tapRunTime", "scriptSha", "source")

    /** True when `tapName` names a tap the restricted session cannot read
      * (missing or outside its grants). Always false for everyone else. */
    def tapHidden(request: HttpServletRequest, tapName: String, readTap: String => TapConfig): Boolean =
        CapabilityCheck.isRestrictedSession(request) && tapName != null && tapName.nonEmpty && {
            val t = readTap(tapName)
            t == null || !CapabilityCheck.grants(request, "tap", "read", CatalogOps.scopeContext(t.catalog, t.createdByKeyLabel))
        }

    /** Taps the restricted session cannot read that feed one of its visible
      * pipelines: the only tap names a visible pipeline's run records can
      * carry. Empty for everyone else. */
    def hiddenFeedingTaps(request: HttpServletRequest, taps: Seq[TapConfig], visiblePipelines: Set[String]): Set[String] =
        if (!CapabilityCheck.isRestrictedSession(request)) Set.empty
        else
            taps.filter(t =>
                t != null && t.name != null && t.name.nonEmpty && t.targetPipeline != null && visiblePipelines.contains(t.targetPipeline) &&
                    !CapabilityCheck.grants(request, "tap", "read", CatalogOps.scopeContext(t.catalog, t.createdByKeyLabel))
            ).map(_.name).toSet

    private val NameChar = "A-Za-z0-9_\\-"

    /** `text` with every hidden tap name replaced by [[Placeholder]]: the
      * `tap-<name>` form always, the bare name as a whole word unless it is
      * also a name the caller can see (`visibleNames`), so a visible
      * pipeline's name is never mangled. Null stays null. */
    def scrubText(text: String, hidden: Set[String], visibleNames: Set[String] = Set.empty): String = {
        if (text == null || hidden.isEmpty) return text
        hidden.toSeq.sortBy(-_.length).foldLeft(text) { (acc, name) =>
            val q = java.util.regex.Pattern.quote(name)
            val prefixed =
                acc.replaceAll("(?<![" + NameChar + "])tap-" + q + "(?![" + NameChar + "])", "tap-" + java.util.regex.Matcher.quoteReplacement(Placeholder))
            if (visibleNames.contains(name)) prefixed
            else prefixed.replaceAll("(?<![" + NameChar + "])" + q + "(?![" + NameChar + "])", java.util.regex.Matcher.quoteReplacement(Placeholder))
        }
    }

    /** A filename with `tap-<hidden name>` (how a tap feed's file is named)
      * replaced; any other filename is unchanged. */
    def scrubFilename(filename: String, hidden: Set[String]): String = scrubText(filename, hidden, hidden)

    /** Scrubs one run's JSON (as [[ai.datris.util.LineageService.runToJson]]
      * writes it) in place when its input names a hidden tap: the tap
      * identity fields go, and the filename reads `tap-[hidden]`. Returns
      * the same object. */
    def scrubRun(run: JsonObject, hidden: String => Boolean): JsonObject = {
        if (run == null || !run.has("input") || !run.get("input").isJsonObject) return run
        val in = run.getAsJsonObject("input")
        val tap = if (in.has("tapName") && in.get("tapName").isJsonPrimitive) in.get("tapName").getAsString else null
        if (tap != null && hidden(tap)) {
            TapIdentityFields.foreach(in.remove)
            if (in.has("filename") && in.get("filename").isJsonPrimitive)
                in.addProperty("filename", scrubFilename(in.get("filename").getAsString, Set(tap)))
        }
        run
    }
}
