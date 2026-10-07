package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{CodeRepoConfig, DatrisEnvironment, DatrisException, TapConfig}
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

/** Built-in backend: `{env}-config` bucket, `{keyPrefix}{name}_{uuid}.py`.
  * Delegates to the TapScriptGenerator helpers so tap behavior (including
  * keep-old-script-for-revert) is unchanged. `guardDeletes` refuses to
  * delete a path outside `keyPrefix`. */
class MinioScriptStore(keyPrefix: String, objects: ObjectStoreUtility = null, guardDeletes: Boolean = false) extends CodeStore {
    val storage = "minio"

    private def store: ObjectStoreUtility = Option(objects).getOrElse(ObjectStoreUtil)

    override def readScript(ref: ScriptRef): Option[String] =
        if (ref == null || ref.scriptPath == null || ref.scriptPath.isEmpty) None
        else store.readBucketObject(DatrisEnvironment.current.environment + "-config", ref.scriptPath)

    override def storeScript(name: String, script: String, prior: ScriptRef, actor: String): StoredScript = {
        val oldPath = if (prior != null) prior.scriptPath else null
        StoredScript(storage, scriptPath = TapScriptGenerator.storeScript(name, script, oldPath, keyPrefix, objects))
    }

    override def deleteScript(ref: ScriptRef): Unit =
        if (ref != null) TapScriptGenerator.deleteScriptUnder(ref.scriptPath, if (guardDeletes) keyPrefix else null, objects)

    override def scriptExists(ref: ScriptRef): Boolean = readScript(ref).isDefined
}

/** Built-in backend for taps (`tap-scripts/`), and the factory for a
  * pipeline's CodeGen script store (`pipeline-scripts/<pipeline>/`). */
object MinioCodeStore extends TapCodeStore {
    val TapPrefix = "tap-scripts/"
    val PipelinePrefix = "pipeline-scripts/"

    protected val underlying: CodeStore = new MinioScriptStore(TapPrefix)

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
  * decides the repository path and the commit message. */
class GithubScriptStore(familyOf: String => ScriptFamily, disabledMessage: String = null) extends CodeStore {
    val storage = "github"

    // Resolved at call time: the companion's messages are not initialised
    // while the companion object itself is being constructed.
    private def config: CodeRepoConfig =
        CodeRepoConfigIO.readEnabled().getOrElse(
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
                        GithubClient.getFile(cfg, ref.scriptRepoPath, sha).map { file =>
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
            GithubClient.getFile(cfg, ref.scriptRepoPath, cfg.branch).map(_.content)
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
        val commitSha = GithubClient.putFile(cfg, path, script, GithubCodeStore.commitMessage(cfg, family, action, actor), baseSha)
        TapScriptCache.put(cfg.repo, commitSha, path, script)
        StoredScript(storage, scriptRepoPath = path, scriptCommitSha = commitSha)
    }

    override def deleteScript(ref: ScriptRef): Unit = {
        if (ref != null && ref.scriptRepoPath != null && ref.scriptRepoPath.nonEmpty) {
            val cfg = config
            GithubClient.deleteFile(cfg, ref.scriptRepoPath, GithubCodeStore.commitMessage(cfg, familyOf(ref.name), "delete", null))
        }
    }

    override def scriptExists(ref: ScriptRef): Boolean = {
        if (ref == null || ref.scriptRepoPath == null || ref.scriptRepoPath.isEmpty) false
        else {
            val cfg = config
            val gitRef = if (ref.scriptCommitSha != null && ref.scriptCommitSha.nonEmpty) ref.scriptCommitSha else cfg.branch
            TapScriptCache.get(cfg.repo, gitRef, ref.scriptRepoPath).isDefined ||
            GithubClient.getFile(cfg, ref.scriptRepoPath, gitRef).isDefined
        }
    }

    /** Drift check without downloading into the cache: the branch-head sha
      * when the file's blob at branch head differs from its blob at the
      * recorded commit. Nothing is written to [[TapScriptCache]], so polling
      * drift never evicts pinned commits. */
    override def driftHead(ref: ScriptRef): Option[String] = {
        if (ref == null || ref.scriptRepoPath == null || ref.scriptRepoPath.isEmpty || ref.scriptCommitSha == null) return None
        val cfg = config
        val headSha = GithubClient.branchHeadSha(cfg)
        if (headSha == ref.scriptCommitSha) return None
        GithubClient.getFile(cfg, ref.scriptRepoPath, headSha).flatMap { head =>
            val pinned = GithubClient.getFile(cfg, ref.scriptRepoPath, ref.scriptCommitSha)
            if (pinned.exists(_.blobSha == head.blobSha)) None else Some(headSha)
        }
    }

    /** Branch-head read for the drift-pull flow: returns (content, headSha),
      * bypassing the pin; the head version is cached under its sha. */
    override def pullLatest(ref: ScriptRef): Option[(String, String)] = {
        if (ref == null || ref.scriptRepoPath == null || ref.scriptRepoPath.isEmpty) return None
        val cfg = config
        val headSha = GithubClient.branchHeadSha(cfg)
        GithubClient.getFile(cfg, ref.scriptRepoPath, headSha).map { file =>
            TapScriptCache.put(cfg.repo, headSha, ref.scriptRepoPath, file.content)
            (file.content, headSha)
        }
    }
}

/** The tap scripts' repository store. */
object GithubScriptStore extends GithubScriptStore(name => ScriptFamily.Tap(name), null) {
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

    def scriptRepoPath(tapName: String, cfg: CodeRepoConfig): String =
        normalizedPrefix(cfg) + tapName + ".py"

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
  * datris home dir so it survives restarts but not image rebuilds. */
object TapScriptCache {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    private val MaxShasPerRepo = 50

    private def cacheRoot: Path =
        Paths.get(
            sys.props.get("datris.tap.cache.dir")
                .orElse(sys.env.get("DATRIS_TAP_CACHE_DIR"))
                .getOrElse(sys.props("user.home") + "/datris/tap-cache")
        )

    private def repoDir(repo: String): Path =
        cacheRoot.resolve(java.util.UUID.nameUUIDFromBytes(repo.getBytes(StandardCharsets.UTF_8)).toString)

    private def entry(repo: String, sha: String, path: String): Path =
        repoDir(repo).resolve(sha).resolve(path.replace('/', '_'))

    def get(repo: String, sha: String, path: String): Option[String] = {
        val file = entry(repo, sha, path)
        if (Files.isRegularFile(file))
            try Some(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
            catch { case _: Exception => None }
        else None
    }

    def put(repo: String, sha: String, path: String, content: String): Unit = {
        try {
            val file = entry(repo, sha, path)
            Files.createDirectories(file.getParent)
            Files.write(file, content.getBytes(StandardCharsets.UTF_8))
            prune(repo)
        } catch {
            case e: Exception => logger.warn("Tap script cache write failed (non-fatal): " + e.getMessage)
        }
    }

    /** Keep the most recently touched N sha directories per repo. */
    private def prune(repo: String): Unit = {
        val dir = repoDir(repo)
        if (!Files.isDirectory(dir)) return
        val shaDirs = Files.list(dir).toArray.map(_.asInstanceOf[Path]).filter(Files.isDirectory(_))
        if (shaDirs.length > MaxShasPerRepo) {
            shaDirs.sortBy(p => Files.getLastModifiedTime(p).toMillis)
                .take(shaDirs.length - MaxShasPerRepo)
                .foreach { old =>
                    Files.walk(old).toArray.map(_.asInstanceOf[Path]).sortBy(-_.getNameCount).foreach(Files.deleteIfExists(_))
                }
        }
    }
}
