package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{RunLineage, TapConfig}
import ai.datris.util.{CatalogOps, VectorChunkWriter}
import com.google.gson.{JsonArray, JsonObject}
import jakarta.servlet.http.HttpServletRequest

import scala.collection.JavaConverters._

/** Catalog-scoped user access: a visible pipeline can be fed by a tap filed in
  * a catalog the restricted user cannot read. The pipeline's run records still
  * name that tap (run input, provenance tap-run block, the `tap-<name>.csv`
  * filename a tap feed lands under), and so do the provenance stamp columns
  * of the rows and vector chunks it wrote (`_datris_tap_run`,
  * `_datris_script_sha`, `_datris_source`): in scratch result rows
  * ([[scrubStampedRows]], also by the job's feed filename when
  * `provenance.fields` is a subset) and in search hits ([[scrubHits]]).
  * The pipeline-bound query routes drop those columns from every row for a
  * restricted session, whatever the tap ([[dropTapStampColumns]],
  * [[dropTapStampNames]]).
  * These helpers scrub the hidden tap's identity from those records for a
  * restricted user's session only; every other caller gets the records
  * unchanged. */
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

    /** The `<name>` of an exact `tap-<name>.csv|json` filename (the file a
      * tap record feed lands under). None for anything else, null included. */
    def feedTap(filename: String): Option[String] =
        if (filename == null) None
        else {
            val m = FeedFileExact.matcher(filename)
            if (m.matches()) Some(m.group(1)) else None
        }

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

    private val StampTapColumnsLower: Set[String] = StampTapColumns.map(_.toLowerCase(java.util.Locale.ROOT)).toSet

    /** True when `name` is one of [[StampTapColumns]], in any letter case
      * (Snowflake folds unquoted names to upper case). Exact names only;
      * false for null. */
    def isTapStampColumn(name: String): Boolean =
        name != null && StampTapColumnsLower.contains(name.toLowerCase(java.util.Locale.ROOT))

    /** Query result rows with the tap stamp columns ([[isTapStampColumn]])
      * removed from each row's top-level keys (nested values are left
      * alone). Only rows that change are copied (key order kept); the input
      * rows are never modified; `rows` itself when no row changes (null
      * stays null). */
    def dropTapStampColumns(rows: java.util.List[java.util.Map[String, Any]]): java.util.List[java.util.Map[String, Any]] = {
        if (rows == null || rows.isEmpty) return rows
        var changed = false
        val out = new java.util.ArrayList[java.util.Map[String, Any]](rows.size())
        rows.forEach { r =>
            if (r != null && r.keySet.asScala.exists(isTapStampColumn)) {
                val copy = new java.util.LinkedHashMap[String, Any]()
                r.forEach((k, v) => if (!isTapStampColumn(k)) copy.put(k, v))
                out.add(copy)
                changed = true
            } else out.add(r)
        }
        if (changed) out else rows
    }

    /** Column names without the tap stamp columns ([[isTapStampColumn]]);
      * `columns` itself when none is one (null stays null). */
    def dropTapStampNames(columns: java.util.List[String]): java.util.List[String] =
        if (columns == null || !columns.asScala.exists(isTapStampColumn)) columns
        else new java.util.ArrayList[String](columns.asScala.filterNot(isTapStampColumn).asJava)

    /** Every tap a stamped row names (`_datris_tap_run` up to `|` and a
      * `tap:<name>` source), not only the first: see [[stampTaps]]. */
    private def stampedTaps(row: JsonObject): Set[String] = {
        def str(f: String): Option[String] =
            if (row.has(f) && row.get(f).isJsonPrimitive) Some(row.get(f).getAsString) else None
        str("_datris_tap_run").map(v => v.takeWhile(_ != '|')).filter(_.nonEmpty).toSet ++
            str("_datris_source").filter(_.startsWith("tap:")).map(_.stripPrefix("tap:"))
    }

    private def holdsStampColumn(row: JsonObject): Boolean = StampTapColumns.exists(row.has)

    /** Whether `row` loses its tap stamp columns: every row holding one when
      * the job's feeding tap (`jobTap`, from its `tap-<name>.csv|json`
      * filename, [[feedTap]]) is hidden, whatever `provenance.fields`
      * selected; otherwise only a row stamped with a hidden tap. */
    private def rowStripped(row: JsonObject, hidden: String => Boolean, jobHidden: Boolean): Boolean =
        if (jobHidden) holdsStampColumn(row) else stampedTaps(row).exists(hidden)

    /** True when some row in `rows` is stamped with a tap `hidden` holds
      * (`_datris_tap_run` = `<tap>|<runTime>`, or a `tap:<name>` source), or
      * holds any tap stamp column while `jobTap` is hidden. */
    def stampsHiddenTap(rows: JsonArray, hidden: String => Boolean, jobTap: Option[String] = None): Boolean =
        rows != null && {
            val jobHidden = jobTap.exists(hidden)
            rows.asScala.exists(r => r.isJsonObject && rowStripped(r.getAsJsonObject, hidden, jobHidden))
        }

    /** Scratch result rows (provenance-stamped) with the tap stamp columns
      * removed from every row stamped with a hidden tap, or from every row
      * of a job whose feeding tap `jobTap` is hidden. A copy when any row
      * changes; `rows` itself otherwise (null stays null). */
    def scrubStampedRows(rows: JsonArray, hidden: String => Boolean, jobTap: Option[String] = None): JsonArray = {
        if (!stampsHiddenTap(rows, hidden, jobTap)) return rows
        val jobHidden = jobTap.exists(hidden)
        val out = rows.deepCopy()
        out.forEach { r =>
            if (r.isJsonObject && rowStripped(r.getAsJsonObject, hidden, jobHidden))
                StampTapColumns.foreach(r.getAsJsonObject.remove)
        }
        out
    }

    /** A hit value as text: String.valueOf of a non-null value (stores return
      * strings or JDBC objects); None for null or empty. */
    private def hitStr(hit: java.util.Map[String, Any], key: String): Option[String] =
        Option(hit.get(key)).map(v => String.valueOf(v)).filter(_.nonEmpty)

    /** Every tap a search hit's stamp columns name: `_datris_tap_run` up to
      * `|` and a `tap:<name>` `_datris_source`. All of them, not the first
      * match: a chunk written with a static destination `metadata` key under
      * a stamp name (before the stamper dropped those) can name a readable
      * tap in one column while another column names the real, hidden one.
      * The `tap-<name>.csv|json` filename is not among them: it is weighed
      * separately in [[scrubHit]], because a chunk written before
      * `filename` became a reserved metadata key can carry a planted one. */
    private def stampTaps(hit: java.util.Map[String, Any]): Set[String] =
        hitStr(hit, "_datris_tap_run").map(_.takeWhile(_ != '|')).filter(_.nonEmpty).toSet ++
            hitStr(hit, "_datris_source").filter(_.startsWith("tap:")).map(_.stripPrefix("tap:")).filter(_.nonEmpty)

    /** Vector search hits (already limited to readable writers) with the tap
      * stamp columns ([[StampTapColumns]]) removed from each hit fed by a tap
      * `hidden` holds. A stamped hit is stripped when its feed filename
      * (`tap-<name>.csv|json`) names a hidden tap, or when its stamp columns
      * name taps ([[stampTaps]]) and any of them is hidden. Otherwise, when
      * the stamp columns name no tap and the hit has a `_datris_run_id`, the
      * tap is the input of the run record `run` returns for it, trusted only
      * when the record's `pipeline` is the hit's own stored writer
      * ([[VectorChunkWriter.writerOf]]) and it has an input (an upload, or no
      * tapName, keeps the columns): a readable feed filename does not vouch
      * then, since older chunks can carry a planted one. With no run id, a
      * readable feed filename keeps the columns. A hit whose tap cannot be
      * determined loses any non-empty tap stamp column (fail closed). A
      * `tap-<name>.csv|json` filename of a hidden tap reads `tap-[hidden]`.
      * `run` is called only when the hit's stamp columns name no tap, its
      * filename names no hidden tap, and it holds a non-empty stamp column
      * and a run id. No name-keyed lookups: chunks and run
      * records keep the name they were written under. Changed hits are
      * copies (key order kept); the input maps are never modified; `hits`
      * itself when no hit changes. */
    def scrubHits(
        hits: java.util.List[java.util.Map[String, Any]],
        hidden: String => Boolean,
        run: String => Option[RunLineage]
    ): java.util.List[java.util.Map[String, Any]] = {
        if (hits == null || hits.isEmpty) return hits
        var changed = false
        val out = hits.asScala.map { hit =>
            val scrubbed = if (hit == null) hit else scrubHit(hit, hidden, run)
            if (!(scrubbed eq hit)) changed = true
            scrubbed
        }
        if (!changed) hits else new java.util.ArrayList[java.util.Map[String, Any]](out.asJava)
    }

    private def scrubHit(
        hit: java.util.Map[String, Any],
        hidden: String => Boolean,
        run: String => Option[RunLineage]
    ): java.util.Map[String, Any] = {
        val stamped = StampTapColumns.exists(c => hitStr(hit, c).isDefined)
        val filename = hitStr(hit, "filename").orNull
        val fileTap = feedTap(filename)
        if (!stamped && fileTap.isEmpty) return hit
        val renameFile = fileTap.exists(hidden)
        // A filename only ever causes a strip on its own; it vouches for the
        // stamps only when nothing better (stamp names, a run id) is present.
        lazy val stampNamed = stampTaps(hit)
        lazy val runId = hitStr(hit, "_datris_run_id")
        val strip = stamped && (
            renameFile ||
                (if (stampNamed.nonEmpty) stampNamed.exists(hidden)
                 else if (runId.isEmpty && fileTap.isDefined) false
                 else {
                     val record = runId
                         .flatMap(id => Option(run(id)).flatten)
                         .filter(r => r != null && r.input != null && r.pipeline != null && VectorChunkWriter.writerOf(hit).contains(r.pipeline))
                     record match {
                         case Some(r) => r.input.kind != "upload" && Option(r.input.tapName).filter(_.nonEmpty).exists(hidden)
                         case None => true
                     }
                 })
        )
        if (!strip && !renameFile) return hit
        val copy = new java.util.LinkedHashMap[String, Any](hit)
        if (strip) StampTapColumns.foreach(copy.remove)
        if (renameFile) copy.put("filename", scrubFilename(filename, fileTap.toSet))
        copy
    }
}
