package ai.datris.incident

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, RunLineage}
import ai.datris.util.{LineageService, TapConfigIO}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

/** Where a pipeline incident can get input to verify a regenerated script
  * on. A pipeline fed by a tap can be re-fed by running that tap (scratch
  * first, then for real); a pipeline fed by uploads, Kafka or object-store
  * triggers has no recoverable input until deterministic replay ships. */
trait PipelineInputSource {

    /** The input the last good run read, when the platform can feed it again. */
    def lastGoodInput(incident: Incident): Option[PipelineInputSource.Input]

    /** What fed the pipeline's newest good run ("upload", "tap", …), for the
      * "not verified" step. None = unknown. */
    def inputKind(incident: Incident): Option[String] = None
}

object PipelineInputSource extends PipelineInputSource {

    private val logger = LoggerFactory.getLogger(getClass)

    sealed trait Input
    case class TapFed(tap: String) extends Input

    /** Reserved for plans/generated/deterministic-replay.md (RunSnapshot);
      * never produced yet. */
    case class Snapshot(ref: String) extends Input

    /** Recorded runs inspected, newest first. */
    private val RecentRuns = 10

    /** The newest SUCCESS run that wrote a real destination. Rehearsals never
      * record lineage (JobRunner.sideEffectsFor); a run whose outputs are all
      * the scratch destination is skipped as well. */
    private def newestGoodRun(incident: Incident): Option[RunLineage] =
        if (incident == null || incident.resourceType != "pipeline") None
        else
            LineageService.recentRuns("pipeline", incident.resourceName, RecentRuns).find { r =>
                r.status == "SUCCESS" && {
                    val outs = Option(r.outputs).map(_.asScala.toList).getOrElse(Nil)
                    outs.isEmpty || !outs.forall(o => o != null && o.kind == "scratch")
                }
            }

    /** `TapFed(tap)` when the newest good run was fed by a tap that still
      * targets this pipeline; otherwise None. Never throws. */
    override def lastGoodInput(incident: Incident): Option[Input] =
        try {
            newestGoodRun(incident).flatMap { r =>
                val in = r.input
                if (in == null || in.kind != "tap" || in.tapName == null || in.tapName.isEmpty) None
                else {
                    val tap = Option(TapConfigIO.read(DatrisEnvironment.current.tapTableName, in.tapName))
                    if (tap.exists(_.targetPipeline == incident.resourceName)) Some(TapFed(in.tapName)) else None
                }
            }
        } catch {
            case e: Exception =>
                logger.debug("pipeline input lookup failed for " + Option(incident).map(_.resourceName).orNull + ": " + e.getMessage)
                None
        }

    override def inputKind(incident: Incident): Option[String] =
        try newestGoodRun(incident).flatMap(r => Option(r.input)).flatMap(i => Option(i.kind)).filter(_.nonEmpty)
        catch { case _: Exception => None }
}
