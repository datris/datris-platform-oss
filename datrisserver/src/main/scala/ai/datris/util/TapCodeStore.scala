package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{CodeRepoConfig, DatrisEnvironment, DatrisException, TapConfig, ValidationException}
import org.slf4j.{Logger, LoggerFactory}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** Result of storing a script: the fields the caller stamps onto its
  * record (TapConfig, CodeGenScript). Exactly one backend's fields are
  * populated. */
case class StoredScript(
    storage: String,
    scriptPath: String = null,
    scriptRepoPath: String = null,
    scriptCommitSha: String = null
)

/** Where one stored script lives: the owner-neutral view of the fields a tap
  * (TapConfig) or a pipeline CodeGen script (CodeGenScript) records. `name`
  * is the script's logical name (tap name, or CodeGen kind). */
case class ScriptRef(
    name: String,
    storage: String,
    scriptPath: String = null,
    scriptRepoPath: String = null,
    scriptCommitSha: String = null
)

object ScriptRef {
    def of(tap: TapConfig): ScriptRef =
        if (tap == null) null else ScriptRef(tap.name, tap.scriptStorage, tap.scriptPath, tap.scriptRepoPath, tap.scriptCommitSha)
}

/** Which family a repository-backed script belongs to: decides its path and
  * commit message in the code repository. */
sealed trait ScriptFamily

object ScriptFamily {

    /** A tap script: `<prefix><name>.py`. */
    case class Tap(name: String) extends ScriptFamily

    /** A pipeline CodeGen script: `<prefix>pipelines/<pipeline>/<file>.py`.
      * kind: "dataQuality" | "transformation" (the CodeGen kind). */
    case class Pipeline(pipeline: String, kind: String) extends ScriptFamily
}

/** Storage backend for Python scripts (tap scripts, pipeline CodeGen
  * scripts). MinIO is the built-in default; the github backend stores
  * scripts in the tenant's configured code repository. */
trait CodeStore {

    /** Storage discriminator stamped on the record ("minio" | "github"). */
    def storage: String

    /** The script source, or None if missing from the backend. */
    def readScript(ref: ScriptRef): Option[String]

    /** Store the script and return the fields to stamp on the record.
      * `prior` is the existing reference (may be null). */
    def storeScript(name: String, script: String, prior: ScriptRef, actor: String): StoredScript

    /** Remove the script from the backend. Idempotent. */
    def deleteScript(ref: ScriptRef): Unit

    /** Whether the script exists in the backend. */
    def scriptExists(ref: ScriptRef): Boolean

    /** Branch head of the script's file: (content, head commit sha), bypassing
      * the pin. None for a backend with no branch (built-in store) or a file
      * missing at head. */
    def pullLatest(ref: ScriptRef): Option[(String, String)] = None

    /** The repository path a new script named `name` gets in this store, so a
      * caller can look at branch head before its first write. None for a
      * backend with no repository (built-in store). */
    def repoPathFor(name: String): Option[String] = None

    /** Some(headSha) when the script's file at branch head differs from the
      * recorded commit's. Default: compare [[pullLatest]] with [[readScript]];
      * the repository store overrides it with a non-caching blob compare. */
    def driftHead(ref: ScriptRef): Option[String] =
        pullLatest(ref).flatMap { case (content, headSha) =>
            if (ref == null || headSha == ref.scriptCommitSha) None
            else {
                val pinned =
                    try readScript(ref)
                    catch { case e: Throwable if scala.util.control.NonFatal(e) => None }
                if (pinned.contains(content)) None else Some(headSha)
            }
        }

    /** Delete the script unless its file changed at branch head since the
      * recorded commit (a hand edit is never destroyed). The outcome says
      * whether it was deleted, kept as edited, or kept because the check
      * could not run. A delete request that fails after a clean check is
      * `DeleteFailed` for a repository script (the file may or may not be
      * gone: a timed-out request can still have committed); for a built-in
      * script it propagates, as before. */
    def deleteIfUnchanged(ref: ScriptRef): DeleteOutcome = {
        val drift =
            try driftHead(ref)
            catch {
                case e: Throwable if scala.util.control.NonFatal(e) =>
                    return DeleteOutcome.Unchecked(Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName))
            }
        drift match {
            case Some(headSha) => DeleteOutcome.KeptEdited(headSha)
            case None =>
                try {
                    deleteScript(ref)
                    DeleteOutcome.Deleted
                } catch {
                    case e: Throwable if scala.util.control.NonFatal(e) && ref != null && ref.scriptRepoPath != null && ref.scriptRepoPath.nonEmpty =>
                        DeleteOutcome.DeleteFailed(Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName))
                }
        }
    }

    /** As [[deleteIfUnchanged]], as text: None when deleted, else the
      * warning naming the kept file. On a backend with no branch (built-in
      * store) [[driftHead]] is None, so this is a plain [[deleteScript]]. */
    def deleteUnlessEdited(ref: ScriptRef): Option[String] =
        deleteIfUnchanged(ref) match {
            case DeleteOutcome.Deleted => None
            case DeleteOutcome.KeptEdited(_) =>
                Some(CodeStore.keptOnDeleteWarning(ownerOf(ref), pathOf(ref), Option(ref).map(_.scriptCommitSha).orNull))
            case DeleteOutcome.Unchecked(error) => Some(CodeStore.uncheckedOnDeleteWarning(ownerOf(ref), pathOf(ref), error))
            case DeleteOutcome.DeleteFailed(error) => Some(CodeStore.deleteUnconfirmedWarning(pathOf(ref), error))
        }

    /** What owns a script in warnings ("tap" | "pipeline"); "owner" when the
      * backend cannot tell. */
    protected def ownerOf(ref: ScriptRef): String = "owner"

    private def pathOf(ref: ScriptRef): String =
        Option(ref).flatMap(r => Option(r.scriptRepoPath).orElse(Option(r.scriptPath))).getOrElse("the script file")
}

/** What [[CodeStore.deleteIfUnchanged]] did. */
sealed trait DeleteOutcome

object DeleteOutcome {
    case object Deleted extends DeleteOutcome

    /** Kept: the file at branch head (`headSha`) differs from the recorded commit's. */
    case class KeptEdited(headSha: String) extends DeleteOutcome

    /** Kept: the drift check failed (repository unreachable, token expired, disabled). */
    case class Unchecked(error: String) extends DeleteOutcome

    /** The check passed but the delete request failed: the file may or may
      * not still be there (a timed-out request can have committed). */
    case class DeleteFailed(error: String) extends DeleteOutcome
}

object CodeStore {

    /** A file kept on delete because it changed at head since `sha`.
      * `owner` is "tap" or "pipeline". */
    def keptOnDeleteWarning(owner: String, path: String, sha: String): String =
        "'" + path + "' was kept in the code repository: it changed since the recorded commit " + Option(sha).map(_.take(7)).orNull +
            " and a hand edit is never overwritten. The " + owner + " and its script record were deleted; " +
            "remove the file by hand if it is no longer wanted."

    /** A repository file whose delete request failed after a clean check:
      * nothing is claimed about whether it is still there. */
    def deleteUnconfirmedWarning(path: String, error: String): String =
        "The delete of '" + path + "' could not be confirmed (" + error +
            "); check the repository and remove the file by hand if it is still there."

    /** A file kept on delete because the drift check could not run. */
    def uncheckedOnDeleteWarning(owner: String, path: String, error: String): String =
        "'" + path + "' was kept in the code repository: whether it changed since the recorded commit could not be checked (" + error +
            "), and a hand edit is never overwritten. The " + owner + " and its script record were deleted; " +
            "remove the file by hand if it is no longer wanted."
}

/** Tap-typed adapter over a [[CodeStore]] (see plans/tap-github-storage.md).
  * Callers resolve a backend per tap via `TapCodeStore.forTap` — provider
  * names never leak into call sites. */
trait TapCodeStore {

    /** The script store this adapter delegates to. */
    protected def underlying: CodeStore

    /** Storage discriminator stamped on TapConfig ("minio" | "github"). */
    def storage: String = underlying.storage

    /** The script source, or None if missing from the backend. */
    def readScript(tap: TapConfig): Option[String] = underlying.readScript(ScriptRef.of(tap))

    /** Store the script and return the fields to stamp on the tap. `prior` is
      * the existing config (may be null for a not-yet-saved tap). */
    def storeScript(tapName: String, script: String, prior: TapConfig, actor: String): StoredScript =
        underlying.storeScript(tapName, script, ScriptRef.of(prior), actor)

    /** Remove the script from the backend. Idempotent. */
    def deleteScript(tap: TapConfig): Unit = underlying.deleteScript(ScriptRef.of(tap))

    /** Remove the script unless its repository file was hand-edited since
      * the recorded commit; Some(warning) when the file was kept. */
    def deleteUnlessEdited(tap: TapConfig): Option[String] = underlying.deleteUnlessEdited(ScriptRef.of(tap))

    /** Whether the script exists in the backend. */
    def scriptExists(tap: TapConfig): Boolean = underlying.scriptExists(ScriptRef.of(tap))
}

object TapCodeStore {

    /** Backend for an existing tap: what its fields say it uses. */
    def forTap(tap: TapConfig): TapCodeStore =
        if (tap != null && tap.scriptStorage == "github") GithubCodeStore else MinioCodeStore

    /** Backend by explicit request ("github" | "minio" | null ⇒ tenant
      * default: the enabled repo config if one exists, else MinIO). */
    def forStorage(storage: String): TapCodeStore = storage match {
        case "github" =>
            if (CodeRepoConfigIO.readEnabled().isEmpty)
                throw new DatrisException(
                    "No code repository is configured. Set one up under Configuration > Code Repository before using GitHub storage."
                )
            GithubCodeStore
        case "minio" | "builtin" => MinioCodeStore
        case null | "" =>
            if (CodeRepoConfigIO.readEnabled().isDefined) GithubCodeStore else MinioCodeStore
        case other => throw new DatrisException("Unknown script storage backend: " + other)
    }
}

/** Path checks shared by the script stores. */
object ScriptPaths {

    /** The repository folder of pipeline CodeGen scripts, under the path prefix. */
    val PipelinesFolder = "pipelines/"

    /** True when every `/`-separated segment of `p` (trailing empties kept)
      * is non-empty and neither `.` nor `..`. */
    def plainSegments(p: String): Boolean =
        p != null && p.nonEmpty && p.split("/", -1).forall(seg => seg.nonEmpty && seg != "." && seg != "..")

    /** Some(message) when a script stored for the tap `name` would get a path
      * with an empty, `.` or `..` segment after the store's prefix. Both
      * stores append to the name's last segment (`.py`, `_<id>.py`), so that
      * is the case exactly when `name + ".py"` has such a segment. */
    def tapNameRefusal(name: String): Option[String] =
        if (name == null || name.isEmpty || plainSegments(name + ".py")) None
        else Some("Invalid tap name for script storage: a tap name may not contain an empty, '.' or '..' path segment.")
}

/** Built-in backend: `{env}-config` bucket, `{keyPrefix}{name}_{uuid}.py`.
  * Delegates to the TapScriptGenerator helpers so tap behavior (including
  * keep-old-script-for-revert) is unchanged. A name whose key would have an
  * empty, `.` or `..` segment after `keyPrefix` is refused before anything
  * is written. `guardDeletes` leaves a path outside `keyPrefix` in place. */
class MinioScriptStore(keyPrefix: String, objects: ObjectStoreUtility = null, guardDeletes: Boolean = false) extends CodeStore {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    val storage = "minio"

    private def store: ObjectStoreUtility = Option(objects).getOrElse(ObjectStoreUtil)

    override def readScript(ref: ScriptRef): Option[String] =
        if (ref == null || ref.scriptPath == null || ref.scriptPath.isEmpty) None
        else store.readBucketObject(DatrisEnvironment.current.environment + "-config", ref.scriptPath)

    override def storeScript(name: String, script: String, prior: ScriptRef, actor: String): StoredScript = {
        ScriptPaths.tapNameRefusal(name).foreach(m => throw new ValidationException(m))
        val oldPath = if (prior != null) prior.scriptPath else null
        StoredScript(storage, scriptPath = TapScriptGenerator.storeScript(name, script, oldPath, keyPrefix, objects))
    }

    override def deleteScript(ref: ScriptRef): Unit =
        if (ref != null && ref.scriptPath != null) {
            if (guardDeletes && !ref.scriptPath.startsWith(keyPrefix))
                logger.info("Script of '" + LogSafe.text(ref.name) + "' left in place: its stored reference is outside this store's folder")
            else TapScriptGenerator.deleteScriptUnder(ref.scriptPath, if (guardDeletes) keyPrefix else null, objects)
        }

    override def scriptExists(ref: ScriptRef): Boolean = readScript(ref).isDefined
}

/** Built-in backend for taps (`tap-scripts/`), and the factory for a
  * pipeline's CodeGen script store (`pipeline-scripts/<pipeline>/`). */
object MinioCodeStore extends TapCodeStore {
    val TapPrefix = "tap-scripts/"
    val PipelinePrefix = "pipeline-scripts/"

    protected val underlying: CodeStore = tapStore(null)

    /** The built-in tap script store over `objects` (null: the platform's
      * object store). Deletes only keys under [[TapPrefix]]. */
    private[util] def tapStore(objects: ObjectStoreUtility): CodeStore = new MinioScriptStore(TapPrefix, objects, guardDeletes = true)

    /** Built-in store for one pipeline's CodeGen scripts, keys under `pipeline-scripts/<pipeline>/`. */
    def forPipeline(pipeline: String, objects: ObjectStoreUtility = null): CodeStore = {
        if (pipeline == null || pipeline.trim.isEmpty || pipeline.contains("/") || pipeline.contains(".."))
            throw new DatrisException("Invalid pipeline name for script storage: " + pipeline)
        new MinioScriptStore(PipelinePrefix + pipeline + "/", objects, guardDeletes = true)
    }
}

/** Code-repository backend. Reads are pinned to the recorded commit sha and
  * served from an immutable local cache; writes are commits on the
  * configured branch. Requires an enabled CodeRepoConfig. `familyOf` maps a
  * script's logical name (tap name, or CodeGen kind) to its family, which
  * decides the repository path and the commit message. Every repository
  * request goes through `client`, except the branch lookups and file reads
  * a given `branchHead` / `fileAt` answers (null: through `client`). */
class GithubScriptStore(
    familyOf: String => ScriptFamily,
    disabledMessage: String = null,
    readEnabled: () => Option[CodeRepoConfig] = () => CodeRepoConfigIO.readEnabled(),
    branchHead: CodeRepoConfig => String = null,
    fileAt: (CodeRepoConfig, String, String) => Option[RepoFile] = null,
    client: GithubClient = GithubClient
) extends CodeStore {
    private val logger: Logger = LoggerFactory.getLogger(classOf[GithubScriptStore])
    val storage = "github"

    private def headOf(cfg: CodeRepoConfig): String =
        if (branchHead != null) branchHead(cfg) else client.branchHeadSha(cfg)

    private def readAt(cfg: CodeRepoConfig, path: String, ref: String): Option[RepoFile] =
        if (fileAt != null) fileAt(cfg, path, ref) else client.getFile(cfg, path, ref)

    // Resolved at call time: the companion's messages are not initialised
    // while the companion object itself is being constructed.
    private def config: CodeRepoConfig =
        readEnabled().getOrElse(
            throw new DatrisException(Option(disabledMessage).getOrElse(GithubScriptStore.TapDisabledMessage))
        )

    override def readScript(ref: ScriptRef): Option[String] = {
        if (ref == null || ref.scriptRepoPath == null || ref.scriptRepoPath.isEmpty) return None
        val cfg = config
        val sha = ref.scriptCommitSha

        if (sha != null && sha.nonEmpty) {
            TapScriptCache.get(cfg.repo, sha, ref.scriptRepoPath) match {
                case cached @ Some(_) => cached
                case None =>
                    try {
                        readAt(cfg, ref.scriptRepoPath, sha).map { file =>
                            TapScriptCache.put(cfg.repo, sha, ref.scriptRepoPath, file.content)
                            file.content
                        }
                    } catch {
                        case e: Exception =>
                            // Offline fallback would have hit the cache above; with no
                            // cache entry there is nothing safe to run.
                            throw new DatrisException(
                                "Code repository is unreachable and no cached copy of '" + ref.scriptRepoPath +
                                    "' at " + sha.take(9) + " exists locally. " + e.getMessage
                            )
                    }
            }
        } else {
            // No pin yet (pre-first-save edge) — read branch head, don't cache.
            readAt(cfg, ref.scriptRepoPath, cfg.branch).map(_.content)
        }
    }

    override def storeScript(name: String, script: String, prior: ScriptRef, actor: String): StoredScript = {
        val cfg = config
        val family = familyOf(name)
        val path =
            if (prior != null && prior.storage == "github" && prior.scriptRepoPath != null && prior.scriptRepoPath.nonEmpty)
                prior.scriptRepoPath
            else GithubCodeStore.scriptRepoPath(family, cfg)
        val action = if (prior != null && prior.storage == "github") "update" else "create"
        val baseSha = if (prior != null && prior.storage == "github") prior.scriptCommitSha else null
        val commitSha = client.putFile(cfg, path, script, GithubCodeStore.commitMessage(cfg, family, action, actor), baseSha)
        TapScriptCache.put(cfg.repo, commitSha, path, script)
        StoredScript(storage, scriptRepoPath = path, scriptCommitSha = commitSha)
    }

    /** Deletes the script's file. For a tap, only the tap's own file
      * (`<prefix><tap>.py` under the current or a recorded earlier prefix,
      * [[TapScriptOwnership.ownRepo]]): any other path is left in place with
      * one INFO line naming the tap, and nothing is sent. A pipeline's
      * CodeGen script is deleted as before. */
    override def deleteScript(ref: ScriptRef): Unit = {
        if (ref != null && ref.scriptRepoPath != null && ref.scriptRepoPath.nonEmpty) {
            val cfg = config
            val family = familyOf(ref.name)
            family match {
                case ScriptFamily.Tap(name) if !TapScriptOwnership.ownRepo(name, ref.scriptRepoPath, TapScriptOwnership.prefixesOf(cfg)) =>
                    logger.info("Script of tap '" + LogSafe.text(name) + "' left in place: its stored path is not the tap's own script file")
                case _ =>
                    client.deleteFile(cfg, ref.scriptRepoPath, GithubCodeStore.commitMessage(cfg, family, "delete", null))
            }
        }
    }

    override def scriptExists(ref: ScriptRef): Boolean = {
        if (ref == null || ref.scriptRepoPath == null || ref.scriptRepoPath.isEmpty) false
        else {
            val cfg = config
            val gitRef = if (ref.scriptCommitSha != null && ref.scriptCommitSha.nonEmpty) ref.scriptCommitSha else cfg.branch
            TapScriptCache.get(cfg.repo, gitRef, ref.scriptRepoPath).isDefined ||
            readAt(cfg, ref.scriptRepoPath, gitRef).isDefined
        }
    }

    /** Drift check without downloading into the cache: the branch-head sha
      * when the file's blob at branch head differs from its blob at the
      * recorded commit. Nothing is written to [[TapScriptCache]], so polling
      * drift never evicts pinned commits. */
    override def driftHead(ref: ScriptRef): Option[String] = {
        if (ref == null || ref.scriptRepoPath == null || ref.scriptRepoPath.isEmpty || ref.scriptCommitSha == null) return None
        val cfg = config
        val headSha = headOf(cfg)
        if (headSha == ref.scriptCommitSha) return None
        readAt(cfg, ref.scriptRepoPath, headSha).flatMap { head =>
            val pinned = readAt(cfg, ref.scriptRepoPath, ref.scriptCommitSha)
            if (pinned.exists(_.blobSha == head.blobSha)) None else Some(headSha)
        }
    }

    override protected def ownerOf(ref: ScriptRef): String =
        if (ref == null) "owner"
        else
            familyOf(ref.name) match {
                case ScriptFamily.Tap(_) => "tap"
                case ScriptFamily.Pipeline(_, _) => "pipeline"
            }

    override def repoPathFor(name: String): Option[String] = Some(GithubCodeStore.scriptRepoPath(familyOf(name), config))

    /** Branch-head read for the drift-pull flow: returns (content, headSha),
      * bypassing the pin; the head version is cached under its sha. */
    override def pullLatest(ref: ScriptRef): Option[(String, String)] = {
        if (ref == null || ref.scriptRepoPath == null || ref.scriptRepoPath.isEmpty) return None
        val cfg = config
        val headSha = headOf(cfg)
        readAt(cfg, ref.scriptRepoPath, headSha).map { file =>
            TapScriptCache.put(cfg.repo, headSha, ref.scriptRepoPath, file.content)
            (file.content, headSha)
        }
    }
}

/** The tap scripts' repository store. */
object GithubScriptStore
    extends GithubScriptStore(
        name => ScriptFamily.Tap(name),
        null,
        () => CodeRepoConfigIO.readEnabled(),
        cfg => GithubClient.branchHeadSha(cfg),
        (cfg, path, ref) => GithubClient.getFile(cfg, path, ref),
        GithubClient
    ) {
    val TapDisabledMessage: String =
        "This tap stores its script in a code repository, but no enabled repository is configured. " +
            "Re-enable it under Configuration > Code Repository, or move the tap back to built-in storage."

    val PipelineDisabledMessage: String =
        "This pipeline's CodeGen script is stored in a code repository, but no enabled repository is configured. " +
            "Re-enable it under Configuration > Code Repository, or regenerate the script with storage=builtin to move it back to built-in storage."
}

/** Tap adapter over [[GithubScriptStore]], plus the repository helpers
  * (repo path, commit message, drift pull) and the factory for a pipeline's
  * repository-backed CodeGen script store. */
object GithubCodeStore extends TapCodeStore {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    protected val underlying: CodeStore = GithubScriptStore

    /** The default commit template a CodeRepoConfig carries; for pipeline
      * scripts it counts as "no template" (see [[commitMessage]]). */
    val TapDefaultTemplate = "tap({name}): {action} via Datris"
    val PipelineDefaultTemplate = "pipeline({name}): {action} via Datris"

    private def normalizedPrefix(cfg: CodeRepoConfig): String = {
        val prefix = Option(cfg.pathPrefix).getOrElse("")
        if (prefix.isEmpty || prefix.endsWith("/")) prefix else prefix + "/"
    }

    /** `<prefix><tap>.py`. Refuses (a [[ValidationException]], answered as
      * 400) a tap name whose path would have an empty, `.` or `..` segment
      * after the prefix, or would be under the `pipelines/` folder. */
    def scriptRepoPath(tapName: String, cfg: CodeRepoConfig): String = {
        ScriptPaths.tapNameRefusal(tapName).foreach(m => throw new ValidationException(m))
        if (tapName.startsWith(ScriptPaths.PipelinesFolder))
            throw new ValidationException(
                "Invalid tap name for repository storage: a tap script may not be stored under the '" + ScriptPaths.PipelinesFolder + "' folder."
            )
        normalizedPrefix(cfg) + tapName + ".py"
    }

    /** `<prefix><tap>.py` for a tap; `<prefix>pipelines/<pipeline>/data-quality.py`
      * or `.../transformation.py` for a pipeline CodeGen script. */
    def scriptRepoPath(family: ScriptFamily, cfg: CodeRepoConfig): String = family match {
        case ScriptFamily.Tap(name) => scriptRepoPath(name, cfg)
        case ScriptFamily.Pipeline(pipeline, kind) =>
            normalizedPrefix(cfg) + "pipelines/" + pipeline + "/" + pipelineFileName(kind)
    }

    /** File name of a pipeline CodeGen script in the repository. */
    def pipelineFileName(kind: String): String = kind match {
        case "dataQuality" => "data-quality.py"
        case "transformation" => "transformation.py"
        case other => throw new DatrisException("Unknown CodeGen script kind for repository storage: " + other)
    }

    def commitMessage(cfg: CodeRepoConfig, tapName: String, action: String, actor: String): String = {
        val template = Option(cfg.commitMessageTemplate).filter(_.nonEmpty).getOrElse(TapDefaultTemplate)
        render(template, tapName, action, actor)
    }

    /** Tap: as the String overload. Pipeline: `{name}` is `<pipeline>/<kind>`;
      * a template that is null, empty or the stored tap default renders as
      * `pipeline({name}): {action} via Datris`; any other template is applied
      * as written. */
    def commitMessage(cfg: CodeRepoConfig, family: ScriptFamily, action: String, actor: String): String = family match {
        case ScriptFamily.Tap(name) => commitMessage(cfg, name, action, actor)
        case ScriptFamily.Pipeline(pipeline, kind) =>
            val template = Option(cfg.commitMessageTemplate).filter(t => t.nonEmpty && t != TapDefaultTemplate).getOrElse(PipelineDefaultTemplate)
            render(template, pipeline + "/" + kind, action, actor)
    }

    private def render(template: String, name: String, action: String, actor: String): String =
        template
            .replace("{name}", name)
            .replace("{action}", action)
            .replace("{user}", if (actor != null && actor.nonEmpty) actor else "datris")

    /** Repository-backed store for one pipeline's CodeGen scripts; the
      * script's logical name is its CodeGen kind. */
    def forPipeline(pipeline: String): CodeStore = {
        if (pipeline == null || pipeline.trim.isEmpty || pipeline.contains("/") || pipeline.contains(".."))
            throw new DatrisException("Invalid pipeline name for script storage: " + pipeline)
        new GithubScriptStore(kind => ScriptFamily.Pipeline(pipeline, kind), GithubScriptStore.PipelineDisabledMessage)
    }

    /** Branch-head read for the drift-pull flow: returns (content, headSha),
      * bypassing the pin. */
    def pullLatest(tap: TapConfig): Option[(String, String)] = underlying.pullLatest(ScriptRef.of(tap))
}

/** Immutable on-disk cache of repo scripts, keyed by commit sha — safe to keep
  * forever, bounded by pruning old sha directories per repo. Lives under the
  * datris home dir so it survives restarts but not image rebuilds.
  *
  * Layout: `<root>/<uuid of repo>/<commit id>/<file name>`, the file name
  * being the SHA-256 of the exact repository path in hexadecimal, plus the
  * path's extension ([[fileName]]): one file per repository path. Only a
  * commit id (7 to 64 hexadecimal characters) is used as a folder name: for
  * any other value [[get]] is a miss and [[put]] writes nothing, so the read
  * goes to the repository as on a cold cache. Independently of that, a
  * location that does not resolve to a file directly inside a commit folder
  * of the cache directory is never read or written. */
object TapScriptCache {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    private val MaxShasPerRepo = 50

    /** Most files one commit folder holds. */
    private[util] val MaxFilesPerCommit = 200

    private val CommitIdPattern = java.util.regex.Pattern.compile("[0-9a-fA-F]{7,64}")

    private val ExtensionPattern = java.util.regex.Pattern.compile("\\.[A-Za-z0-9]{1,16}$")

    /** True when `sha` is a hexadecimal commit id of 7 to 64 characters. */
    def isCommitId(sha: String): Boolean = sha != null && CommitIdPattern.matcher(sha).matches()

    /** The file name a repository path is cached under: the SHA-256 of the
      * exact path (UTF-8) in lowercase hexadecimal, followed by the
      * extension of the path's last segment (`.py`), if it has one. Distinct
      * paths have distinct file names. Null for null. */
    def fileName(path: String): String =
        if (path == null) null
        else {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8))
            val hex = digest.map(b => "%02x".format(b & 0xff)).mkString
            val last = path.substring(path.lastIndexOf('/') + 1)
            val m = ExtensionPattern.matcher(last)
            val ext = if (last.length > 1 && m.find() && m.start() > 0) m.group() else ""
            hex + ext
        }

    private def cacheRoot: Path =
        Paths.get(
            sys.props.get("datris.tap.cache.dir")
                .orElse(sys.env.get("DATRIS_TAP_CACHE_DIR"))
                .getOrElse(sys.props("user.home") + "/datris/tap-cache")
        ).toAbsolutePath.normalize

    private def repoDir(root: Path, repo: String): Path =
        root.resolve(java.util.UUID.nameUUIDFromBytes(repo.getBytes(StandardCharsets.UTF_8)).toString)

    /** True when `file`, normalised, is a file directly inside a commit
      * folder of a repository folder of `root` (three levels below it). */
    private[util] def inside(root: Path, file: Path): Boolean = {
        val r = root.toAbsolutePath.normalize
        val f = file.toAbsolutePath.normalize
        f.startsWith(r) && f.getNameCount == r.getNameCount + 3
    }

    /** The cache file of (`repo`, `sha`, `path`), or None when `sha` is not a
      * commit id or the location is not inside the cache directory. */
    private[util] def location(repo: String, sha: String, path: String): Option[Path] =
        if (repo == null || path == null || path.isEmpty || !isCommitId(sha)) None
        else
            try {
                val root = cacheRoot
                val file = repoDir(root, repo).resolve(sha).resolve(fileName(path)).normalize
                if (inside(root, file)) Some(file) else None
            } catch { case _: Exception => None }

    def get(repo: String, sha: String, path: String): Option[String] =
        location(repo, sha, path).flatMap { file =>
            if (Files.isRegularFile(file))
                try Some(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                catch { case _: Exception => None }
            else None
        }

    /** Writes the cache file of (`repo`, `sha`, `path`). Nothing is written
      * when the commit folder already holds [[MaxFilesPerCommit]] files and
      * this file is not one of them: the read then goes to the repository,
      * as on a cold cache. */
    def put(repo: String, sha: String, path: String, content: String): Unit = {
        try {
            location(repo, sha, path).foreach { file =>
                if (Files.isRegularFile(file) || filesIn(file.getParent) < MaxFilesPerCommit) {
                    Files.createDirectories(file.getParent)
                    Files.write(file, content.getBytes(StandardCharsets.UTF_8))
                    prune(repo)
                }
            }
        } catch {
            case e: Exception => logger.warn("Tap script cache write failed (non-fatal): " + e.getMessage)
        }
    }

    /** The number of entries directly inside `dir` (0 when it does not exist). */
    private def filesIn(dir: Path): Long =
        if (!Files.isDirectory(dir)) 0L
        else {
            val listing = Files.list(dir)
            try listing.count()
            finally listing.close()
        }

    /** Keep the most recently touched N sha directories per repo. */
    private def prune(repo: String): Unit = {
        val dir = repoDir(cacheRoot, repo)
        if (!Files.isDirectory(dir)) return
        val listing = Files.list(dir)
        val shaDirs =
            try listing.toArray.map(_.asInstanceOf[Path]).filter(Files.isDirectory(_))
            finally listing.close()
        if (shaDirs.length > MaxShasPerRepo) {
            shaDirs.sortBy(p => Files.getLastModifiedTime(p).toMillis)
                .take(shaDirs.length - MaxShasPerRepo)
                .foreach { old =>
                    val walk = Files.walk(old)
                    val paths =
                        try walk.toArray.map(_.asInstanceOf[Path])
                        finally walk.close()
                    paths.sortBy(-_.getNameCount).foreach(Files.deleteIfExists(_))
                }
        }
    }
}
