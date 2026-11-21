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
  * schema (appended, as `string`) and the stored pipeline is updated, the way
  * a new source column joins the schema at ingest. Loaders then create or extend the destination from the
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

    /** Header columns the transformation created: not in the destination
      * schema, not in the source schema and not in `inputHeader` (the rows'
      * header before the transformation ran), names compared trimmed and
      * lower-cased, in header order. A source column the destination schema
      * leaves out is never one of them, whatever the transformation did with
      * it. */
    def created(header: List[String], destFields: List[SchemaField], sourceFields: List[SchemaField], inputHeader: List[String]): List[String] = {
        val known = (Option(sourceFields).getOrElse(Nil).filter(_ != null).map(f => key(f.name)) ++ Option(inputHeader).getOrElse(Nil).map(key)).toSet
        added(header, destFields).filterNot(h => known.contains(key(h)))
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
    private[util] def persistStored(
        pipelineName: String,
        fields: List[SchemaField],
        read: String => PipelineConfig = name => PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, name),
        write: PipelineConfig => Unit = PipelineConfigIO.write
    ): Unit = {
        val stored = read(pipelineName)
        if (stored != null && destFieldsOf(stored).isDefined) {
            val updated = withDestFields(stored, fields)
            if (updated ne stored) write(updated)
        }
    }

    /** The job context after an AI transformation wrote rows in `ctx.data.header`
      * order: unchanged when the transformation created no column (see
      * [[created]]; `inputHeader` is the header the transformation read),
      * otherwise with the created columns listed in the destination schema as
      * `string` (and stored, unless this is a rehearsal run).
      *
      * A name the destination cannot hold, or a name two created columns
      * share: with an object store destination the run fails (its positional
      * read cannot skip a column, as on v1.60.0); otherwise the column is left
      * out of the schema with a warn line, and the load leaves it out by name.
      *
      * With an object store destination, rows that still carry a column the
      * destination schema leaves out are read by position, as on v1.60.0; the
      * schema is then left as it is so that read is unchanged. */
    def reconcile(
        ctx: JobContext,
        persist: (String, List[SchemaField]) => Unit = (n, f) => persistStored(n, f),
        inputHeader: List[String] = Nil
    ): JobContext = {
        val config = ctx.config
        val data = ctx.data
        val header = data.header
        val sourceFields = Option(config.source).flatMap(s => Option(s.schemaProperties)).flatMap(sp => Option(sp.fields)).map(_.asScala.toList).getOrElse(Nil)
        val (names, excluded) = destFieldsOf(config) match {
            case Some(destFields) if header != null =>
                val made = created(header, destFields, sourceFields, inputHeader)
                val madeKeys = made.map(key).toSet
                (made, added(header, destFields).filterNot(h => madeKeys.contains(key(h))))
            case _ => (Nil, Nil)
        }
        if (names.isEmpty) return ctx

        val objectStore = Option(config.destination).exists(_.objectStore != null)
        if (objectStore && excluded.nonEmpty) {
            logger.info("Transformation added column(s) [" + names.mkString(", ") + "] to pipeline " + config.name +
                "; the object store destination schema is left as it is: the rows also carry a column it leaves out")
            ctx.statusUtil.info(
                "processing",
                "CodeGen transformation added column(s) " + names.map(n => "'" + IngestColumnNames.display(n) + "'").mkString(", ") +
                    "; the object store destination schema is left as it is because the rows also carry a column the destination schema leaves out"
            )
            return ctx
        }
        val limits = IngestColumnNames.limitsFor(config)
        val unholdable = names.flatMap(n => IngestColumnNames.holdProblem(n, limits).map(n -> _))
        val holdable = names.filter(n => IngestColumnNames.holdProblem(n, limits).isEmpty)
        val shared = holdable.groupBy(key).filter(_._2.size > 1)
        val sharedFirst = holdable.filter(n => shared.contains(key(n))).map(key).distinct.map(k => shared(k).head)

        if (objectStore) {
            unholdable.headOption.foreach { case (n, why) =>
                throw new DatrisException(
                    "Pipeline " + config.name + ": the transformation added a column named '" + IngestColumnNames.display(n) +
                        "', which the destination cannot hold: " + why + ". Change the instruction so the column has another name"
                )
            }
            sharedFirst.headOption.foreach { n =>
                throw new DatrisException(
                    "Pipeline " + config.name + ": the transformation's output has two columns named '" + IngestColumnNames.display(n) +
                        "'. Change the instruction so each new column has its own name"
                )
            }
        }

        unholdable.foreach { case (n, _) =>
            val why = IngestColumnNames.holdProblem(n, limits, "the destination").getOrElse("")
            val line = "CodeGen transformation added a column named '" + IngestColumnNames.display(n) + "', which the destination cannot hold: " + why +
                "; it is left out of the destination. Change the instruction so the column has another name"
            logger.warn(line + " (pipeline " + config.name + ")")
            ctx.statusUtil.warn("processing", line)
        }
        sharedFirst.foreach { n =>
            val line = "CodeGen transformation output has more than one column named '" + IngestColumnNames.display(n) +
                "'; they are left out of the destination. Change the instruction so each new column has its own name"
            logger.warn(line + " (pipeline " + config.name + ")")
            ctx.statusUtil.warn("processing", line)
        }

        // Stored as string, the rule a new source column follows: no later
        // run's values can fail the load. A type can be set afterwards through
        // the destination-types flow.
        val fields = holdable.filterNot(n => shared.contains(key(n))).map(n => SchemaField(n, "string"))
        if (fields.isEmpty) return ctx

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
