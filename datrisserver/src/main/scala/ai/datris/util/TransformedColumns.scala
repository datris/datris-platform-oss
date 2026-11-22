package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, DatrisException, JobContext, PipelineConfig, SchemaField}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._
import scala.util.Try
import scala.util.control.NonFatal

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
      * [[created]]; `inputHeader` is the header the transformation read, required
      * so a caller cannot leave it out),
      * otherwise with the created columns listed in the destination schema as
      * `string` and stored, unless this is a rehearsal run: at once when the
      * pipeline has no SQL table destination, else after the load
      * ([[storeAfterLoad]]; the context then carries a [[RunColumns]]).
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
        inputHeader: List[String],
        persist: (String, List[SchemaField]) => Unit = (n, f) => persistStored(n, f)
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

        // With a SQL table destination the columns are stored after the load
        // ([[storeAfterLoad]]), and only those every SQL table took; otherwise
        // here, as before. A rehearsal stores nothing either way.
        val store = !PipelineScripts.rehearsing
        val run = if (hasSqlTable(config)) new RunColumns(fields, store, persist) else null
        if (run == null && store) persist(config.name, fields)

        val typed = fields.map(f => key(f.name) -> f).toMap
        val headerWithSchema =
            if (data.headerWithSchema == null) null
            else data.headerWithSchema.map(f => if (f == null) f else typed.getOrElse(key(f.name), f))
        ctx.copy(data = data.copy(headerWithSchema = headerWithSchema), config = withDestFields(config, fields), transformationColumns = run)
    }

    // ---- SQL table destinations (PostgreSQL, SQL Server, Snowflake, Databricks) ----
    //  On v1.60.0 a column the transformation added was never loaded and the
    //  run succeeded. A SQL table may be unable to take it (the login may not
    //  ALTER the table, the table is managed manually, or it already has a
    //  column of that name whose type cannot take every text value). Such a
    //  column is left out of that destination for that run with a warn line;
    //  the other columns load as usual. The stored destination schema gains
    //  the column only after a successful run in which every SQL table took
    //  it, so it never claims a column a table lacks, and a later run that
    //  meets the same table warns again instead of failing.

    /** The columns this run's transformation added, on a pipeline with a SQL
      * table destination. Shared by the run's loaders (they run in
      * parallel), which record each column they leave out. */
    final class RunColumns(val fields: List[SchemaField], val store: Boolean, persist: (String, List[SchemaField]) => Unit) {
        private val keys = fields.map(f => key(f.name)).toSet
        private val left = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

        def isAdded(name: String): Boolean = keys.contains(key(name))
        def leaveOut(name: String): Unit = if (isAdded(name)) left.add(key(name))
        def leftOut: Set[String] = left.asScala.toSet
        private[util] def toStore: List[SchemaField] = fields.filterNot(f => left.contains(key(f.name)))
        private[util] def write(pipelineName: String, kept: List[SchemaField]): Unit = persist(pipelineName, kept)
    }

    def runColumns(ctx: JobContext): Option[RunColumns] = Option(ctx).flatMap(c => Option(c.transformationColumns))

    /** True when the pipeline loads into PostgreSQL, SQL Server, Snowflake or
      * Databricks. */
    def hasSqlTable(config: PipelineConfig): Boolean = {
        val db = Option(config).flatMap(c => Option(c.destination)).flatMap(d => Option(d.database))
        db.exists(d => d.usePostgres || d.useSqlServer || d.useSnowflake || d.useDatabricks)
    }

    /** After every destination loaded: store the columns this run added that
      * no SQL table left out (nothing for a rehearsal). A failed write is a
      * warning, not a failed run: the rows are loaded and the next run adds
      * the column again. */
    def storeAfterLoad(ctx: JobContext): Unit = runColumns(ctx).foreach { run =>
        val kept = run.toStore
        if (run.store && kept.nonEmpty)
            try run.write(ctx.config.name, kept)
            catch {
                case NonFatal(e) =>
                    val line = "CodeGen transformation added column(s) " + kept.map(f => "'" + IngestColumnNames.display(f.name) + "'").mkString(", ") +
                        " were loaded but could not be recorded in the stored destination schema: " + reason(e) + ". The next run adds them again"
                    logger.warn(line + " (pipeline " + ctx.config.name + ")", e)
                    ctx.statusUtil.warn("processing", line)
            }
    }

    /** A column as a SQL store's catalog (information_schema) reports it:
      * its stored name, type and character length, and whether it takes NULL
      * and whether the database generates its values (None when the catalog
      * did not say). */
    case class StoreColumn(name: String, dataType: String, maxLength: Option[Long] = None, nullable: Option[Boolean] = None, generated: Option[Boolean] = None)

    val Postgres = "postgres"
    val SqlServer = "sqlserver"
    val Snowflake = "snowflake"
    val Databricks = "databricks"

    /** True when an existing column of this type takes every text value, as
      * the `string` column the loader would add does: PostgreSQL `text` or
      * `character varying` without a length; SQL Server `varchar(max)`,
      * `nvarchar(max)`, `text`, `ntext`; Snowflake `TEXT` at the default
      * (maximum) length, 16,777,216 or more; Databricks `STRING`. Anything
      * else, a bounded length included, is false. */
    def holdsAnyText(engine: String, c: StoreColumn): Boolean = {
        val t = Option(c).flatMap(x => Option(x.dataType)).map(_.trim.toLowerCase(java.util.Locale.ROOT)).getOrElse("")
        val len = Option(c).flatMap(_.maxLength)
        engine match {
            case Postgres => t == "text" || (t == "character varying" && len.isEmpty)
            case SqlServer => t == "text" || t == "ntext" || ((t == "varchar" || t == "nvarchar") && len.contains(-1L))
            case Snowflake => t == "text" && len.forall(_ >= 16777216L)
            case Databricks => t == "string"
            case _ => false
        }
    }

    /** The store's message on one line, at most 300 characters. */
    private[util] def reason(e: Throwable): String = {
        val m =
            Option(e).flatMap(x => Option(x.getMessage)).map(_.trim).filter(_.nonEmpty).getOrElse(Option(e).map(_.getClass.getSimpleName).getOrElse("unknown"))
        val one = m.map(c => if (Character.isISOControl(c)) ' ' else c).replaceAll(" +", " ")
        if (one.length > 300) one.take(300) + "..." else one
    }

    private def leave(ctx: JobContext, run: RunColumns, name: String, line: String): Unit = {
        run.leaveOut(name)
        logger.warn(line + " (pipeline " + ctx.config.name + ")")
        ctx.statusUtil.warn("processing", line)
    }

    private def shown(n: String): String = "'" + IngestColumnNames.display(n) + "'"

    private val LeftOut = "it is left out of this destination for this run and is not added to the stored destination schema; " +
        "the other columns load as usual and the next run tries again"

    /** The SQL loader's additive step, in the loader's order. `adds` pairs
      * each field the table lacks with its ADD COLUMN statement. A declared
      * column's statement runs through `execute`, exactly as before (a
      * failure fails the load). A column this run's transformation added runs
      * through `attempt`: Some(error) when the store refused it and the
      * connection can go on (a throw means it cannot, and fails the load);
      * the column is then left out with a warn line naming it,
      * `destination` and the store's reason. An added column the table had
      * before this step is checked with [[holdsAnyText]] against
      * `readColumns` (read once, only then); one whose type does not take
      * every text value, or whose type cannot be read, is left out with a
      * warn line. Returns the names left out, lower-cased. */
    def evolve(
        ctx: JobContext,
        destination: String,
        engine: String,
        adds: Seq[(SchemaField, String)],
        existing: Set[String],
        execute: String => Unit,
        attempt: String => Option[Throwable],
        readColumns: () => Either[Throwable, Seq[StoreColumn]]
    ): Set[String] = {
        val run = runColumns(ctx)
        val status = ctx.statusUtil
        val out = scala.collection.mutable.LinkedHashSet[String]()
        adds.foreach { case (field, sql) =>
            status.info("processing", "Schema evolution: " + sql)
            if (run.exists(_.isAdded(field.name)))
                attempt(sql).foreach { e =>
                    leave(
                        ctx,
                        run.get,
                        field.name,
                        "CodeGen transformation added column " + shown(field.name) + ", which could not be added to " + destination + ": " + reason(
                            e
                        ) + "; " + LeftOut
                    )
                    out += key(field.name)
                }
            else execute(sql)
        }
        run.foreach { r =>
            val present = destFieldsOf(ctx.config).getOrElse(Nil).filter(f => f != null && r.isAdded(f.name) && existing.contains(f.name.toLowerCase))
            out ++= checkExisting(ctx, r, destination, engine, present, readColumns, manual = false)
        }
        out.toSet
    }

    /** A table the loader does not create or alter (`manageTableManually`):
      * each column this run's transformation added that the table lacks, or
      * holds as a type that does not take every text value, is left out with
      * a warn line. The table is read only when there is such a column.
      * Returns the names left out, lower-cased. */
    def manualTable(ctx: JobContext, destination: String, engine: String, readColumns: () => Either[Throwable, Seq[StoreColumn]]): Set[String] =
        runColumns(ctx) match {
            case None => Set.empty
            case Some(run) =>
                val added = destFieldsOf(ctx.config).getOrElse(Nil).filter(f => f != null && run.isAdded(f.name))
                checkExisting(ctx, run, destination, engine, added, readColumns, manual = true)
        }

    private def checkExisting(
        ctx: JobContext,
        run: RunColumns,
        destination: String,
        engine: String,
        fields: List[SchemaField],
        readColumns: () => Either[Throwable, Seq[StoreColumn]],
        manual: Boolean
    ): Set[String] =
        if (fields.isEmpty) Set.empty
        else
            readColumns() match {
                case Left(e) =>
                    fields.foreach(f =>
                        leave(
                            ctx,
                            run,
                            f.name,
                            "CodeGen transformation added column " + shown(f.name) + ", but the columns of " + destination + " could not be read: " + reason(
                                e
                            ) +
                                "; " + LeftOut
                        )
                    )
                    fields.map(f => key(f.name)).toSet
                case Right(columns) =>
                    fields.flatMap { f =>
                        val same = columns.filter(c => c != null && key(c.name) == key(f.name))
                        same.find(c => sameName(engine, f.name, c.name)).orElse(same.headOption) match {
                            case None if manual =>
                                leave(
                                    ctx,
                                    run,
                                    f.name,
                                    "CodeGen transformation added column " + shown(f.name) + ", which " + destination +
                                        " does not have; the table is managed manually (manageTableManually), so no column is added to it. " +
                                        "It is left out of this destination for this run and is not added to the stored destination schema"
                                )
                                Some(key(f.name))
                            case None =>
                                leave(
                                    ctx,
                                    run,
                                    f.name,
                                    "CodeGen transformation added column " + shown(
                                        f.name
                                    ) + ", but its column in " + destination + " could not be read; " + LeftOut
                                )
                                Some(key(f.name))
                            case Some(c) =>
                                refusal(engine, f.name, c).map { why =>
                                    leave(
                                        ctx,
                                        run,
                                        f.name,
                                        "CodeGen transformation added column " + shown(
                                            f.name
                                        ) + ", but " + destination + " already has a column of that name" + why +
                                            "; it is left out of this destination, that column gets no value from this run, " +
                                            "and it is not added to the stored destination schema"
                                    )
                                    key(f.name)
                                }
                        }
                    }.toSet
            }

    /** The name the load writes for `loadName`, as the store keeps it:
      * Snowflake folds a plain name to upper case; the others keep it. */
    private def storedName(engine: String, loadName: String): String =
        if (engine == Snowflake) SnowflakeConnectionUtil.effectiveName(loadName) else loadName

    /** True when the load's quoted `loadName` reaches the table's column
      * `stored`: Databricks resolves names whatever their letter case;
      * PostgreSQL and Snowflake compare quoted names exactly; SQL Server is
      * compared exactly too, so a case-sensitive collation cannot fail the
      * load. */
    private def sameName(engine: String, loadName: String, stored: String): Boolean =
        stored != null && loadName != null &&
            (if (engine == Databricks) loadName.toLowerCase == stored.toLowerCase else storedName(engine, loadName) == stored)

    /** Why the existing column `c` cannot take this run's values for the
      * added column `loadName` without risking the load, worded to follow
      * "already has a column of that name"; None when it can: a type that
      * takes every text value ([[holdsAnyText]]), the exact name the load
      * writes, nullable and not generated, both as read from the catalog
      * (unknown counts as no). On Databricks never: Unity Catalog may not
      * report a generated column as one, so the column is left as it is. */
    def refusal(engine: String, loadName: String, c: StoreColumn): Option[String] =
        if (engine == Databricks)
            // Unity Catalog may not report a generated column as such, so a
            // column of that name already in the table is never written to.
            Some(", which is left as it is: on Databricks such a column is not loaded")
        else if (!holdsAnyText(engine, c))
            Some(
                " of type " + IngestColumnNames.display(String.valueOf(c.dataType)) + c.maxLength.map(l => "(" + l + ")").getOrElse("") +
                    ", which does not take every text value"
            )
        else if (!sameName(engine, loadName, c.name))
            Some(" spelled " + shown(c.name) + ", which differs in letter case from the column the load writes, " + shown(storedName(engine, loadName)))
        else if (c.nullable.isEmpty || c.generated.isEmpty) Some(" whose nullability or kind could not be read")
        else if (c.nullable.contains(false)) Some(" that is NOT NULL")
        else if (c.generated.contains(true)) Some(" whose values the database generates")
        else None

    /** Run `add` (an [[attempt]]) between `set` and `putBack`, the statements
      * that bound a session setting and put it back. `putBack` runs whatever
      * `add` did. When it fails, `onPutBackFailure` runs (the connection must
      * not be reused) and the load fails: with `add`'s own error, the
      * put-back's suppressed on it, when `add` failed; else with the
      * put-back's error, since the setting is then unknown for the rest of
      * the load. */
    def withSettingPutBack(set: => Unit, putBack: => Unit, onPutBackFailure: () => Unit)(add: => Option[Throwable]): Option[Throwable] = {
        set
        val outcome =
            try add
            catch {
                case NonFatal(e) =>
                    try putBack
                    catch {
                        case NonFatal(r) =>
                            onPutBackFailure()
                            e.addSuppressed(r)
                    }
                    throw e
            }
        try putBack
        catch {
            case NonFatal(r) =>
                onPutBackFailure()
                outcome.foreach { e =>
                    e.addSuppressed(r)
                    throw e
                }
                throw r
        }
        outcome
    }

    /** A catalog YES / NO as a Boolean; None for anything else. */
    def yesNo(s: String): Option[Boolean] =
        Option(s).map(_.trim.toUpperCase(java.util.Locale.ROOT)) match {
            case Some("YES") => Some(true)
            case Some("NO") => Some(false)
            case _ => None
        }

    /** `ctx` with the destination schema fields named in `keys` (lower-cased)
      * removed, for one loader: the load then leaves them out by name. The
      * rows and the other destinations are unchanged. */
    def withoutColumns(ctx: JobContext, keys: Set[String]): JobContext =
        if (keys.isEmpty) ctx
        else {
            val sp = ctx.config.destination.schemaProperties
            val kept = new java.util.ArrayList[SchemaField](sp.fields.asScala.filterNot(f => f != null && keys.contains(key(f.name))).asJava)
            ctx.copy(config = ctx.config.copy(destination = ctx.config.destination.copy(schemaProperties = sp.copy(fields = kept))))
        }

    /** Run `body` so that a failure leaves the connection usable: inside a
      * transaction (autocommit off) and with `savepoint`, behind a savepoint
      * that is rolled back on failure. Some(error) when `body` failed and the
      * connection can go on; throws `body`'s error when the rollback to the
      * savepoint fails. */
    def attempt(conn: java.sql.Connection, savepoint: Boolean)(body: => Unit): Option[Throwable] = {
        val sp = if (savepoint && !conn.getAutoCommit) conn.setSavepoint() else null
        try {
            body
            if (sp != null) Try(conn.releaseSavepoint(sp))
            None
        } catch {
            case NonFatal(e) =>
                if (sp != null)
                    try conn.rollback(sp)
                    catch {
                        case NonFatal(r) =>
                            e.addSuppressed(r)
                            throw e
                    }
                Some(e)
        }
    }

    /** [[attempt]] for a read: Right(value), or Left(error) when it failed
      * and the connection can go on. */
    def attemptRead[T](conn: java.sql.Connection, savepoint: Boolean)(body: => T): Either[Throwable, T] = {
        var value: Option[T] = None
        attempt(conn, savepoint)(value = Some(body)) match {
            case Some(e) => Left(e)
            case None => Right(value.get)
        }
    }
}
