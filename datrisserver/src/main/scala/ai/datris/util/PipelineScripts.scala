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
  * Two backends: the built-in object store (`storeFor`) and the code
  * repository (`repoStoreFor`). A new script goes to `defaultStorage()`
  * (the repository when one is enabled); an existing script keeps its
  * backend until a forced regenerate asks for another. A repository-backed
  * script is pinned to the commit recorded on its record; a commit whose base
  * moved in the repository (a hand edit) is rejected and reported as a
  * warning, never overwritten silently, and [[pull]] adopts the head version.
  *
  * Store, record IO, the model call and the current-model lookup are
  * parameters so specs run without Mongo, MinIO, GitHub or a model;
  * production defaults are on the companion object.
  */
class PipelineScripts(
    records: CodeGenScriptRecords,
    storeFor: String => CodeStore,
    ai: (String, String) => String,
    currentModel: () => String,
    repoStoreFor: String => CodeStore = null,
    defaultStorage: () => String = () => "minio"
) {
    import PipelineScripts._

    private val logger: Logger = LoggerFactory.getLogger(classOf[PipelineScripts])

    // ------------------------------------------------------------------ save

    /** Called after a pipeline config is written. For each AI kind the saved
      * config has: keep the stored script when its fingerprint still matches
      * (no model call), else generate from the schema and store it, or record
      * it pending with the reason. Kinds the config no longer has are removed.
      * `actor` is the saving key's label (the commit's `{user}`). Never
      * throws. */
    def onSave(previous: PipelineConfig, saved: PipelineConfig, actor: String): List[Outcome] = {
        if (saved == null || saved.name == null) return Nil
        Kinds.flatMap { kind =>
            try {
                instructionOf(saved, kind) match {
                    case None =>
                        removeKind(saved.name, kind)
                        None
                    case Some(instruction) => Some(saveKind(saved, kind, instruction, actor))
                }
            } catch {
                case e: Throwable if scala.util.control.NonFatal(e) =>
                    logger.warn("CodeGen script save hook failed for " + saved.name + "/" + kind + ": " + e.getMessage)
                    if (instructionOf(saved, kind).isDefined) Some(Outcome(kind, Pending, messageOf(e))) else None
            }
        }
    }

    private def saveKind(config: PipelineConfig, kind: String, instruction: String, actor: String): Outcome = {
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
                            val rec = generateAndStore(config, kind, instruction, fp, fields, "save", existing, actor)
                            Outcome(kind, Ready, generatedAt = rec.generatedAt, model = rec.model)
                        } catch {
                            case _: CodeRepoConflictException =>
                                // A hand edit in the repository: leave the record and the
                                // file as they are, and say how to resolve it.
                                val warning = conflictWarning(config.name, kind, existing.orNull)
                                logger.warn("CodeGen script for " + config.name + "/" + kind + " not committed: " + warning)
                                val r = existing.orNull
                                Outcome(
                                    kind,
                                    Option(r).flatMap(x => Option(x.status)).getOrElse(Ready),
                                    Option(r).map(_.pendingReason).orNull,
                                    Option(r).map(_.generatedAt).orNull,
                                    Option(r).map(_.model).orNull,
                                    warning
                                )
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
        existing: Option[CodeGenScript],
        actor: String,
        storage: String = null,
        overwrite: Boolean = false
    ): CodeGenScript = {
        val delimiter = CsvAttributes.delimiterOf(config)
        val script =
            if (kind == PipelineScripts.DataQuality) CodeGenRuleEvaluator.generateFromSchema(instruction, fields, delimiter, ai)
            else CodeGenTransformationEvaluator.generateFromSchema(instruction, fields, delimiter, ai)
        if (script == null || script.trim.isEmpty) throw new DatrisException("The model returned an empty script")
        storeRecord(config, kind, instruction, fp, script, fields.map(_.name), origin, existing, actor, storage, overwrite)
    }

    /** Store the script in the chosen backend and write the record. Within one
      * backend the previous object is replaced (built-in) or the same file
      * gets a new commit whose base is the recorded commit (repository); a
      * move to another backend leaves the old copy in place, as tap moves do. */
    private def storeRecord(
        config: PipelineConfig,
        kind: String,
        instruction: String,
        fp: String,
        script: String,
        generatedAgainst: List[String],
        origin: String,
        existing: Option[CodeGenScript],
        actor: String,
        storage: String = null,
        overwrite: Boolean = false
    ): CodeGenScript = {
        val backend = chooseBackend(existing, storage)
        val store = storeOf(config.name, backend)
        val sameBackendPrior = existing.filter(r => backendOf(r) == backend)
        val prior = sameBackendPrior.map(refOf).map(ref => if (overwrite) ref.copy(scriptCommitSha = null) else ref).orNull
        val stored = store.storeScript(kind, script, prior, actor)
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
            case e: Throwable if backend == Github =>
                // The commit stays (history) and the record keeps the old pin;
                // name the new commit so it can be adopted with pull.
                throw new DatrisException(
                    "The " + kind + " script was committed to '" + rec.scriptRepoPath + "' at " + rec.scriptCommitSha +
                        " but its record could not be written (" + messageOf(e) + "); adopt that commit with POST " +
                        pullPath(config.name, kind)
                )
            case e: Throwable =>
                // A built-in object nobody points at is removed.
                deleteObject(rec)
                throw e
        }
        sameBackendPrior.foreach(old => if (backend != Github && !sameObject(old, rec)) deleteObject(old))
        rec
    }

    /** Record the script pending. A built-in object is removed as before; a
      * repository file is kept (no delete commit) and its pin stays on the
      * record as the base of the next commit. The backend sticks: an existing
      * record's, or the explicitly requested one. */
    private def writePending(
        config: PipelineConfig,
        kind: String,
        instruction: String,
        fp: String,
        reason: String,
        existing: Option[CodeGenScript],
        origin: String = "save",
        storage: String = null
    ): CodeGenScript = {
        val requested = normalizeStorage(storage)
        val target: String = requested.orElse(existing.flatMap(r => Option(r.storage)).filter(_.nonEmpty)).orNull
        val keepRepoRef = target == Github && existing.exists(r => backendOf(r) == Github && r.scriptRepoPath != null)
        val rec = CodeGenScript(
            pipeline = config.name,
            kind = kind,
            instruction = instruction,
            script = null,
            storage = target,
            scriptRepoPath = if (keepRepoRef) existing.get.scriptRepoPath else null,
            scriptCommitSha = if (keepRepoRef) existing.get.scriptCommitSha else null,
            fingerprint = fp,
            status = Pending,
            pendingReason = reason,
            origin = origin,
            contractVersion = contractVersion(kind)
        )
        records.write(rec)
        existing.foreach(old => if (backendOf(old) != Github && (target == null || target != Github)) deleteObject(old))
        rec
    }

    private def removeKind(pipeline: String, kind: String): Unit =
        records.read(pipeline, kind).foreach { old =>
            deleteObject(old)
            records.delete(pipeline, kind)
        }

    // ------------------------------------------------------------------- run

    /** Decide what a run executes. `runHeader` is the delimited header
      * reaching the stage (Nil for JSON/XML); `generate` builds a script from
      * this run's data and is called only when the stored one cannot be used.
      * Generation failures propagate (the run fails, as before); a failure to
      * store a script generated at run is logged and the run continues with
      * the generated script, which stays pending. A repository-backed script
      * whose recorded commit cannot be read fails the run (no fallback to
      * generating). */
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
                readStoredForRun(r) match {
                    case Some(text) => return Resolved(Stored, text, null, r)
                    case None => "the stored script could not be read"
                }
        }

        val script = generate()
        val rec =
            try storeRecord(config, kind, instruction, fp, script, header, "run", existing, null)
            catch {
                case e: Throwable if scala.util.control.NonFatal(e) =>
                    val why = messageOf(e)
                    logger.warn("Could not store the CodeGen script generated at run for " + config.name + "/" + kind + ": " + why)
                    // Not written: the stored record (pending, or the previous
                    // script) is unchanged and the next run generates again.
                    CodeGenScript(
                        config.name,
                        kind,
                        instruction,
                        null,
                        java.time.Instant.now().toString,
                        fingerprint = fp,
                        model = currentModel(),
                        status = Pending,
                        pendingReason = "the script could not be stored: " + why,
                        origin = "run",
                        contractVersion = contractVersion(kind)
                    )
            }
        Resolved(GenerateAndStore, script, reason, rec)
    }

    // ------------------------------------------------------------ regenerate

    /** Force a new script. Delimited: generate from the schema now and replace
      * the stored script (on failure the error is returned and the current
      * script stays). JSON/XML: mark it pending so the next run generates.
      * `storage` ("github" | "builtin"; null keeps the current backend) is the
      * only way to move a script; `overwrite` replaces a repository file that
      * changed since the recorded commit instead of rejecting the commit. */
    def regenerate(
        config: PipelineConfig,
        kind: String,
        actor: String,
        storage: String = null,
        overwrite: Boolean = false
    ): Either[String, CodeGenScript] = {
        if (!Kinds.contains(kind)) return Left(unknownKindMessage(kind))
        if (storage != null && storage.nonEmpty && normalizeStorage(storage).isEmpty) return Left(unknownStorageMessage(storage))
        val instruction = instructionOf(config, kind) match {
            case Some(i) => i
            case None =>
                return Left("Pipeline " + config.name + " has no AI " + (if (kind == PipelineScripts.DataQuality) "data-quality rule" else "transformation"))
        }
        val fp = fingerprint(instruction, schemaSignature(config))
        val existing = records.read(config.name, kind)
        try {
            schemaFields(config) match {
                case Some(fields) =>
                    Right(generateAndStore(config, kind, instruction, fp, fields, "regenerate", existing, actor, storage, overwrite))
                case None =>
                    val rec = writePending(
                        config,
                        kind,
                        instruction,
                        fp,
                        "Regenerate requested; this pipeline has no delimited schema to generate from, so the next run generates the script from its data",
                        existing,
                        origin = "regenerate",
                        storage = storage
                    )
                    Right(rec)
            }
        } catch {
            case _: CodeRepoConflictException => Left(conflictWarning(config.name, kind, existing.orNull))
            case e: Throwable if scala.util.control.NonFatal(e) => Left(messageOf(e))
        }
    }

    // ------------------------------------------------------- drift and pull

    /** Some(headSha) when the script's file at branch head differs from the
      * recorded commit's; None for a built-in script, no drift, or an
      * unreachable repository (logged). */
    def drift(pipeline: String, kind: String): Option[String] =
        records.read(pipeline, kind).filter(isRepoBacked).flatMap { r =>
            try storeOf(pipeline, Github).driftHead(refOf(r))
            catch {
                case e: Throwable if scala.util.control.NonFatal(e) =>
                    logger.warn("CodeGen script drift check failed for " + pipeline + "/" + kind + ": " + messageOf(e))
                    None
            }
        }

    /** Adopt the branch-head version of a repository-backed script: the record
      * pins the head commit, origin "repository", ready. No commit is made.
      * With `config` (the pipeline as it is now) the record's fingerprint
      * becomes the current instruction and schema's, so a pull made to
      * resolve a save-time conflict is not regenerated over by the next run
      * or save; later saves keep the adopted edit while the instruction and
      * schema stay unchanged. Without `config` the recorded fingerprint is
      * kept. Left for a built-in script. */
    def pull(pipeline: String, kind: String, actor: String, config: PipelineConfig = null): Either[String, CodeGenScript] = {
        if (!Kinds.contains(kind)) return Left(unknownKindMessage(kind))
        val current: Option[String] =
            if (config == null) None
            else
                instructionOf(config, kind) match {
                    case Some(i) => Some(fingerprint(i, schemaSignature(config)))
                    case None =>
                        return Left("Pipeline " + pipeline + " has no AI " + (if (kind == PipelineScripts.DataQuality) "data-quality rule"
                                                                              else "transformation"))
                }
        records.read(pipeline, kind) match {
            case None => Left("Pipeline " + pipeline + " has no stored " + kind + " script")
            case Some(r) if !isRepoBacked(r) => Left(notRepositoryMessage(pipeline, kind))
            case Some(r) =>
                try {
                    storeOf(pipeline, Github).pullLatest(refOf(r)) match {
                        case None => Left("'" + r.scriptRepoPath + "' is not on the configured branch of the code repository; there is nothing to pull")
                        case Some((content, _)) if content == null || content.trim.isEmpty =>
                            Left("'" + r.scriptRepoPath + "' is empty at the head of the configured branch; there is nothing to pull")
                        case Some((_, headSha)) =>
                            val base = r.copy(
                                scriptCommitSha = headSha,
                                generatedAt = java.time.Instant.now().toString,
                                status = Ready,
                                pendingReason = null,
                                origin = "repository"
                            )
                            val rec = current match {
                                case Some(fp) => base.copy(fingerprint = fp, contractVersion = contractVersion(kind), generatedAgainst = null)
                                case None => base
                            }
                            records.write(rec)
                            logger.info(
                                "CodeGen script " + pipeline + "/" + kind + " pinned to repository commit " + headSha +
                                    Option(actor).map(" by " + _).getOrElse("")
                            )
                            Right(rec)
                    }
                } catch {
                    case e: Throwable if scala.util.control.NonFatal(e) => Left(messageOf(e))
                }
        }
    }

    // ------------------------------------------------------------------ read

    /** The record for `pipeline|kind`, if any. */
    def record(pipeline: String, kind: String): Option[CodeGenScript] = records.read(pipeline, kind)

    /** The script text: from the script store, or from a legacy record's
      * `script` field. A pending or unreadable script is absent. */
    def readText(pipeline: String, kind: String): Option[String] =
        records.read(pipeline, kind).filter(_.status != Pending).flatMap(readStored)

    private def hasRef(r: CodeGenScript): Boolean =
        (r.scriptPath != null && r.scriptPath.nonEmpty) || (r.scriptRepoPath != null && r.scriptRepoPath.nonEmpty)

    private def readStored(r: CodeGenScript): Option[String] =
        if (hasRef(r))
            try storeOf(r.pipeline, backendOf(r)).readScript(refOf(r))
            catch {
                case e: Exception =>
                    logger.warn("CodeGen script unreadable for " + r.pipeline + "/" + r.kind + ": " + e.getMessage)
                    None
            }
        else Option(r.script).filter(_.nonEmpty)

    /** As [[readStored]], except that a repository read failure (unreachable,
      * token expired, repository disabled) propagates: a run must not fall
      * back to generating a different script. */
    private def readStoredForRun(r: CodeGenScript): Option[String] =
        if (backendOf(r) == Github && hasRef(r)) storeOf(r.pipeline, Github).readScript(refOf(r))
        else readStored(r)

    /** The CodeGen model setting right now. */
    def modelNow: String =
        try currentModel()
        catch { case _: Exception => null }

    // ---------------------------------------------------------------- delete

    /** Remove both kinds' objects (a delete commit per file for repository
      * scripts; history is kept) and records. Best effort, never throws. */
    def deleteAll(pipeline: String): Unit =
        Kinds.foreach { kind =>
            try removeKind(pipeline, kind)
            catch { case e: Exception => logger.warn("CodeGen script cleanup failed for " + pipeline + "/" + kind + ": " + e.getMessage) }
        }

    // --------------------------------------------------------------- helpers

    private def usable(r: CodeGenScript, fp: String, kind: String): Boolean =
        r.status == Ready && r.fingerprint == fp && r.contractVersion == contractVersion(kind)

    /** Backend for a write: an explicit request, else the existing record's
      * backend, else the install default. */
    private def chooseBackend(existing: Option[CodeGenScript], requested: String): String =
        normalizeStorage(requested).getOrElse {
            existing.flatMap(r => Option(r.storage)).filter(_.nonEmpty).map(s => if (s == Github) Github else Builtin).getOrElse {
                if (Option(defaultStorage()).contains(Github) && repoStoreFor != null) Github else Builtin
            }
        }

    private def storeOf(pipeline: String, backend: String): CodeStore =
        if (backend == Github) {
            if (repoStoreFor == null) throw new DatrisException("No code repository store is available for pipeline scripts")
            repoStoreFor(pipeline)
        } else storeFor(pipeline)

    private def deleteObject(r: CodeGenScript): Unit =
        if (r != null && hasRef(r))
            try storeOf(r.pipeline, backendOf(r)).deleteScript(refOf(r))
            catch { case e: Exception => logger.warn("Could not delete CodeGen script object " + refOf(r) + ": " + e.getMessage) }

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
        () => scala.util.Try(DatrisEnvironment.aiConfigForCodegen.model).getOrElse(null),
        (pipeline: String) => GithubCodeStore.forPipeline(pipeline),
        () => if (CodeRepoConfigIO.readEnabled().isDefined) "github" else "minio"
    ) {

    val DataQuality = "dataQuality"
    val Transformation = "transformation"
    val Kinds: List[String] = List(DataQuality, Transformation)

    val Ready = "ready"
    val Pending = "pending"

    /** Storage values on a record. */
    val Github = "github"
    val Builtin = "minio"

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

    /** `warning` is set when a repository commit was rejected because the
      * file changed in the repository since the recorded commit. */
    case class Outcome(
        kind: String,
        status: String,
        pendingReason: String = null,
        generatedAt: String = null,
        model: String = null,
        warning: String = null
    )

    /** action: "stored" | "generate-and-store" | "generate-once". */
    case class Resolved(action: String, script: String, reason: String, record: CodeGenScript)

    def unknownKindMessage(kind: String): String =
        "Unknown CodeGen script kind '" + kind + "'; expected one of: " + Kinds.mkString(", ")

    def regeneratePath(pipeline: String, kind: String): String =
        "/api/v1/pipelines/" + pipeline + "/codegen-scripts/" + kind + "/regenerate"

    def pullPath(pipeline: String, kind: String): String =
        "/api/v1/pipelines/" + pipeline + "/codegen-scripts/" + kind + "/pull"

    /** "github" for "github"; "minio" for "builtin" / "minio"; None for null,
      * empty or anything else. */
    def normalizeStorage(storage: String): Option[String] = storage match {
        case "github" => Some(Github)
        case "builtin" | "minio" => Some(Builtin)
        case _ => None
    }

    def unknownStorageMessage(storage: String): String =
        "Unknown script storage '" + storage + "'; expected github or builtin"

    def notRepositoryMessage(pipeline: String, kind: String): String =
        "The " + kind + " script for pipeline " + pipeline +
            " is not stored in the code repository, so there is nothing to pull. Regenerate it with storage=github to move it there."

    /** Text that marks a rejected repository commit (the API maps it to 409). */
    val ConflictMarker = "changed in the code repository since the recorded commit"

    /** The warning for a commit rejected because the file was edited in the
      * repository: names pull and regenerate with overwrite=true. */
    def conflictWarning(pipeline: String, kind: String, existing: CodeGenScript): String = {
        val path = Option(existing).flatMap(r => Option(r.scriptRepoPath)).getOrElse("the script file")
        val sha = Option(existing).flatMap(r => Option(r.scriptCommitSha)).map(" " + _.take(7)).getOrElse("")
        "The CodeGen " + kind + " script for pipeline " + pipeline + " was not replaced: '" + path + "' " + ConflictMarker + sha +
            ", and a hand edit is never overwritten. Runs keep using the recorded commit. Adopt the repository version with POST " +
            pullPath(pipeline, kind) + " (pull), or replace it with POST " + regeneratePath(pipeline, kind) + "?overwrite=true."
    }

    private[util] def backendOf(r: CodeGenScript): String =
        if (r != null && r.storage == Github) Github else Builtin

    def isRepoBacked(r: CodeGenScript): Boolean =
        r != null && r.storage == Github && r.scriptRepoPath != null && r.scriptRepoPath.nonEmpty

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
        case Stored => "Running stored CodeGen script generated at " + r.record.generatedAt + commitNote(r.record)
        case GenerateOnce => "Generated CodeGen script for this run only (reason: " + r.reason + ")"
        case _ if r.record != null && r.record.status == Pending =>
            "Generated CodeGen script (reason: " + r.reason + "); " + Option(r.record.pendingReason).getOrElse("it could not be stored") +
                ", so it stays pending and the next run generates again"
        case _ => "Generated CodeGen script (reason: " + r.reason + "); stored for later runs" + commitNote(r.record)
    }

    /** " (code repository commit abc1234)" for a repository-backed record. */
    private def commitNote(r: CodeGenScript): String =
        if (r != null && r.storage == Github && r.scriptCommitSha != null && r.scriptCommitSha.nonEmpty)
            " (code repository commit " + r.scriptCommitSha.take(7) + ")"
        else ""

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
