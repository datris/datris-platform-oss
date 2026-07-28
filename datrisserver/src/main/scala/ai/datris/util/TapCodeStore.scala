package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{CodeRepoConfig, DatrisEnvironment, DatrisException, TapConfig}
import org.slf4j.{Logger, LoggerFactory}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** Result of storing a script: the fields the caller stamps onto TapConfig.
  * Exactly one backend's fields are populated. */
case class StoredScript(
    storage: String,
    scriptPath: String = null,
    scriptRepoPath: String = null,
    scriptCommitSha: String = null
)

/** Storage backend for tap Python scripts. MinIO is the built-in default; the
  * github backend stores scripts in the tenant's configured code repository
  * (see plans/tap-github-storage.md). Callers resolve a backend per tap via
  * `TapCodeStore.forTap` — provider names never leak into call sites.
  */
trait TapCodeStore {

    /** Storage discriminator stamped on TapConfig ("minio" | "github"). */
    def storage: String

    /** The script source, or None if missing from the backend. */
    def readScript(tap: TapConfig): Option[String]

    /** Store the script and return the fields to stamp on the tap. `prior` is
      * the existing config (may be null for a not-yet-saved tap). */
    def storeScript(tapName: String, script: String, prior: TapConfig, actor: String): StoredScript

    /** Remove the script from the backend. Idempotent. */
    def deleteScript(tap: TapConfig): Unit

    /** Whether the script exists in the backend. */
    def scriptExists(tap: TapConfig): Boolean
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

/** Built-in backend: `{env}-config` bucket, `tap-scripts/{name}_{uuid}.py`.
  * Delegates to the pre-existing TapScriptGenerator helpers so behavior
  * (including keep-old-script-for-revert) is unchanged. */
object MinioCodeStore extends TapCodeStore {
    val storage = "minio"

    override def readScript(tap: TapConfig): Option[String] = {
        if (tap.scriptPath == null || tap.scriptPath.isEmpty) None
        else {
            val bucket = DatrisEnvironment.current.environment + "-config"
            ObjectStoreUtil.readBucketObject(bucket, tap.scriptPath)
        }
    }

    override def storeScript(tapName: String, script: String, prior: TapConfig, actor: String): StoredScript = {
        val oldPath = if (prior != null) prior.scriptPath else null
        StoredScript(storage, scriptPath = TapScriptGenerator.storeScript(tapName, script, oldPath))
    }

    override def deleteScript(tap: TapConfig): Unit =
        TapScriptGenerator.deleteScript(tap.scriptPath)

    override def scriptExists(tap: TapConfig): Boolean = readScript(tap).isDefined
}

/** Code-repository backend. Reads are pinned to the tap's recorded commit sha
  * and served from an immutable local cache; writes are commits on the
  * configured branch. Requires an enabled CodeRepoConfig. */
object GithubCodeStore extends TapCodeStore {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    val storage = "github"

    private def config: CodeRepoConfig =
        CodeRepoConfigIO.readEnabled().getOrElse(throw new DatrisException(
            "This tap stores its script in a code repository, but no enabled repository is configured. " +
                "Re-enable it under Configuration > Code Repository, or move the tap back to built-in storage."
        ))

    def scriptRepoPath(tapName: String, cfg: CodeRepoConfig): String = {
        val prefix = Option(cfg.pathPrefix).getOrElse("")
        val normalized = if (prefix.isEmpty || prefix.endsWith("/")) prefix else prefix + "/"
        normalized + tapName + ".py"
    }

    def commitMessage(cfg: CodeRepoConfig, tapName: String, action: String, actor: String): String = {
        val template = Option(cfg.commitMessageTemplate).filter(_.nonEmpty).getOrElse("tap({name}): {action} via Datris")
        template
            .replace("{name}", tapName)
            .replace("{action}", action)
            .replace("{user}", if (actor != null && actor.nonEmpty) actor else "datris")
    }

    override def readScript(tap: TapConfig): Option[String] = {
        if (tap.scriptRepoPath == null || tap.scriptRepoPath.isEmpty) return None
        val cfg = config
        val sha = tap.scriptCommitSha

        if (sha != null && sha.nonEmpty) {
            TapScriptCache.get(cfg.repo, sha, tap.scriptRepoPath) match {
                case cached @ Some(_) => cached
                case None =>
                    try {
                        GithubClient.getFile(cfg, tap.scriptRepoPath, sha).map { file =>
                            TapScriptCache.put(cfg.repo, sha, tap.scriptRepoPath, file.content)
                            file.content
                        }
                    } catch {
                        case e: Exception =>
                            // Offline fallback would have hit the cache above; with no
                            // cache entry there is nothing safe to run.
                            throw new DatrisException(
                                "Code repository is unreachable and no cached copy of '" + tap.scriptRepoPath +
                                    "' at " + sha.take(9) + " exists locally. " + e.getMessage
                            )
                    }
            }
        } else {
            // No pin yet (pre-first-save edge) — read branch head, don't cache.
            GithubClient.getFile(cfg, tap.scriptRepoPath, cfg.branch).map(_.content)
        }
    }

    override def storeScript(tapName: String, script: String, prior: TapConfig, actor: String): StoredScript = {
        val cfg = config
        val path =
            if (prior != null && prior.scriptStorage == "github" && prior.scriptRepoPath != null && prior.scriptRepoPath.nonEmpty)
                prior.scriptRepoPath
            else scriptRepoPath(tapName, cfg)
        val action = if (prior != null && prior.scriptStorage == "github") "update" else "create"
        val baseSha = if (prior != null && prior.scriptStorage == "github") prior.scriptCommitSha else null
        val commitSha = GithubClient.putFile(cfg, path, script, commitMessage(cfg, tapName, action, actor), baseSha)
        TapScriptCache.put(cfg.repo, commitSha, path, script)
        StoredScript(storage, scriptRepoPath = path, scriptCommitSha = commitSha)
    }

    override def deleteScript(tap: TapConfig): Unit = {
        if (tap.scriptRepoPath != null && tap.scriptRepoPath.nonEmpty) {
            val cfg = config
            GithubClient.deleteFile(cfg, tap.scriptRepoPath, commitMessage(cfg, tap.name, "delete", null))
        }
    }

    override def scriptExists(tap: TapConfig): Boolean = {
        if (tap.scriptRepoPath == null || tap.scriptRepoPath.isEmpty) false
        else {
            val cfg = config
            val ref = if (tap.scriptCommitSha != null && tap.scriptCommitSha.nonEmpty) tap.scriptCommitSha else cfg.branch
            TapScriptCache.get(cfg.repo, ref, tap.scriptRepoPath).isDefined ||
            GithubClient.getFile(cfg, tap.scriptRepoPath, ref).isDefined
        }
    }

    /** Branch-head read for the drift-pull flow: returns (content, headSha),
      * bypassing the pin. */
    def pullLatest(tap: TapConfig): Option[(String, String)] = {
        val cfg = config
        val headSha = GithubClient.branchHeadSha(cfg)
        GithubClient.getFile(cfg, tap.scriptRepoPath, headSha).map { file =>
            TapScriptCache.put(cfg.repo, headSha, tap.scriptRepoPath, file.content)
            (file.content, headSha)
        }
    }
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
