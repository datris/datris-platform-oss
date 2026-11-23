package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.{Gson, GsonBuilder, JsonElement, JsonNull, JsonParser}
import ai.datris.auth.{CapabilityCheck, CapabilityDeniedException, ResolvedKeyAccess, ResourceHiddenException, VersionActor}
import ai.datris.model.{TapConfig, DatrisEnvironment, DatrisException, EntityVersion, PipelineConfig, ValidationException}
import ai.datris.util._
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import java.time.Instant
import scala.collection.JavaConverters._

object TapAPIController {

    /** 404 for a tap name with no config (GET and DELETE /tap), with the
      * "Tap: <name> not found" error body. */
    def tapNotFound(name: String): ResponseEntity[String] =
        ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_JSON)
            .body[String](ApiErrors.errorBody("Tap: " + name + " not found"))

    /** The modes `POST /tap/run` accepts. `scratch` (TapRunner.ScratchMode)
      * is internal to the recovery agent's rehearsal and is not one of them. */
    val RunModes: Seq[String] = Seq("run", "test")

    /** The `mode` of a `POST /tap/run` body: lower-cased; missing, null or
      * empty means `test`, as before. Not trimmed, so `"run "` is not a run. */
    def requestedRunMode(raw: Any): String =
        Option(raw).map(_.toString.toLowerCase).filter(_.nonEmpty).getOrElse("test")

    /** `POST /tap/run`: 400 for a `mode` (already lower-cased) other than
      * [[RunModes]]; None otherwise. */
    def runModeRefusal(mode: String): Option[ResponseEntity[String]] =
        if (RunModes.contains(mode)) None
        else
            Some(
                ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body[String](ApiErrors.errorBody("Unknown mode '" + mode + "'. Valid values: run, test (default)."))
            )

    /** Tap lookup for POST /tap/run and POST /tap/state: Left(tapNotFound(name))
      * when no config was read for `name`, Right(config) otherwise. */
    def tapOr404(name: String, config: Option[TapConfig]): Either[ResponseEntity[String], TapConfig] =
        config.toRight(tapNotFound(name))

    /** GET /taps: the taps the caller may read (per-item scope check on
      * catalog + owner: owner=self keys, catalog-scoped keys and restricted
      * users' catalog grants); nulls dropped. */
    def visibleTaps(request: HttpServletRequest, taps: Seq[TapConfig]): Seq[TapConfig] =
        taps.filter(t => mayRead(request, t))

    /** The one per-item read rule for a STORED tap: `tap:read` on its
      * catalog and owner (`createdByKeyLabel`). False for null. */
    def mayRead(request: HttpServletRequest, t: TapConfig): Boolean =
        t != null && ai.datris.auth.CallerVisibility.mayReadStored(CapabilityCheck.resolvedKey(request), "tap", t.catalog, t.createdByKeyLabel)

    /** GET /tap/logs: the pipeline rollup embedded in one run log (`response`
      * is the run's publisher-token status with rollup). None (the route
      * embeds JSON null) when there is no rollup or it has no jobs. A caller
      * that is not limited ([[CapabilityCheck.isLimited]]) gets
      * `response.rollup` itself and no reader is called. A limited caller
      * (restricted session, catalog- or owner-scoped key) gets what
      * `GET /pipeline/status` with that token shows it
      * ([[PipelineStatusAPIController.restrictedViewWith]]): the jobs of
      * pipelines it may read, the rollup recomputed from them when a job
      * was dropped, and scrubbed (hidden tap names `[hidden]`, preview rows
      * stripped); None when no job of the run is on a pipeline it may read. */
    def shownRollup(
        request: HttpServletRequest,
        response: ai.datris.model.PipelineStatusResponse,
        pipelines: () => Seq[PipelineConfig],
        taps: () => Seq[TapConfig],
        run: String => Option[ai.datris.model.RunLineage]
    ): Option[ai.datris.model.PipelineStatusRollup] =
        if (response == null || response.rollup == null || response.rollup.jobs == null || response.rollup.jobs.isEmpty) None
        else if (!CapabilityCheck.isLimited(request)) Some(response.rollup)
        else
            PipelineStatusAPIController.restrictedViewWith(request, response, pipelines(), taps(), run) match {
                case Right(r: ai.datris.model.PipelineStatusResponse) =>
                    Option(r.rollup).filter(ro => ro.jobs != null && !ro.jobs.isEmpty)
                case _ => None
            }

    /** GET /tap/logs/all: the run-log values (each `{tapName, ...}`) the
      * caller sees. A limited caller ([[CapabilityCheck.isLimited]]: a
      * restricted session, or an API key limited by a `catalog=` / `owner=`
      * scope): one `readAll`, then only logs whose `tapName` names a stored
      * tap it may read ([[mayRead]]); a log with a null or missing
      * `tapName`, or naming a tap no longer stored, is hidden. Everyone
      * else: `logs` itself, `readAll` not called. The response carries no
      * count; the route reads through [[visibleTapLogsPage]]. */
    def visibleTapLogs(request: HttpServletRequest, logs: Seq[JsonElement], readAll: () => Seq[TapConfig]): Seq[JsonElement] =
        if (!CapabilityCheck.isLimited(request)) logs
        else {
            val names = visibleTaps(request, Option(readAll()).getOrElse(Nil)).map(_.name).filter(_ != null).toSet
            logs.filter { l =>
                l != null && l.isJsonObject && {
                    val o = l.getAsJsonObject
                    o.has("tapName") && o.get("tapName").isJsonPrimitive && names.contains(o.get("tapName").getAsString)
                }
            }
        }

    /** Most run-log rows GET /tap/logs/all reads in one request. */
    val MaxTapLogRows = 5000

    /** GET /tap/logs/all: the newest `maxItems` run logs the caller sees,
      * `read(n)` reading the `n` newest run-log values in the window. A
      * caller that is not limited: `read(maxItems)` itself, `readAll` not
      * called. A limited caller ([[CapabilityCheck.isLimited]]): one read of
      * up to five times `maxItems` (at most [[MaxTapLogRows]]), then
      * [[visibleTapLogs]], cut to `maxItems`; past that bound the list is
      * short. */
    def visibleTapLogsPage(
        request: HttpServletRequest,
        maxItems: Int,
        read: Int => Seq[JsonElement],
        readAll: () => Seq[TapConfig]
    ): Seq[JsonElement] =
        if (!CapabilityCheck.isLimited(request)) read(maxItems)
        else {
            val n = math.min(5L * math.max(0, maxItems), MaxTapLogRows.toLong).toInt
            visibleTapLogs(request, read(math.max(n, maxItems)), readAll).take(math.max(0, maxItems))
        }

    /** Scope check on a loaded tap, mapped to a response: None when allowed,
      * the tap's 404 when hidden from a restricted user, 403 for an API
      * key's scope miss. */
    def scopeRefusal(request: HttpServletRequest, config: TapConfig, action: String): Option[ResponseEntity[String]] =
        try {
            CapabilityCheck.assertVisibleToWrite(request, "tap", action, CatalogOps.scopeContext(config.catalog, config.createdByKeyLabel))
            None
        } catch {
            case _: ResourceHiddenException => Some(tapNotFound(config.name))
            case e: CapabilityDeniedException => Some(PipelineAPIController.capabilityDenied(e))
        }

    private def readTap(name: String): TapConfig = TapConfigIO.read(DatrisEnvironment.current.tapTableName, name)

    /** The refusal for a tap named in a request: a restricted user's session
      * gets 404 when it is missing or outside the grants and 403 when visible
      * but `action` is not granted; an API key whose scope refuses `action`
      * on the stored tap gets the capability-denied 403
      * (plans/stories/scoped-key-read-routes.md). The tap is read only for
      * those callers. None for everyone else and for a key naming a missing
      * tap (the route answers as today). */
    def restrictedRefusal(request: HttpServletRequest, name: String, action: String): Option[ResponseEntity[String]] =
        restrictedRefusal(request, name, action, readTap)

    /** [[restrictedRefusal]] with the tap read given. */
    def restrictedRefusal(request: HttpServletRequest, name: String, action: String, read: String => TapConfig): Option[ResponseEntity[String]] =
        if (CapabilityCheck.isRestrictedSession(request))
            Option(read(name)) match {
                case None => Some(tapNotFound(name))
                case Some(t) => scopeRefusal(request, t, action)
            }
        else if (!keyCheckNeeded(request, action)) None
        else Option(read(name)).flatMap(t => keyRefusalOn(request, t, action))

    /** [[restrictedRefusal]] for routes that also serve a tap not saved yet
      * (generate / fix from the wizard): only an EXISTING tap is checked. */
    def restrictedRefusalIfExists(request: HttpServletRequest, name: String, action: String): Option[ResponseEntity[String]] =
        restrictedRefusalIfExists(request, name, action, readTap)

    /** [[restrictedRefusalIfExists]] with the tap read given. */
    def restrictedRefusalIfExists(
        request: HttpServletRequest,
        name: String,
        action: String,
        read: String => TapConfig
    ): Option[ResponseEntity[String]] =
        if (name == null || name.isEmpty) None
        else if (CapabilityCheck.isRestrictedSession(request)) Option(read(name)).flatMap(t => scopeRefusal(request, t, action))
        else if (!keyCheckNeeded(request, action)) None
        else Option(read(name)).flatMap(t => keyRefusalOn(request, t, action))

    /** Whether an API key's scope can refuse `action` on a stored tap, so
      * the tap is read only then: `read` needs a scoped read; a write is
      * decided by [[storedTapWriteRefusal]], which can refuse when the key's
      * create, update or read is scoped. */
    private def keyCheckNeeded(request: HttpServletRequest, action: String): Boolean =
        if (action == "read") CapabilityCheck.keyScopeApplies(request, "tap", "read")
        else (ai.datris.auth.CallerVisibility.TapWriteActions :+ "read").exists(a => CapabilityCheck.keyScopeApplies(request, "tap", a))

    /** An API key's scope refusal for `action` on a loaded tap (403), or
      * None. `read` checks tap read; any other action is a write and is
      * decided by the one stored-tap write rule `POST /tap` uses
      * ([[storedTapWriteRefusal]]). */
    private def keyRefusalOn(request: HttpServletRequest, t: TapConfig, action: String): Option[ResponseEntity[String]] =
        if (action == "read") PipelineAPIController.keyRefusal(request, "tap", "read", CatalogOps.scopeContext(t.catalog, t.createdByKeyLabel))
        else storedTapWriteRefusal(request, t.catalog, t.createdByKeyLabel)

    /** The one rule for an API key writing a stored tap with scope
      * (`catalog`, `owner`): [[ai.datris.auth.CallerVisibility.storedWriteDenial]]
      * with `tap:create` or `tap:update` accepted, the refusal naming
      * `update`, then `tap:read`. Used by `POST /tap` ([[saveGuardDecision]]),
      * every tap write sub-route ([[restrictedRefusal]] /
      * [[restrictedRefusalIfExists]] with a write action) and tap version
      * restore, so they cannot drift apart. */
    def storedTapWriteRefusal(request: HttpServletRequest, catalog: String, owner: String): Option[ResponseEntity[String]] =
        PipelineAPIController.storedWriteRefusal(
            request,
            "tap",
            CatalogOps.scopeContext(catalog, owner),
            ai.datris.auth.CallerVisibility.TapWriteActions,
            "update"
        )

    /** Refusal when the tap's `targetPipeline` is outside the caller's
      * scope. A restricted session: missing or outside the grants (pipeline
      * 404), visible but not runnable (403). An API key checked on loads
      * ([[ai.datris.auth.CallerVisibility.loadChecked]]: it holds a
      * `pipeline:run` capability or is limited for pipelines, the same keys
      * as upload and kill_job; owner decisions), whether or not it holds a
      * pipeline capability: 403
      * when its scope does not grant, on the stored target, `pipeline:run`
      * (when it holds any `pipeline:run`) and `pipeline:read`
      * ([[ai.datris.auth.CallerVisibility.loadDenial]]), so a key holding
      * only tap capabilities feeds no stored pipeline; a target not stored
      * yet is left to the save. None when no target and for everyone else
      * (keys with no `pipeline:run` that are not limited for pipelines; no
      * request),
      * who read nothing here. */
    def targetPipelineRefusal(
        request: HttpServletRequest,
        tap: TapConfig,
        readPipeline: String => PipelineConfig = n => PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, n)
    ): Option[ResponseEntity[String]] =
        if (tap == null || tap.targetPipeline == null || tap.targetPipeline.trim.isEmpty) None
        else if (CapabilityCheck.isRestrictedSession(request))
            PipelineAPIController.refusalFor(request, tap.targetPipeline, readPipeline(tap.targetPipeline), "run")
        else
            CapabilityCheck.resolvedKey(request) match {
                case Some(rk) if ai.datris.auth.CallerVisibility.loadChecked(rk) =>
                    Option(readPipeline(tap.targetPipeline)).flatMap { p =>
                        val ctx = CatalogOps.scopeContext(p.catalog, p.createdByKeyLabel)
                        ai.datris.auth.CallerVisibility.loadDenial(rk, ctx).map { action =>
                            try {
                                CapabilityCheck.assertScope(request, "pipeline", action, ctx)
                                PipelineAPIController.capabilityDenied(new CapabilityDeniedException("capability denied"))
                            } catch {
                                case e: CapabilityDeniedException => PipelineAPIController.capabilityDenied(e)
                            }
                        }
                    }
                case _ => None
            }

    /** Report only (plans/stories/tap-runs-hold-owner-scope.md, rule 1):
      * called by `POST /tap/test` and `POST /tap/run` (every mode) right after
      * the run, before the response is built. One WARN line and one
      * `tap/reads-narrowed` audit row when the caller is limited and the
      * script read platform data through the callback. Reads nothing, never
      * marks the request as audited, never throws. */
    def reportCallerReads(request: HttpServletRequest, tap: TapConfig, result: TapScriptResult): Unit =
        TapOwnerScopeReport.callerReads(request, tap, result)

    /** Report only (rule 6): called by `POST /tap/script` after the script
      * was stored, with the stored tap. One WARN line when the save-time
      * target rule ([[targetPipelineRefusal]]) would refuse the stored target
      * for the caller. Never throws. */
    def reportScriptTarget(request: HttpServletRequest, tap: TapConfig, readPipeline: String => PipelineConfig): Unit =
        TapOwnerScopeReport.scriptTarget(request, tap, readPipeline)

    /** `read` answering each name from at most one call (a throw is not kept). */
    private[api] def readOnce(read: String => PipelineConfig): String => PipelineConfig = {
        val held = new java.util.concurrent.ConcurrentHashMap[String, Option[PipelineConfig]]()
        n =>
            if (n == null) read(n)
            else {
                val hit = held.get(n)
                if (hit != null) hit.orNull
                else {
                    val p = Option(read(n))
                    held.put(n, p)
                    p.orNull
                }
            }
    }

    /** POST /tap (`saveTapInternal`), every caller, decided on `stored` (the
      * tap of that name, or null). A restricted session: the catalog rules
      * ([[catalogForSave]]) as before. An API key
      * (plans/stories/scoped-key-read-routes.md, Design 4): a stored tap is
      * written only under [[storedTapWriteRefusal]] (the rule every tap
      * write sub-route uses: 403 on `update` when the key holds neither
      * `tap:create` nor `tap:update` on it, else 403 on `tap:read` when it
      * cannot read it, naming nothing of it), and the tap as it would be
      * saved (the body's catalog, the stored owner or the caller's label)
      * must be in scope: `tap:create` for a new name (403 on `create`),
      * `tap:create` or `tap:update` over a stored one (403 on `update`).
      * Right is the config to save; keys get no catalog defaulting. Reads
      * nothing. */
    def saveGuardDecision(request: HttpServletRequest, config: TapConfig, stored: TapConfig): Either[ResponseEntity[String], TapConfig] =
        if (CapabilityCheck.isRestrictedSession(request)) catalogForSave(request, config, stored)
        else
            CapabilityCheck.resolvedKey(request) match {
                case Some(rk) if config != null =>
                    import ai.datris.auth.CallerVisibility.keyOutOfScope
                    def denied(action: String, ctx: Map[String, String]): Left[ResponseEntity[String], TapConfig] =
                        try {
                            CapabilityCheck.assertScope(request, "tap", action, ctx)
                            Left(PipelineAPIController.capabilityDenied(new CapabilityDeniedException("capability denied")))
                        } catch {
                            case e: CapabilityDeniedException => Left(PipelineAPIController.capabilityDenied(e))
                        }
                    val overwrite = ai.datris.auth.CallerVisibility.TapWriteActions
                    if (stored != null) storedTapWriteRefusal(request, stored.catalog, stored.createdByKeyLabel).foreach(r => return Left(r))
                    val owner = if (stored != null) stored.createdByKeyLabel else rk.label
                    val ctx = CatalogOps.scopeContext(config.catalog, owner)
                    val acceptable = if (stored != null) overwrite else Seq("create")
                    if (acceptable.exists(a => keyOutOfScope(rk, "tap", a, ctx)) && !acceptable.exists(a => rk.grants("tap", a, ctx)))
                        denied(if (stored != null) "update" else "create", ctx)
                    else Right(config)
                case _ => Right(config)
            }

    /** Tap version restore of a DELETED tap (owner decision): the restore
      * re-creates the tap, so the caller needs what `POST /tap` needs to
      * create that tap: `tap:create` held at all (the capability `POST /tap`'s
      * route gate requires; the restore route requires only `tap:update`), and
      * in scope on the tap as re-created, its catalog with the caller as owner
      * ([[saveGuardDecision]] with no stored tap). A key is refused 403 on
      * `create` through `CapabilityCheck.assertScope` (shared wording). None
      * for full access, unrestricted sessions holding `tap:create` (every
      * role bundle that holds `tap:update`), no identity and a null request;
      * a restricted session is answered by [[saveGuardDecision]] (its catalog
      * rules), though the restore hides a deleted tap's history from it
      * first. Reads nothing. */
    def recreateRefusal(request: HttpServletRequest, config: TapConfig): Option[ResponseEntity[String]] =
        CapabilityCheck.resolvedKey(request) match {
            case Some(rk) if config != null && !rk.restrictedSession && !ai.datris.auth.CallerVisibility.holdsAny(rk, "tap", "create") =>
                val ctx = CatalogOps.scopeContext(config.catalog, rk.label)
                try {
                    CapabilityCheck.assertScope(request, "tap", "create", ctx)
                    Some(PipelineAPIController.capabilityDenied(new CapabilityDeniedException("capability denied")))
                } catch {
                    case e: CapabilityDeniedException => Some(PipelineAPIController.capabilityDenied(e))
                }
            case _ => saveGuardDecision(request, config, null).left.toOption
        }

    /** POST /tap/test (plans/stories/scoped-key-read-routes.md, rows 10 and
      * D4), before anything runs. A test runs the body's script with the
      * body's secret under the body's name, so for a restricted session and
      * for an API key whose tap read is scoped: (1) a stored tap of that name
      * must be one the caller may read; (2) the body's `secretName`, when it
      * names an existing secret, is checked as the tap as tested would be:
      * `tap:read` on the body's catalog (else the stored tap's) with the
      * secret's owner (`createdByKeyLabel`) as owner, so an `owner=self` key
      * tests only with secrets it created and a catalog-scoped key only a tap
      * of its catalog. A restricted session gets the tap's 404, a key the
      * capability-denied 403 on `tap:read`. `secretOwner`: outer None = no
      * such secret (left to the run's missing-secret error), inner = its
      * owner. Nothing is read for anyone else. */
    def testRefusal(
        request: HttpServletRequest,
        body: TapConfig,
        read: String => TapConfig,
        secretOwner: String => Option[Option[String]]
    ): Option[ResponseEntity[String]] = {
        val session = CapabilityCheck.isRestrictedSession(request)
        if (body == null || !session && !CapabilityCheck.keyScopeApplies(request, "tap", "read")) return None
        val name = Option(body.name).filter(_.nonEmpty).orNull
        val stored = if (name == null) null else read(name)
        if (stored != null) {
            val r = if (session) scopeRefusal(request, stored, "read") else keyRefusalOn(request, stored, "read")
            if (r.isDefined) return r
        }
        val secret = Option(body.secretName).map(_.trim).filter(_.nonEmpty)
        secret.flatMap(secretOwner).flatMap { owner =>
            val catalog = Option(body.catalog).filter(_.nonEmpty).orElse(Option(stored).flatMap(s => Option(s.catalog))).orNull
            val ctx = CatalogOps.scopeContext(catalog, owner.orNull)
            if (session) {
                if (CapabilityCheck.grants(request, "tap", "read", ctx)) None
                else Some(tapNotFound(Option(name).getOrElse("")))
            } else PipelineAPIController.keyRefusal(request, "tap", "read", ctx)
        }
    }

    /** The owner tag of a secret of the current environment, for
      * [[testRefusal]]: None when it does not exist or its name is not a
      * safe path (the run refuses it), else its `createdByKeyLabel`. A
      * failed secret-store read throws, so the test is refused (500) rather
      * than run as if the secret did not exist. */
    private[datris] def liveSecretOwner(name: String): Option[Option[String]] = {
        val path = DatrisEnvironment.current.environment + "/" + name
        if (!SecretNames.isSafePath(path)) None
        else SecretsUtil.tryGetSecretMap(path).get.map(m => Option(m.get("createdByKeyLabel")).filter(_.nonEmpty))
    }

    /** Whether POST /tap must read the stored tap before deciding: a
      * restricted session, or an API key whose scope can refuse a tap read
      * or create ([[saveGuardDecision]]). */
    def saveGuardNeeded(request: HttpServletRequest): Boolean =
        CapabilityCheck.isRestrictedSession(request) ||
            CapabilityCheck.keyScopeApplies(request, "tap", "read") ||
            CapabilityCheck.keyScopeApplies(request, "tap", "create") ||
            CapabilityCheck.keyScopeApplies(request, "tap", "update")

    /** The stored pipeline of a name, as [[targetPipelineRefusal]] reads it
      * by default; tap version restore passes it to its guard explicitly. */
    private[api] val readPipelineForSave: String => PipelineConfig = n => PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, n)

    /** The store reads and the versioned write the tap save path
      * (`saveTapCore`) makes, so specs can drive every save check on a save
      * and on a version restore without Mongo, Vault or a script store.
      * [[ProductionSaveIO]] is what `POST /tap`, the catalog restore and the
      * version restore use; the recovery agent's tap revert uses it too.
      * `scriptExists` and `readScript` may throw; the core handles that as
      * before. `secretOwner` and `repoPathPrefix` serve the script
      * reference and secret rules ([[referenceRefusal]]) only. */
    private[datris] case class TapSaveIO(
        readTap: String => TapConfig,
        readPipeline: String => PipelineConfig,
        secretType: String => Option[Option[String]],
        scriptExists: TapConfig => Boolean,
        readScript: TapConfig => Option[String],
        loadAll: () => (Seq[TapConfig], Seq[PipelineConfig]),
        writeVersioned: (TapConfig, String, String) => TapConfig,
        secretOwner: String => Option[Option[String]] = n => TapAPIController.liveSecretOwner(n),
        repoPathPrefix: () => String = () => TapAPIController.liveRepoPathPrefix()
    )

    /** The enabled code repository's path prefix as the repository store
      * writes it ("" or ending in `/`); "" when none is enabled. */
    private[datris] def liveRepoPathPrefix(): String =
        CodeRepoConfigIO.readEnabled().map(c => TapReferenceRules.normalizePrefix(c.pathPrefix)).getOrElse("")

    /** `f` answering from at most one call; a throw is not kept. */
    private[api] def once[T](f: () => T): () => T = {
        val held = new java.util.concurrent.atomic.AtomicReference[Option[T]](None)
        () =>
            held.get match {
                case Some(v) => v
                case None =>
                    val v = f()
                    held.set(Some(v))
                    v
            }
    }

    /** The script reference and secret rules at `site`
      * ([[TapReferenceRules.judge]] for the request's caller): Some(refusal)
      * for the first enforced finding, the answer `POST /tap/test` gives a
      * tap the caller may not use (a restricted session: the 404 of the tap
      * named in `candidate`; an API key: the shared 403 on `tap:read` through
      * `CapabilityCheck.assertScope`), the same for every reason and naming
      * nothing of another tap; nothing is reported then. Otherwise None, and
      * each reported finding is logged and audited
      * ([[TapOwnerScopeReport.referenceWouldBeRefused]]). A report never
      * throws; a lookup an enforced check needs that fails is thrown. */
    def referenceRefusal(
        request: HttpServletRequest,
        site: String,
        candidate: TapConfig,
        stored: TapConfig,
        lookups: TapReferenceRules.Lookups
    ): Option[ResponseEntity[String]] = {
        val caller = CapabilityCheck.resolvedKey(request)
        val findings = TapReferenceRules.judge(caller, site, candidate, stored, lookups)
        if (findings.exists(_.mode == TapReferenceRules.Enforced)) Some(referenceRefused(request, candidate))
        else {
            findings.foreach(f => TapOwnerScopeReport.referenceWouldBeRefused(request, candidate.name, f.rule, site, f.reason))
            None
        }
    }

    private def referenceRefused(request: HttpServletRequest, candidate: TapConfig): ResponseEntity[String] =
        if (CapabilityCheck.isRestrictedSession(request)) tapNotFound(Option(candidate.name).getOrElse(""))
        else
            try {
                CapabilityCheck.assertScope(request, "tap", "read", Map.empty)
                PipelineAPIController.capabilityDenied(
                    new CapabilityDeniedException(
                        CapabilityCheck.resolvedKey(request).map(rk => CapabilityCheck.scopeDenialMessage(rk, "tap", "read")).getOrElse("capability denied")
                    )
                )
            } catch {
                case e: CapabilityDeniedException => PipelineAPIController.capabilityDenied(e)
            }

    /** Rule 2 of plans/stories/tap-save-references-in-scope.md: a request
      * names a tap only with a `tapName` that is not missing, null or blank. */
    private[api] def namesTap(tapName: String): Boolean = tapName != null && tapName.trim.nonEmpty

    /** The `error` a review, generate, fix or optimize response carries when
      * the caller may not update the stored tap, which is left unchanged. */
    val StoredTapLeftUnchanged = "The stored tap was left unchanged: this caller may not update it"

    /** Whether the review, generate, fix and optimize routes may change the
      * stored `tap`: no identity, yes; a restricted session, when its grants
      * allow `tap:update` on the tap (what [[scopeRefusal]] with `update`
      * checks); any other identity, when it holds `tap:create` or
      * `tap:update` granting on the tap's catalog and owner and the stored
      * tap write rule ([[storedTapWriteRefusal]]) does not refuse it. Pure:
      * notes no denial. */
    def mayWriteStoredTap(request: HttpServletRequest, tap: TapConfig): Boolean =
        CapabilityCheck.resolvedKey(request) match {
            case None => true
            case Some(rk) =>
                val ctx = CatalogOps.scopeContext(tap.catalog, tap.createdByKeyLabel)
                if (rk.restrictedSession) rk.grants("tap", "update", ctx)
                else
                    ai.datris.auth.CallerVisibility.TapWriteActions.exists(a => rk.grants("tap", a, ctx)) &&
                    ai.datris.auth.CallerVisibility.storedWriteDenial(rk, "tap", ctx, ai.datris.auth.CallerVisibility.TapWriteActions, "update").isEmpty
        }

    /** True when the route gate let `request` through only because
      * `CAPABILITY_ENFORCEMENT=log-only` (it noted a would-deny,
      * [[ai.datris.config.CapabilityInterceptor.LogOnlyWouldDenyAttr]]).
      * Log-only refuses nothing, so review, generate, fix and optimize then
      * change the stored tap as v1.61.0 did instead of applying
      * [[mayWriteStoredTap]]. A caller that holds the route's capability
      * (for review, tap read) is not marked, so rule 1 holds for it in both
      * modes. */
    private[api] def passedGateOnlyInLogOnly(request: HttpServletRequest): Boolean =
        request != null && request.getAttribute(ai.datris.config.CapabilityInterceptor.LogOnlyWouldDenyAttr) != null

    /** The store reads, writes, secret reads and model calls of the review,
      * generate, fix and optimize routes, so specs can drive them without
      * Mongo, Vault, a script store or a model. [[ProductionAiIO]] makes the
      * same calls the routes made inline. */
    private[api] case class TapAiIO(
        readTap: String => TapConfig,
        readScript: TapConfig => Option[String],
        write: TapConfig => Unit,
        writeVersioned: (TapConfig, String, String) => TapConfig,
        secretType: String => Option[Option[String]],
        secretOwner: String => Option[Option[String]],
        generate: (String, String, String, String, String) => TapGenerateResult,
        fix: (String, String, String, String, String, String, List[ai.datris.model.IterationRecord]) => TapFixResult,
        review: (String, String, Int, Long, String, String, List[ai.datris.model.IterationRecord]) => TapReviewResult,
        optimize: (String, String, Int, Long, String, String, List[ai.datris.model.IterationRecord]) => TapOptimizeResult
    )

    private[api] lazy val ProductionAiIO: TapAiIO = TapAiIO(
        readTap = n => TapConfigIO.read(DatrisEnvironment.current.tapTableName, n),
        readScript = t => TapCodeStore.forTap(t).readScript(t),
        write = t => { TapConfigIO.write(t); () },
        writeVersioned = (t, note, actor) => TapConfigIO.writeVersioned(t, note, actor),
        secretType = n => liveSecretType(n),
        secretOwner = n => liveSecretOwner(n),
        generate = (description, tapName, oldScriptPath, secretName, tapType) =>
            TapScriptGenerator.generate(description, tapName, oldScriptPath, secretName, tapType),
        fix = (tapName, script, diagnosis, logs, error, oldScriptPath, prior) =>
            TapScriptFixer.fix(tapName, script, diagnosis, logs, error, oldScriptPath, prior),
        review = (tapName, script, recordCount, durationMs, logs, oldScriptPath, prior) =>
            TapScriptReviewer.review(tapName, script, recordCount, durationMs, logs, oldScriptPath, prior),
        optimize = (tapName, script, recordCount, durationMs, logs, oldScriptPath, prior) =>
            TapScriptOptimizer.optimize(tapName, script, recordCount, durationMs, logs, oldScriptPath, prior)
    )

    /** The production store wiring of [[TapSaveIO]]: the same calls the save
      * path made inline. */
    private[datris] lazy val ProductionSaveIO: TapSaveIO = TapSaveIO(
        readTap = n => TapConfigIO.read(DatrisEnvironment.current.tapTableName, n),
        readPipeline = readPipelineForSave,
        secretType = n => liveSecretType(n),
        scriptExists = t => TapCodeStore.forTap(t).scriptExists(t),
        readScript = t => TapCodeStore.forTap(t).readScript(t),
        loadAll = () => loadAllForDependencies(),
        writeVersioned = (t, note, actor) => TapConfigIO.writeVersioned(t, note, actor),
        secretOwner = n => liveSecretOwner(n),
        repoPathPrefix = () => liveRepoPathPrefix()
    )

    /** A tap version restore through the save path: the live tap the
      * restore decided on (null when deleted) and the version restored. */
    case class RestoreOf(live: TapConfig, version: Int)

    /** 409 when the tap changed while a version restore was being checked:
      * nothing was restored. */
    def restoreConflict(name: String): ResponseEntity[String] =
        ResponseEntity
            .status(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_JSON)
            .body[String](ApiErrors.errorBody("Tap '" + name + "' changed while it was being restored; nothing was restored. Try again."))

    /** POST /tap catalog rules for a restricted user's session (no-op for
      * everyone else); see [[RestrictedCatalogRules.catalogForSave]]. */
    def catalogForSave(request: HttpServletRequest, config: TapConfig, existing: TapConfig): Either[ResponseEntity[String], TapConfig] =
        RestrictedCatalogRules.catalogForSave(
            request,
            "tap",
            config.catalog,
            existing != null,
            if (existing == null) null else existing.catalog,
            if (existing == null) null else existing.createdByKeyLabel,
            tapNotFound(config.name)
        ).right.map {
            case Some(c) if c != config.catalog => config.copy(catalog = c)
            case _ => config
        }

    /** Some(message) when the tap's secretName names a server-managed secret
      * (SecretNames.ServerManaged); checked on save. TapScriptRunner refuses
      * the same at run time for taps saved before this check. */
    private[api] def serverManagedSecretProblem(tap: TapConfig): Option[String] =
        Option(tap.secretName).flatMap(SecretNames.referenceProblem)

    /** Some(message) when the tap may not be saved with its secretName: a
      * server-managed or unsafe name (always, whatever the scope), or — when
      * `enforced` — a secret not tagged `_type=tap`. `typeOf(secretName)`:
      * outer None = the secret does not exist (left to the run-time
      * missing-secret error), inner Option = its stored `_type`. Applies to
      * script and HTTP taps alike (endpoint_token lives in the same secret). */
    def tapSecretSaveProblem(tap: TapConfig, typeOf: String => Option[Option[String]], enforced: Boolean): Option[String] =
        serverManagedSecretProblem(tap).orElse {
            val name = tap.secretName
            if (name == null || name.trim.isEmpty) None
            else typeOf(name).flatMap(storedType => SecretNames.tapSecretProblem(tap.name, name, storedType, enforced))
        }

    /** The stored `_type` of a secret in the current environment: None when
      * it does not exist, Some(None) when it has no `_type`. A failed read
      * throws (the save fails closed) rather than passing as "absent". */
    private[datris] def liveSecretType(name: String): Option[Option[String]] =
        SecretsUtil.tryGetSecretMap(DatrisEnvironment.current.environment + "/" + name).get.map(m => Option(m.get("_type")))

    /** The refusal `POST /tap` returns for the tap's `dependsOn`, or None.
      * `catalogTaps` = the stored taps of the tap's catalog; a stored copy of
      * the incoming tap is replaced by it before the cycle check. 400 for a
      * name that is not a tap of the catalog (or the tap itself); 409 with
      * `cycle` when the declared list closes a dependency cycle. An absent or
      * empty `dependsOn` always passes. */
    def dependencySaveProblem(tap: TapConfig, catalogTaps: Seq[TapConfig]): Option[ResponseEntity[String]] = {
        if (tap.dependsOn == null || tap.dependsOn.isEmpty) return None
        if (tap.dependsOn.asScala.exists(n => n == null || n.trim.isEmpty)) {
            val o = new com.google.gson.JsonObject
            o.addProperty("error", "dependsOn may not contain blank tap names; send [] to declare that the tap reads no other tap")
            return Some(ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body[String](o.toString))
        }
        val others = catalogTaps.filter(t => t != null && t.name != tap.name)
        TapDependencies.validate(tap, others) match {
            case Left(msg) =>
                val o = new com.google.gson.JsonObject
                o.addProperty("error", msg)
                return Some(ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body[String](o.toString))
            case Right(_) =>
        }
        TapDependencies.cycles(others :+ tap).find(_.contains(tap.name)).map { cyc =>
            val o = new com.google.gson.JsonObject
            o.addProperty(
                "error",
                "dependsOn of tap '" + tap.name + "' closes a dependency cycle: " + cyc.mkString(", ") +
                    ". Remove one of these dependencies (an explicit dependsOn of [] overrides the inferred list)."
            )
            val arr = new com.google.gson.JsonArray
            cyc.foreach(n => arr.add(n))
            o.add("cycle", arr)
            ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body[String](o.toString)
        }
    }

    /** The tap as stored: any incoming `inferredDependsOn` is discarded and
      * replaced by `TapDependencies.infer` over `script` (None, e.g. an HTTP
      * tap or no script yet -> null; nothing inferred -> null). The explicit
      * `dependsOn` is left as sent. */
    def stampInferredDependsOn(
        tap: TapConfig,
        script: Option[String],
        pipelines: Seq[PipelineConfig],
        taps: Seq[TapConfig]
    ): TapConfig = {
        val inferred = script.map(s => TapDependencies.infer(s, pipelines, taps, tap))
        // A script that was read but reads no other tap stamps [] (analysed,
        // nothing found), so it is not re-analysed by the lazy backfill.
        tap.copy(inferredDependsOn = inferred.map(l => new java.util.ArrayList[String](l.asJava): java.util.List[String]).orNull)
    }

    private val depLogger: Logger = LoggerFactory.getLogger("ai.datris.api.TapDependencies")

    /** Every tap and pipeline for dependency validation and inference,
      * skipping any document that fails to parse so one bad config cannot
      * block a tap save. */
    private[datris] def loadAllForDependencies(): (Seq[TapConfig], Seq[PipelineConfig]) = {
        val env = DatrisEnvironment.current
        val taps = NoSQLDbUtil.getItemsKeysByKeyName(env.tapTableName, "name").flatMap { n =>
            try Option(TapConfigIO.read(env.tapTableName, n))
            catch { case e: Exception => depLogger.warn("Dependency check: skipping unreadable tap '" + n + "': " + e.getMessage); None }
        }
        val pipelines = NoSQLDbUtil.getItemsKeysByKeyName(env.pipelineTableName, "name").flatMap { n =>
            try Option(PipelineConfigIO.read(env.pipelineTableName, n))
            catch { case e: Exception => depLogger.warn("Dependency check: skipping unreadable pipeline '" + n + "': " + e.getMessage); None }
        }
        (taps, pipelines)
    }

    /** Lazy backfill for taps saved before dependencies existed: when
      * `TapDependencies.needsBackfill(t)`, infer from `script` (read by the
      * caller; None = unreadable, left unstamped and retried next time) and
      * persist only `inferredDependsOn` on a fresh read of the tap. Returns
      * the tap as it should be shown. Never throws. */
    private[datris] def backfillInferred(
        t: TapConfig,
        script: Option[String],
        taps: Seq[TapConfig],
        pipelines: Seq[PipelineConfig]
    ): TapConfig = {
        if (!TapDependencies.needsBackfill(t) || script.isEmpty) return t
        try {
            val stamped = stampInferredDependsOn(t, script, pipelines, taps)
            // Read-modify-write without a version bump (like run-status
            // writes): only the stamp is copied onto a fresh read, and only
            // while the script reference is the one that was analysed.
            val fresh = TapConfigIO.read(DatrisEnvironment.current.tapTableName, t.name)
            if (
                fresh != null && fresh.inferredDependsOn == null && fresh.scriptPath == t.scriptPath &&
                fresh.scriptRepoPath == t.scriptRepoPath && fresh.scriptCommitSha == t.scriptCommitSha
            ) TapConfigIO.write(fresh.copy(inferredDependsOn = stamped.inferredDependsOn))
            stamped
        } catch {
            case e: Exception =>
                depLogger.warn("Dependency backfill failed for tap '" + t.name + "': " + e.getMessage)
                t
        }
    }

    /** Script text for the backfill; None (logged) when it cannot be read. */
    private[datris] def readScriptForBackfill(t: TapConfig): Option[String] =
        try TapCodeStore.forTap(t).readScript(t)
        catch {
            case e: Exception =>
                depLogger.warn("Dependency backfill: script of tap '" + t.name + "' unreadable: " + e.getMessage)
                None
        }

    // mode=test response caps `records` to this many rows. The UI's preview already
    // slices to 20 (tap-run.component.ts), so this matches without losing display
    // fidelity, and prevents large taps from bloating agent context.
    private val testRecordSampleSize = 20

    /** Ceiling on the `records` a test response carries when the caller asks
      * for a larger preview (a higher `limit` on propose_pipeline_schema, or
      * the Assistant card's "Pull more rows"): keeps the body bounded however
      * high `testLimit` goes. */
    private[datris] val maxTestPreviewRecords = 500

    /** The `/tap/run` body for a `reuseLastTest` request answered from `held`:
      * the usual test response (records cut to `testPreviewSize(testLimit)`)
      * plus `reused: true` and `testedAt`. No logs, no tokens: nothing ran. */
    private[api] def reusedTestResponse(tap: TapConfig, held: TapTestPreviewCache.Held, testLimit: Int): java.util.Map[String, Any] = {
        val size = testPreviewSize(testLimit)
        val cut = held.records.size() > size
        val records = new com.google.gson.JsonArray()
        (0 until math.min(size, held.records.size())).foreach(i => records.add(held.records.get(i).deepCopy()))
        val response = new java.util.HashMap[String, Any]()
        response.put("tap", tap.name)
        response.put("description", tap.description)
        response.put("status", "success")
        response.put("mode", "test")
        response.put("targetPipeline", tap.targetPipeline)
        response.put("persisted", java.lang.Boolean.FALSE)
        response.put("persistedReason", "test_mode")
        response.put("records", records)
        if (cut || held.recordsTruncated) response.put("recordsTruncated", java.lang.Boolean.TRUE)
        response.put("recordCount", Integer.valueOf(held.recordCount))
        response.put("dataType", held.dataType)
        response.put("columns", held.columns)
        response.put("reused", java.lang.Boolean.TRUE)
        response.put("testedAt", java.time.Instant.ofEpochMilli(held.capturedAtMs).toString)
        response
    }

    /** After a `mode=test` run: hold its preview for a later `reuseLastTest`
      * request only when it passed with a non-empty array preview and no
      * `params` were sent. Anything else drops the held entry: a failed test no
      * longer vouches for the stored script, an empty test must not be
      * answered with older rows, and a parameterised test's rows must never
      * feed a later plain proposal. `scriptId` is the identity captured before
      * the run; None (unreadable script) holds nothing. */
    private[api] def holdTestPreview(
        cache: TapTestPreviewCache,
        tap: TapConfig,
        result: TapScriptResult,
        preview: JsonElement,
        previewTruncated: Boolean,
        testLimit: Int,
        paramsSent: Boolean,
        scriptId: Option[String]
    ): Unit =
        preview match {
            case arr: com.google.gson.JsonArray if result.error == null && !paramsSent && arr.size() > 0 && scriptId.isDefined =>
                cache.put(
                    tap,
                    records = arr,
                    columns = result.columns,
                    recordCount = result.recordCount,
                    recordsTruncated = previewTruncated,
                    testLimit = testLimit,
                    dataType = result.dataType,
                    scriptId = scriptId
                )
            case _ => cache.drop(tap.name)
        }

    /** Whether a `/tap/run` request may answer from the tap's held last test
      * preview ([[ai.datris.util.TapTestPreviewCache]]). Only `mode=test`
      * honours it — a production run always runs the tap. `bodyReuseLastTest`
      * is `body.get("reuseLastTest")`: Boolean true or the string "true". */
    private[api] def resolveReuseLastTest(mode: String, bodyReuseLastTest: Any): Boolean = {
        if (mode == null || mode.toLowerCase != "test") return false
        bodyReuseLastTest match {
            case b: java.lang.Boolean => b.booleanValue()
            case s: String => s.trim.equalsIgnoreCase("true")
            case _ => false
        }
    }

    /** The reuse gate of `POST /tap/run`: the request asks for reuse
      * ([[resolveReuseLastTest]]) and sends no `params` (held rows come from an
      * unparameterised test, so a parameterised request always runs the tap). */
    private[api] def reuseCandidate(mode: String, bodyReuseLastTest: Any, params: Map[String, String]): Boolean =
        resolveReuseLastTest(mode, bodyReuseLastTest) && (params == null || params.isEmpty)

    /** How many staged records a test response returns: the requested
      * `testLimit` when positive (clamped to [[maxTestPreviewRecords]]),
      * else the default [[testRecordSampleSize]] — an unlimited test
      * (`testLimit` 0) still previews only the default 20. */
    private[datris] def testPreviewSize(testLimit: Int): Int =
        if (testLimit > 0) math.min(testLimit, maxTestPreviewRecords) else testRecordSampleSize

    // Server-side debounce for /tap/run with mode=run. Prevents duplicate pushes when
    // an agent emits parallel tool_use blocks, when a UI button is rapidly clicked,
    // or when MCP transport retries fire concurrent requests. Keyed by tenant + tap
    // name; mode=test bypasses the debounce since previewing has no side-effects.
    private val runDebounceWindowMs: Long = 5000L
    private val recentRunStarts: java.util.concurrent.ConcurrentHashMap[String, java.lang.Long] =
        new java.util.concurrent.ConcurrentHashMap[String, java.lang.Long]()

    /** Body of `GET /tap/generate/status`. Inactive is exactly `{active: false}`;
      * active carries phase/attempt/elapsed plus the CodeGen `provider`/`model`. */
    private[api] def generateStatusBody(
        p: Option[TapGenerationProgress.Progress],
        nowMs: Long
    ): java.util.Map[String, Any] = {
        val response = new java.util.LinkedHashMap[String, Any]()
        p match {
            case Some(p) =>
                response.put("active", java.lang.Boolean.TRUE)
                response.put("phase", p.phase)
                response.put("attempt", Integer.valueOf(p.attempt))
                response.put("elapsedSeconds", java.lang.Long.valueOf((nowMs - p.startedAtMs) / 1000))
                response.put("phaseElapsedSeconds", java.lang.Long.valueOf((nowMs - p.phaseStartedAtMs) / 1000))
                response.put("provider", p.provider)
                response.put("model", p.model)
            case None =>
                response.put("active", java.lang.Boolean.FALSE)
        }
        response
    }

    /** The current time in the configured date format and timezone (the
      * format every `last*RunTime` field is stored in). */
    private[datris] def nowStamp(): String = {
        val sdf = new java.text.SimpleDateFormat(DatrisEnvironment.current.dateFormat)
        sdf.setTimeZone(java.util.TimeZone.getTimeZone(DatrisEnvironment.current.dateTimezone))
        sdf.format(new java.util.Date())
    }

    /** `tap` with the outcome of a `mode=test` run stamped on its
      * `lastTestRun*` fields. Shared by `POST /tap/run` and the ordered
      * catalog enable so a tap tested by either is indistinguishable. */
    private[datris] def stampTestResult(tap: TapConfig, result: TapScriptResult, now: String): TapConfig = {
        val passed = result.error == null
        tap.copy(
            lastTestRunStatus = if (passed) "success" else "failure",
            lastTestRunTime = now,
            lastTestRunRecordCount = result.recordCount,
            lastTestRunError = result.error,
            lastTestRunDataType = result.dataType,
            lastTestRunColumns = result.columns,
            // Stamp the identity of the exact script/endpoint that just
            // passed, so TapCronGate can tie a later cron to these bytes.
            // A failed test clears the stamp: the previous green no longer
            // vouches for what is stored now.
            lastTestRunScriptId = if (passed) TapScriptIdentity.of(tap).orNull else null
        )
    }

    /** Effective `testLimit` for a `/tap/run` request.
      *
      *  `bodyTestLimit` is `body.get("testLimit")` straight off the Jackson-parsed
      *  `@RequestBody` map: null when absent, Integer for `20`, Double for `20.0`,
      *  String for `"20"`.
      *
      *  Only `mode=test` caps at all — a `testLimit` sent with `mode=run` is ignored,
      *  not an error, because production runs must read every row. Absent/null means
      *  the default preview size (`testRecordSampleSize`); an explicit 0 or negative
      *  value is the deliberate unlimited opt-out.
      */
    private[datris] def resolveTestLimit(mode: String, bodyTestLimit: Any): Int = {
        if (mode == null || mode.toLowerCase != "test") return 0

        val requested: Option[Int] = bodyTestLimit match {
            case null => None
            case n: java.lang.Number =>
                val d = n.doubleValue()
                if (d.isNaN || d.isInfinite)
                    throw new DatrisException("testLimit must be an integer, got: " + n)
                Some(n.intValue())
            case s: String =>
                val t = s.trim
                if (t.isEmpty) None
                else {
                    val d =
                        try java.lang.Double.parseDouble(t)
                        catch {
                            case _: NumberFormatException =>
                                throw new DatrisException("testLimit must be an integer, got: " + s)
                        }
                    // "NaN" / "Infinity" parse as doubles but are not limits:
                    // NaN.toInt is 0 (= unlimited) and Infinity.toInt is Int.MaxValue.
                    if (d.isNaN || d.isInfinite)
                        throw new DatrisException("testLimit must be an integer, got: " + s)
                    Some(d.toInt)
                }
            case b: java.lang.Boolean => throw new DatrisException("testLimit must be an integer, got: " + b)
            case other => throw new DatrisException("testLimit must be an integer, got: " + other)
        }

        requested match {
            case None => testRecordSampleSize
            case Some(v) if v > 0 => v
            case Some(_) => 0
        }
    }
}

@RestController
@RequestMapping(Array("/api/v1"))
class TapAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[TapAPIController])

    @GetMapping(path = Array("/taps"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getTaps(@RequestHeader(name = "x-api-key", required = false) apiKey: String, request: HttpServletRequest): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /taps called")
            APIKeyValidator.validate(apiKey)

            val allTaps = TapConfigIO.readAll(DatrisEnvironment.current.tapTableName)
            // Per-item scope filter (owner=self keys, catalog-scoped keys and
            // restricted users' catalog grants), same as /pipelines.
            val filteredTaps = TapAPIController.visibleTaps(request, allTaps)
            val gson = new Gson
            val json = gson.toJson(filteredTaps.asJava)
            new ResponseEntity[String](json, HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    @GetMapping(path = Array("/tap"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getTap(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam name: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /tap called with name: " + name)
            APIKeyValidator.validate(apiKey)

            val config = TapConfigIO.read(DatrisEnvironment.current.tapTableName, name)
            if (config == null)
                return TapAPIController.tapNotFound(name)
            TapAPIController.scopeRefusal(request, config, "read").foreach(return _)
            val gson = new Gson
            // Include script content from MinIO. Track "missing" separately from
            // "never generated" so the UI can tell the user the script was deleted
            // vs. not yet created.
            var scriptMissing = false
            val hasScriptRef =
                (config.scriptPath != null && config.scriptPath.nonEmpty) ||
                    (config.scriptRepoPath != null && config.scriptRepoPath.nonEmpty)
            val scriptContent: String = if (hasScriptRef) {
                try {
                    TapCodeStore.forTap(config).readScript(config) match {
                        case Some(content) => content
                        case None =>
                            scriptMissing = true
                            logger.warn("Tap '" + name + "' has a script reference but the object is missing from its storage backend")
                            null
                    }
                } catch {
                    case e: Exception =>
                        scriptMissing = true
                        logger.warn("Tap '" + name + "' script read failed: " + e.getMessage)
                        null
                }
            } else null
            // Taps saved before dependencies existed are analysed once, here.
            val shown =
                if (scriptContent != null && TapDependencies.needsBackfill(config)) {
                    val (taps, pipelines) = TapAPIController.loadAllForDependencies()
                    TapAPIController.backfillInferred(config, Some(scriptContent), taps, pipelines)
                } else config
            val response = gson.fromJson(gson.toJson(shown), classOf[java.util.Map[String, Any]])
            response.put("script", scriptContent)
            if (scriptMissing) response.put("scriptMissing", java.lang.Boolean.TRUE)
            // Incremental-sync state, when the tap has committed any — lets agents see
            // the current cursor from get_tap without an extra call.
            try {
                val stateRow = TapStateIO.read(name)
                if (stateRow != null && stateRow.state != null) {
                    response.put("state", gson.fromJson(stateRow.state, classOf[java.util.Map[String, Any]]))
                    response.put("stateUpdatedAt", stateRow.updatedAt)
                }
            } catch {
                case e: Exception => logger.warn("Tap state read failed for '" + name + "': " + e.getMessage)
            }
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    @GetMapping(path = Array("/tap/logs/all"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getAllTapLogs(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam(required = false) since: java.lang.Long,
        @RequestParam(required = false) limit: java.lang.Integer,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            APIKeyValidator.validate(apiKey)
            // Default: last 30 days, capped at 2000 rows. Both bounds protect the
            // dashboard from a runaway scan on a very chatty tenant.
            val sinceMs: Long = if (since != null) since.longValue() else System.currentTimeMillis() - 30L * 86400000L
            val maxItems: Int = {
                val raw = if (limit != null) limit.intValue() else 2000
                if (raw <= 0) 2000 else math.min(raw, TapAPIController.MaxTapLogRows)
            }
            logger.info("API endpoint GET /tap/logs/all called since=" + sinceMs + " limit=" + maxItems)

            // Mongo rows have shape {"key": ..., "value": {...TapRunLog...}, "created_at": ...}.
            // Unwrap to just the embedded TapRunLog values so the response is a flat
            // array of run logs the UI can iterate. No pipeline-rollup enrichment
            // here — that's only needed by the per-tap detail view; this endpoint
            // exists to feed the Ops activity dashboard's tile/chart aggregation.
            def read(n: Int): Seq[JsonElement] = {
                val rows = NoSQLDbUtil.getItemsSinceAsJSON(
                    DatrisEnvironment.current.tapLogTableName,
                    "created_at",
                    sinceMs,
                    n
                )
                val parser = new JsonParser()
                rows.flatMap { json =>
                    try {
                        val el = parser.parse(json)
                        if (el.isJsonObject && el.getAsJsonObject.has("value")) Some(el.getAsJsonObject.get("value")) else None
                    } catch {
                        case e: Exception =>
                            // skip malformed row, don't fail the whole request
                            logger.debug("Skipping malformed tap run log row", e)
                            None
                    }
                }
            }
            // A limited caller (restricted user, catalog- or owner-scoped key)
            // sees run logs of the taps it may read only, read further back
            // (bounded) so its list is filled.
            val out = new com.google.gson.JsonArray()
            TapAPIController
                .visibleTapLogsPage(request, maxItems, read, () => TapConfigIO.readAll(DatrisEnvironment.current.tapTableName))
                .foreach(out.add)
            new ResponseEntity[String](out.toString, HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    @GetMapping(path = Array("/tap/logs"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getTapLogs(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam name: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /tap/logs called for tap: " + name)
            APIKeyValidator.validate(apiKey)
            TapAPIController.restrictedRefusal(request, name, "read").foreach(return _)

            val allKeys = NoSQLDbUtil.getItemsKeysByKeyName(DatrisEnvironment.current.tapLogTableName, "key")
            val tapKeys = allKeys.filter(_.startsWith(name + "|")).sorted.reverse.take(50)

            val gson = new Gson
            val logs = tapKeys.flatMap(key => {
                val json = NoSQLDbUtil.getItemJSON(DatrisEnvironment.current.tapLogTableName, "key", key, "value").orNull
                if (json != null) {
                    Some(gson.fromJson(json, classOf[ai.datris.model.TapRunLog]))
                } else None
            })

            // Enrich each run with its downstream pipeline rollup so the Run History
            // modal can show per-document job outcomes — not just the tap-script
            // fetch summary. Without this, a tap run that fetched 28 docs but had
            // 1 chunking/embedding failure shows up as "SUCCESS 28 records" with
            // no hint of the failure. Only runs that actually fed a pipeline
            // (mode=run with a publisherToken) get a rollup.
            // A limited caller gets each rollup exactly as GET /pipeline/status
            // would show it (filtered, hidden tap names [hidden], previews
            // stripped; null when it may not read the run): the stores are read
            // once per request, and only for a limited caller.
            lazy val pipelines = PipelineConfigIO.readAll(DatrisEnvironment.current.pipelineTableName)
            lazy val taps = TapConfigIO.readAll(DatrisEnvironment.current.tapTableName)
            val enriched = logs.map { log =>
                val rollupJson: JsonElement =
                    if (log.publisherToken != null && log.publisherToken.nonEmpty) {
                        try {
                            val response = PipelineStatusUtil.getPipelineStatusByPublisherWithRollup(log.publisherToken)
                            TapAPIController
                                .shownRollup(request, response, () => pipelines, () => taps, ai.datris.util.RunLineageIO.read)
                                .map(r => gson.toJsonTree(r))
                                .getOrElse(JsonNull.INSTANCE)
                        } catch {
                            case e: Exception =>
                                logger.warn("Could not load rollup for publisher token " + log.publisherToken + ": " + e.getMessage)
                                JsonNull.INSTANCE
                        }
                    } else JsonNull.INSTANCE
                val obj = gson.toJsonTree(log).getAsJsonObject
                obj.add("pipelineRollup", rollupJson)
                obj
            }

            val arr = new com.google.gson.JsonArray()
            enriched.foreach(arr.add)
            new ResponseEntity[String](arr.toString, HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    @PostMapping(path = Array("/tap"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def createOrUpdateTap(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam(name = "changeNote", required = false) changeNote: String,
        @RequestBody tapConfig: TapConfig,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /tap called with name: " + tapConfig.name)
            APIKeyValidator.validate(apiKey)

            saveTapInternal(tapConfig, changeNote, request) match {
                case Left(refused) => refused
                case Right(saved) => new ResponseEntity[String](new Gson().toJson(saved), HttpStatus.OK)
            }
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    /** Body of `POST /tap` after key validation: secret-scope check
      * (`tapSecretSaveProblem`), HTTP-tap validation, document-tap
      * compatibility, script-existence guard, kind/script/tag preservation,
      * ownership stamp, cron validation, test-before-cron gate and the
      * versioned write. Shared with the catalog restore (`CatalogAPIController`).
      * Left is the refusal response (400/409); other failures are thrown.
      *
      * `restored = true` is only for a catalog restore, both for a new tap and
      * for overwriting an existing member of the target catalog. In both
      * cases the test-before-cron gate is skipped when the tap is saved with
      * `enabled = false` (restore always does); the gate applies again on the
      * save that re-enables it. For a NEW tap the archived
      * `lastTestRunScriptId` is also kept (it names the same bytes on built-in
      * storage); an existing tap keeps its stored, server-owned test fields as
      * on any save.
      *
      * Tap version restore saves through the same path
      * ([[restoreTapInternal]]); `restored` is still the catalog restore
      * only. */
    def saveTapInternal(
        tapConfig: TapConfig,
        changeNote: String,
        request: HttpServletRequest,
        restored: Boolean = false
    ): Either[ResponseEntity[String], TapConfig] =
        saveTapCore(tapConfig, changeNote, request, restored, None, () => null, TapAPIController.ProductionSaveIO)

    /** [[saveTapInternal]] with the store reads and the write given (specs). */
    private[api] def saveTapInternalWith(
        tapConfig: TapConfig,
        changeNote: String,
        request: HttpServletRequest,
        restored: Boolean,
        io: TapAPIController.TapSaveIO
    ): Either[ResponseEntity[String], TapConfig] =
        saveTapCore(tapConfig, changeNote, request, restored, None, () => null, io)

    /** `POST /api/v1/tap/version/restore` after the version lookup:
      * `candidate` ([[EntityVersionAPIController.tapRestoreCandidate]]) is
      * saved through the same path as `POST /tap`, so a version the platform
      * would refuse on save today is refused with the same answer and nothing
      * is written. `live` is the ONE stored-tap read the restore decision
      * uses (null when the tap was deleted); `hidden` is the version 404 a
      * hidden history answers. Left is the refusal; other failures are
      * thrown. */
    private[api] def restoreTapInternal(
        candidate: TapConfig,
        live: TapConfig,
        version: Int,
        hidden: => ResponseEntity[String],
        request: HttpServletRequest,
        io: TapAPIController.TapSaveIO = TapAPIController.ProductionSaveIO
    ): Either[ResponseEntity[String], TapConfig] =
        saveTapCore(candidate, null, request, restored = false, Some(TapAPIController.RestoreOf(live, version)), () => hidden, io)

    /** The recovery agent's tap revert (`IncidentRunner`), checked like a
      * version restore of `version` over `live` (the stored tap the revert
      * decided on, never null): every save check of [[restoreTapInternal]]
      * runs on `candidate` with no caller identity, except the
      * test-before-cron gate (the pre-agent script is the one that was
      * scheduled before the agent acted). Nothing is written: Right is the
      * config the save path would write (`updatedAt` and `inferredDependsOn`
      * stamped; the live tap's test state and stamp, which the caller
      * replaces); Left is the refusal a save of it gets. Store failures are
      * thrown. The caller writes, under the catalog lock, through the same
      * `io`. */
    private[datris] def checkTapRevert(
        candidate: TapConfig,
        live: TapConfig,
        version: Int,
        io: TapAPIController.TapSaveIO
    ): Either[ResponseEntity[String], TapConfig] = {
        if (live == null) throw new DatrisException("tap " + candidate.name + " no longer exists")
        saveTapCore(candidate, null, null, restored = false, Some(TapAPIController.RestoreOf(live, version)), () => null, io, revertCheck = true)
    }

    /** The save path. `versionRestore` None is `POST /tap` and the catalog
      * restore (`restored`), unchanged. Some is a tap version restore, which
      * differs in four places only: (a) the guard is always taken, decided on
      * `versionRestore.live` (no read) by
      * [[EntityVersionAPIController.tapRestoreGuardDecision]], which also
      * holds the target-pipeline rule; (b) `existing` is that live tap and no
      * omitted field is carried over from it (the candidate is complete);
      * (c) no no-change shortcut, and the version note is `restored from
      * version N`; (d) the re-read, the re-check
      * ([[EntityVersionAPIController.tapRestoreRecheck]]) and the write run
      * under `CatalogOps.withCatalogLock`. `hidden` is used only when
      * `versionRestore` is Some. Every store read and the write go through
      * `io` ([[TapAPIController.ProductionSaveIO]] outside specs).
      * `revertCheck` ([[checkTapRevert]], with `versionRestore` Some): no
      * caller identity, so no scope guard; no test-before-cron gate; a script
      * that cannot be read for dependency inference keeps the candidate's
      * `inferredDependsOn`; nothing is written and the checked config is
      * returned. */
    private def saveTapCore(
        tapConfig: TapConfig,
        changeNote: String,
        request: HttpServletRequest,
        restored: Boolean,
        versionRestore: Option[TapAPIController.RestoreOf],
        hidden: () => ResponseEntity[String],
        io: TapAPIController.TapSaveIO,
        revertCheck: Boolean = false
    ): Either[ResponseEntity[String], TapConfig] = {
        if (tapConfig.name == null || tapConfig.name.isEmpty)
            throw new DatrisException("Tap name is required")

        // The stored tap as the guard reads it, shared with the script
        // reference rule below (one read for both); and the tap and pipeline
        // listing, shared by that rule and the dependency step.
        val storedOnce = TapAPIController.once(() => io.readTap(tapConfig.name))
        val loadOnce = TapAPIController.once(io.loadAll)
        val coreIO = io.copy(loadAll = loadOnce)
        val lookups = TapReferenceRules.Lookups(() => loadOnce()._1, io.secretOwner, io.repoPathPrefix)
        // Script reference and secret rules: rule 3 refuses a limited caller's
        // save (never a version restore), before anything is validated or
        // read; every other finding is only reported. The stored tap is
        // needed only for a limited caller sending a reference (the guard
        // already read it for every such caller).
        def referenceCheck(site: String, candidate: TapConfig, stored: => TapConfig): Option[ResponseEntity[String]] = {
            val needsStored = TapReferenceRules.limited(CapabilityCheck.resolvedKey(request).orNull) &&
                ((candidate.scriptPath != null && candidate.scriptPath.nonEmpty) || (candidate.scriptRepoPath != null && candidate.scriptRepoPath.nonEmpty))
            TapAPIController.referenceRefusal(request, site, candidate, if (needsStored) stored else null, lookups)
        }

        // Restricted users: default a blank catalog and refuse targets
        // outside their grants; scoped API keys: no overwrite of a tap outside
        // the scope and no save outside it (TapAPIController.saveGuardDecision).
        // Both before anything is validated or written.
        // A version restore is always guarded, on the live tap it has already
        // read (it reads nothing more here); its guard holds the target
        // pipeline rule too. The recovery agent's revert has no caller.
        val scoped =
            if (revertCheck) tapConfig
            else if (versionRestore.isDefined)
                EntityVersionAPIController.tapRestoreGuardDecision(
                    request,
                    tapConfig,
                    versionRestore.get.live,
                    io.readPipeline,
                    hidden()
                ) match {
                    case Left(refused) => return Left(refused)
                    case Right(c) => c
                }
            else if (!TapAPIController.saveGuardNeeded(request)) tapConfig
            else
                TapAPIController.saveGuardDecision(request, tapConfig, storedOnce()) match {
                    case Left(refused) => return Left(refused)
                    case Right(c) => c
                }
        // The tap feeds its target pipeline: that pipeline must be one the
        // caller may load into (restricted session: hidden/missing -> pipeline
        // 404; pipeline-limited key: 403).
        if (versionRestore.isEmpty) {
            // The save's own pipeline read, shared with the report below so the
            // report adds no read.
            val readTarget = TapAPIController.readOnce(io.readPipeline)
            TapAPIController.targetPipelineRefusal(request, scoped, readTarget).foreach(r => return Left(r))
            if (!revertCheck) referenceCheck(TapReferenceRules.Save, scoped, storedOnce()).foreach(r => return Left(r))
            val saved = saveTapScoped(scoped, changeNote, request, restored, versionRestore, hidden, coreIO, revertCheck)
            // Report only (rule 5): a key whose loads are checked saved a tap
            // whose target does not exist yet. The save's answer is unchanged.
            if (saved.isRight) TapOwnerScopeReport.saveTarget(request, scoped, readTarget)
            saved
        } else {
            // A version restore is never refused by the reference rules in
            // this release: rule 3 is reported there, like rules 5 to 8.
            if (!revertCheck)
                try referenceCheck(TapReferenceRules.Restore, scoped, versionRestore.get.live)
                catch { case e: Exception => logger.debug("Tap reference report skipped (restore): " + e.getMessage) }
            saveTapScoped(scoped, changeNote, request, restored, versionRestore, hidden, coreIO, revertCheck)
        }
    }

    private def saveTapScoped(
        tapConfig: TapConfig,
        changeNote: String,
        request: HttpServletRequest,
        restored: Boolean,
        versionRestore: Option[TapAPIController.RestoreOf],
        hidden: () => ResponseEntity[String],
        io: TapAPIController.TapSaveIO,
        revertCheck: Boolean
    ): Either[ResponseEntity[String], TapConfig] = {
        // A tap may never reference a server-managed secret (field-protection
        // keys, API-key stores): its fields would be injected into the script.
        // Field protection 8: and only a tap-typed secret unless
        // DATRIS_TAP_SECRET_SCOPE=any.
        TapAPIController.tapSecretSaveProblem(tapConfig, io.secretType, SecretNames.tapScopeEnforced) match {
            case Some(msg) =>
                return Left(ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                    .body[String]("{\"error\": " + new Gson().toJson(msg) + "}"))
            case None =>
        }

        // Tap-kind validation. null/"python" is the script lane; "http" is a
        // user-hosted endpoint speaking the tap HTTP contract. Anything else is
        // a typo we reject rather than silently treating as a script tap.
        val isHttpTap = tapConfig.isHttp
        if (
            tapConfig.scriptKind != null && tapConfig.scriptKind.nonEmpty &&
            !isHttpTap && !tapConfig.scriptKind.equalsIgnoreCase("python")
        )
            return Left(ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](
                "{\"error\": \"Unknown scriptKind '" + tapConfig.scriptKind + "'. Valid values: python (default), http.\"}"
            ))
        if (isHttpTap) {
            val urlProblem: Option[String] =
                if (tapConfig.endpointUrl == null || tapConfig.endpointUrl.trim.isEmpty)
                    Some("endpointUrl is required for HTTP taps")
                else
                    try {
                        val u = new java.net.URI(tapConfig.endpointUrl.trim)
                        if (!u.isAbsolute || u.getHost == null) Some("endpointUrl must be an absolute http:// or https:// URL")
                        else if (u.getScheme != "http" && u.getScheme != "https")
                            Some("endpointUrl scheme must be http or https (got: " + u.getScheme + ")")
                        else {
                            // Plain http to a non-local host sends the bearer token in
                            // the clear. Operator-configured on a self-hosted instance,
                            // so warn rather than fail.
                            if (u.getScheme == "http" && u.getHost != "localhost" && u.getHost != "127.0.0.1" && u.getHost != "host.docker.internal")
                                logger.warn("HTTP tap '" + tapConfig.name + "' uses plain http:// to a non-local host — " +
                                    "the endpoint token (if any) is sent unencrypted: " + tapConfig.endpointUrl)
                            None
                        }
                    } catch {
                        case _: Exception => Some("endpointUrl is not a valid URL: " + tapConfig.endpointUrl)
                    }
            urlProblem match {
                case Some(p) =>
                    return Left(ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](
                        "{\"error\": \"" + p.replace("\"", "'") + "\"}"
                    ))
                case None => // valid
            }
            // Script-lane fields have no meaning for an HTTP tap. Reject rather
            // than silently ignore, naming the conflict so the client can fix it.
            val conflicting = Seq(
                "scriptPath" -> tapConfig.scriptPath,
                "scriptStorage" -> tapConfig.scriptStorage,
                "scriptRepoPath" -> tapConfig.scriptRepoPath,
                "scriptCommitSha" -> tapConfig.scriptCommitSha
            ).collect {
                case (n, v) if v != null && v.nonEmpty => n
            } ++
                (if (tapConfig.packages != null && !tapConfig.packages.isEmpty) Seq("packages") else Nil)
            if (conflicting.nonEmpty)
                return Left(ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](
                    "{\"error\": \"HTTP taps run no code on the platform, so these field(s) do not apply: " +
                        conflicting.mkString(", ") + ". Remove them, or set scriptKind to python.\"}"
                ))
        } else if (tapConfig.endpointUrl != null && tapConfig.endpointUrl.nonEmpty) {
            return Left(ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](
                "{\"error\": \"endpointUrl is only valid when scriptKind is 'http'.\"}"
            ))
        }

        // Document-tap pipeline compatibility guard: document taps push raw bytes,
        // so the target pipeline must be unstructured + vector-store or the bytes
        // will crash the destination loader (e.g. PDF into MongoDBLoader).
        if (tapConfig.tapType == "document" && tapConfig.targetPipeline != null && tapConfig.targetPipeline.nonEmpty) {
            val pipeline = io.readPipeline(tapConfig.targetPipeline)
            DocumentTapValidator.incompatibilityReason(pipeline) match {
                case Some(reason) =>
                    return Left(ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](
                        "{\"error\": \"Document tap '" + tapConfig.name + "' cannot target pipeline '" +
                            tapConfig.targetPipeline + "': " + reason.replace("\"", "'") + "\"}"
                    ))
                case None => // compatible
            }
        }

        // Script-existence guard: a tap save with a script reference that points
        // at a missing file (MinIO object or repo path) would silently succeed
        // here and then fail at run_tap with "scriptMissing". Reject the save
        // with a clear error so the UI can prompt the user to push the script
        // before retrying.
        val hasScriptRef =
            (tapConfig.scriptPath != null && tapConfig.scriptPath.nonEmpty) ||
                (tapConfig.scriptRepoPath != null && tapConfig.scriptRepoPath.nonEmpty)
        if (hasScriptRef) {
            val exists =
                try {
                    io.scriptExists(tapConfig)
                } catch {
                    case e: Exception =>
                        logger.warn("Tap script existence check failed for tap '" + tapConfig.name + "'", e)
                        false
                }
            if (!exists) {
                return Left(ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](
                    "{\"error\": \"Tap script not found in its storage backend. " +
                        "The script must be uploaded (via the tap wizard's script-store endpoints) before saving the tap config.\"}"
                ))
            }
        }

        // Script-reference preservation: clients (UI save, MCP create_tap)
        // rebuild the config body from scratch and may omit the script
        // fields entirely. If the body carries NO script reference but the
        // stored tap has one, carry it over — otherwise an unrelated edit
        // (description, cron) would silently orphan the script.
        // A version restore: the live tap its guard decided on, and no
        // omitted field is carried over (the candidate is complete).
        val existing =
            if (versionRestore.isDefined) versionRestore.get.live
            else io.readTap(tapConfig.name)
        val bodyHasScriptRef =
            (tapConfig.scriptPath != null && tapConfig.scriptPath.nonEmpty) ||
                (tapConfig.scriptRepoPath != null && tapConfig.scriptRepoPath.nonEmpty)
        // Kind preservation, mirroring script-reference preservation below: a
        // partial update that omits scriptKind must not silently convert an HTTP
        // tap into a script tap with no script. Sending a script reference (or an
        // explicit scriptKind) is the deliberate way to convert back to python.
        val tapConfigWithKind =
            if (
                versionRestore.isEmpty &&
                (tapConfig.scriptKind == null || tapConfig.scriptKind.isEmpty) &&
                existing != null && existing.isHttp && !bodyHasScriptRef
            )
                tapConfig.copy(
                    scriptKind = existing.scriptKind,
                    endpointUrl =
                        if (tapConfig.endpointUrl != null && tapConfig.endpointUrl.nonEmpty) tapConfig.endpointUrl
                        else existing.endpointUrl
                )
            else tapConfig
        // Script-reference preservation is a script-lane concern: an HTTP tap save
        // (including a python→http conversion) must NOT re-attach the old script.
        val tapConfigWithScript =
            if (versionRestore.isEmpty && !bodyHasScriptRef && existing != null && !tapConfigWithKind.isHttp)
                tapConfigWithKind.copy(
                    scriptStorage = existing.scriptStorage,
                    scriptPath = existing.scriptPath,
                    scriptRepoPath = existing.scriptRepoPath,
                    scriptCommitSha = existing.scriptCommitSha
                )
            else tapConfigWithKind

        // Set timestamps and stamp the issuing key's label on first create.
        // On update we preserve the original `createdByKeyLabel` — ownership
        // is a property of creation, not the last edit (otherwise an editor
        // key would silently claim ownership and `owner=self` would drift).
        val sdf2 = new java.text.SimpleDateFormat(DatrisEnvironment.current.dateFormat)
        sdf2.setTimeZone(java.util.TimeZone.getTimeZone(DatrisEnvironment.current.dateTimezone))
        val now = sdf2.format(new java.util.Date())
        // A blank cron means unscheduled: stored as null, never "".
        val configToSave = TapCronGate.normalizeCron(if (existing != null)
            tapConfigWithScript.copy(
                createdAt = existing.createdAt,
                updatedAt = now,
                createdByKeyLabel = existing.createdByKeyLabel,
                // Tags preservation: a body that omits tags entirely must not
                // wipe them on an unrelated edit; an explicit empty list clears.
                // A version restore takes the snapshot's, nulls included.
                tags = if (versionRestore.isEmpty && tapConfigWithScript.tags == null) existing.tags else tapConfigWithScript.tags,
                // Same rule for the declared source: omitted ⇒ keep; "" ⇒ clear.
                source = if (versionRestore.isEmpty && tapConfigWithScript.source == null) existing.source else tapConfigWithScript.source,
                // Same rule for declared dependencies: omitted ⇒ keep; [] is an
                // explicit "none" (it overrides inference).
                dependsOn =
                    if (versionRestore.isEmpty && tapConfigWithScript.dependsOn == null) existing.dependsOn else tapConfigWithScript.dependsOn,
                // Test outcome + script stamp are server-owned (written only by
                // the run endpoint in mode=test). Clients echo stale copies in the
                // save body; never let a body promote a tap to "tested".
                lastTestRunStatus = existing.lastTestRunStatus,
                lastTestRunScriptId = existing.lastTestRunScriptId,
                // Run-stamped by TapRunner; a body never sets or clears it.
                lastRunUsedCallback = existing.lastRunUsedCallback
            )
        else
            tapConfigWithScript.copy(
                createdAt = now,
                updatedAt = now,
                createdByKeyLabel = ResolvedKeyAccess.keyLabel(request).orNull,
                lastTestRunStatus = null,
                // A new tap has not run.
                lastRunUsedCallback = false,
                // A restored tap keeps its archived stamp (bytes-identical on
                // built-in storage, null otherwise); the status stays null so
                // the gate still wants a fresh test before a schedule runs.
                lastTestRunScriptId = if (restored) tapConfigWithScript.lastTestRunScriptId else null
            ))

        // Tap dependencies: a changed explicit dependsOn must name taps of the
        // same catalog and must not close a cycle (400 / 409). A catalog
        // restore skips this (its producers may not be written yet) and
        // re-stamps after every tap is in. `inferredDependsOn` is always
        // re-derived from the stored script, whatever the body said.
        val (allTaps, allPipelines) = io.loadAll()
        val dependsOnChanged = existing == null || existing.dependsOn != configToSave.dependsOn
        if (!restored && dependsOnChanged) {
            val catalogTaps = allTaps.filter(t => t.catalog == configToSave.catalog && !CatalogOps.Placeholder.is(t.name))
            TapAPIController.dependencySaveProblem(configToSave, catalogTaps).foreach(r => return Left(r))
        }
        // A failed read (e.g. the code repository briefly unreachable) keeps
        // the stored stamp rather than clearing it.
        val deduped = TapDependencies.dedupeDependsOn(configToSave)
        val readFailed = new java.util.concurrent.atomic.AtomicBoolean(false)
        val script: Option[String] =
            if (configToSave.isHttp) None
            else if (
                (configToSave.scriptPath == null || configToSave.scriptPath.isEmpty) &&
                (configToSave.scriptRepoPath == null || configToSave.scriptRepoPath.isEmpty)
            ) None
            else
                try io.readScript(configToSave)
                catch {
                    case e: Exception =>
                        logger.warn("Tap '" + configToSave.name + "': script unreadable for dependency inference: " + e.getMessage)
                        readFailed.set(true)
                        None
                }
        val stamped =
            if (readFailed.get && revertCheck) deduped
            else if (readFailed.get) deduped.copy(inferredDependsOn = if (existing != null) existing.inferredDependsOn else null)
            else
                TapAPIController.stampInferredDependsOn(
                    deduped,
                    script,
                    allPipelines,
                    allTaps.filter(_.name != configToSave.name) :+ configToSave
                )

        // Cron validation: a schedule the scheduler cannot parse is refused
        // here (400) rather than stored to log an error on every tick. Runs
        // after blank normalisation (blank = unscheduled, never validated)
        // and before the test-before-cron gate, whose 409 text is unchanged.
        TapCronValidation.check(configToSave.name, configToSave.cronExpression) match {
            case Some(msg) =>
                return Left(ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](TapCronValidation.errorBody(msg)))
            case None =>
        }

        // Test-before-cron gate: a schedule may only be set on a script (or
        // endpoint) that has passed a mode=test run. Reads test state from
        // the STORED tap only. Refused saves persist nothing and mint no version.
        // A catalog restore writes the tap disabled, so nothing can fire; the
        // gate applies on the save that enables it.
        // The recovery agent's revert puts back the script that was
        // scheduled before the agent acted: never refused by the gate.
        val gate = if (revertCheck || (restored && !configToSave.enabled)) None else TapCronGate.check(existing, configToSave)
        gate match {
            case Some(msg) =>
                return Left(ResponseEntity.status(HttpStatus.CONFLICT).body[String]("{\"error\": \"" + msg.replace("\"", "'") + "\"}"))
            case None =>
        }

        // Definition-edit write → mints a new immutable version snapshot.
        // (Status churn from TapRunner stays on plain TapConfigIO.write.)
        // A save that changes nothing (e.g. the config write that follows a
        // script store, which already versioned the new script) mints no
        // version and leaves the stored tap as it is.
        // A version restore always mints a version. The revert's check writes
        // nothing: its caller writes the checked config.
        if (revertCheck) return Right(stamped)
        if (versionRestore.isEmpty && existing != null && TapScriptStoreVersioning.sameDefinition(existing, stamped)) return Right(existing)
        val note = if (versionRestore.isDefined) "restored from version " + versionRestore.get.version
        else if (changeNote != null && changeNote.nonEmpty) changeNote
        else if (existing != null) "updated" else "created"
        // A version restore re-reads, re-checks and writes under the lock a
        // rename, a catalog restore and the enable-in-order job hold, so a
        // change since its decision is seen before anything is written; the
        // live run, retry and test state written are the tap's under the
        // lock. Only the re-read, re-check and write are held.
        val saved =
            if (versionRestore.isEmpty) io.writeVersioned(stamped, note, VersionActor.resolve(request))
            else
                CatalogOps.withCatalogLock {
                    val fresh = io.readTap(tapConfig.name)
                    EntityVersionAPIController
                        .tapRestoreRecheck(request, tapConfig, existing, fresh, io.readPipeline, hidden())
                        .foreach { refused =>
                            logger.warn("Restore of tap " + LogSafe.text(tapConfig.name) + " rejected at write: HTTP " + refused.getStatusCode.value)
                            return Left(refused)
                        }
                    io.writeVersioned(EntityVersionAPIController.tapRestoreWriteConfig(stamped, fresh), note, VersionActor.resolve(request))
                }
        Right(saved)
    }

    @DeleteMapping(path = Array("/tap"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def deleteTap(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam name: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint DELETE /tap called with name: " + name)
            APIKeyValidator.validate(apiKey)

            val warnings = deleteTapInternal(name, request) match {
                case None => return TapAPIController.tapNotFound(name)
                case Some(w) => w
            }
            val out = new com.google.gson.JsonObject
            out.addProperty("message", "Tap deleted: " + name)
            val arr = new com.google.gson.JsonArray
            warnings.foreach(arr.add(_))
            out.add("warnings", arr)
            ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(out.toString)
        } catch {
            case _: ResourceHiddenException => TapAPIController.tapNotFound(name)
            case e: CapabilityDeniedException => PipelineAPIController.capabilityDenied(e)
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    /** Body of DELETE /tap after key validation: owner-scope check, script,
      * document ledger + staged files, run log, versions, sync state, config.
      * Shared with the catalog cascade delete and placeholder cleanup
      * (`CatalogAPIController`). Throws on a scope denial. `checkScope=false`
      * is only for `__catalog__` placeholders, which are catalog bookkeeping
      * already authorised by the catalog route's capability gate. Returns
      * warnings for the caller: a repository script file kept because it was
      * hand-edited since its recorded commit (or could not be checked).
      * None when no tap of that name exists (the leftover run log, versions
      * and sync state are still cleaned up, as before); the route maps it to
      * 404, the catalog callers tolerate it. */
    def deleteTapInternal(name: String, request: HttpServletRequest, checkScope: Boolean = true): Option[Seq[String]] = {
        val warnings = Seq.newBuilder[String]
        // Delete script from MinIO if it exists
        val existing = TapConfigIO.read(DatrisEnvironment.current.tapTableName, name)
        if (existing != null) {
            // Scope check: `tap:delete:owner=self` keys may only delete
            // taps they created. Loaded resource carries createdByKeyLabel.
            if (checkScope)
                CapabilityCheck.assertVisibleToWrite(request, "tap", "delete", CatalogOps.scopeContext(existing.catalog, existing.createdByKeyLabel))
            // For repo-backed taps this commits a file removal (history is
            // preserved — that's the point of the repo), unless the file was
            // hand-edited at branch head since the recorded commit: then it is
            // kept and named in a warning. Non-fatal: a dead repo connection
            // must not block deleting the tap itself.
            try TapCodeStore.forTap(existing).deleteUnlessEdited(existing).foreach(warnings += _)
            catch { case e: Exception => logger.warn("Tap script cleanup failed for '" + name + "': " + e.getMessage) }
        }

        // Document taps: also clean up staged files and ledger entries
        if (existing != null && existing.tapType == "document") {
            try {
                val ledgerTable = DatrisEnvironment.current.tapLedgerTableName
                val bucket = DatrisEnvironment.current.environment + "-config"
                val entries = TapDocumentLedgerIO.readByTap(ledgerTable, name)
                entries.foreach { e =>
                    if (e.stagedPath != null && e.stagedPath.nonEmpty) {
                        try { ObjectStoreUtil.deleteBucketObject(bucket, e.stagedPath) }
                        catch { case ex: Exception => logger.warn("Failed to delete staged doc " + e.stagedPath + ": " + ex.getMessage) }
                    }
                }
                TapDocumentLedgerIO.deleteByTap(ledgerTable, name)
            } catch {
                case ex: Exception => logger.warn("Tap ledger cleanup failed for " + name + ": " + ex.getMessage)
            }
        }

        // Run history: every TapRunner.writeRunLog inserts a row keyed by
        // "<tapName>|<runTime>" into {env}-tap-log. Without this cleanup, deleting
        // and recreating a tap with the same name would silently surface the old
        // tap's run history under the new tap.
        try {
            val tapLogTable = DatrisEnvironment.current.tapLogTableName
            val prefix = name + "|"
            val allKeys = NoSQLDbUtil.getItemsKeysByKeyName(tapLogTable, "key")
            allKeys.filter(_.startsWith(prefix)).foreach { k =>
                NoSQLDbUtil.deleteItemJSON(tapLogTable, "key", k)
            }
        } catch {
            case ex: Exception => logger.warn("Tap run-log cleanup failed for " + name + ": " + ex.getMessage)
        }

        // Definition versions: hard-delete every snapshot for this tap AND
        // GC the script objects they pinned (now that we have the index to
        // do it cleanly). deleteScript is idempotent, so re-deleting the
        // current scriptPath removed above is harmless. Only keys under the
        // tap script folder are deleted; any other reference is left in place.
        try {
            TapScriptGenerator.deleteTapScripts(
                name,
                EntityVersionIO.deleteAllForEntity(DatrisEnvironment.current.tapVersionTableName, name),
                // the built-in store's delete above already logged the current reference
                alreadyReported = Option(existing).filter(e => TapCodeStore.forTap(e) == MinioCodeStore).flatMap(e => Option(e.scriptPath)).toSet
            )
        } catch {
            case ex: Exception => logger.warn("Tap version cleanup failed for " + name + ": " + ex.getMessage)
        }

        // Incremental-sync state: a recreated tap with the same name must start
        // from scratch, not inherit the old tap's cursor.
        try TapStateIO.delete(name)
        catch { case ex: Exception => logger.warn("Tap state cleanup failed for " + name + ": " + ex.getMessage) }

        TapConfigIO.delete(DatrisEnvironment.current.tapTableName, name)
        TapTestPreviewCache.drop(name)
        if (existing == null) None else Some(warnings.result())
    }

    /** Repoint an existing tap at freshly written MinIO bytes unless the
      * test-before-cron gate refuses (scheduled tap, untested bytes): then the
      * stored tap stays on its tested script and the gate message is returned
      * so the caller can surface it. The new script and reference are still
      * returned to the caller either way (the wizard needs the bytes to show).
      * Callers put the message under `error` in an otherwise-200 body: the
      * wizard reads only script/scriptPath/packages/changes from these
      * responses, and MCP create_tap hands any body with `error` to the agent.
      * A caller who may not update the stored tap
      * ([[TapAPIController.mayWriteStoredTap]], asked first) never changes it:
      * [[TapAPIController.StoredTapLeftUnchanged]] is returned instead,
      * except when `logOnlyAsBefore` (review, generate, fix and optimize, for
      * a request the route gate passed only under log-only): then the tap is
      * changed as on v1.61.0. */
    private def repointUnlessGated(
        existing: TapConfig,
        newScriptPath: String,
        request: HttpServletRequest,
        note: String,
        io: TapAPIController.TapAiIO,
        logOnlyAsBefore: Boolean = false
    ): Option[String] =
        if (!logOnlyAsBefore && !TapAPIController.mayWriteStoredTap(request, existing)) {
            logger.info("Not repointing tap '" + LogSafe.text(existing.name) + "': the caller may not update it")
            Some(TapAPIController.StoredTapLeftUnchanged)
        } else TapCronGate.checkAiRepoint(existing, newScriptPath) match {
            case None =>
                // Same rule as the store lane: new bytes on an existing tap are a
                // definition edit and land as a new version.
                val repointed = existing.copy(scriptPath = newScriptPath)
                TapScriptStoreVersioning.decide(existing, repointed, incidentHeader(request), t => TapScriptIdentity.of(t, io.readScript), note) match {
                    case Some(n) => io.writeVersioned(repointed, n, VersionActor.resolve(request))
                    case None => io.write(repointed)
                }
                None
            case Some(msg) =>
                logger.info("Not repointing scheduled tap '" + existing.name + "' at untested script: " + msg)
                Some(msg)
        }

    @PostMapping(path = Array("/tap/script"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def storeScript(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: java.util.Map[String, String],
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            val tapName = body.get("tapName")
            val script = body.get("script")
            val requestedStorage = body.get("storage")
            val baseCommitSha = body.get("baseCommitSha")
            logger.info("API endpoint POST /tap/script called, tapName: " + tapName)
            APIKeyValidator.validate(apiKey)

            if (tapName == null || tapName.isEmpty)
                throw new DatrisException("tapName is required")
            if (script == null || script.isEmpty)
                throw new DatrisException("script is required")
            // Every caller, before anything is read or written: a name whose
            // script path would have an empty, `.` or `..` segment is a 400.
            ScriptPaths.tapNameRefusal(tapName).foreach(m => throw new ValidationException(m))

            TapAPIController.restrictedRefusal(request, tapName, "update").foreach(return _)
            val existing = TapConfigIO.read(DatrisEnvironment.current.tapTableName, tapName)

            // Test-before-cron, evaluated BEFORE any bytes are written: a script
            // edit on a SCHEDULED tap is refused up front, so nothing is committed
            // to the repo (or MinIO) that the tap will not be repointed at. The
            // remedy clears the cron first, after which the store is allowed.
            TapCronGate.checkScriptStore(existing, script, t => TapCodeStore.forTap(t).readScript(t)) match {
                case Some(msg) =>
                    return ResponseEntity.status(HttpStatus.CONFLICT).body[String]("{\"error\": \"" + msg.replace("\"", "'") + "\"}")
                case None =>
            }

            // Backend resolution: explicit request > the tap's current backend
            // (existing taps never switch silently) > tenant default.
            val store =
                if (requestedStorage != null && requestedStorage.nonEmpty) TapCodeStore.forStorage(requestedStorage)
                else if (existing != null) TapCodeStore.forTap(existing)
                else TapCodeStore.forStorage(null)

            // The editor sends the commit sha it opened against so a concurrent
            // external commit to the same file is a conflict, not an overwrite.
            val prior =
                if (existing != null && baseCommitSha != null && baseCommitSha.nonEmpty)
                    existing.copy(scriptCommitSha = baseCommitSha)
                else existing
            val stored = store.storeScript(tapName, script, prior, actorLabel(request))

            if (existing != null) {
                val repointed = existing.copy(
                    scriptStorage = stored.storage,
                    scriptPath = stored.scriptPath,
                    scriptRepoPath = stored.scriptRepoPath,
                    scriptCommitSha = stored.scriptCommitSha
                )
                // Belt and braces behind checkScriptStore above: never repoint a
                // SCHEDULED tap at untested bytes (the scheduler would fire them
                // before the gated save ever runs). The new reference is still
                // returned so the caller can test against it.
                // A passing scratch rehearsal of exactly these bytes stamped the
                // tap; the stamp moves onto the new reference so the scheduled
                // tap is repointed and stays green.
                val (gateBase, toStore) =
                    TapCronGate.carryRehearsedStamp(existing, script, repointed, t => TapScriptIdentity.of(t)) match {
                        case Some(carried) => (existing.copy(lastTestRunScriptId = carried.lastTestRunScriptId), carried)
                        case None => (existing, repointed)
                    }
                TapCronGate.check(gateBase, toStore) match {
                    case None =>
                        // Re-derive the dependency stamp from the new bytes.
                        val stamped =
                            try {
                                val (taps, pipelines) = TapAPIController.loadAllForDependencies()
                                TapAPIController.stampInferredDependsOn(toStore, Some(script), pipelines, taps)
                            } catch {
                                case e: Exception =>
                                    logger.warn("POST /tap/script: dependency inference failed for '" + tapName + "': " + e.getMessage)
                                    toStore
                            }
                        // Replacing an existing tap's script is a definition
                        // edit: it lands as a new version naming who made it
                        // (and the incident, when the recovery agent did).
                        // An identical re-store or a stamp-only move stays a
                        // plain write.
                        TapScriptStoreVersioning.decide(existing, stamped, incidentHeader(request), t => TapScriptIdentity.of(t)) match {
                            case Some(note) => TapConfigIO.writeVersioned(stamped, note, VersionActor.resolve(request))
                            case None => TapConfigIO.write(stamped)
                        }
                    case Some(msg) =>
                        logger.info("POST /tap/script: not repointing scheduled tap '" + tapName + "' at untested script: " + msg)
                }
                // Report only (rule 6): the stored target and this caller.
                TapAPIController.reportScriptTarget(request, existing, TapAPIController.readPipelineForSave)
            }

            val gson = new Gson
            val response = new java.util.HashMap[String, String]()
            response.put("scriptPath", stored.scriptPath)
            response.put("storage", stored.storage)
            response.put("scriptRepoPath", stored.scriptRepoPath)
            response.put("scriptCommitSha", stored.scriptCommitSha)
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: CodeRepoConflictException =>
                ResponseEntity.status(HttpStatus.CONFLICT).body[String]("{\"error\": \"" + e.getMessage.replace("\"", "'") + "\"}")
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    /** The incident id the recovery executor tags its calls with, if any
      * (only from the platform's own callers: AuditActor.incidentOf). */
    private def incidentHeader(request: HttpServletRequest): Option[String] =
        ai.datris.audit.AuditActor.incidentOf(request)

    private def actorLabel(request: HttpServletRequest): String =
        ResolvedKeyAccess.keyLabel(request).orNull

    @PostMapping(path = Array("/tap/cron"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def generateCron(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: java.util.Map[String, String]
    ): ResponseEntity[String] = {
        try {
            val description = body.get("description")
            logger.info("API endpoint POST /tap/cron called: " + description)
            APIKeyValidator.validate(apiKey)

            if (description == null || description.isEmpty)
                throw new DatrisException("Description is required")

            val prompt =
                s"""Convert this schedule description to a Quartz CRON expression (6 fields: second minute hour day-of-month month day-of-week).
                   |Return ONLY the CRON expression string, nothing else. No explanation, no quotes, no markdown.
                   |
                   |Examples:
                   |  "every hour" → 0 0 * * * ?
                   |  "every weekday at 4pm" → 0 0 16 ? * MON-FRI
                   |  "every 15 minutes" → 0 */15 * * * ?
                   |  "daily at midnight" → 0 0 0 * * ?
                   |  "twice a day at 8am and 6pm" → 0 0 8,18 * * ?
                   |
                   |Schedule: "$description"""".stripMargin

            val responseText = AIUtil.callAI(prompt)
            // LLMs occasionally wrap the cron in quotes, backticks, brackets, or
            // markdown fences. Strip them all before handing the value back.
            val cron = AIUtil.extractText(responseText).trim
                .replaceAll("(?s)^```(?:\\w+)?\\s*", "")
                .replaceAll("(?s)\\s*```$", "")
                .replaceAll("[\"'`\\[\\]]", "")
                .trim

            val gson = new Gson
            val response = new java.util.HashMap[String, String]()
            response.put("cronExpression", cron)
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    @PostMapping(path = Array("/tap/brainstorm"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def brainstorm(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: java.util.Map[String, Any]
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /tap/brainstorm called")
            APIKeyValidator.validate(apiKey)

            val messagesRaw = body.get("messages").asInstanceOf[java.util.List[java.util.Map[String, String]]]
            val currentDescription = Option(body.get("currentDescription")).map(_.toString).getOrElse("")
            val tapType = Option(body.get("tapType")).map(_.toString).getOrElse("structured")

            if (messagesRaw == null || messagesRaw.isEmpty)
                throw new DatrisException("messages array is required")

            val messages = messagesRaw.asScala.map { m =>
                (m.get("role"), m.get("content"))
            }.toSeq

            val response = TapBrainstormer.brainstorm(messages, currentDescription, tapType)

            val gson = new Gson
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    @PostMapping(path = Array("/tap/generate"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def generateScript(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: java.util.Map[String, String],
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            // The name a missing tapName stands for, made once per request so
            // the logged name is the one used.
            val fallbackName = "tap-" + System.currentTimeMillis()
            val tapName = Option(body.get("tapName")).getOrElse(fallbackName)
            val tapType = Option(body.get("tapType")).getOrElse("structured")
            logger.info("API endpoint POST /tap/generate called, tapName: " + tapName + ", tapType: " + tapType)
            APIKeyValidator.validate(apiKey)
            generateScriptWith(body, request, TapAPIController.ProductionAiIO, fallbackName)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    /** `POST /tap/generate` after key validation, every store, secret and
      * model call through `io`. A request naming no tap (missing or blank
      * `tapName`) reads and writes no stored tap and is not reported; the
      * model still gets the name a missing one stands for (`fallbackName`,
      * `tap-<millis>` made here when not given). */
    private[api] def generateScriptWith(
        body: java.util.Map[String, String],
        request: HttpServletRequest,
        io: TapAPIController.TapAiIO,
        fallbackName: String = null
    ): ResponseEntity[String] = {
        try {
            val description = body.get("description")
            val named = TapAPIController.namesTap(body.get("tapName"))
            val tapName = Option(body.get("tapName")).getOrElse(Option(fallbackName).getOrElse("tap-" + System.currentTimeMillis()))
            val oldScriptPath = body.get("oldScriptPath")
            val secretName = body.get("secretName")
            val tapType = Option(body.get("tapType")).getOrElse("structured")
            // Generating for an existing tap repoints it: same visibility as an update.
            if (named) TapAPIController.restrictedRefusalIfExists(request, tapName, "update", io.readTap).foreach(return _)

            if (description == null || description.isEmpty)
                throw new DatrisException("Description is required")

            // Field protection 8: the generator lists the secret's field names
            // in the prompt, so the same secret rule as the save path applies
            // here — a platform secret's field names are not disclosed.
            if (secretName != null && secretName.trim.nonEmpty) {
                val probe = TapConfig(name = tapName, description = "", targetPipeline = "", secretName = secretName)
                TapAPIController.tapSecretSaveProblem(probe, io.secretType, SecretNames.tapScopeEnforced) match {
                    case Some(msg) =>
                        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                            .body[String]("{\"error\": " + new Gson().toJson(msg) + "}")
                    case None =>
                }
            }

            val result = io.generate(description, tapName, oldScriptPath, secretName, tapType)

            // Update scriptPath in MongoDB if tap already exists
            val existing = if (named) io.readTap(tapName) else null
            val gateMsg =
                if (existing != null)
                    repointUnlessGated(
                        existing,
                        result.scriptPath,
                        request,
                        TapScriptStoreVersioning.ScriptGenerated,
                        io,
                        TapAPIController.passedGateOnlyInLogOnly(request)
                    )
                else None

            // Report only (rule 7): a secret the caller's tap read does not
            // cover. Not for a request naming no tap (its name is made up).
            if (named && secretName != null && secretName.trim.nonEmpty) {
                val candidate = TapConfig(
                    name = tapName,
                    description = "",
                    targetPipeline = "",
                    secretName = secretName,
                    catalog = Option(existing).map(_.catalog).orNull
                )
                try
                    TapAPIController.referenceRefusal(
                        request,
                        TapReferenceRules.Generate,
                        candidate,
                        existing,
                        TapReferenceRules.Lookups(() => Nil, io.secretOwner, () => "")
                    )
                catch { case e: Exception => logger.debug("Tap reference report skipped (generate): " + e.getMessage) }
            }

            val gson = new Gson
            val response = new java.util.HashMap[String, Any]()
            response.put("script", result.script)
            response.put("packages", result.packages)
            response.put("scriptPath", result.scriptPath)
            response.put("injectedPrompts", result.injectedPrompts)
            response.put("provider", result.provider)
            response.put("model", result.model)
            gateMsg.foreach(response.put("error", _))
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    /** Live progress of an in-flight `/tap/generate` for this tap, so a client
      * can show what the 1–3 minute blocking call is doing. `active: false`
      * means no generation is running — either it finished (the blocking call
      * carries the outcome) or none was started. Deliberately not logged per
      * call: clients poll this every couple of seconds. */
    @GetMapping(path = Array("/tap/generate/status"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def generateStatus(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam tap: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            APIKeyValidator.validate(apiKey)
            val gson = new Gson
            // A hidden tap's generation reads as "none running" for a restricted user.
            val progress =
                if (TapAPIController.restrictedRefusalIfExists(request, tap, "read").isDefined) None
                else TapGenerationProgress.get(tap)
            val response = TapAPIController.generateStatusBody(progress, System.currentTimeMillis())
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    @PostMapping(path = Array("/tap/fix"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def fixScript(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: java.util.Map[String, String],
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            // Parsed as before key validation, so a malformed body fails as before.
            val tapName = body.get("tapName")
            val priorIterationsJson = Option(body.get("priorIterations")).getOrElse("[]")
            val priorIterations = IterationHistoryPromptBuilder.parseFromJson(priorIterationsJson)
            logger.info("API endpoint POST /tap/fix called, tapName: " + tapName +
                ", priorIterations: " + priorIterations.size)
            APIKeyValidator.validate(apiKey)
            fixScriptWith(body, request, TapAPIController.ProductionAiIO)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    /** `POST /tap/fix` after key validation, every store and model call
      * through `io`. A request naming no tap reads and writes no stored tap. */
    private[api] def fixScriptWith(
        body: java.util.Map[String, String],
        request: HttpServletRequest,
        io: TapAPIController.TapAiIO
    ): ResponseEntity[String] = {
        try {
            val tapName = body.get("tapName")
            val named = TapAPIController.namesTap(tapName)
            val script = body.get("script")
            val diagnosis = body.get("diagnosis")
            val logs = Option(body.get("logs")).getOrElse("")
            val error = Option(body.get("error")).getOrElse("")
            val oldScriptPath = body.get("oldScriptPath")
            val priorIterationsJson = Option(body.get("priorIterations")).getOrElse("[]")
            val priorIterations = IterationHistoryPromptBuilder.parseFromJson(priorIterationsJson)
            // Fixing an existing tap repoints it: same visibility as an update.
            if (named) TapAPIController.restrictedRefusalIfExists(request, tapName, "update", io.readTap).foreach(return _)

            if (script == null || script.isEmpty)
                throw new DatrisException("Script is required")
            if (diagnosis == null || diagnosis.isEmpty)
                throw new DatrisException("Diagnosis is required")

            val result = io.fix(tapName, script, diagnosis, logs, error, oldScriptPath, priorIterations)

            // Update scriptPath in MongoDB if tap already exists
            val existingTap = if (named) io.readTap(tapName) else null
            val gateMsg =
                if (existingTap != null)
                    repointUnlessGated(
                        existingTap,
                        result.scriptPath,
                        request,
                        TapScriptStoreVersioning.ScriptFixed,
                        io,
                        TapAPIController.passedGateOnlyInLogOnly(request)
                    )
                else None

            val gson = new Gson
            val response = new java.util.HashMap[String, Any]()
            response.put("script", result.script)
            response.put("packages", result.packages)
            response.put("scriptPath", result.scriptPath)
            gateMsg.foreach(response.put("error", _))
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    @PostMapping(path = Array("/tap/review"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def reviewScript(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: java.util.Map[String, Object],
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            // Parsed as before key validation, so a malformed body fails as before.
            val tapName = Option(body.get("tapName")).map(_.toString).getOrElse("tap")
            val recordCount = Option(body.get("recordCount")).map(_.toString.toDouble.toInt).getOrElse(0)
            Option(body.get("durationMs")).map(_.toString.toDouble.toLong)
            val priorIterations = IterationHistoryPromptBuilder.parseFromJson(Option(body.get("priorIterations")).map(_.toString).getOrElse("[]"))
            logger.info(s"API endpoint POST /tap/review called, tapName: $tapName, recordCount: $recordCount, " +
                s"priorIterations: ${priorIterations.size}")
            APIKeyValidator.validate(apiKey)
            reviewScriptWith(body, request, TapAPIController.ProductionAiIO)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    /** `POST /tap/review` after key validation, every store and model call
      * through `io`. A request naming no tap reads and writes no stored tap;
      * the model still gets the name `tap` a missing one stands for. Report
      * only (rule 4): a review by an identity holding no `tap:update`. */
    private[api] def reviewScriptWith(
        body: java.util.Map[String, Object],
        request: HttpServletRequest,
        io: TapAPIController.TapAiIO
    ): ResponseEntity[String] = {
        try {
            val bodyName = Option(body.get("tapName")).map(_.toString)
            val named = bodyName.exists(TapAPIController.namesTap)
            val tapName = bodyName.getOrElse("tap")
            val script = Option(body.get("script")).map(_.toString).getOrElse("")
            val recordCount = Option(body.get("recordCount")).map(_.toString.toDouble.toInt).getOrElse(0)
            val durationMs = Option(body.get("durationMs")).map(_.toString.toDouble.toLong).getOrElse(0L)
            val logs = Option(body.get("logs")).map(_.toString).getOrElse("")
            val oldScriptPath = Option(body.get("oldScriptPath")).map(_.toString).orNull
            val priorIterationsJson = Option(body.get("priorIterations")).map(_.toString).getOrElse("[]")
            val priorIterations = IterationHistoryPromptBuilder.parseFromJson(priorIterationsJson)
            if (named) TapAPIController.restrictedRefusalIfExists(request, tapName, "update", io.readTap).foreach(return _)

            if (script.isEmpty)
                throw new DatrisException("Script is required")

            if (TapReferenceRules.reviewWouldBeRefused(CapabilityCheck.resolvedKey(request)))
                TapOwnerScopeReport.reviewWithoutUpdate(request, if (named) tapName else null)

            val result = io.review(tapName, script, recordCount, durationMs, logs, oldScriptPath, priorIterations)

            // Persist the new scriptPath onto the TapConfig if the tap already exists.
            val gateMsg =
                if (result.rewritten && named) {
                    val existingTap = io.readTap(tapName)
                    if (existingTap != null && result.scriptPath != null && result.scriptPath != oldScriptPath)
                        repointUnlessGated(
                            existingTap,
                            result.scriptPath,
                            request,
                            TapScriptStoreVersioning.ScriptReviewed,
                            io,
                            TapAPIController.passedGateOnlyInLogOnly(request)
                        )
                    else None
                } else None

            val gson = new Gson
            val response = new java.util.HashMap[String, Any]()
            response.put("script", result.script)
            response.put("packages", result.packages)
            response.put("scriptPath", result.scriptPath)
            response.put("changes", result.changes)
            response.put("rewritten", Boolean.box(result.rewritten))
            gateMsg.foreach(response.put("error", _))
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    @PostMapping(path = Array("/tap/optimize"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def optimizeScript(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: java.util.Map[String, Object],
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            // Parsed as before key validation, so a malformed body fails as before.
            val tapName = Option(body.get("tapName")).map(_.toString).getOrElse("tap")
            val recordCount = Option(body.get("recordCount")).map(_.toString.toDouble.toInt).getOrElse(0)
            val durationMs = Option(body.get("durationMs")).map(_.toString.toDouble.toLong).getOrElse(0L)
            val priorIterations = IterationHistoryPromptBuilder.parseFromJson(Option(body.get("priorIterations")).map(_.toString).getOrElse("[]"))
            logger.info(s"API endpoint POST /tap/optimize called, tapName: $tapName, recordCount: $recordCount, " +
                s"durationMs: $durationMs, priorIterations: ${priorIterations.size}")
            APIKeyValidator.validate(apiKey)
            optimizeScriptWith(body, request, TapAPIController.ProductionAiIO)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    /** `POST /tap/optimize` after key validation, every store and model call
      * through `io`. A request naming no tap reads and writes no stored tap;
      * the model still gets the name `tap` a missing one stands for. */
    private[api] def optimizeScriptWith(
        body: java.util.Map[String, Object],
        request: HttpServletRequest,
        io: TapAPIController.TapAiIO
    ): ResponseEntity[String] = {
        try {
            val bodyName = Option(body.get("tapName")).map(_.toString)
            val named = bodyName.exists(TapAPIController.namesTap)
            val tapName = bodyName.getOrElse("tap")
            val script = Option(body.get("script")).map(_.toString).getOrElse("")
            val recordCount = Option(body.get("recordCount")).map(_.toString.toDouble.toInt).getOrElse(0)
            val durationMs = Option(body.get("durationMs")).map(_.toString.toDouble.toLong).getOrElse(0L)
            val logs = Option(body.get("logs")).map(_.toString).getOrElse("")
            val oldScriptPath = Option(body.get("oldScriptPath")).map(_.toString).orNull
            val priorIterationsJson = Option(body.get("priorIterations")).map(_.toString).getOrElse("[]")
            val priorIterations = IterationHistoryPromptBuilder.parseFromJson(priorIterationsJson)
            if (named) TapAPIController.restrictedRefusalIfExists(request, tapName, "update", io.readTap).foreach(return _)

            if (script.isEmpty)
                throw new DatrisException("Script is required")

            val opt = io.optimize(tapName, script, recordCount, durationMs, logs, oldScriptPath, priorIterations)

            val existingTap = if (named) io.readTap(tapName) else null
            val gateMsg =
                if (existingTap != null && opt.scriptPath != null && opt.scriptPath != oldScriptPath)
                    repointUnlessGated(
                        existingTap,
                        opt.scriptPath,
                        request,
                        TapScriptStoreVersioning.ScriptOptimized,
                        io,
                        TapAPIController.passedGateOnlyInLogOnly(request)
                    )
                else None

            val gson = new Gson
            val response = new java.util.HashMap[String, Any]()
            response.put("script", opt.script)
            response.put("packages", opt.packages)
            response.put("scriptPath", opt.scriptPath)
            response.put("changes", opt.changes)
            gateMsg.foreach(response.put("error", _))
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    @PostMapping(path = Array("/tap/test"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def testTap(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam(required = false) testLimit: Integer,
        @RequestBody tapConfig: TapConfig,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /tap/test called for tap: " + tapConfig.name + (if (testLimit != null) s" (testLimit=$testLimit)" else ""))
            APIKeyValidator.validate(apiKey)
            // A tap outside the caller's scope, or a secret it may not use,
            // is refused before anything runs.
            // One read of the stored tap, shared by the test check and the
            // script reference rule.
            val readStored: String => TapConfig = {
                val held = scala.collection.mutable.HashMap[String, Option[TapConfig]]()
                n => held.getOrElseUpdate(n, Option(TapConfigIO.read(DatrisEnvironment.current.tapTableName, n))).orNull
            }
            TapAPIController.testRefusal(request, tapConfig, readStored, TapAPIController.liveSecretOwner)
                .foreach(return _)
            // Script reference rule: a limited caller's new or changed
            // reference outside the tap script area, or naming a hidden tap's
            // script, is refused with the answer above; the rest is reported.
            if (tapConfig != null) {
                val hasRef = (tapConfig.scriptPath != null && tapConfig.scriptPath.nonEmpty) ||
                    (tapConfig.scriptRepoPath != null && tapConfig.scriptRepoPath.nonEmpty)
                val stored =
                    if (hasRef && tapConfig.name != null && tapConfig.name.nonEmpty && TapReferenceRules.limited(CapabilityCheck.resolvedKey(request).orNull))
                        readStored(tapConfig.name)
                    else null
                val lookups = TapReferenceRules.Lookups(
                    TapAPIController.once(() => TapConfigIO.readAll(DatrisEnvironment.current.tapTableName)),
                    TapAPIController.liveSecretOwner,
                    TapAPIController.once(() => TapAPIController.liveRepoPathPrefix())
                )
                TapAPIController.referenceRefusal(request, TapReferenceRules.Test, tapConfig, stored, lookups).foreach(return _)
            }

            // HTTP taps have no script — a test run is just a call with testLimit set.
            val hasTestScriptRef =
                (tapConfig.scriptPath != null && tapConfig.scriptPath.nonEmpty) ||
                    (tapConfig.scriptRepoPath != null && tapConfig.scriptRepoPath.nonEmpty)
            if (!tapConfig.isHttp && !hasTestScriptRef)
                throw new DatrisException("Script path is required for testing")
            if (tapConfig.isHttp && (tapConfig.endpointUrl == null || tapConfig.endpointUrl.trim.isEmpty))
                throw new DatrisException("endpointUrl is required for testing an HTTP tap")

            // Run in test mode (no push to pipeline). testLimit > 0 tells the
            // runner to inject DATRIS_TAP_TEST_LIMIT into the script env so a
            // well-written tap script caps its source reads. Cron/manual runs
            // never set this.
            val testLimitInt: Int = if (testLimit != null && testLimit.intValue() > 0) testLimit.intValue() else 0
            val testStartMs = System.currentTimeMillis()
            val result = TapRunner.run(tapConfig, mode = "test", testLimit = testLimitInt)
            val testDurationMs = System.currentTimeMillis() - testStartMs
            TapAPIController.reportCallerReads(request, tapConfig, result)
            val gson = new Gson
            // Preview off the staged file (Phase 4): the first testPreviewSize(testLimit)
            // records plus `recordsTruncated` when there were more. The staged
            // file is released once the preview is built.
            val (recordsJson, recordsTruncated) =
                try TapRunner.preview(result.staged, TapAPIController.testPreviewSize(testLimitInt))
                finally TapRunner.release(result)
            val response = new java.util.HashMap[String, Any]()
            response.put("records", recordsJson)
            if (recordsTruncated) response.put("recordsTruncated", java.lang.Boolean.TRUE)
            response.put("recordCount", Integer.valueOf(result.recordCount))
            response.put("error", result.error)
            response.put("logs", result.logs)
            response.put("dataType", result.dataType)
            response.put("columns", result.columns)
            response.put("durationMs", java.lang.Long.valueOf(testDurationMs))

            // AI explanation if there's an error, 0 records, or logs contain notable indicators.
            // "deprecat" and "warning" catch cases where the script "succeeded" but the runtime
            // output is trying to tell us something (e.g. DeprecationWarning, urllib3
            // warnings, pandas FutureWarning) — the user shouldn't have to manually ask for a
            // review when the logs are already shouting.
            val logsHaveIssues = result.logs != null && result.logs.nonEmpty && {
                val lower = result.logs.toLowerCase
                lower.contains("error") || lower.contains("exception") ||
                lower.contains("failed") || lower.contains("forbidden") ||
                lower.contains("traceback") || lower.contains("deprecat") ||
                lower.contains("warning")
            }
            // AI diagnosis reads the tap's script — HTTP taps have none, and their
            // code lives outside the platform, so Fix/Review/Optimize/Diagnose AI
            // actions don't apply to them.
            val needsExplanation = (result.error != null || result.recordCount == 0 || logsHaveIssues) && !tapConfig.isHttp
            if (needsExplanation) {
                // Read via TapCodeStore so repo-backed taps (scriptStorage == "github",
                // empty scriptPath) resolve too — a direct MinIO read throws
                // "object name must be a non-empty string" for those and the
                // diagnosis would run against an empty script.
                val script =
                    try {
                        TapCodeStore.forTap(tapConfig).readScript(tapConfig).getOrElse("")
                    } catch {
                        case e: Exception =>
                            logger.warn("Failed to read tap script for AI run explanation (tap: " + tapConfig.name + ")", e)
                            ""
                    }
                val aiExplanation = TapRunDiagnoser.explain(tapConfig.description, script, result)
                // Swallow the "all clear" response so the UI doesn't show an empty diagnosis
                // panel just because the heuristic fired on a benign warning.
                val isAllClear = aiExplanation != null &&
                    aiExplanation.trim.toLowerCase.stripSuffix(".").stripSuffix("!") == "no issues detected"
                if (aiExplanation != null && !isAllClear)
                    response.put("aiExplanation", aiExplanation)
            }

            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    @PostMapping(path = Array("/tap/run"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def runTap(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: java.util.Map[String, Any],
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            val name = Option(body.get("name")).map(_.toString).orNull
            val requestedMode = TapAPIController.requestedRunMode(body.get("mode"))
            logger.info("API endpoint POST /tap/run called for tap: " + name + s" (mode=$requestedMode)")
            APIKeyValidator.validate(apiKey)

            // mode=test previews the first `testLimit` records (default 20, like the UI's
            // Test button); mode=run always reads every row.
            val effectiveTestLimit = TapAPIController.resolveTestLimit(requestedMode, body.get("testLimit"))
            logger.info("POST /tap/run for tap: " + name + s" (mode=$requestedMode, testLimit=$effectiveTestLimit)")

            if (name == null || name.isEmpty)
                throw new DatrisException("Tap name is required")
            // Only run and test are modes of this route; anything else (a
            // typo, or the recovery agent's internal scratch rehearsal) is
            // refused before anything is read or run.
            TapAPIController.runModeRefusal(requestedMode).foreach(return _)

            val tapConfig = TapAPIController.tapOr404(name, Option(TapConfigIO.read(DatrisEnvironment.current.tapTableName, name))) match {
                case Left(notFound) => return notFound
                case Right(c) => c
            }

            // Scope check: `tap:run:owner=self` keys (e.g. rag-builder) may
            // only run taps they created. The loaded tap's createdByKeyLabel
            // is matched against the caller's label.
            TapAPIController.scopeRefusal(request, tapConfig, "run").foreach(return _)

            val mode = requestedMode
            // A run feeds the target pipeline: refuse one outside a restricted user's grants.
            // Every mode other than test feeds the target pipeline.
            if (mode != "test") TapAPIController.targetPipelineRefusal(request, tapConfig).foreach(return _)

            // Report only: the script reference and secret rules never refuse a run.
            try
                TapAPIController.referenceRefusal(
                    request,
                    TapReferenceRules.Run,
                    tapConfig,
                    tapConfig,
                    TapReferenceRules.Lookups(() => Nil, TapAPIController.liveSecretOwner, () => "")
                )
            catch { case e: Exception => logger.debug("Tap reference report skipped (run): " + e.getMessage) }

            // Optional per-run params. Stringify each value so the script sees
            // env vars regardless of whether the agent sent {start_date: "2026-05-01"}
            // or {limit: 1000}. Nested objects/arrays get JSON-encoded so a script
            // that wants structured params can json.loads() them back.
            val params: Map[String, String] = Option(body.get("params")) match {
                case Some(m: java.util.Map[_, _]) =>
                    val gson = new Gson
                    m.asInstanceOf[java.util.Map[String, Any]].asScala.toMap.map { case (k, v) =>
                        val sv = v match {
                            case null => ""
                            case s: String => s
                            case n: java.lang.Number => n.toString
                            case b: java.lang.Boolean => b.toString
                            case other => gson.toJson(other)
                        }
                        k -> sv
                    }
                case _ => Map.empty[String, String]
            }

            // Debounce push runs to suppress accidental duplicates (parallel tool calls,
            // double-clicks, transport retries). mode=test is read-only, no need to debounce.
            // checkAndAcceptRunDebounce returns Some(ageMs) when the request should be
            // rejected (a recent run is still inside the window), or None to proceed.
            val debouncedAgeMs: Option[Long] =
                if (mode == "run") checkAndAcceptRunDebounce(name) else None

            // mode=test with `reuseLastTest` (propose_pipeline_schema): answer from the
            // rows the tap's last successful test returned when they are still held
            // and valid, so a slow or non-deterministic source is not run twice. No
            // TapRunner.run, no lastTestRun* re-stamp. On a miss, fall through.
            val reuseHit: Option[TapTestPreviewCache.Held] =
                if (TapAPIController.reuseCandidate(mode, body.get("reuseLastTest"), params))
                    TapTestPreviewCache.get(tapConfig, effectiveTestLimit)
                else None

            if (reuseHit.isDefined) {
                val response = TapAPIController.reusedTestResponse(tapConfig, reuseHit.get, effectiveTestLimit)
                logger.info(
                    "POST /tap/run for tap: " + name + " answered from the last successful test (" +
                        response.get("records").asInstanceOf[com.google.gson.JsonArray].size() + " of " +
                        reuseHit.get.recordCount + " records)"
                )
                new ResponseEntity[String](new Gson().toJson(response), HttpStatus.OK)
            } else if (debouncedAgeMs.isDefined) {
                val ageMs = debouncedAgeMs.get
                logger.info(
                    "Debounced /tap/run for tap: " + name + " — last run started " + ageMs + "ms ago (window=" + TapAPIController.runDebounceWindowMs + "ms)"
                )
                val gson = new Gson
                val response = new java.util.HashMap[String, Any]()
                response.put("tap", name)
                response.put("description", tapConfig.description)
                response.put("status", "skipped")
                response.put("mode", mode)
                response.put("targetPipeline", tapConfig.targetPipeline)
                response.put("persisted", java.lang.Boolean.FALSE)
                response.put("persistedReason", "debounced")
                response.put(
                    "error",
                    "Tap '" + name + "' was triggered " + ageMs + " ms ago; ignoring this duplicate request " +
                        "(debounce window: " + TapAPIController.runDebounceWindowMs + " ms). Wait for the in-flight " +
                        "run to finish, then check `get_pipeline_status` or `get_tap_logs` for the outcome."
                )
                response.put("recordCount", Integer.valueOf(0))
                new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
            } else {

                // Identity of the script this test runs, captured BEFORE the run so a
                // script replaced mid-test never has the old script's rows held under it.
                val testedScriptId: Option[String] = if (mode != "run") TapScriptIdentity.of(tapConfig) else None

                val result = TapRunner.run(tapConfig, mode = mode, testLimit = effectiveTestLimit, params = params)
                TapAPIController.reportCallerReads(request, tapConfig, result)

                // Save test run status when not pushing to pipeline
                if (mode != "run") {
                    // Status churn: plain write, no version minted.
                    TapConfigIO.write(TapAPIController.stampTestResult(tapConfig, result, TapAPIController.nowStamp()))
                }

                val gson = new Gson

                // Records policy:
                //   - mode=run: omit records entirely. They are in transit to the destination;
                //     the agent must verify via get_pipeline_status, not from this body.
                //     `recordCount` is enough to summarize what was submitted. (TapRunner
                //     already released the tap's staging directory.)
                //   - mode=test: include records as a preview read off the staged file,
                //     capped at TapAPIController.testPreviewSize(testLimit) (the requested
                //     limit up to 500, else 20); `recordsTruncated=true`
                //     when we trimmed it. The staged file is released after the preview.
                val (recordsToReturn, recordsTruncated): (com.google.gson.JsonElement, Boolean) =
                    if (mode == "run") (null, false)
                    else
                        try TapRunner.preview(result.staged, TapAPIController.testPreviewSize(effectiveTestLimit))
                        finally TapRunner.release(result)

                if (mode != "run")
                    TapAPIController.holdTestPreview(
                        TapTestPreviewCache,
                        tapConfig,
                        result,
                        recordsToReturn,
                        recordsTruncated,
                        effectiveTestLimit,
                        paramsSent = params.nonEmpty,
                        scriptId = testedScriptId
                    )

                val hasTargetPipeline = tapConfig.targetPipeline != null && tapConfig.targetPipeline.nonEmpty
                val persisted = mode == "run" && hasTargetPipeline && result.error == null && result.recordCount > 0
                val persistedReason: String =
                    if (persisted) null
                    else if (mode != "run") "test_mode"
                    else if (result.error != null) "run_error"
                    else if (result.recordCount == 0) "no_records"
                    else if (!hasTargetPipeline) "no_target_pipeline"
                    else "unknown"
                val response = new java.util.HashMap[String, Any]()
                response.put("tap", name)
                response.put("description", tapConfig.description)
                response.put("status", if (result.error == null) "success" else "failure")
                response.put("mode", mode)
                response.put("targetPipeline", tapConfig.targetPipeline)
                response.put("persisted", java.lang.Boolean.valueOf(persisted))
                if (persistedReason != null) response.put("persistedReason", persistedReason)
                if (result.publisherToken != null) response.put("publisherToken", result.publisherToken)
                if (result.pipelineTokens != null && !result.pipelineTokens.isEmpty) response.put("pipelineTokens", result.pipelineTokens)
                if (recordsToReturn != null) response.put("records", recordsToReturn)
                if (recordsTruncated) response.put("recordsTruncated", java.lang.Boolean.TRUE)
                response.put("recordCount", Integer.valueOf(result.recordCount))
                response.put("error", result.error)
                response.put("logs", result.logs)
                response.put("dataType", result.dataType)
                response.put("columns", result.columns)
                new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
            } // end else (non-debounced path)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    /** Atomically check + accept the run-debounce window for a tap. Returns Some(ageMs)
     *  when a recent run is still inside the window (caller should reject), or None when
     *  the slot is now reserved for the caller to proceed. */
    private def checkAndAcceptRunDebounce(tapName: String): Option[Long] = {
        val tenant = if (DatrisEnvironment.values.multiTenant) DatrisEnvironment.current.environment else "global"
        val dedupKey = tenant + "::" + tapName
        val now = System.currentTimeMillis()
        val accepted = new java.util.concurrent.atomic.AtomicBoolean(false)
        TapAPIController.recentRunStarts.compute(
            dedupKey,
            (_, existing) => {
                if (existing != null && (now - existing.longValue()) < TapAPIController.runDebounceWindowMs)
                    existing
                else {
                    accepted.set(true)
                    java.lang.Long.valueOf(now)
                }
            }
        )
        if (accepted.get()) None
        else Some(now - TapAPIController.recentRunStarts.get(dedupKey).longValue())
    }

    @GetMapping(path = Array("/tap/state"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getTapState(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam name: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /tap/state called for tap: " + name)
            APIKeyValidator.validate(apiKey)
            TapAPIController.restrictedRefusal(request, name, "read").foreach(return _)

            val row = TapStateIO.read(name)
            val gson = new Gson
            val response = new java.util.HashMap[String, Any]()
            response.put("tap", name)
            // Parse the stored blob back to an object so the response nests it as JSON
            // rather than a double-encoded string.
            response.put("state", if (row != null && row.state != null) JsonParser.parseString(row.state) else JsonNull.INSTANCE)
            if (row != null) {
                response.put("updatedAt", row.updatedAt)
                response.put("updatedBy", row.updatedBy)
            }
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    @PostMapping(path = Array("/tap/state"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def setTapState(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: java.util.Map[String, Any],
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            val name = Option(body.get("name")).map(_.toString).orNull
            logger.info("API endpoint POST /tap/state called for tap: " + name)
            APIKeyValidator.validate(apiKey)

            if (name == null || name.isEmpty)
                throw new DatrisException("Tap name is required")
            TapAPIController.restrictedRefusal(request, name, "update").foreach(return _)
            TapAPIController.tapOr404(name, Option(TapConfigIO.read(DatrisEnvironment.current.tapTableName, name))) match {
                case Left(notFound) => return notFound
                case Right(_) =>
            }

            val gson = new Gson
            // `state` must be a JSON object (the same shape the wrapper accepts from
            // DATRIS_STATE). An empty object {} is a valid "reset to first-run" value.
            val stateJson: String = body.get("state") match {
                case m: java.util.Map[_, _] => gson.toJson(m)
                case null => throw new DatrisException("state object is required (use {} to reset, or DELETE /tap/state)")
                case other => throw new DatrisException("state must be a JSON object, got: " + other.getClass.getSimpleName)
            }

            val sdf = new java.text.SimpleDateFormat(DatrisEnvironment.current.dateFormat)
            sdf.setTimeZone(java.util.TimeZone.getTimeZone(DatrisEnvironment.current.dateTimezone))
            TapStateIO.write(TapState(name, stateJson, sdf.format(new java.util.Date()), "manual"))

            val response = new java.util.HashMap[String, Any]()
            response.put("tap", name)
            response.put("state", JsonParser.parseString(stateJson))
            response.put("message", "State saved. The next run receives it via DATRIS_TAP_STATE.")
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    @DeleteMapping(path = Array("/tap/state"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def deleteTapState(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam name: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint DELETE /tap/state called for tap: " + name)
            APIKeyValidator.validate(apiKey)
            TapAPIController.restrictedRefusal(request, name, "update").foreach(return _)

            TapStateIO.delete(name)
            new ResponseEntity[String](
                "{\"message\": \"State reset for tap: " + name + ". The next run starts from scratch (full fetch).\"}",
                HttpStatus.OK
            )
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    @GetMapping(path = Array("/tap/ledger"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getTapLedger(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam name: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /tap/ledger called for tap: " + name)
            APIKeyValidator.validate(apiKey)
            TapAPIController.restrictedRefusal(request, name, "read").foreach(return _)

            val entries = TapDocumentLedgerIO.readByTap(DatrisEnvironment.current.tapLedgerTableName, name).asJava
            val gson = new Gson
            new ResponseEntity[String](gson.toJson(entries), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    @DeleteMapping(path = Array("/tap/ledger"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def deleteTapLedger(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam name: String,
        @RequestParam(required = false) uri: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint DELETE /tap/ledger called for tap: " + name + (if (uri != null) ", uri: " + uri else ""))
            APIKeyValidator.validate(apiKey)
            TapAPIController.restrictedRefusal(request, name, "update").foreach(return _)

            val ledgerTable = DatrisEnvironment.current.tapLedgerTableName
            val bucket = DatrisEnvironment.current.environment + "-config"

            if (uri != null && uri.nonEmpty) {
                // Delete one entry, plus its staged file
                val entry = TapDocumentLedgerIO.read(ledgerTable, name, uri)
                if (entry != null && entry.stagedPath != null && entry.stagedPath.nonEmpty) {
                    try { ObjectStoreUtil.deleteBucketObject(bucket, entry.stagedPath) }
                    catch { case ex: Exception => logger.warn("Failed to delete staged doc " + entry.stagedPath + ": " + ex.getMessage) }
                }
                TapDocumentLedgerIO.delete(ledgerTable, name, uri)
                new ResponseEntity[String]("{\"message\": \"Ledger entry deleted\"}", HttpStatus.OK)
            } else {
                // Clear the entire ledger for this tap + all staged files
                val entries = TapDocumentLedgerIO.readByTap(ledgerTable, name)
                entries.foreach { e =>
                    if (e.stagedPath != null && e.stagedPath.nonEmpty) {
                        try { ObjectStoreUtil.deleteBucketObject(bucket, e.stagedPath) }
                        catch { case ex: Exception => logger.warn("Failed to delete staged doc " + e.stagedPath + ": " + ex.getMessage) }
                    }
                }
                TapDocumentLedgerIO.deleteByTap(ledgerTable, name)
                new ResponseEntity[String]("{\"message\": \"Ledger cleared: " + entries.size + " entries\"}", HttpStatus.OK)
            }
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }
}
