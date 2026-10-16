package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{CodeGenScript, CodeGenScriptVersion, CsvAttributes, DatrisEnvironment, DatrisException, PipelineConfig, SchemaField}
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
  * Every change to the live script (save, run-time store, regenerate,
  * restore of a version, catalog import, pull) appends a numbered version
  * holding the text to `versions` (`<env>-codegen-script-version`); the live
  * record's `scriptVersion` names it. History failures are warnings and
  * never fail the change itself.
  *
  * Store, record IO, history IO, the model call and the current-model lookup
  * are parameters so specs run without Mongo, MinIO, GitHub or a model;
  * production defaults are on the companion object. A spec that injects its
  * own records but not `versions` gets no history (the production history
  * store is used only with the production record store).
  */
class PipelineScripts(
    records: CodeGenScriptRecords,
    storeFor: String => CodeStore,
    ai: (String, String) => String,
    currentModel: () => String,
    repoStoreFor: String => CodeStore = null,
    defaultStorage: () => String = () => "minio",
    versions: CodeGenScriptVersions = CodeGenScriptVersionIO
) {
    import PipelineScripts._

    private val logger: Logger = LoggerFactory.getLogger(classOf[PipelineScripts])

    /** The script history, or None when a caller injected in-memory records
      * but left `versions` at the Mongo default (history rows would describe
      * records that are not the production ones, and the spec would need
      * Mongo). */
    private val history: Option[CodeGenScriptVersions] =
        if (versions == null) None
        else if ((versions eq CodeGenScriptVersionIO) && !(records eq CodeGenScriptIO)) None
        else Some(versions)

    // ------------------------------------------------------------------ save

    /** Called after a pipeline config is written. For each AI kind the saved
      * config has: keep the stored script when its fingerprint still matches
      * (no model call), else generate from the schema and store it, or record
      * it pending with the reason. Kinds the config no longer has are removed.
      * `actor` is the saving key's label (the commit's `{user}`). Never
      * throws. */
    def onSave(previous: PipelineConfig, saved: PipelineConfig, actor: String): List[Outcome] =
        onSaveResult(previous, saved, actor).outcomes

    /** [[onSave]] plus the warnings for kinds the config no longer has whose
      * repository file was kept (hand-edited at head since the recorded
      * commit, or the repository could not be checked). The record is removed
      * either way. Never throws. */
    def onSaveResult(previous: PipelineConfig, saved: PipelineConfig, actor: String): SaveResult = {
        if (saved == null || saved.name == null) return SaveResult(Nil, Nil)
        val warnings = scala.collection.mutable.ListBuffer[String]()
        val outcomes = Kinds.flatMap { kind =>
            try {
                instructionOf(saved, kind) match {
                    case None =>
                        removeKind(saved.name, kind, removedBySave = true).foreach { w =>
                            logger.warn("Pipeline " + saved.name + "/" + kind + ": " + w)
                            warnings += w
                        }
                        None
                    case Some(instruction) => Some(saveKind(saved, kind, instruction, actor))
                }
            } catch {
                case e: Throwable if scala.util.control.NonFatal(e) =>
                    logger.warn("CodeGen script save hook failed for " + saved.name + "/" + kind + ": " + e.getMessage)
                    if (instructionOf(saved, kind).isDefined) Some(Outcome(kind, Pending, messageOf(e))) else None
            }
        }
        SaveResult(outcomes, warnings.toList)
    }

    private def saveKind(config: PipelineConfig, kind: String, instruction: String, actor: String): Outcome = {
        val fp = fingerprint(instruction, schemaSignature(config))
        val existing = clearStaleConflict(records.read(config.name, kind), fp)
        existing match {
            case Some(r) if usable(r, fp, kind) =>
                Outcome(kind, Ready, generatedAt = r.generatedAt, model = r.model)
            case Some(r) if inConflict(r, fp) =>
                // Still the conflicted instruction/schema: nothing new to try.
                Outcome(kind, Option(r.status).getOrElse(Ready), r.pendingReason, r.generatedAt, r.model, r.conflictReason)
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
                                existing.foreach(markConflict(_, fp, warning))
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
        overwrite: Boolean = false,
        model: String = null,
        note: String = null
    ): CodeGenScript = {
        val plan = planVersion(config.name, kind, existing)
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
            model = if (model != null) model else currentModel(),
            status = Ready,
            origin = origin,
            contractVersion = contractVersion(kind),
            scriptVersion = plan.map(_.number).getOrElse(0)
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
        commitVersion(plan, rec, script, actor, note)
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

    /** Remove one kind's object (unless its repository file was hand-edited)
      * and its record; the warning when the file was kept. `removedBySave`
      * picks the save wording (the instruction was dropped) over the delete
      * wording (the pipeline was deleted). */
    private def removeKind(pipeline: String, kind: String, removedBySave: Boolean = false): Option[String] =
        records.read(pipeline, kind).flatMap { old =>
            val warning = deleteObject(old, removedBySave)
            records.delete(pipeline, kind)
            warning
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
        val rehearsal = PipelineScripts.rehearsing
        // A scratch rehearsal writes nothing: no conflict clearing, no store.
        val existing = if (rehearsal) records.read(config.name, kind) else clearStaleConflict(records.read(config.name, kind), fp)
        val header = Option(runHeader).getOrElse(Nil)

        val reason: String = existing match {
            case None => "no stored script for this pipeline yet"
            case Some(r) if inConflict(r, fp) =>
                readStoredForRun(r) match {
                    case Some(text) => return Resolved(Stored, text, r.conflictReason, r)
                    // The recorded commit has no such file (not an unreachable
                    // repository, which throws above): nothing to run, so this run
                    // generates, and so does every run until the conflict is
                    // resolved with pull or regenerate?overwrite=true.
                    case None =>
                        "the recorded commit " + Option(r.scriptCommitSha).map(_.take(7)).orNull +
                            " has no script file and the repository file changed since; pull, or regenerate with overwrite=true, to stop generating on every run"
                }
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
        if (rehearsal) {
            // The candidate's header may be wrong (that is what is being
            // rehearsed): never store a script written against it.
            val transient = existing.map(_.copy(
                generatedAt = java.time.Instant.now().toString,
                generatedAgainst = new java.util.ArrayList[String](header.asJava),
                model = currentModel(),
                origin = "run"
            )).getOrElse(CodeGenScript(
                config.name,
                kind,
                instruction,
                null,
                java.time.Instant.now().toString,
                fingerprint = fp,
                model = currentModel(),
                status = Pending,
                pendingReason = RehearsalReason,
                origin = "run",
                contractVersion = contractVersion(kind)
            ))
            return Resolved(GenerateOnce, script, RehearsalReason + " (" + reason + ")", transient)
        }
        val rec =
            try storeRecord(config, kind, instruction, fp, script, header, "run", existing, null)
            catch {
                case _: CodeRepoConflictException if existing.exists(r => isRepoBacked(r) && r.scriptCommitSha != null) =>
                    // The file was edited in the repository since the recorded
                    // commit: mark the conflict and run the recorded commit, so
                    // this and later runs execute what the warning says and make
                    // no further model call until it is resolved.
                    val warning = conflictWarning(config.name, kind, existing.orNull)
                    logger.warn("CodeGen script generated at run for " + config.name + "/" + kind + " not committed: " + warning)
                    val marked = markConflict(existing.get, fp, warning)
                    readStoredForRun(marked) match {
                        case Some(text) => return Resolved(Stored, text, warning, marked)
                        case None =>
                            CodeGenScript(
                                config.name,
                                kind,
                                instruction,
                                null,
                                java.time.Instant.now().toString,
                                fingerprint = fp,
                                model = currentModel(),
                                status = Pending,
                                pendingReason = warning,
                                origin = "run",
                                contractVersion = contractVersion(kind)
                            )
                    }
                case e: Throwable if scala.util.control.NonFatal(e) =>
                    val why = e match {
                        case _: CodeRepoConflictException => conflictWarning(config.name, kind, existing.orNull)
                        case _ => "the script could not be stored: " + messageOf(e)
                    }
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
                        pendingReason = why,
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
            case _: CodeRepoConflictException =>
                val warning = conflictWarning(config.name, kind, existing.orNull)
                existing.foreach(markConflict(_, fp, warning))
                Left(warning)
            case e: Throwable if scala.util.control.NonFatal(e) => Left(messageOf(e))
        }
    }

    // ---------------------------------------------------------------- import

    /** Make an archived script (catalog restore) the live one, with the
      * archive's fingerprint, model, generatedAt, generatedAgainst and
      * contractVersion; status ready, origin "restore". No model call, so a
      * restored pipeline runs the bytes the archive carried.
      *
      * Left (nothing written) when `archived.fingerprint` is not this config's
      * fingerprint (the script was written for another instruction or source
      * schema), when its contract version is not this server's, or when the
      * script cannot be stored. The caller's next save then regenerates.
      *
      * Repository backend: the file at branch head (the existing record's
      * path, or the path a new script would get) equal to `text` (normalised
      * as [[CatalogArchive.normaliseScript]]) is pinned at the head commit
      * with no commit. A different file at head: when the target has no
      * record for it (or a record that pins no commit), Left with a reason
      * that is not a conflict warning and nothing is committed (a catalog
      * restore never reaches this for a new pipeline: its pre-check,
      * [[CatalogArchive.planRepoScripts]], refuses the whole restore with 409
      * first); otherwise the usual commit, rejected with [[conflictWarning]] when the
      * file changed since the recorded commit. */
    def importScript(config: PipelineConfig, kind: String, text: String, archived: CodeGenScript, actor: String): Either[String, CodeGenScript] = {
        if (config == null || config.name == null) return Left("No pipeline config to import the script into")
        if (!Kinds.contains(kind)) return Left(unknownKindMessage(kind))
        val instruction = instructionOf(config, kind) match {
            case Some(i) => i
            case None =>
                return Left("Pipeline " + config.name + " has no AI " + (if (kind == PipelineScripts.DataQuality) "data-quality rule" else "transformation"))
        }
        if (archived == null) return Left("no script record in the archive")
        if (text == null || text.trim.isEmpty) return Left("the archived script is empty")
        val fp = fingerprint(instruction, schemaSignature(config))
        if (archived.fingerprint != fp)
            return Left("the archived script was generated for a different instruction or source schema than the archived pipeline config")
        if (archived.contractVersion != contractVersion(kind))
            return Left(
                "the archived script was written for script contract version " + archived.contractVersion + "; this server uses version " +
                    contractVersion(kind)
            )
        val existing = records.read(config.name, kind)
        val backend = chooseBackend(existing, null)
        val plan = planVersion(config.name, kind, existing)
        try {
            val store = storeOf(config.name, backend)
            val sameBackendPrior = existing.filter(r => backendOf(r) == backend)
            val stored: StoredScript =
                if (backend == Github) {
                    val existingRef = sameBackendPrior.filter(isRepoBacked).map(refOf)
                    val probe = existingRef.orElse(store.repoPathFor(kind).map(path => ScriptRef(kind, Github, scriptRepoPath = path)))
                    probe.flatMap(store.pullLatest) match {
                        case Some((content, headSha)) if CatalogArchive.normaliseScript(content) == CatalogArchive.normaliseScript(text) =>
                            StoredScript(Github, scriptRepoPath = probe.get.scriptRepoPath, scriptCommitSha = headSha)
                        case Some(_) if existingRef.isEmpty =>
                            // A file this install has no record of. Not imported and
                            // not a conflict warning. A catalog restore refuses this
                            // case up front (CatalogArchive.planRepoScripts), so only a
                            // direct caller gets here.
                            return Left(differentFileAtHeadReason(probe.get.scriptRepoPath))
                        case Some(_) if existingRef.exists(r => r.scriptCommitSha == null || r.scriptCommitSha.isEmpty) =>
                            // A record that pins no commit: there is no base to check a
                            // hand edit against, so do not commit over the file.
                            return Left(
                                "a different file is already at '" + probe.get.scriptRepoPath +
                                    "' at branch head in the code repository and this pipeline's script record pins no commit to check it against; the archived script was not imported"
                            )
                        case _ => store.storeScript(kind, text, existingRef.orNull, actor)
                    }
                } else store.storeScript(kind, text, sameBackendPrior.map(refOf).orNull, actor)
            val rec = CodeGenScript(
                pipeline = config.name,
                kind = kind,
                instruction = instruction,
                script = null,
                generatedAt = archived.generatedAt,
                storage = stored.storage,
                scriptPath = stored.scriptPath,
                scriptRepoPath = stored.scriptRepoPath,
                scriptCommitSha = stored.scriptCommitSha,
                fingerprint = fp,
                generatedAgainst = archived.generatedAgainst,
                model = archived.model,
                status = Ready,
                origin = "restore",
                contractVersion = contractVersion(kind),
                scriptVersion = plan.map(_.number).getOrElse(0)
            )
            try records.write(rec)
            catch {
                case e: Throwable if backend != Github =>
                    deleteObject(rec)
                    throw e
            }
            sameBackendPrior.foreach(old => if (backend != Github && !sameObject(old, rec)) deleteObject(old))
            commitVersion(plan, rec, text, actor, "imported from catalog archive")
            logger.info(
                "CodeGen script " + config.name + "/" + kind + " imported from a catalog archive (model " + archived.model + ", generated " +
                    archived.generatedAt + ")" + Option(actor).map(" by " + _).getOrElse("")
            )
            Right(rec)
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
                        case Some((content, headSha)) =>
                            val plan = planVersion(pipeline, kind, Some(r))
                            val base = r.copy(
                                scriptCommitSha = headSha,
                                generatedAt = java.time.Instant.now().toString,
                                status = Ready,
                                pendingReason = null,
                                origin = "repository",
                                conflictFingerprint = null,
                                conflictReason = null,
                                scriptVersion = plan.map(_.number).getOrElse(r.scriptVersion)
                            )
                            val rec = current match {
                                case Some(fp) => base.copy(fingerprint = fp, contractVersion = contractVersion(kind), generatedAgainst = null)
                                case None => base
                            }
                            records.write(rec)
                            commitVersion(plan, rec, content, actor, "adopted repository commit " + headSha.take(7))
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

    /** Remove both kinds' objects and records. A repository script gets a
      * delete commit (history is kept) unless its file changed at branch head
      * since the recorded commit, or that cannot be checked: then the file is
      * kept and one warning per kept file is returned. Records are deleted
      * either way. Best effort, never throws. */
    def deleteAll(pipeline: String): Seq[String] = {
        val warnings = Kinds.flatMap { kind =>
            try removeKind(pipeline, kind)
            catch {
                case e: Exception =>
                    logger.warn("CodeGen script cleanup failed for " + pipeline + "/" + kind + ": " + e.getMessage)
                    None
            }
        }
        history.foreach { h =>
            try h.deleteAllForPipeline(pipeline)
            catch {
                case e: Throwable if scala.util.control.NonFatal(e) =>
                    logger.warn("CodeGen script history cleanup failed for " + pipeline + ": " + messageOf(e))
            }
        }
        warnings
    }

    // --------------------------------------------------------------- history

    /** The script's versions, ascending. When the kind has no rows yet and
      * its live record is ready and readable (a script stored before
      * versioning), that script is seeded as v1 "(seeded from pre-versioning
      * state)" first, once. Empty without history, for an unknown kind or for
      * a kind with no readable script. Read failures propagate. */
    def scriptVersions(pipeline: String, kind: String): List[CodeGenScriptVersion] = history match {
        case None => Nil
        case Some(_) if !Kinds.contains(kind) => Nil
        case Some(h) =>
            val rows = h.listVersions(pipeline, kind)
            if (rows.nonEmpty || rehearsing) rows
            else
                records.read(pipeline, kind).flatMap(seedOf) match {
                    case None => Nil
                    case Some(seed) =>
                        h.append(seed)
                        records.read(pipeline, kind).filter(_.scriptVersion == 0).foreach { live =>
                            try records.write(live.copy(scriptVersion = seed.version))
                            catch {
                                case e: Throwable if scala.util.control.NonFatal(e) =>
                                    logger.warn("Could not number the seeded CodeGen script " + pipeline + "/" + kind + ": " + messageOf(e))
                            }
                        }
                        List(seed)
                }
    }

    /** One version (seeding as [[scriptVersions]] does). */
    def scriptVersion(pipeline: String, kind: String, version: Int): Option[CodeGenScriptVersion] =
        scriptVersions(pipeline, kind).find(_.version == version)

    /** Make version `version`'s text the live script again, through the same
      * store path as a regenerate (no model call): the result is a NEW latest
      * version with origin "restore-version" and the model that wrote the
      * restored text. A built-in object is replaced; a repository-backed
      * script gets a new commit, refused (Left with [[conflictWarning]]) when
      * the file was edited since the recorded commit. Left, with nothing
      * changed, for an unknown kind or version, or a version written for
      * another instruction, source schema or script contract version. */
    def restoreVersion(config: PipelineConfig, kind: String, version: Int, actor: String): Either[String, CodeGenScript] = {
        if (!Kinds.contains(kind)) return Left(unknownKindMessage(kind))
        if (config == null || config.name == null) return Left("No pipeline config to restore the script into")
        val instruction = instructionOf(config, kind) match {
            case Some(i) => i
            case None =>
                return Left("Pipeline " + config.name + " has no AI " + (if (kind == PipelineScripts.DataQuality) "data-quality rule" else "transformation"))
        }
        val found =
            try scriptVersion(config.name, kind, version)
            catch {
                case e: Throwable if scala.util.control.NonFatal(e) => return Left("the script history could not be read: " + messageOf(e))
            }
        val v = found match {
            case Some(x) => x
            case None => return Left(noVersionMessage(config.name, kind, version))
        }
        val fp = fingerprint(instruction, schemaSignature(config))
        if (v.fingerprint != fp)
            return Left(
                "script version " + version + " was generated for a different instruction or source schema than pipeline " + config.name +
                    " has now; nothing was changed"
            )
        if (v.contractVersion != contractVersion(kind))
            return Left(
                "script version " + version + " was written for script contract version " + v.contractVersion + "; this server uses version " +
                    contractVersion(kind) + "; nothing was changed"
            )
        if (v.script == null || v.script.trim.isEmpty) return Left("script version " + version + " holds no script text; nothing was changed")
        val existing = records.read(config.name, kind)
        val against = Option(v.generatedAgainst).map(_.asScala.toList).getOrElse(Nil)
        try
            Right(
                storeRecord(
                    config,
                    kind,
                    instruction,
                    fp,
                    v.script,
                    against,
                    "restore-version",
                    existing,
                    actor,
                    model = v.model,
                    note = "restored from version " + version
                )
            )
        catch {
            // A hand-edited repository file is never overwritten by a restore.
            case _: CodeRepoConflictException => Left(conflictWarning(config.name, kind, existing.orNull))
            case e: Throwable if scala.util.control.NonFatal(e) => Left(messageOf(e))
        }
    }

    /** The number the next live record gets and the v1 seed to write first. */
    private case class VersionPlan(number: Int, seed: Option[CodeGenScriptVersion])

    /** Decided before the live record is written (the prior object may be
      * replaced by the write). None without history, in a rehearsal, or when
      * the history cannot be read (a warning). */
    private def planVersion(pipeline: String, kind: String, existing: Option[CodeGenScript]): Option[VersionPlan] =
        if (rehearsing) None
        else
            history.flatMap { h =>
                try {
                    val latest = h.latestVersion(pipeline, kind)
                    val seed = if (latest > 0) None else existing.flatMap(seedOf)
                    Some(VersionPlan(latest + 1 + seed.size, seed))
                } catch {
                    case e: Throwable if scala.util.control.NonFatal(e) =>
                        logger.warn("CodeGen script history unavailable for " + pipeline + "/" + kind + "; this change is not versioned: " + messageOf(e))
                        None
                }
            }

    /** v1 from a live record written before versioning: only a ready (not
      * pending), readable script; keeps its model and fingerprint. */
    private def seedOf(r: CodeGenScript): Option[CodeGenScriptVersion] =
        if (r == null || r.status == Pending) None
        else readStored(r).filter(_.trim.nonEmpty).map(text => versionRow(r, 1, text, null, SeedNote))

    /** Append the planned version (and seed) once the live record is written,
      * then prune to `versionCap`. Never throws. */
    private def commitVersion(plan: Option[VersionPlan], rec: CodeGenScript, text: String, actor: String, note: String): Unit =
        for (p <- plan; h <- history)
            try {
                p.seed.foreach(h.append)
                h.append(versionRow(rec, p.number, text, actor, note))
                try h.prune(rec.pipeline, rec.kind, DatrisEnvironment.current.versionCap)
                catch {
                    case e: Throwable if scala.util.control.NonFatal(e) =>
                        logger.warn("Could not prune the CodeGen script history of " + rec.pipeline + "/" + rec.kind + ": " + messageOf(e))
                }
            } catch {
                case e: Throwable if scala.util.control.NonFatal(e) =>
                    logger.warn(
                        "Could not record version " + p.number + " of the CodeGen script " + rec.pipeline + "/" + rec.kind +
                            " (the script itself is stored): " + messageOf(e)
                    )
            }

    private def versionRow(r: CodeGenScript, n: Int, text: String, actor: String, note: String): CodeGenScriptVersion =
        CodeGenScriptVersion(
            key = CodeGenScriptVersionIO.docKey(r.pipeline, r.kind, n),
            entityName = CodeGenScriptVersionIO.entityName(r.pipeline, r.kind),
            pipeline = r.pipeline,
            kind = r.kind,
            version = n,
            script = text,
            storage = r.storage,
            scriptRepoPath = r.scriptRepoPath,
            scriptCommitSha = r.scriptCommitSha,
            fingerprint = r.fingerprint,
            model = r.model,
            origin = r.origin,
            changeNote = note,
            createdAt = java.time.Instant.now().toString,
            createdBy = actor,
            generatedAt = r.generatedAt,
            contractVersion = r.contractVersion,
            generatedAgainst = r.generatedAgainst
        )

    // --------------------------------------------------------------- helpers

    private def usable(r: CodeGenScript, fp: String, kind: String): Boolean =
        r.status == Ready && r.fingerprint == fp && r.contractVersion == contractVersion(kind)

    /** An unresolved repository conflict for this fingerprint: the recorded
      * commit is still what runs execute. */
    private def inConflict(r: CodeGenScript, fp: String): Boolean =
        r.conflictFingerprint != null && r.conflictFingerprint == fp && isRepoBacked(r) && r.scriptCommitSha != null

    /** The instruction and schema are back to the recorded script's: a
      * conflict marked for another fingerprint no longer applies, so clear it
      * (best effort) and return the cleared record. */
    private def clearStaleConflict(existing: Option[CodeGenScript], fp: String): Option[CodeGenScript] =
        existing.map { r =>
            if (r.conflictFingerprint != null && r.conflictFingerprint != fp && r.fingerprint == fp) {
                val cleared = r.copy(conflictFingerprint = null, conflictReason = null)
                try records.write(cleared)
                catch { case e: Exception => logger.warn("Could not clear the CodeGen script conflict for " + r.pipeline + "/" + r.kind + ": " + e.getMessage) }
                cleared
            } else r
        }

    /** Record the conflict on the existing record (pin, script and status
      * unchanged). Best effort: a failed write is logged. */
    private def markConflict(r: CodeGenScript, fp: String, warning: String): CodeGenScript = {
        val marked = r.copy(conflictFingerprint = fp, conflictReason = warning)
        try records.write(marked)
        catch { case e: Exception => logger.warn("Could not record the CodeGen script conflict for " + r.pipeline + "/" + r.kind + ": " + e.getMessage) }
        marked
    }

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

    /** Delete the record's object unless it is a repository file edited at
      * branch head since the recorded commit (or that cannot be checked):
      * Some(warning) when a repository file was kept. A built-in object is
      * deleted as before (failures logged). `removedBySave` words the warning
      * for a save that dropped the instruction rather than a delete. */
    private def deleteObject(r: CodeGenScript, removedBySave: Boolean = false): Option[String] =
        if (r != null && hasRef(r)) {
            val ref = refOf(r)
            val path = Option(r.scriptRepoPath).getOrElse(r.scriptPath)
            try
                storeOf(r.pipeline, backendOf(r)).deleteIfUnchanged(ref) match {
                    case DeleteOutcome.Deleted => None
                    case DeleteOutcome.KeptEdited(headSha) =>
                        logger.warn("Kept CodeGen script " + ref + ": the file changed at branch head " + headSha + " since the recorded commit")
                        Some(
                            if (removedBySave) PipelineScripts.keptOnRemoveWarning(r.pipeline, r.kind, path, r.scriptCommitSha)
                            else PipelineScripts.keptOnDeleteWarning(r.pipeline, r.kind, path, r.scriptCommitSha)
                        )
                    case DeleteOutcome.Unchecked(error) =>
                        logger.warn("Kept CodeGen script " + ref + ": the repository could not be checked: " + error)
                        Some(
                            if (removedBySave) PipelineScripts.uncheckedOnRemoveWarning(r.pipeline, r.kind, path, error)
                            else PipelineScripts.uncheckedOnDeleteWarning(r.pipeline, r.kind, path, error)
                        )
                    case DeleteOutcome.DeleteFailed(error) =>
                        logger.warn("Could not confirm the delete of CodeGen script " + ref + ": " + error)
                        Some(CodeStore.deleteUnconfirmedWarning(path, error))
                }
            catch {
                case e: Exception =>
                    logger.warn("Could not delete CodeGen script object " + ref + ": " + e.getMessage)
                    if (isRepoBacked(r)) Some(CodeStore.deleteUnconfirmedWarning(path, messageOf(e))) else None
            }
        } else None

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
        () => if (CodeRepoConfigIO.readEnabled().isDefined) "github" else "minio",
        CodeGenScriptVersionIO
    ) {

    val DataQuality = "dataQuality"
    val Transformation = "transformation"
    val Kinds: List[String] = List(DataQuality, Transformation)

    val Ready = "ready"
    val Pending = "pending"

    /** Storage values on a record. */
    val Github = "github"
    val Builtin = "minio"

    /** changeNote of a v1 seeded from a script stored before versioning. */
    val SeedNote = "(seeded from pre-versioning state)"

    def noVersionMessage(pipeline: String, kind: String, version: Int): String =
        "no version " + version + " of the " + kind + " script for pipeline " + pipeline

    /** Reason text of a script generated during a scratch rehearsal. */
    val RehearsalReason = "rehearsal run — not stored"

    private val rehearsalVar = new scala.util.DynamicVariable[Boolean](false)

    /** True inside [[withRehearsal]]`(true)`: the current job is a scratch
      * rehearsal, so [[PipelineScripts.forRun]] stores nothing. */
    def rehearsing: Boolean = rehearsalVar.value

    def withRehearsal[T](on: Boolean)(f: => T): T = rehearsalVar.withValue(on)(f)

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

    /** A save's per-kind outcomes plus the warnings for repository files
      * kept when the save dropped a kind's instruction. */
    case class SaveResult(outcomes: List[Outcome], warnings: List[String])

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

    /** importScript's reason when a new pipeline's script path already holds
      * a different file at branch head (a catalog restore refuses this case
      * before importing; only a direct importScript caller sees it). */
    def differentFileAtHeadReason(path: String): String =
        "a different file is already at '" + path + "' at branch head in the code repository; the archived script was not imported"

    /** Text that marks a rejected repository commit (the API maps it to 409). */
    val ConflictMarker = "changed in the code repository since the recorded commit"

    /** The warning for a commit rejected because the file was edited in the
      * repository: names pull and regenerate with overwrite=true. */
    def conflictWarning(pipeline: String, kind: String, existing: CodeGenScript): String = {
        val path = Option(existing).flatMap(r => Option(r.scriptRepoPath)).getOrElse("the script file")
        val sha = Option(existing).flatMap(r => Option(r.scriptCommitSha)).map(" " + _.take(7)).getOrElse("")
        "The CodeGen " + kind + " script for pipeline " + pipeline + " was not replaced: '" + path + "' " + ConflictMarker + sha +
            ", and a hand edit is never overwritten. Runs keep using the recorded commit, without the new instruction or schema " +
            "and with no model call, until you resolve it: adopt the repository version with POST " +
            pullPath(pipeline, kind) + " (pull), or replace it with POST " + regeneratePath(pipeline, kind) + "?overwrite=true."
    }

    /** The warning for a repository script kept on delete because its file
      * changed at branch head since the recorded commit `sha`. */
    def keptOnDeleteWarning(pipeline: String, kind: String, path: String, sha: String): String =
        "'" + path + "' was kept in the code repository: it changed since the recorded commit " + Option(sha).map(_.take(7)).orNull +
            " and a hand edit is never overwritten. The pipeline " + pipeline + " and its " + kind +
            " script record were deleted; remove the file by hand if it is no longer wanted."

    /** The warning for a repository script kept on delete because the
      * repository could not be checked (unreachable, token expired, disabled). */
    def uncheckedOnDeleteWarning(pipeline: String, kind: String, path: String, error: String): String =
        "'" + path + "' was kept in the code repository: whether it changed since the recorded commit could not be checked (" + error +
            "), and a hand edit is never overwritten. The pipeline " + pipeline + " and its " + kind +
            " script record were deleted; remove the file by hand if it is no longer wanted."

    /** The warning for a repository script kept when a save dropped its
      * instruction, because its file changed at branch head since the
      * recorded commit `sha`. */
    def keptOnRemoveWarning(pipeline: String, kind: String, path: String, sha: String): String =
        "'" + path + "' was kept in the code repository: it changed since the recorded commit " + Option(sha).map(_.take(7)).orNull +
            " and a hand edit is never overwritten. " + removedOnSaveTail(pipeline, kind)

    /** The warning for a repository script kept when a save dropped its
      * instruction, because the repository could not be checked. */
    def uncheckedOnRemoveWarning(pipeline: String, kind: String, path: String, error: String): String =
        "'" + path + "' was kept in the code repository: whether it changed since the recorded commit could not be checked (" + error +
            "), and a hand edit is never overwritten. " + removedOnSaveTail(pipeline, kind)

    private def removedOnSaveTail(pipeline: String, kind: String): String =
        "The pipeline " + pipeline + " was saved without its " + kind +
            " instruction and the script record was removed; remove the file by hand if it is no longer wanted."

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
        case Stored if r.reason != null =>
            "Running stored CodeGen script generated at " + r.record.generatedAt + commitNote(r.record) +
                "; it was not replaced because the repository file changed since that commit: pull, or regenerate with overwrite=true, to apply the current instruction"
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
                // The runner could not be used: the script is not at fault, so no
                // "regenerate it" advice; the message names the runner.
                case e: CodeGenRunnerUnavailable => throw e
                case e: Throwable if scala.util.control.NonFatal(e) =>
                    throw new DatrisException(
                        "The stored CodeGen " + (if (kind == DataQuality) "data quality" else "transformation") + " script (generated at " +
                            r.record.generatedAt + ") failed: " + Option(e.getMessage).getOrElse(e.getClass.getSimpleName) +
                            ". It is not regenerated automatically; change the instruction, or call POST " + regeneratePath(pipeline, kind) +
                            " to replace it."
                    )
            }
}
