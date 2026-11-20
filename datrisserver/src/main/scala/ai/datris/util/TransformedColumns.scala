package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, DatrisException, JobContext, PipelineConfig, SchemaField}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._

/** Columns an AI transformation adds to the rows.
  *
  * The destination schema is built when the pipeline is saved, from the
  * source columns, so it does not list a column the transformation creates.
  * After the transformation runs, each such column joins the destination
  * schema (appended, with a type inferred from every value this run produced)
  * and the stored pipeline is updated, the way a new source column joins the
  * schema at ingest. Loaders then create or extend the destination from the
  * schema as they do for any new column.
  */
object TransformedColumns {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    private def key(n: String): String = if (n == null) "" else n.trim.toLowerCase

    private def destFieldsOf(config: PipelineConfig): Option[List[SchemaField]] =
        Option(config).flatMap(c => Option(c.destination)).flatMap(d => Option(d.schemaProperties)).flatMap(sp => Option(sp.fields)).map(_.asScala.toList)

    /** Header columns the destination schema does not list, in header order. */
    def added(header: List[String], destFields: List[SchemaField]): List[String] = {
        val declared = destFields.filter(_ != null).map(f => key(f.name)).toSet
        Option(header).getOrElse(Nil).filterNot(h => declared.contains(key(h)))
    }

    /** A type for each of `names`, from every value the rows hold in that
      * column (DestTypeInference: conservative, string when values disagree or
      * none is present). `rows` are in `header` order. */
    def inferTypes(rows: Iterator[String], header: List[String], names: List[String], delimiter: String): List[SchemaField] = {
        val positions = names.map(n => header.indexWhere(h => key(h) == key(n)))
        val types = new Array[String](names.size)
        rows.foreach { row =>
            // Once every column is string nothing can change; the rest of the file is not parsed.
            if (!types.forall(_ == "string")) {
                val values = CodeGenTransformationEvaluator.splitLine(row, delimiter)
                var i = 0
                while (i < positions.size) {
                    val p = positions(i)
                    if (types(i) != "string" && p >= 0 && p < values.size)
                        DestTypeInference.classify(values(p)).foreach(t => types(i) = if (types(i) == null) t else DestTypeInference.widen(types(i), t))
                    i += 1
                }
            }
        }
        names.zip(types).map { case (n, t) => SchemaField(n, if (t == null) "string" else t) }
    }

    /** `config` with `fields` appended to its destination schema (names it
      * already lists are skipped) and the schema version raised by one. */
    def withDestFields(config: PipelineConfig, fields: List[SchemaField]): PipelineConfig = {
        val sp = config.destination.schemaProperties
        val existing = sp.fields.asScala.toList
        val have = existing.filter(_ != null).map(f => key(f.name)).toSet
        val fresh = fields.filterNot(f => have.contains(key(f.name)))
        if (fresh.isEmpty) config
        else {
            val merged = new java.util.ArrayList[SchemaField]((existing ++ fresh).asJava)
            config.copy(destination = config.destination.copy(schemaProperties = sp.copy(fields = merged, schemaVersion = sp.schemaVersion + 1)))
        }
    }

    /** Add `fields` to the stored pipeline's destination schema. The stored
      * config is read fresh: the run's own copy can differ from it (field
      * protection, provenance). */
    private def persistStored(pipelineName: String, fields: List[SchemaField]): Unit = {
        val stored = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, pipelineName)
        if (stored != null && destFieldsOf(stored).isDefined) {
            val updated = withDestFields(stored, fields)
            if (updated ne stored) PipelineConfigIO.write(updated)
        }
    }

    /** The job context after an AI transformation wrote rows in `ctx.data.header`
      * order: unchanged when every header column is a destination column,
      * otherwise with the added columns typed and listed in the destination
      * schema (and stored, unless this is a rehearsal run). */
    def reconcile(ctx: JobContext, persist: (String, List[SchemaField]) => Unit = persistStored): JobContext = {
        val config = ctx.config
        val data = ctx.data
        val header = data.header
        val names = destFieldsOf(config) match {
            case Some(destFields) if header != null => added(header, destFields)
            case _ => Nil
        }
        if (names.isEmpty) return ctx

        val limits = IngestColumnNames.limitsFor(config)
        names.foreach { n =>
            IngestColumnNames.holdProblem(n, limits).foreach { why =>
                throw new DatrisException(
                    "Pipeline " + config.name + ": the transformation added a column named '" + IngestColumnNames.display(n) +
                        "', which the destination cannot hold: " + why + ". Change the instruction so the column has another name"
                )
            }
        }
        names.groupBy(key).collectFirst { case (_, same) if same.size > 1 => same.head }.foreach { n =>
            throw new DatrisException(
                "Pipeline " + config.name + ": the transformation's output has two columns named '" + IngestColumnNames.display(n) +
                    "'. Change the instruction so each new column has its own name"
            )
        }

        val rows = data.rowIterator()
        val fields =
            try inferTypes(rows, header, names, data.delimiter)
            finally rows.close()

        val listed = fields.map(f => f.name + " (" + f.`type` + ")").mkString(", ")
        logger.info("Transformation added destination column(s) [" + listed + "] to pipeline " + config.name)
        ctx.statusUtil.info("processing", "CodeGen transformation added columns to the destination schema: " + listed)

        if (!PipelineScripts.rehearsing) persist(config.name, fields)

        val typed = fields.map(f => key(f.name) -> f).toMap
        val headerWithSchema =
            if (data.headerWithSchema == null) null
            else data.headerWithSchema.map(f => if (f == null) f else typed.getOrElse(key(f.name), f))
        ctx.copy(data = data.copy(headerWithSchema = headerWithSchema), config = withDestFields(config, fields))
    }
}
