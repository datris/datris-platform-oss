package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{CodeGenScript, CsvAttributes, DatrisEnvironment, DatrisException, PipelineConfig, SchemaField}
import org.slf4j.{Logger, LoggerFactory}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.collection.JavaConverters._

/** The CodeGen scripts (AI data-quality rule, AI transformation) a pipeline
  * runs. Generated when the pipeline is saved (from the source schema, no
  * row values), stored in the script store, and executed on every run, so an
  * unchanged instruction gives the same implementation on every run and a run
  * needs no model call.
  *
  * One rule at run time ([[forRun]]): execute the stored script when its
  * fingerprint (instruction + source schema signature) and contract version
  * match; otherwise generate from this run's data, store, then execute. A
  * delimited run whose header differs from the header the script was written
  * against generates a script for that run only and keeps the stored one.
  *
  * Store, record IO, the model call and the current-model lookup are
  * parameters so specs run without Mongo, MinIO or a model; production
  * defaults are on the companion object.
  */
class PipelineScripts(
    records: CodeGenScriptRecords,
    storeFor: String => CodeStore,
    ai: (String, String) => String,
    currentModel: () => String
) {
    import PipelineScripts._

    private val logger: Logger = LoggerFactory.getLogger(classOf[PipelineScripts])

    // ------------------------------------------------------------------ save

    /** Called after a pipeline config is written. For each AI kind the saved
      * config has: keep the stored script when its fingerprint still matches
      * (no model call), else generate from the schema and store it, or record
      * it pending with the reason. Kinds the config no longer has are removed.
      * Never throws. */
    def onSave(previous: PipelineConfig, saved: PipelineConfig, actor: String): List[Outcome] = {
        if (saved == null || saved.name == null) return Nil
        Kinds.flatMap { kind =>
            try {
                instructionOf(saved, kind) match {
                    case None =>
                        removeKind(saved.name, kind)
                        None
                    case Some(instruction) => Some(saveKind(saved, kind, instruction))
                }
            } catch {
                case e: Throwable if scala.util.control.NonFatal(e) =>
                    logger.warn("CodeGen script save hook failed for " + saved.name + "/" + kind + ": " + e.getMessage)
                    if (instructionOf(saved, kind).isDefined) Some(Outcome(kind, Pending, messageOf(e))) else None
            }
        }
    }

    private def saveKind(config: PipelineConfig, kind: String, instruction: String): Outcome = {
        val fp = fingerprint(instruction, schemaSignature(config))
        val existing = records.read(config.name, kind)
        existing match {
            case Some(r) if usable(r, fp, kind) =>
                Outcome(kind, Ready, generatedAt = r.generatedAt, model = r.model)
            case _ =>
                schemaFields(config) match {
                    case None =>
                        val reason =
                            if (isDocument(config))
                                "JSON and XML pipelines have no column schema to generate from; the first run generates the script from its data and stores it"
                            else "This source has no delimited schema to generate from at save; the first run generates the script from its data and stores it"
                        writePending(config, kind, instruction, fp, reason, existing)
                        Outcome(kind, Pending, reason)
                    case Some(fields) =>
                        try {
                            val rec = generateAndStore(config, kind, instruction, fp, fields, "save", existing)
                            Outcome(kind, Ready, generatedAt = rec.generatedAt, model = rec.model)
                        } catch {
                            case e: Throwable if scala.util.control.NonFatal(e) =>
                                val reason = messageOf(e)
                                logger.warn("CodeGen script generation at save failed for " + config.name + "/" + kind + ": " + reason)
                                writePending(config, kind, instruction, fp, reason, existing)
                                Outcome(kind, Pending, reason)
                        }
                }
        }
    }

    /** Generate from the schema, store, write the record; the previous object
      * is removed only once the new record is written. */
    private def generateAndStore(
        config: PipelineConfig,
        kind: String,
        instruction: String,
        fp: String,
        fields: List[SchemaField],
        origin: String,
        existing: Option[CodeGenScript]
    ): CodeGenScript = {
        val delimiter = CsvAttributes.delimiterOf(config)
        val script =
            if (kind == PipelineScripts.DataQuality) CodeGenRuleEvaluator.generateFromSchema(instruction, fields, delimiter, ai)
            else CodeGenTransformationEvaluator.generateFromSchema(instruction, fields, delimiter, ai)
        if (script == null || script.trim.isEmpty) throw new DatrisException("The model returned an empty script")
        storeRecord(config, kind, instruction, fp, script, fields.map(_.name), origin, existing)
    }

    private def storeRecord(
        config: PipelineConfig,
        kind: String,
        instruction: String,
        fp: String,
        script: String,
        generatedAgainst: List[String],
        origin: String,
        existing: Option[CodeGenScript]
    ): CodeGenScript = {
        val store = storeFor(config.name)
        val stored = store.storeScript(kind, script, existing.map(refOf).orNull, null)
        val rec = CodeGenScript(
            pipeline = config.name,
            kind = kind,
            instruction = instruction,
            script = null,
            generatedAt = java.time.Instant.now().toString,
            storage = stored.storage,
            scriptPath = stored.scriptPath,
            scriptRepoPath = stored.scriptRepoPath,
            scriptCommitSha = stored.scriptCommitSha,
            fingerprint = fp,
            generatedAgainst = if (generatedAgainst == null || generatedAgainst.isEmpty) null else new java.util.ArrayList[String](generatedAgainst.asJava),
            model = currentModel(),
            status = Ready,
            origin = origin,
            contractVersion = contractVersion(kind)
        )
        try records.write(rec)
        catch {
            case e: Throwable =>
                deleteObject(store, refOf(rec))
                throw e
        }
        existing.foreach(old => if (!sameObject(old, rec)) deleteObject(store, refOf(old)))
        rec
    }

    private def writePending(
        config: PipelineConfig,
        kind: String,
        instruction: String,
        fp: String,
        reason: String,
        existing: Option[CodeGenScript]
    ): CodeGenScript = {
        val rec = CodeGenScript(
            pipeline = config.name,
            kind = kind,
            instruction = instruction,
            script = null,
            fingerprint = fp,
            status = Pending,
            pendingReason = reason,
            origin = "save",
            contractVersion = contractVersion(kind)
        )
        records.write(rec)
        existing.foreach(old => deleteObject(storeFor(config.name), refOf(old)))
        rec
    }

    private def removeKind(pipeline: String, kind: String): Unit =
        records.read(pipeline, kind).foreach { old =>
            deleteObject(storeFor(pipeline), refOf(old))
            records.delete(pipeline, kind)
        }

    // ------------------------------------------------------------------- run

    /** Decide what a run executes. `runHeader` is the delimited header
      * reaching the stage (Nil for JSON/XML); `generate` builds a script from
      * this run's data and is called only when the stored one cannot be used.
      * Generation failures propagate (the run fails, as before); a failure to
      * store a script generated at run is logged and the run continues. */
    def forRun(config: PipelineConfig, kind: String, runHeader: List[String], generate: () => String): Resolved = {
        val instruction = instructionOf(config, kind).getOrElse(throw new DatrisException("Pipeline " + config.name + " has no AI " + kind + " instruction"))
        val fp = fingerprint(instruction, schemaSignature(config))
        val existing = records.read(config.name, kind)
        val header = Option(runHeader).getOrElse(Nil)

        val reason: String = existing match {
            case None => "no stored script for this pipeline yet"
            case Some(r) if r.status == Pending => "the script is pending" + Option(r.pendingReason).map(": " + _).getOrElse("")
            case Some(r) if r.status != Ready || r.fingerprint == null => "the stored script predates stored CodeGen scripts"
            case Some(r) if r.fingerprint != fp => "the instruction or source schema changed since the script was generated"
            case Some(r) if r.contractVersion != contractVersion(kind) => "the script contract changed in this release"
            case Some(r) =>
                val against = Option(r.generatedAgainst).map(_.asScala.toList).getOrElse(Nil)
                if (against.nonEmpty && header.nonEmpty && normalize(against) != normalize(header)) {
                    val script = generate()
                    val transient = r.copy(
                        generatedAt = java.time.Instant.now().toString,
                        generatedAgainst = new java.util.ArrayList[String](header.asJava),
                        model = currentModel(),
                        origin = "run"
                    )
                    return Resolved(
                        GenerateOnce,
                        script,
                        "the header reaching this stage (" + header.mkString(", ") + ") differs from the header the stored script was written against (" +
                            against.mkString(", ") + ")",
                        transient
                    )
                }
                readStored(r) match {
                    case Some(text) => return Resolved(Stored, text, null, r)
                    case None => "the stored script could not be read"
                }
        }

        val script = generate()
        val rec =
            try storeRecord(config, kind, instruction, fp, script, header, "run", existing)
            catch {
                case e: Throwable if scala.util.control.NonFatal(e) =>
                    logger.warn("Could not store the CodeGen script generated at run for " + config.name + "/" + kind + ": " + messageOf(e))
                    CodeGenScript(
                        config.name,
                        kind,
                        instruction,
                        null,
                        java.time.Instant.now().toString,
                        fingerprint = fp,
                        model = currentModel(),
                        status = Ready,
                        origin = "run",
                        contractVersion = contractVersion(kind)
                    )
            }
        Resolved(GenerateAndStore, script, reason, rec)
    }

    // ------------------------------------------------------------ regenerate

    /** Force a new script. Delimited: generate from the schema now and replace
      * the stored script (on failure the error is returned and the current
      * script stays). JSON/XML: mark it pending so the next run generates. */
    def regenerate(config: PipelineConfig, kind: String, actor: String): Either[String, CodeGenScript] = {
        if (!Kinds.contains(kind)) return Left(unknownKindMessage(kind))
        val instruction = instructionOf(config, kind) match {
            case Some(i) => i
            case None =>
                return Left("Pipeline " + config.name + " has no AI " + (if (kind == PipelineScripts.DataQuality) "data-quality rule" else "transformation"))
        }
        val fp = fingerprint(instruction, schemaSignature(config))
        val existing = records.read(config.name, kind)
        try {
            schemaFields(config) match {
                case Some(fields) => Right(generateAndStore(config, kind, instruction, fp, fields, "regenerate", existing))
                case None =>
                    val rec = writePending(
                        config,
                        kind,
                        instruction,
                        fp,
                        "Regenerate requested; this pipeline has no delimited schema to generate from, so the next run generates the script from its data",
                        existing
                    ).copy(origin = "regenerate")
                    records.write(rec)
                    Right(rec)
            }
        } catch {
            case e: Throwable if scala.util.control.NonFatal(e) => Left(messageOf(e))
        }
    }

    // ------------------------------------------------------------------ read

    /** The record for `pipeline|kind`, if any. */
    def record(pipeline: String, kind: String): Option[CodeGenScript] = records.read(pipeline, kind)

    /** The script text: from the script store, or from a legacy record's
      * `script` field. An unreadable script is absent. */
    def readText(pipeline: String, kind: String): Option[String] =
        records.read(pipeline, kind).flatMap(readStored)

    private def readStored(r: CodeGenScript): Option[String] = {
        val hasRef = (r.scriptPath != null && r.scriptPath.nonEmpty) || (r.scriptRepoPath != null && r.scriptRepoPath.nonEmpty)
        if (hasRef)
            try storeFor(r.pipeline).readScript(refOf(r))
            catch {
                case e: Exception =>
                    logger.warn("CodeGen script unreadable for " + r.pipeline + "/" + r.kind + ": " + e.getMessage)
                    None
            }
        else Option(r.script).filter(_.nonEmpty)
    }

    /** The CodeGen model setting right now. */
    def modelNow: String =
        try currentModel()
        catch { case _: Exception => null }

    // ---------------------------------------------------------------- delete

    /** Remove both kinds' objects and records. Best effort, never throws. */
    def deleteAll(pipeline: String): Unit =
        Kinds.foreach { kind =>
            try removeKind(pipeline, kind)
            catch { case e: Exception => logger.warn("CodeGen script cleanup failed for " + pipeline + "/" + kind + ": " + e.getMessage) }
        }

    // --------------------------------------------------------------- helpers

    private def usable(r: CodeGenScript, fp: String, kind: String): Boolean =
        r.status == Ready && r.fingerprint == fp && r.contractVersion == contractVersion(kind)

    private def deleteObject(store: CodeStore, ref: ScriptRef): Unit =
        if (ref != null && (ref.scriptPath != null || ref.scriptRepoPath != null))
            try store.deleteScript(ref)
            catch { case e: Exception => logger.warn("Could not delete CodeGen script object " + ref + ": " + e.getMessage) }

    private def sameObject(a: CodeGenScript, b: CodeGenScript): Boolean =
        a.storage == b.storage && a.scriptPath == b.scriptPath && a.scriptRepoPath == b.scriptRepoPath

    private def normalize(h: List[String]): List[String] = h.map(x => String.valueOf(x).trim.toLowerCase)

    private def messageOf(e: Throwable): String = Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)
}

object PipelineScripts
    extends PipelineScripts(
        CodeGenScriptIO,
        (pipeline: String) => MinioCodeStore.forPipeline(pipeline),
        (system: String, user: String) => {
            if (!DatrisEnvironment.current.aiEnabled) throw new DatrisException("AI is not enabled on this server")
            CodeGenRuleEvaluator.codegenAI(system, user)
        },
        () => scala.util.Try(DatrisEnvironment.aiConfigForCodegen.model).getOrElse(null)
    ) {

    val DataQuality = "dataQuality"
    val Transformation = "transformation"
    val Kinds: List[String] = List(DataQuality, Transformation)

    val Ready = "ready"
    val Pending = "pending"

    val Stored = "stored"
    val GenerateAndStore = "generate-and-store"
    val GenerateOnce = "generate-once"

    /** Per-kind script contract (arguments and output shape). A release that
      * changes it bumps the number and the next run regenerates. Prompt
      * wording changes alone do not. */
    def contractVersion(kind: String): Int = kind match {
        case DataQuality => 1
        case Transformation => 1
        case _ => 0
    }

    case class Outcome(kind: String, status: String, pendingReason: String = null, generatedAt: String = null, model: String = null)

    /** action: "stored" | "generate-and-store" | "generate-once". */
    case class Resolved(action: String, script: String, reason: String, record: CodeGenScript)

    def unknownKindMessage(kind: String): String =
        "Unknown CodeGen script kind '" + kind + "'; expected one of: " + Kinds.mkString(", ")

    def regeneratePath(pipeline: String, kind: String): String =
        "/api/v1/pipelines/" + pipeline + "/codegen-scripts/" + kind + "/regenerate"

    /** sha-256 of the instruction and the schema signature. */
    def fingerprint(instruction: String, schemaSignature: String): String = {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest((Option(instruction).getOrElse("") + "\u0000" + Option(schemaSignature).getOrElse("")).getBytes(StandardCharsets.UTF_8))
        bytes.map("%02x".format(_)).mkString
    }

    /** Format, delimiter and ordered field names and types. */
    def schemaSignature(config: PipelineConfig): String = {
        val fields = allFields(config).map(f => f.name + ":" + Option(f.`type`).getOrElse("string").toLowerCase)
        "format=" + formatOf(config) + "|delimiter=" + CsvAttributes.delimiterOf(config) + "|fields=" + fields.mkString(",")
    }

    def instructionOf(config: PipelineConfig, kind: String): Option[String] = kind match {
        case DataQuality =>
            Option(config.dataQuality).flatMap(d => Option(d.aiRule)).flatMap(r => Option(r.instruction)).filter(_.trim.nonEmpty)
        case Transformation =>
            Option(config.transformation).flatMap(t => Option(t.aiTransformation)).flatMap(t => Option(t.instruction)).filter(_.trim.nonEmpty)
        case _ => None
    }

    private def formatOf(config: PipelineConfig): String = {
        val fa = if (config.source != null) config.source.fileAttributes else null
        if (fa == null) "other"
        else if (fa.csvAttributes != null) "csv"
        else if (fa.jsonAttributes != null) "json"
        else if (fa.xmlAttributes != null) "xml"
        else "other"
    }

    private[util] def isDocument(config: PipelineConfig): Boolean = Set("json", "xml").contains(formatOf(config))

    private def allFields(config: PipelineConfig): List[SchemaField] =
        if (config.source == null || config.source.schemaProperties == null || config.source.schemaProperties.fields == null) Nil
        else config.source.schemaProperties.fields.asScala.toList.filter(f => f != null && f.name != null && f.name.nonEmpty)

    /** The delimited schema to generate from at save, or None (JSON/XML,
      * other sources, or no fields). */
    private[util] def schemaFields(config: PipelineConfig): Option[List[SchemaField]] = {
        val fields = allFields(config)
        if (formatOf(config) != "csv" || fields.isEmpty || fields.exists(f => f.name == "_json" || f.name == "_xml")) None
        else Some(fields)
    }

    private[util] def refOf(r: CodeGenScript): ScriptRef =
        if (r == null) null else ScriptRef(r.kind, r.storage, r.scriptPath, r.scriptRepoPath, r.scriptCommitSha)

    /** The run's status line. */
    def statusLine(r: Resolved): String = r.action match {
        case Stored => "Running stored CodeGen script generated at " + r.record.generatedAt
        case GenerateOnce => "Generated CodeGen script for this run only (reason: " + r.reason + ")"
        case _ => "Generated CodeGen script (reason: " + r.reason + "); stored for later runs"
    }

    /** Run a resolved script. A stored script that fails is not regenerated:
      * the run fails with a message naming when it was generated and how to
      * replace it. */
    def runResolved[A](r: Resolved, pipeline: String, kind: String)(run: String => A): A =
        if (r.action != Stored) run(r.script)
        else
            try run(r.script)
            catch {
                case e: Throwable if scala.util.control.NonFatal(e) =>
                    throw new DatrisException(
                        "The stored CodeGen " + (if (kind == DataQuality) "data quality" else "transformation") + " script (generated at " +
                            r.record.generatedAt + ") failed: " + Option(e.getMessage).getOrElse(e.getClass.getSimpleName) +
                            ". It is not regenerated automatically; change the instruction, or call POST " + regeneratePath(pipeline, kind) +
                            " to replace it."
                    )
            }
}
