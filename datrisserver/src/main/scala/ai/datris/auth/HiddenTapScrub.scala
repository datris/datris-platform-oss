package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{RunLineage, TapConfig}
import ai.datris.util.VectorChunkWriter
import com.google.gson.{JsonArray, JsonObject}
import jakarta.servlet.http.HttpServletRequest

import scala.collection.JavaConverters._

/** Catalog-scoped user access: a visible pipeline can be fed by a tap filed in
  * a catalog the restricted user cannot read. The pipeline's run records still
  * name that tap (run input, provenance tap-run block, the `tap-<name>.csv`
  * filename a tap feed lands under), and so do the provenance stamp columns
  * of the rows and vector chunks it wrote (`_datris_tap_run`,
  * `_datris_script_sha`, `_datris_source`): in scratch result rows
  * ([[scrubStampedRows]]; when `provenance.fields` is a subset, by the
  * job's run record or the feed file its events name, [[ScratchFeed]]) and
  * in search hits ([[scrubHits]]).
  * The pipeline-bound query routes drop those columns from every row for a
  * restricted session, whatever the tap ([[dropTapStampColumns]],
  * [[dropTapStampNames]]).
  * These helpers scrub the hidden tap's identity from those records for a
  * caller that sees only part of the install ([[CapabilityCheck.isLimited]]:
  * a restricted user's session, or an API key limited by a `catalog=` /
  * `owner=` scope, plans/stories/scoped-key-read-routes.md); every other
  * caller gets the records unchanged and no tap is read. */
object HiddenTapScrub {

    /** What a scrubbed tap name reads as in free text and filenames. */
    val Placeholder = "[hidden]"

    /** Run-input fields that identify the feeding tap. */
    val TapIdentityFields: Seq[String] = Seq("tapName", "tapRunTime", "scriptSha", "source")

    /** The fields of an incident's row profile (`RunProfile`) that identify
      * the feeding tap: its script commit (`scriptSha`, as in
      * [[TapIdentityFields]]) and its config version. A profile's `source`
      * is the profiling method, not the tap. */
    val RunProfileTapFields: Seq[String] = TapIdentityFields.filter(_ == "scriptSha") :+ "tapVersion"

    /** True when `tapName` names a tap a limited caller
      * ([[CapabilityCheck.isLimited]]) cannot read: missing, or outside its
      * grants or key scope, judged on the stored tap's catalog and owner.
      * Always false for everyone else, and `readTap` is not called. */
    def tapHidden(request: HttpServletRequest, tapName: String, readTap: String => TapConfig): Boolean =
        CapabilityCheck.isLimited(request) && tapName != null && tapName.nonEmpty && {
            val t = readTap(tapName)
            t == null || !CallerVisibility.mayReadStored(CapabilityCheck.resolvedKey(request), "tap", t.catalog, t.createdByKeyLabel)
        }

    /** Taps a limited caller ([[CapabilityCheck.isLimited]]) cannot read
      * that feed one of its visible pipelines: the only tap names a visible
      * pipeline's run records can carry. Empty for everyone else. */
    def hiddenFeedingTaps(request: HttpServletRequest, taps: Seq[TapConfig], visiblePipelines: Set[String]): Set[String] =
        if (!CapabilityCheck.isLimited(request)) Set.empty
        else
            taps.filter(t =>
                t != null && t.name != null && t.name.nonEmpty && t.targetPipeline != null && visiblePipelines.contains(t.targetPipeline) &&
                    !CallerVisibility.mayReadStored(CapabilityCheck.resolvedKey(request), "tap", t.catalog, t.createdByKeyLabel)
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

    /** Every tap name a `_datris_tap_run` value (`<tapName>|<runTime>`) can
      * hold: each non-empty prefix ending just before a `|`, or the whole
      * value when it has none. The stamper builds the value from the run's
      * own tap name, so the true name is always one of them; a tap name or
      * run time holding `|` only adds candidates. The value vouches for a
      * readable tap only when every candidate is readable. Bounded: a value
      * holding more than [[MaxTapRunPipes]] pipes is not split at all (no
      * substring, no tap lookup) and counts as naming a tap that cannot be
      * read, so its row or hit loses the columns (fail closed). Candidates
      * are shortest first, so the first hidden or unknown one ends a scan. */
    private def tapRunNames(value: String): TapRunNames =
        if (value == null || value.isEmpty) TapRunNames.Empty
        else {
            var pipes = 0
            var i = 0
            while (i < value.length && pipes <= MaxTapRunPipes) {
                if (value.charAt(i) == '|') pipes += 1
                i += 1
            }
            if (pipes == 0) TapRunNames(Seq(value), unresolvable = false)
            else if (pipes > MaxTapRunPipes) TapRunNames(Nil, unresolvable = true)
            else TapRunNames((1 until value.length).filter(value.charAt(_) == '|').map(value.substring(0, _)), unresolvable = false)
        }

    /** Most `|` a `_datris_tap_run` value may hold and still be split into
      * candidate tap names ([[tapRunNames]]). The default stamp holds one. */
    private val MaxTapRunPipes = 8

    /** The candidate tap names of a `_datris_tap_run` value, shortest first;
      * `unresolvable` when the value held too many pipes to split. */
    private final case class TapRunNames(names: Seq[String], unresolvable: Boolean) {

        /** True when the value cannot vouch: too many pipes, or any candidate hidden or unknown. */
        def hides(hidden: String => Boolean): Boolean = unresolvable || names.exists(hidden)

        /** True when the value names a tap (so it decides without run evidence). */
        def namesTap: Boolean = unresolvable || names.nonEmpty
    }

    private object TapRunNames {
        val Empty: TapRunNames = TapRunNames(Nil, unresolvable = false)
    }

    /** The tap named by a `tap:<name>` `_datris_source`. Author text: it can
      * only cause a strip (a hidden name), never vouch for the stamps. */
    private def sourceTap(value: Option[String]): Option[String] =
        value.filter(_.startsWith("tap:")).map(_.stripPrefix("tap:")).filter(_.nonEmpty)

    /** The tap names a stamped row's `_datris_tap_run` holds ([[tapRunNames]])
      * and the tap its `tap:<name>` source names: see [[stampTaps]]. */
    private def stampedTaps(row: JsonObject): (TapRunNames, Option[String]) = {
        def str(f: String): Option[String] =
            if (row.has(f) && row.get(f).isJsonPrimitive) Option(row.get(f).getAsString) else None
        (str("_datris_tap_run").map(tapRunNames).getOrElse(TapRunNames.Empty), sourceTap(str("_datris_source")))
    }

    private def holdsStampColumn(row: JsonObject): Boolean = StampTapColumns.exists(row.has)

    /** A row value as text: a non-null primitive's string; None for absent,
      * null or empty. */
    private def rowStr(row: JsonObject, f: String): Option[String] =
        if (row.has(f) && row.get(f).isJsonPrimitive) Option(row.get(f).getAsString).filter(_.nonEmpty) else None

    private def holdsNonEmptyStampColumn(row: JsonObject): Boolean = StampTapColumns.exists(c => rowStr(row, c).isDefined)

    /** The job filename StreamNotifier gives every run it starts (tap record
      * feeds, tap document feeds, uploads, stream messages): `stream: <pipeline>`. */
    val StreamFilenamePrefix = "stream: "

    /** The feed filename named by StreamNotifier's begin event of a run of
      * `pipeline`, whose description reads exactly
      * `Stream data received, pipeline: <pipeline>, filename: <filename>`.
      * None for any other text, null included. */
    def streamFeedFilename(pipeline: String, description: String): Option[String] = {
        if (pipeline == null || description == null) return None
        val prefix = "Stream data received, pipeline: " + pipeline + ", filename: "
        if (description.startsWith(prefix)) Some(description.substring(prefix.length)).filter(_.nonEmpty) else None
    }

    /** What ties one scratch job's result rows to its feeding tap, for
      * [[scrubStampedRows]] / [[stampsHiddenTap]]: the job's `pipeline`,
      * its pipeline token (`runId`, also the run-lineage key and the
      * `_datris_run_id` stamp), its own `filename` (as stored, before any
      * scrub) and the descriptions of its status events (read lazily, at
      * most once). */
    final class ScratchFeed(val pipeline: String, val runId: String, val filename: String, descriptionsOf: () => Seq[String]) {
        lazy val descriptions: Seq[String] =
            Option(descriptionsOf).map(f => Option(f()).getOrElse(Nil)).getOrElse(Nil).filter(_ != null)

        /** True when the job came in through StreamNotifier, the only way a tap feed arrives. */
        def streamEntry: Boolean = filename != null && filename.startsWith(StreamFilenamePrefix)

        /** Taps named as this job's feed: an exact `tap-<name>.csv|json` job
          * filename, and the exact feed filename of its StreamNotifier begin event. */
        lazy val namedFeeds: Set[String] =
            (feedTap(filename).toSeq ++ descriptions.flatMap(d => streamFeedFilename(pipeline, d)).flatMap(feedTap)).toSet

        /** Every `tap-<name>.csv|json` the job's event descriptions carry. */
        lazy val textFeeds: Set[String] = feedFileNames(descriptions)
    }

    /** `run` memoised for one request: each distinct run id is read at most
      * once, and at most `max` distinct ids are read (beyond that, and for a
      * read that throws, None). */
    def memoRuns(run: String => Option[RunLineage], max: Int = 16): String => Option[RunLineage] = {
        val memo = scala.collection.mutable.Map[String, Option[RunLineage]]()
        id =>
            memo.synchronized {
                memo.get(id) match {
                    case Some(r) => r
                    case None if memo.size >= max => None
                    case None =>
                        val r =
                            try Option(run(id)).flatten
                            catch { case _: Exception => None }
                        memo.put(id, r)
                        r
                }
            }
    }

    /** Whether `row` loses its tap stamp columns. Every row holding one when
      * the job's feeding tap (`jobTap`, from its `tap-<name>.csv|json`
      * filename, [[feedTap]]) is hidden, whatever `provenance.fields`
      * selected; otherwise a row whose `_datris_tap_run` holds a hidden or
      * unknown tap name ([[tapRunNames]]) or whose `tap:<name>` source names
      * a hidden tap. A `_datris_tap_run` whose names are all readable keeps
      * the columns; a readable `tap:<name>` source never does. With a `feed`,
      * a row holding a non-empty tap stamp column whose `_datris_tap_run`
      * names no tap is tied
      * to its tap by the run record of its `_datris_run_id` (else the job's
      * token), trusted only when filed under the job's own pipeline (an
      * upload, or no tapName, keeps the columns); with no such record, by
      * the feed files the job's events name (a hidden one strips, a readable
      * one keeps); failing both, a job that came in through StreamNotifier
      * loses them (fail closed). */
    private def rowStripped(
        row: JsonObject,
        hidden: String => Boolean,
        jobHidden: Boolean,
        feed: ScratchFeed,
        run: String => Option[RunLineage]
    ): Boolean =
        if (jobHidden) holdsStampColumn(row)
        else {
            val (named, source) = stampedTaps(row)
            if (named.hides(hidden) || source.exists(hidden)) true
            else if (feed == null || named.namesTap || !holdsNonEmptyStampColumn(row)) false
            else {
                val record = rowStr(row, "_datris_run_id")
                    .orElse(Option(feed.runId).filter(_.nonEmpty))
                    .flatMap(id => Option(run(id)).flatten)
                    .filter(r => r != null && r.input != null && r.pipeline != null && r.pipeline == feed.pipeline)
                record match {
                    case Some(r) => r.input.kind != "upload" && Option(r.input.tapName).filter(_.nonEmpty).exists(hidden)
                    case None =>
                        if ((feed.textFeeds ++ feed.namedFeeds).exists(hidden)) true
                        else if (feed.namedFeeds.nonEmpty) false
                        else feed.streamEntry
                }
            }
        }

    /** True when some row in `rows` loses its tap stamp columns
      * ([[rowStripped]]): a `_datris_tap_run` (`<tap>|<runTime>`) holding
      * any tap name `hidden` holds ([[tapRunNames]]), a `tap:<name>` source
      * naming a hidden tap, holding any tap stamp column while `jobTap` is
      * hidden, or, with a `feed`, tied to a hidden tap (or to none, fail
      * closed) through the run record or the job's events. A readable
      * `tap:<name>` source never keeps the columns. `run` is called only
      * for a row with a non-empty tap stamp column whose `_datris_tap_run`
      * names no tap. */
    def stampsHiddenTap(
        rows: JsonArray,
        hidden: String => Boolean,
        jobTap: Option[String] = None,
        feed: ScratchFeed = null,
        run: String => Option[RunLineage] = _ => None
    ): Boolean =
        rows != null && {
            val jobHidden = jobTap.exists(hidden)
            rows.asScala.exists(r => r.isJsonObject && rowStripped(r.getAsJsonObject, hidden, jobHidden, feed, run))
        }

    /** Scratch result rows (provenance-stamped) with the tap stamp columns
      * removed from every row [[stampsHiddenTap]] counts. A copy when any
      * row changes; `rows` itself otherwise (null stays null). */
    def scrubStampedRows(
        rows: JsonArray,
        hidden: String => Boolean,
        jobTap: Option[String] = None,
        feed: ScratchFeed = null,
        run: String => Option[RunLineage] = _ => None
    ): JsonArray = {
        if (!stampsHiddenTap(rows, hidden, jobTap, feed, run)) return rows
        val jobHidden = jobTap.exists(hidden)
        val out = rows.deepCopy()
        out.forEach { r =>
            if (r.isJsonObject && rowStripped(r.getAsJsonObject, hidden, jobHidden, feed, run))
                StampTapColumns.foreach(r.getAsJsonObject.remove)
        }
        out
    }

    /** A hit value as text: String.valueOf of a non-null value (stores return
      * strings or JDBC objects); None for null or empty. */
    private def hitStr(hit: java.util.Map[String, Any], key: String): Option[String] =
        Option(hit.get(key)).map(v => String.valueOf(v)).filter(_.nonEmpty)

    /** The taps a search hit's stamp columns name, split by trust: every
      * tap name its `_datris_tap_run` can hold ([[tapRunNames]]), and the
      * tap a `tap:<name>` `_datris_source` names. Both are weighed, not the
      * first match: a chunk written with a static destination `metadata` key
      * under a stamp name (before the stamper dropped those) can name a
      * readable tap in one column while another column names the real,
      * hidden one. Only the tap-run names can keep the columns (when all are
      * readable); the source is author text and can only strip. The
      * `tap-<name>.csv|json` filename is not among them: it is weighed
      * separately in [[scrubHit]], because a chunk written before
      * `filename` became a reserved metadata key can carry a planted one. */
    private def stampTaps(hit: java.util.Map[String, Any]): (TapRunNames, Option[String]) =
        (hitStr(hit, "_datris_tap_run").map(tapRunNames).getOrElse(TapRunNames.Empty), sourceTap(hitStr(hit, "_datris_source")))

    /** Vector search hits (already limited to readable writers) with the tap
      * stamp columns ([[StampTapColumns]]) removed from each hit fed by a tap
      * `hidden` holds. A stamped hit is stripped when its feed filename
      * (`tap-<name>.csv|json`) names a hidden tap, or when its stamp columns
      * name taps ([[stampTaps]]) and any of them is hidden or unknown; it
      * keeps them when `_datris_tap_run` names only readable taps. A
      * `tap:<name>` source naming a readable tap never keeps them (author
      * text). Otherwise, when `_datris_tap_run` names no tap and the hit has
      * a `_datris_run_id`, the
      * tap is the input of the run record `run` returns for it, trusted only
      * when the record's `pipeline` is the hit's own stored writer
      * ([[VectorChunkWriter.writerOf]]) and it has an input (an upload, or no
      * tapName, keeps the columns). A feed filename never keeps the columns
      * (a hidden document tap can name its document like a readable tap's
      * feed, and older chunks can carry a planted one): with no stamp-named
      * tap and no run id the hit is unresolved. A hit whose tap cannot be
      * determined loses any non-empty tap stamp column (fail closed). A
      * `tap-<name>.csv|json` filename of a hidden tap reads `tap-[hidden]`.
      * `run` is called only when the hit's `_datris_tap_run` names no tap
      * and its source names no hidden tap, its filename names no hidden
      * tap, and it holds a non-empty stamp column and a run id. No name-keyed lookups: chunks and run
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
        // A filename or a `tap:<name>` source only ever causes a strip; neither
        // vouches for the stamps (only `_datris_tap_run` or the run record can).
        lazy val (stampNamed, sourceNamed) = stampTaps(hit)
        lazy val runId = hitStr(hit, "_datris_run_id")
        val strip = stamped && (
            renameFile || stampNamed.hides(hidden) || sourceNamed.exists(hidden) ||
                (!stampNamed.namesTap && {
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
