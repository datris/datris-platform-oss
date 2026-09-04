package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, RunLineage}
import com.google.gson.{Gson, JsonParser}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

/** Storage for [[RunLineage]] docs. Row shape:
  * `{run_id, value: {…RunLineage…}, created_at, pipeline, tap, source, datasets: [ids]}`.
  * The flat top-level fields exist only so lookups by pipeline / tap /
  * source / dataset are single indexed-equality queries (Mongo matches an
  * array field on any element), and `created_at` drives the windowed scan
  * LineageService uses to surface historical datasets.
  *
  * Retention is unbounded by design: these docs are the audit trail agents
  * reason over, and they are tiny. (Definition versions are capped by
  * `versionCap`; run lineage deliberately is not.) */
object RunLineageIO {

    private val logger = LoggerFactory.getLogger(getClass)
    private val gson = new Gson()

    private def table: String = DatrisEnvironment.current.runLineageTableName

    def write(rl: RunLineage): Unit = {
        val createdAt: java.lang.Long =
            try java.time.Instant.parse(rl.completedAt).toEpochMilli
            catch { case _: Exception => System.currentTimeMillis() }
        val extra = new java.util.HashMap[String, AnyRef]()
        if (rl.pipeline != null) extra.put("pipeline", rl.pipeline)
        if (rl.input != null && rl.input.tapName != null) extra.put("tap", rl.input.tapName)
        if (rl.input != null && rl.input.source != null) extra.put("source", rl.input.source)
        val datasetIds = Option(rl.outputs).map(_.asScala.map(_.datasetId).filter(_ != null).distinct.toList).getOrElse(Nil)
        if (datasetIds.nonEmpty) extra.put("datasets", datasetIds.asJava)
        NoSQLDbUtil.putItemJSON(table, "run_id", rl.runId, "value", gson.toJson(rl), "created_at", createdAt, extra)
    }

    def read(runId: String): Option[RunLineage] =
        try NoSQLDbUtil.getItemJSON(table, "run_id", runId, "value").map(gson.fromJson(_, classOf[RunLineage]))
        catch {
            case e: Exception =>
                logger.debug("run-lineage read failed for " + runId + ": " + e.getMessage)
                None
        }

    private def parseRows(rows: List[String]): List[RunLineage] =
        rows.flatMap { json =>
            try {
                val el = JsonParser.parseString(json)
                if (el.isJsonObject && el.getAsJsonObject.has("value"))
                    Option(gson.fromJson(el.getAsJsonObject.get("value"), classOf[RunLineage]))
                else None
            } catch { case _: Exception => None }
        }

    /** Most recent `max` runs touching one lineage node, newest first.
      * `field` is one of the flat lookup fields: pipeline, tap, source, datasets. */
    def recentBy(field: String, value: String, max: Int): List[RunLineage] =
        try parseRows(NoSQLDbUtil.queryJSONItemsByKey(table, field, value))
                .sortBy(r => Option(r.completedAt).getOrElse(""))(Ordering[String].reverse)
                .take(max)
        catch {
            case e: Exception =>
                logger.debug("run-lineage query failed (" + field + "=" + value + "): " + e.getMessage)
                Nil
        }

    /** Runs completed since `sinceEpochMs`, newest first, capped. */
    def since(sinceEpochMs: Long, max: Int): List[RunLineage] =
        try parseRows(NoSQLDbUtil.getItemsSinceAsJSON(table, "created_at", sinceEpochMs, max))
        catch {
            case e: Exception =>
                logger.debug("run-lineage scan failed: " + e.getMessage)
                Nil
        }
}
