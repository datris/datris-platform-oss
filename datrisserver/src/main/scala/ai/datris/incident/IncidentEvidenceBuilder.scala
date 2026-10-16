package ai.datris.incident

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditLogIO
import ai.datris.model.{DatrisEnvironment, PipelineConfig, RunLineage, TapConfig, UnityCatalogSync}
import ai.datris.policy.RecoverySettings
import ai.datris.util.RunProfiler.{ColumnStats, Stats}
import ai.datris.util.{DestSchemaApply, FieldProtection, LineageService, PipelineConfigIO, RunProfiler, TapConfigIO}
import com.google.gson.JsonObject
import org.bson.Document
import org.slf4j.LoggerFactory

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.collection.JavaConverters._

/** The IO side of incident evidence: reads lineage, recorded runs, landed
  * rows and (when reads are audited) the audit log, and hands them to the
  * pure [[RunProfiler]] and [[Thresholds]]. Never throws — a step that fails
  * leaves its field empty and logs at debug.
  *
  * Value safety: a column's values (topK, value quantiles) are shown only
  * when it is neither a field-protected source field nor matched by
  * [[RunProfiler.SensitiveNamePattern]]. The field-protection advisor is
  * AI-driven, so it cannot gate this deterministic path; the name list does. */
object IncidentEvidenceBuilder {

    private val logger = LoggerFactory.getLogger(getClass)

    /** Landed rows sampled per profile. */
    val SampleRows = 1000

    /** Recorded runs read for the baseline and the last-N counts. */
    val RecentRuns = 10

    private def attempt[T](what: String)(f: => T): Option[T] =
        try Option(f)
        catch {
            case e: Throwable if scala.util.control.NonFatal(e) =>
                logger.debug("incident evidence: " + what + " skipped: " + e.getMessage)
                None
        }

    private def isSuccess(r: RunLineage): Boolean = r != null && r.status != null && r.status.equalsIgnoreCase("SUCCESS")

    private val EmptyBlast = BlastRadius(Nil, Nil, Nil, Nil, None, None, None)

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /** Evidence at incident open: blast radius and baseline, verdict pending. */
    def atOpen(incident: Incident, settings: RecoverySettings): IncidentEvidence = {
        val thresholds = attempt("thresholds")(settings.thresholds).getOrElse(Thresholds.Default)
        val blast = attempt("blast radius")(blastRadius(incident)).getOrElse(EmptyBlast)
        val baseline = attempt("baseline")(baselineProfile(incident)).flatten
        IncidentEvidence(
            baseline = baseline,
            candidate = None,
            real = None,
            diff = None,
            blastRadius = blast,
            thresholds = thresholds,
            checks = Nil,
            verdict = Thresholds.Pending,
            computedAt = Instant.now()
        )
    }

    /** After the verifying real run: profile it into `real`, diff baseline
      * against candidate-if-present-else-real, evaluate the thresholds. */
    def afterRealRun(incident: Incident, evidence: IncidentEvidence): IncidentEvidence = {
        try {
            val real = attempt("real profile")(realProfile(incident, evidence.baseline)).flatten
            val withReal = evidence.copy(real = real.orElse(evidence.real), computedAt = Instant.now())
            judge(incident, withReal)
        } catch {
            case e: Throwable if scala.util.control.NonFatal(e) =>
                logger.debug("incident evidence: afterRealRun failed: " + e.getMessage)
                evidence
        }
    }

    /** Diff + checks + verdict from whatever profiles the evidence holds. */
    private[incident] def judge(incident: Incident, evidence: IncidentEvidence): IncidentEvidence = {
        val after = evidence.candidate.orElse(evidence.real)
        (evidence.baseline, after) match {
            case (Some(b), Some(a)) =>
                val lastN = attempt("last-N counts")(lastNCounts(incident, Set(a.runId))).getOrElse(Nil)
                val d = diffProfiles(b, a, lastN)
                val (checks, verdict) = evidence.thresholds.evaluate(b, d)
                evidence.copy(diff = Some(d), checks = checks, verdict = verdict)
            case (None, Some(_)) =>
                val check = ThresholdCheck("baseline", passed = false, observed = "none", limit = "a prior successful run")
                evidence.copy(diff = None, checks = List(check), verdict = Thresholds.NeedsHuman)
            case _ => evidence
        }
    }

    /** RunProfiler.diff plus the DQ delta. Column-level deltas are only
      * meaningful when both sides were profiled from landed rows. */
    private[incident] def diffProfiles(b: RunProfile, a: RunProfile, lastN: Seq[Long]): ProfileDiff = {
        val raw = RunProfiler.diff(Stats(b.recordCount, b.columns), Stats(a.recordCount, a.columns), lastN)
        val comparable = b.source != "lineage-only" && a.source != "lineage-only"
        val d = if (comparable) raw else raw.copy(nullRateDeltas = Nil, quantileShift = Nil)
        d.copy(dqPassRateDelta = Some(a.dqPassRate - b.dqPassRate))
    }

    /** Store the evidence on the incident. Never throws. */
    def store(id: String, evidence: IncidentEvidence): Unit =
        try IncidentIO.set(id, "evidence" -> Document.parse(evidence.toJson.toString))
        catch { case e: Exception => logger.debug("incident evidence: store failed for " + id + ": " + e.getMessage) }

    /** Parsed evidence of a stored incident, if any. */
    def of(incident: Incident): Option[IncidentEvidence] =
        incident.evidence.flatMap(e => attempt("parse")(IncidentEvidence.fromJson(e)))

    // ------------------------------------------------------------------
    // Blast radius
    // ------------------------------------------------------------------

    private def str(o: JsonObject, k: String): Option[String] =
        if (o != null && o.has(k) && o.get(k).isJsonPrimitive) Some(o.get(k).getAsString) else None

    def blastRadius(incident: Incident): BlastRadius = {
        val env = DatrisEnvironment.current
        val n = LineageService.neighborhood(incident.resourceType, incident.resourceName, "down", 3, 1, columns = false)
        val downstream: List[JsonObject] =
            if (n == null || !n.has("downstream")) Nil
            else n.getAsJsonArray("downstream").asScala.toList.filter(_.isJsonObject).map(_.getAsJsonObject)
        val pipelines = downstream.filter(o => str(o, "type").contains("pipeline")).flatMap(str(_, "name")).distinct
        val datasets = downstream.filter(o => str(o, "type").contains("dataset")).flatMap(str(_, "name")).map { name =>
            val i = name.indexOf(':')
            if (i > 0) (name.substring(0, i), name.substring(i + 1)) else ("dataset", name)
        }.distinct
        val lastRunAt =
            if (n == null || !n.has("runs")) None
            else n.getAsJsonArray("runs").asScala.toList.filter(_.isJsonObject).map(_.getAsJsonObject).flatMap(o => str(o, "completedAt")).sorted.lastOption

        val configs: List[PipelineConfig] = pipelines.flatMap(p => attempt("pipeline " + p)(PipelineConfigIO.read(env.pipelineTableName, p)))
        val ucDefault = attempt("uc default")(UnityCatalogSync.defaultEnabledFromEnv).getOrElse(false)
        val ucTables = configs.flatMap { p =>
            UnityCatalogSync.effective(p, ucDefault) match {
                case None => Nil
                case Some((uc, _)) =>
                    val dbx =
                        LineageService.datasets(p).filter(_.kind == "databricks").map(_.coords.map(_._2).filter(v => v != null && v.nonEmpty).mkString("."))
                    val iceberg =
                        if (dbx.isEmpty && uc.catalog != null && uc.catalog.trim.nonEmpty) List(uc.catalog.trim + "." + uc.schemaOrDefault + "." + p.name)
                        else Nil
                    dbx ++ iceberg
            }
        }.distinct

        val secretName =
            if (incident.resourceType == "tap")
                attempt("tap")(TapConfigIO.read(env.tapTableName, incident.resourceName)).flatMap(t => Option(t.secretName)).filter(_.nonEmpty)
            else None

        val agents =
            if (Option(DatrisEnvironment.values).exists(_.auditLogLogReads))
                attempt("audit reads")(agentsThatQueried(pipelines ++ datasets.map(_._2).map(lastSegment)))
            else None

        BlastRadius(
            downstreamPipelines = pipelines,
            downstreamDatasets = datasets,
            ucTables = ucTables,
            consumersFromContracts = Nil,
            lastDownstreamRunAt = lastRunAt,
            agentsThatQueriedLast7d = agents,
            secretName = secretName
        )
    }

    private def lastSegment(coords: String): String = {
        val i = coords.lastIndexOf('.')
        if (i >= 0 && i < coords.length - 1) coords.substring(i + 1) else coords
    }

    /** Distinct agent identities (API keys and the Assistant, not people)
      * that ran a query or search against any of `resources` in the last 7
      * days. Only meaningful when reads are audited. */
    private def agentsThatQueried(resources: List[String]): List[String] = {
        val since = Instant.now().minus(7, ChronoUnit.DAYS)
        val labels = for {
            r <- resources.filter(_.nonEmpty).distinct
            category <- List("query", "search")
            doc <- AuditLogIO.query(AuditLogIO.Query(since = Some(since), category = Some(category), resource = Some(r), limit = AuditLogIO.MaxLimit)).entries
            actor <- Option(doc.get("actor", classOf[Document])).toList
            if Option(actor.getString("type")).exists(t => t == "api-key" || t == "assistant")
            label <- Option(actor.getString("label")).toList
        } yield label
        labels.distinct.sorted
    }

    // ------------------------------------------------------------------
    // Profiles
    // ------------------------------------------------------------------

    /** The pipeline whose landed rows describe this resource's output. */
    private def fedPipeline(incident: Incident): Option[PipelineConfig] = {
        val env = DatrisEnvironment.current
        val name =
            if (incident.resourceType == "pipeline") Some(incident.resourceName)
            else attempt("tap")(TapConfigIO.read(env.tapTableName, incident.resourceName)).flatMap(t => Option(t.targetPipeline)).filter(_.nonEmpty)
        name.flatMap(p => attempt("pipeline " + p)(PipelineConfigIO.read(env.pipelineTableName, p)))
    }

    private def tapConfig(incident: Incident): Option[TapConfig] =
        if (incident.resourceType != "tap") None
        else attempt("tap")(TapConfigIO.read(DatrisEnvironment.current.tapTableName, incident.resourceName))

    private def runs(incident: Incident): List[RunLineage] =
        attempt("recent runs")(LineageService.recentRuns(incident.resourceType, incident.resourceName, RecentRuns)).getOrElse(Nil)

    private def lastNCounts(incident: Incident, exclude: Set[String]): List[Long] =
        runs(incident).filter(isSuccess).filterNot(r => exclude.contains(r.runId)).map(_.recordCount.toLong)

    /** `valueSafe` for a pipeline: not field-protected, not sensitive-looking. */
    private[incident] def valueSafe(config: Option[PipelineConfig]): String => Boolean = {
        val protectedNames = config.toList.flatMap(c => FieldProtection.protectedFields(c)).map(_.name.toLowerCase).toSet
        col => col != null && !protectedNames.contains(col.toLowerCase) && !RunProfiler.looksSensitive(col)
    }

    /** Columns + sampled-row count from landed rows, or from the tap's last
      * run column names when the destination cannot be sampled. */
    private def columnsFor(incident: Incident, config: Option[PipelineConfig]): (String, List[ColumnStats], Long) = {
        val landed = for {
            c <- config
            kind <- DestSchemaApply.inScopeDest(c)
            rows <- attempt("landed sample")(DestSchemaApply.sampleLanded(c, kind, SampleRows)).flatten
        } yield {
            val stats = RunProfiler.profile(rows.iterator, valueSafe(config))
            ("landed-sample", stats.columns, stats.recordCount)
        }
        landed.getOrElse {
            val names = tapConfig(incident).flatMap(t => Option(t.lastRunColumns)).map(_.asScala.toList).getOrElse(Nil)
            ("lineage-only", names.filter(_ != null).map(n => ColumnStats(n, "null", 0.0, 0L, None, None, None)), 0L)
        }
    }

    private def profileOf(r: RunLineage, source: String, columns: List[ColumnStats], sampled: Long): RunProfile = {
        val ok = isSuccess(r)
        RunProfile(
            runId = r.runId,
            source = source,
            recordCount = r.recordCount.toLong,
            columns = columns,
            // Recorded runs carry no per-row DQ counts yet: a SUCCESS run is
            // taken as fully passing, anything else as failing.
            dqFailures = if (ok) 0 else 1,
            dqPassRate = if (ok) 1.0 else 0.0,
            durationMs = r.durationMs,
            scriptSha = Option(r.input).flatMap(i => Option(i.scriptSha)).filter(_.nonEmpty),
            tapVersion = None,
            pipelineVersion = Some(r.configVersion).filter(_ > 0),
            sampledRows = sampled,
            computedAt = Instant.now()
        )
    }

    /** Last successful recorded run before the incident, profiled. */
    def baselineProfile(incident: Incident): Option[RunProfile] = {
        val last = runs(incident).find(isSuccess).getOrElse(return None)
        val config = fedPipeline(incident)
        val (source, cols, sampled) = columnsFor(incident, config)
        Some(profileOf(last, source, cols, sampled))
    }

    /** The newest recorded run since the incident opened, profiled the same
      * way as the baseline. Falls back to the tap's own last-run record when
      * no pipeline run landed (e.g. the tap feeds nothing that records runs). */
    private def realProfile(incident: Incident, baseline: Option[RunProfile]): Option[RunProfile] = {
        val config = fedPipeline(incident)
        val newest = runs(incident).find(r =>
            !baseline.exists(_.runId == r.runId) &&
                Option(r.completedAt).flatMap(s => attempt("ts")(Instant.parse(s))).forall(!_.isBefore(incident.openedAt))
        )
        newest match {
            case Some(r) =>
                val (source, cols, sampled) = columnsFor(incident, config)
                Some(profileOf(r, source, cols, sampled))
            case None =>
                tapConfig(incident).filter(t => t.lastRunTime != null).map { t =>
                    val ok = Option(t.lastRunStatus).map(_.toLowerCase).exists(s => s == "success" || s == "no_records")
                    val names = Option(t.lastRunColumns).map(_.asScala.toList).getOrElse(Nil).filter(_ != null)
                    RunProfile(
                        runId = "tap:" + t.name + "@" + t.lastRunTime,
                        source = "lineage-only",
                        recordCount = t.lastRunRecordCount.toLong,
                        columns = names.map(n => ColumnStats(n, "null", 0.0, 0L, None, None, None)),
                        dqFailures = if (ok) 0 else 1,
                        dqPassRate = if (ok) 1.0 else 0.0,
                        durationMs = 0L,
                        scriptSha = None,
                        tapVersion = Some(t.version),
                        pipelineVersion = None,
                        sampledRows = 0L,
                        computedAt = Instant.now()
                    )
                }
        }
    }

    // ------------------------------------------------------------------
    // Diagnosis message
    // ------------------------------------------------------------------

    /** One plain-facts line for the diagnosis prompt: counts only. */
    def blastRadiusLine(incident: Incident, b: BlastRadius): String = {
        val noun = if (incident.resourceType == "pipeline") "This pipeline" else "This tap"
        val head = noun + " feeds " + b.downstreamPipelines.size + " pipeline" + (if (b.downstreamPipelines.size == 1) "" else "s") +
            " and " + b.ucTables.size + " Unity Catalog table" + (if (b.ucTables.size == 1) "" else "s")
        b.agentsThatQueriedLast7d match {
            case Some(a) => head + "; " + a.size + " agent identit" + (if (a.size == 1) "y" else "ies") + " queried them this week."
            case None => head + "; downstream readers unknown (reads not audited)."
        }
    }
}
