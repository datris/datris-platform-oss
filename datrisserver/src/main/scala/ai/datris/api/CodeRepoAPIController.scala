package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonObject}
import ai.datris.auth.{ResolvedKeyAccess, VersionActor}
import ai.datris.model.{CodeRepoConfig, DatrisEnvironment, DatrisException}
import ai.datris.util._
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

/** Tenant code-repository connection for tap script storage, plus the per-tap
  * drift-pull and storage-migration operations. All repo traffic is
  * server-side; the UI only ever talks to these endpoints.
  * See plans/tap-github-storage.md.
  */
@RestController
@RequestMapping(Array("/api/v1"))
class CodeRepoAPIController {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    @GetMapping(path = Array("/code-repo"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getCodeRepo(@RequestHeader(name = "x-api-key", required = false) apiKey: String): ResponseEntity[String] = {
        try {
            APIKeyValidator.validate(apiKey)
            val config = CodeRepoConfigIO.read().getOrElse(CodeRepoConfig())
            new ResponseEntity[String](new Gson().toJson(config), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    @PutMapping(path = Array("/code-repo"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def putCodeRepo(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody config: CodeRepoConfig
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint PUT /code-repo called, repo: " + config.repo)
            APIKeyValidator.validate(apiKey)

            if (config.enabled) {
                if (config.repo == null || !config.repo.matches("^[^/\\s]+/[^/\\s]+$"))
                    throw new DatrisException("Repository must be in owner/repo form")
                if (config.authSecretName == null || config.authSecretName.isEmpty)
                    throw new DatrisException("A token secret is required to enable the code repository")
                // Validate the connection before persisting: token works and has
                // push rights (via the permissions block — no probe commits).
                val (_, canPush) = GithubClient.repoInfo(config)
                if (!canPush)
                    throw new DatrisException(
                        "The token can read '" + config.repo + "' but cannot push to it. It needs Contents read/write."
                    )
                // Branch must exist (surfaces typos before the first save fails).
                GithubClient.branchHeadSha(config)
            }

            // Earlier path prefixes: a changed prefix is recorded, and a body
            // without the list keeps the stored one.
            val saved = CodeRepoConfigIO.write(CodeRepoAPIController.withEarlierPrefixes(CodeRepoConfigIO.read(), config))
            new ResponseEntity[String](new Gson().toJson(saved), HttpStatus.OK)
        } catch {
            case e: DatrisException =>
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String]("{\"error\": \"" + e.getMessage.replace("\"", "'") + "\"}")
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    /** Round-trip check for the UI "Test connection" button: repo reachable,
      * token has push rights, branch exists. Tests the POSTED config so the
      * user can verify before saving. */
    @PostMapping(path = Array("/code-repo/test"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def testCodeRepo(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody config: CodeRepoConfig
    ): ResponseEntity[String] = {
        try {
            APIKeyValidator.validate(apiKey)
            if (config.repo == null || config.repo.isEmpty)
                throw new DatrisException("Repository is required")
            if (config.authSecretName == null || config.authSecretName.isEmpty)
                throw new DatrisException("A token secret is required")

            val (defaultBranch, canPush) = GithubClient.repoInfo(config)
            val branches = GithubClient.listBranches(config)
            val branchExists = branches.contains(Option(config.branch).filter(_.nonEmpty).getOrElse(defaultBranch))

            val result = new JsonObject
            result.addProperty("ok", canPush && branchExists)
            result.addProperty("canPush", canPush)
            result.addProperty("branchExists", branchExists)
            result.addProperty("defaultBranch", defaultBranch)
            result.add("branches", new Gson().toJsonTree(branches.toArray))
            if (!canPush)
                result.addProperty("error", "Token can read the repository but cannot push. It needs Contents read/write.")
            else if (!branchExists)
                result.addProperty("error", "Branch '" + config.branch + "' does not exist in " + config.repo + ".")
            new ResponseEntity[String](new Gson().toJson(result), HttpStatus.OK)
        } catch {
            case e: Exception =>
                val result = new JsonObject
                result.addProperty("ok", false)
                result.addProperty("error", Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
                new ResponseEntity[String](new Gson().toJson(result), HttpStatus.OK)
        }
    }

    /** Drift pull: re-read a repo-backed tap's script at the branch head. If
      * the head differs from the tap's pin, returns the latest source so the
      * UI can offer it; `apply=true` also advances the pin (a versioned edit).
      */
    @PostMapping(path = Array("/tap/script/pull"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def pullScript(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: java.util.Map[String, String],
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            val tapName = body.get("tapName")
            val apply = "true".equalsIgnoreCase(body.get("apply"))
            APIKeyValidator.validate(apiKey)
            if (tapName == null || tapName.isEmpty)
                throw new DatrisException("tapName is required")
            TapAPIController.restrictedRefusal(request, tapName, "update").foreach(return _)

            val tap = TapConfigIO.read(DatrisEnvironment.current.tapTableName, tapName)
            if (tap == null)
                throw new DatrisException("Tap: " + tapName + " not found")
            if (tap.scriptStorage != "github")
                throw new DatrisException("Tap '" + tapName + "' does not store its script in a code repository")

            val result = new JsonObject
            GithubCodeStore.pullLatest(tap) match {
                case None =>
                    result.addProperty("found", false)
                    result.addProperty("drifted", false)
                case Some((content, headSha)) =>
                    // Drift means THIS tap's script changed, not that the branch
                    // moved: comparing the pin against the branch-head sha flags
                    // every repo-backed tap as drifted whenever ANY other file in
                    // the repo gets a commit (each tap save moves the shared head).
                    // Compare the file's content at head with the pinned commit's
                    // content instead; an unreadable pin counts as drifted so the
                    // user still gets a runnable copy.
                    val pinnedContent: Option[String] =
                        if (tap.scriptCommitSha == null || tap.scriptCommitSha.isEmpty) None
                        else
                            try GithubCodeStore.readScript(tap)
                            catch { case _: Exception => None }
                    val drifted = !pinnedContent.contains(content)
                    result.addProperty("found", true)
                    result.addProperty("drifted", drifted)
                    result.addProperty("headCommitSha", headSha)
                    result.addProperty("pinnedCommitSha", tap.scriptCommitSha)
                    if (drifted) result.addProperty("script", content)
                    if (drifted && apply) {
                        TapConfigIO.writeVersioned(
                            tap.copy(scriptCommitSha = headSha),
                            "pulled external repo edits (" + headSha.take(9) + ")",
                            VersionActor.resolve(request)
                        )
                        result.addProperty("applied", true)
                    }
            }
            new ResponseEntity[String](new Gson().toJson(result), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    /** Move a tap's script between backends. Reads from the current backend,
      * writes to the target, and stamps the tap (a versioned edit). MinIO
      * objects are never deleted on migrate-out — version snapshots pin them.
      */
    @PostMapping(path = Array("/tap/migrate-storage"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def migrateStorage(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: java.util.Map[String, String],
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /tap/migrate-storage called, tapName: " + body.get("tapName") + ", target: " + body.get("target"))
            APIKeyValidator.validate(apiKey)
            migrateStorageWith(body, request, TapAPIController.ProductionUploadIO)
        } catch {
            case e: DatrisException =>
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String]("{\"error\": \"" + e.getMessage.replace("\"", "'") + "\"}")
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    /** The storage move after key validation, over `io`'s store reads and
      * writes. A move into the code repository writes the tap's own path
      * `<prefix><tap>.py`, so it is refused, with nothing written, when a
      * stored tap of another name runs its script from that file (rule S2,
      * [[TapAPIController.uploadTargetRefusal]]). */
    private[datris] def migrateStorageWith(
        body: java.util.Map[String, String],
        request: HttpServletRequest,
        uploadIO: TapAPIController.TapScriptUploadIO
    ): ResponseEntity[String] = {
        val io = uploadIO.memoised
        try {
            val tapName = body.get("tapName")
            val target = body.get("target")
            if (tapName == null || tapName.isEmpty)
                throw new DatrisException("tapName is required")
            TapAPIController.restrictedRefusal(request, tapName, "update", io.readTap).foreach(return _)
            if (target != "github" && target != "minio")
                throw new DatrisException("target must be 'github' or 'minio'")

            val tap = io.readTap(tapName)
            if (tap == null)
                throw new DatrisException("Tap: " + tapName + " not found")

            val currentStore = io.storeFor(null, tap)
            if (currentStore.storage == target) {
                // Idempotent: already there.
                return new ResponseEntity[String](new Gson().toJson(tap), HttpStatus.OK)
            }

            val script = currentStore.readScript(tap).getOrElse(
                throw new DatrisException("Tap '" + tapName + "' has no readable script to migrate")
            )
            val targetStore = io.storeFor(target, tap)
            // The move writes with no prior reference: never over the file
            // another stored tap runs from.
            TapAPIController.uploadTargetRefusal(request, tapName, targetStore, null, io).foreach(return _)
            val actor = ResolvedKeyAccess.keyLabel(request).orNull
            val stored = targetStore.storeScript(tapName, script, null, actor)
            val migrated = io.writeVersioned(
                tap.copy(
                    scriptStorage = stored.storage,
                    scriptPath = stored.scriptPath,
                    scriptRepoPath = stored.scriptRepoPath,
                    scriptCommitSha = stored.scriptCommitSha
                ),
                "moved script storage to " + target,
                VersionActor.resolve(request)
            )
            new ResponseEntity[String](new Gson().toJson(migrated), HttpStatus.OK)
        } catch {
            case e: DatrisException =>
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String]("{\"error\": \"" + e.getMessage.replace("\"", "'") + "\"}")
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }
}

object CodeRepoAPIController {

    /** What `PUT /code-repo` stores for `incoming` over the `stored`
      * configuration: the earlier path prefixes are the body's list, or the
      * stored list when the body has none (null); a save that changes the
      * path prefix adds the stored prefix to them (once, compared in
      * normalised form). Never null. Every other field is the body's. */
    private[datris] def withEarlierPrefixes(stored: Option[CodeRepoConfig], incoming: CodeRepoConfig): CodeRepoConfig = {
        def norm(p: String): String = TapReferenceRules.normalizedRef(Option(p).getOrElse(""))
        val base: Seq[String] =
            Option(incoming.earlierPathPrefixes)
                .map(l => scala.collection.JavaConverters.asScalaBuffer(l).toList)
                .getOrElse(stored.map(TapScriptOwnership.earlierOf).getOrElse(Nil))
                .filter(_ != null)
        val changed = stored.map(_.pathPrefix).filter(old => norm(old) != norm(incoming.pathPrefix))
        val withOld = changed match {
            case Some(old) if !base.exists(p => norm(p) == norm(old)) => base :+ Option(old).getOrElse("")
            case _ => base
        }
        incoming.copy(earlierPathPrefixes = new java.util.ArrayList[String](scala.collection.JavaConverters.seqAsJavaList(withOld)))
    }
}
