package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.TapConfig
import ai.datris.util.CatalogOps
import com.google.gson.{JsonArray, JsonObject}
import jakarta.servlet.http.HttpServletRequest

import scala.collection.JavaConverters._

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

    /** A tap feed's file is named `tap-<name>.csv` or `tap-<name>.json`. */
    private val FeedFileExact = java.util.regex.Pattern.compile("^tap-(.+)\\.(csv|json)$")
    private val FeedFileInText = java.util.regex.Pattern.compile("(?<![A-Za-z0-9_\\-])tap-([^\\s'\"/]+?)\\.(csv|json)(?![A-Za-z0-9_])")

    /** Tap names a payload can carry without a tap field: the `<name>` of
      * every `tap-<name>.csv|json` in `texts` (filenames or messages). The
      * caller decides which are hidden (a deleted tap is hidden). */
    def feedFileNames(texts: Iterable[String]): Set[String] =
        texts.filter(_ != null).flatMap { t =>
            val exact = FeedFileExact.matcher(t)
            if (exact.matches()) Seq(exact.group(1))
            else {
                val m = FeedFileInText.matcher(t)
                val out = scala.collection.mutable.ListBuffer[String]()
                while (m.find()) out += m.group(1)
                out.toList
            }
        }.toSet

    /** End of a name: end of text, or a char that cannot continue a name. A
      * dot ends it only when no name char follows (`tap hr.` vs `hr.eu`). */
    private val NameEnd = "(?=$|[^A-Za-z0-9_\\-.]|\\.(?![A-Za-z0-9_]))"

    /** `text` with each hidden tap name replaced by [[Placeholder]] where it
      * appears in a tap context only: `tap-<name>.csv|json` (feed files),
      * `tap-docs/<name>/` (staged documents), and `tap <name>`, `tap '<name>'`,
      * `tap "<name>"`, `tap:<name>`, `tap: <name>` (any case of "tap"). A
      * bare occurrence elsewhere (a table, column or pipeline that shares the
      * name) is left alone, so legitimate text is never rewritten. Longest
      * names first; null stays null. */
    def scrubText(text: String, hidden: Set[String]): String = {
        if (text == null || hidden.isEmpty) return text
        val ph = java.util.regex.Matcher.quoteReplacement(Placeholder)
        hidden.toSeq.filter(n => n != null && n.nonEmpty).sortBy(-_.length).foldLeft(text) { (acc, name) =>
            val q = java.util.regex.Pattern.quote(name)
            acc
                .replaceAll("(?<![A-Za-z0-9_\\-])tap-" + q + "\\.(csv|json)(?![A-Za-z0-9_])", "tap-" + ph + ".$1")
                .replaceAll("(?<![A-Za-z0-9_\\-])tap-docs/" + q + "/", "tap-docs/" + ph + "/")
                .replaceAll("(?<![A-Za-z0-9_])([Tt][Aa][Pp](?::\\s*|\\s+)['\"]?)" + q + NameEnd, "$1" + ph)
        }
    }

    /** A filename scrubbed the same way ([[scrubText]]). */
    def scrubFilename(filename: String, hidden: Set[String]): String = scrubText(filename, hidden)

    /** Scrubs one run's JSON (as [[ai.datris.util.LineageService.runToJson]]
      * writes it) in place. When its input names a hidden tap, the tap
      * identity fields go. A `tap-<name>.csv|json` feed filename naming a
      * hidden tap (also one no longer stored) reads `tap-[hidden]`, in the
      * input filename and in each output's `error`. For an `upload` run the
      * filename is the uploader's own, so it is not read for tap names.
      * Returns the same object. */
    def scrubRun(run: JsonObject, hidden: String => Boolean): JsonObject = {
        if (run == null || !run.has("input") || !run.get("input").isJsonObject) return run
        val in = run.getAsJsonObject("input")
        def str(f: String): String = if (in.has(f) && in.get(f).isJsonPrimitive) in.get(f).getAsString else null
        val tap = str("tapName")
        val hiddenTap = tap != null && hidden(tap)
        if (hiddenTap) TapIdentityFields.foreach(in.remove)
        val filename = str("filename")
        val upload = "upload" == str("kind")
        val fromFiles =
            if (upload) Set.empty[String] else feedFileNames(Seq(filename) ++ outputErrors(run)).filter(hidden)
        val names = fromFiles ++ (if (hiddenTap) Set(tap) else Set.empty[String])
        if (names.nonEmpty) {
            if (filename != null) in.addProperty("filename", scrubFilename(filename, names))
            scrubOutputErrors(run, names)
        }
        run
    }

    /** `error` text of each entry in `o.outputs`. */
    def outputErrors(o: JsonObject): Seq[String] =
        if (o == null || !o.has("outputs") || !o.get("outputs").isJsonArray) Nil
        else
            o.getAsJsonArray("outputs").asScala.toSeq.collect {
                case e if e.isJsonObject && e.getAsJsonObject.has("error") && e.getAsJsonObject.get("error").isJsonPrimitive =>
                    e.getAsJsonObject.get("error").getAsString
            }

    /** Each `o.outputs[].error` scrubbed in place ([[scrubText]]). */
    def scrubOutputErrors(o: JsonObject, names: Set[String]): Unit =
        if (names.nonEmpty && o != null && o.has("outputs") && o.get("outputs").isJsonArray)
            o.getAsJsonArray("outputs").forEach { e =>
                if (e.isJsonObject && e.getAsJsonObject.has("error") && e.getAsJsonObject.get("error").isJsonPrimitive) {
                    val x = e.getAsJsonObject
                    x.addProperty("error", scrubText(x.get("error").getAsString, names))
                }
            }

    /** Provenance stamp columns that identify the feeding tap. */
    val StampTapColumns: Seq[String] = Seq("_datris_tap_run", "_datris_script_sha", "_datris_source")

    private def stampedTap(row: JsonObject): Option[String] = {
        def str(f: String): Option[String] =
            if (row.has(f) && row.get(f).isJsonPrimitive) Some(row.get(f).getAsString) else None
        str("_datris_tap_run").map(v => v.takeWhile(_ != '|')).filter(_.nonEmpty)
            .orElse(str("_datris_source").filter(_.startsWith("tap:")).map(_.stripPrefix("tap:")))
    }

    /** True when some row in `rows` is stamped with a tap `hidden` holds
      * (`_datris_tap_run` = `<tap>|<runTime>`, or a `tap:<name>` source). */
    def stampsHiddenTap(rows: JsonArray, hidden: String => Boolean): Boolean =
        rows != null && rows.asScala.exists(r => r.isJsonObject && stampedTap(r.getAsJsonObject).exists(hidden))

    /** Scratch result rows (provenance-stamped) with the tap stamp columns
      * removed from every row stamped with a hidden tap. A copy when any row
      * changes; `rows` itself otherwise (null stays null). */
    def scrubStampedRows(rows: JsonArray, hidden: String => Boolean): JsonArray = {
        if (!stampsHiddenTap(rows, hidden)) return rows
        val out = rows.deepCopy()
        out.forEach { r =>
            if (r.isJsonObject && stampedTap(r.getAsJsonObject).exists(hidden))
                StampTapColumns.foreach(r.getAsJsonObject.remove)
        }
        out
    }
}
