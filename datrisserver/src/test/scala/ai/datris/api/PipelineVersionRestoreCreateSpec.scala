package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.{CapabilityRoutes, RouteCheck, ScopeDenialWording}
import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.APIKeyValidator
import ai.datris.util.InMemoryUserDb.grants
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

/** Owner decision: restoring a version of a DELETED pipeline re-creates it, so
  * it needs `pipeline:create` as well as `pipeline:update`, decided as
  * `POST /pipeline` decides a new pipeline (`PipelineAPIController.recreateRefusal`:
  * `pipeline:create` held at all, then the save guard's new-pipeline rule
  * `keyTargetRefusal`), on the context the re-created pipeline will have: the
  * version's catalog and the version's owner (a restore keeps the owner).
  * Restore over a live pipeline needs `pipeline:update` only, as before.
  *
  * Refusals here come from the guard, the first step of the restore's save
  * path, before any store is read; the environment has no stores, so a 403
  * from `restorePipelineSnapshot` proves nothing was written. */
class PipelineVersionRestoreCreateSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val env: DatrisEnvironment = DatrisEnvironment(
        initialized = true,
        environment = "test",
        fileNotifierQueue = null,
        ttlFileNotifierQueueMessages = 0,
        pipelineTopic = null,
        pipelineTableName = null,
        archivedMetadataTableName = null,
        pipelineStatusTableName = null,
        fileNotifierMessageTableName = null,
        dataPullTableName = null,
        useApiKeys = false,
        apiKeysSecretName = null,
        postgresSecretName = null,
        mongoDbSecretName = null,
        kafkaProducerSecretName = null,
        kafkaConsumerConfig = null,
        mongoDbConfig = null,
        minIOConfig = null,
        activeMQConfig = null,
        aiConfig = null,
        aiEnabled = false,
        embeddingSecretName = null,
        qdrantSecretName = null,
        weaviateSecretName = null,
        milvusSecretName = null,
        chromaSecretName = null,
        pgvectorSecretName = null,
        multiTenant = false
    )

    private var savedValues: DatrisEnvironment = _

    override def beforeEach(): Unit = {
        savedValues = DatrisEnvironment.values
        DatrisEnvironment.init(env)
        TenantContext.set(env)
    }

    override def afterEach(): Unit = {
        TenantContext.clear()
        DatrisEnvironment.values = savedValues
    }

    // --------------------------------------------------------------- requests

    private def req(rk: ResolvedKey): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("POST")
        when(r.getRequestURI).thenReturn("/api/v1/pipeline/version/restore")
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def key(label: String, caps: Seq[String]): HttpServletRequest =
        req(ResolvedKey(None, label, Capability.parseList(caps), isLegacyFullAccess = false))

    private val updScopedCaps = Seq("pipeline:update:catalog=mine", "pipeline:read:catalog=mine")
    private val allScopedCaps = "pipeline:create:catalog=mine" +: updScopedCaps
    private val unscopedUpdCaps = Seq("pipeline:update", "pipeline:read")
    private val ownCaps = Seq("pipeline:create:owner=self", "pipeline:update:owner=self", "pipeline:read:owner=self")

    private def updScoped = key("upd-scoped", updScopedCaps)
    private def allScoped = key("mine-all", allScopedCaps)
    private def unscopedUpd = key("upd-any", unscopedUpdCaps)
    private def own = key("own", ownCaps)

    private def full = req(ResolvedKey(None, "full", Nil, isLegacyFullAccess = true))
    private def editor = req(APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null)))
    private def authOff = req(null)
    private def restricted(role: String, level: String) =
        req(APIKeyValidator.resolveFromSession(User("bob", "h", role, null, null, null, restricted = true, catalogGrants = grants("mine" -> level))))

    // --------------------------------------------------------------- fixtures

    private def pg(name: String, catalog: String, owner: String = "someone"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = owner,
            destination = Destination(database = Database(dbName = "datris", schema = "public", table = name + "_t", usePostgres = true))
        )

    private def versionNotFound(name: String): ResponseEntity[String] =
        ResponseEntity.status(404).body[String]("{\"error\": \"No version 1 for pipeline '" + name + "'\"}")

    private val noReads: () => Seq[PipelineConfig] = () => Nil

    private def decide(r: HttpServletRequest, candidate: PipelineConfig, stored: PipelineConfig) =
        EntityVersionAPIController.restoreGuardDecision(r, candidate, stored, noReads, versionNotFound(candidate.name))

    private def recheck(r: HttpServletRequest, candidate: PipelineConfig, decided: PipelineConfig, fresh: PipelineConfig) =
        EntityVersionAPIController.restoreRecheck(r, candidate, decided, fresh, noReads, versionNotFound(candidate.name))

    /** What `POST /pipeline` decides for the same caller and a new pipeline
      * with the same config (its save guard; the route gate is separate). */
    private def saveDecision(r: HttpServletRequest, config: PipelineConfig) =
        PipelineAPIController.saveGuardDecision(r, config, null, noReads)

    private def restoreRoute(r: HttpServletRequest, snap: PipelineConfig, live: PipelineConfig): ResponseEntity[String] =
        new EntityVersionAPIController().restorePipelineSnapshot(snap.name, 1, snap, live, r)

    private def sameResponse(a: ResponseEntity[String], b: ResponseEntity[String]): Unit =
        assert(a.getStatusCode == b.getStatusCode && a.getBody == b.getBody, s"${a.getStatusCode} ${a.getBody} vs ${b.getStatusCode} ${b.getBody}")

    private def refused(d: Either[ResponseEntity[String], PipelineConfig]): ResponseEntity[String] = {
        assert(d.isLeft, s"expected a refusal, got $d")
        d.left.get
    }

    private val deleted = pg("gone", "mine")

    // ------------------------------------------------------------------ tests

    test("deleted pipeline: a key with update and read in scope but no create is refused 403 naming create; nothing written") {
        val r = refused(decide(updScoped, deleted, null))
        assert(r.getStatusCode.value == 403)
        ScopeDenialWording.assertBody(r.getBody, ScopeDenialWording.message("upd-scoped", "pipeline", "create", updScopedCaps), Seq("someone"))
        // Through the restore route (no stores here: a write would fail).
        sameResponse(restoreRoute(updScoped, deleted, null), r)
        // POST /pipeline of a new pipeline with the same catalog: the same answer.
        sameResponse(r, refused(saveDecision(updScoped, deleted)))
    }

    test("live pipeline: the same update-only key restores over it as before") {
        val live = pg("gone", "mine")
        assert(decide(updScoped, live.copy(tags = new java.util.ArrayList[String](java.util.Arrays.asList("v1"))), live).isRight)
    }

    test("deleted pipeline: a key with create, update and read in scope restores it (keeping the version's owner)") {
        val d = decide(allScoped, deleted, null)
        assert(d.isRight, s"$d")
        assert(d.right.get.createdByKeyLabel == "someone")
        assert(saveDecision(allScoped, deleted).isRight)
        // Update and read anywhere, create only in `mine`: a version in another
        // catalog is refused on create, as POST /pipeline refuses that new pipeline.
        val wideCaps = Seq("pipeline:update", "pipeline:read") ++ allScopedCaps
        val wide = key("mine-all", wideCaps)
        val hr = pg("gone", "HR")
        val r = refused(decide(wide, hr, null))
        ScopeDenialWording.assertBody(r.getBody, ScopeDenialWording.message("mine-all", "pipeline", "create", wideCaps), Seq("HR", "someone"))
        sameResponse(r, refused(saveDecision(wide, hr)))
    }

    test("deleted pipeline: a key holding unscoped update and no create at all is refused 403 naming create, as POST /pipeline's route gate refuses it") {
        val r = refused(decide(unscopedUpd, deleted, null))
        ScopeDenialWording.assertBody(r.getBody, ScopeDenialWording.message("upd-any", "pipeline", "create", unscopedUpdCaps), Seq("mine", "someone"))
        sameResponse(restoreRoute(unscopedUpd, deleted, null), r)
        assert(CapabilityRoutes.lookup("POST", "/api/v1/pipeline") == RouteCheck.Require("pipeline", "create"))
        assert(CapabilityRoutes.lookup("POST", "/api/v1/pipeline/version/restore") == RouteCheck.Require("pipeline", "update"))
        // Over a live pipeline it restores as before.
        val live = pg("gone", "mine")
        assert(decide(unscopedUpd, live, live).isRight)
    }

    test("deleted pipeline, owner=self key: its own version restores; another key's version is refused") {
        val mine = pg("gone", "mine", owner = "own")
        assert(decide(own, mine, null).isRight)
        // Another key's version: refused on update by the existing scope rule
        // (the version's owner decides), before the create check.
        val theirs = pg("gone", "mine", owner = "other")
        val r = refused(decide(own, theirs, null))
        ScopeDenialWording.assertBody(r.getBody, ScopeDenialWording.message("own", "pipeline", "update", ownCaps), Seq("other", "mine"))
        // The create check alone judges the owner the re-created pipeline will
        // have (the version's), not the caller: refused on create.
        val c = PipelineAPIController.recreateRefusal(own, theirs)
        assert(c.isDefined)
        ScopeDenialWording.assertBody(c.get.getBody, ScopeDenialWording.message("own", "pipeline", "create", ownCaps), Seq("other", "mine"))
        assert(PipelineAPIController.recreateRefusal(own, mine).isEmpty)
        // A version with no recorded owner: the re-created pipeline has none either.
        assert(PipelineAPIController.recreateRefusal(own, pg("gone", "mine", owner = null)).isDefined)
    }

    test("under the lock: deleted since the check, an update-only caller gets the 403 naming create, a caller with create gets the 409") {
        val live = pg("gone", "mine")
        val candidate = live.copy(tags = new java.util.ArrayList[String](java.util.Arrays.asList("v1")))
        val u = recheck(updScoped, candidate, live, null)
        assert(u.isDefined && u.get.getStatusCode.value == 403, s"$u")
        ScopeDenialWording.assertBody(u.get.getBody, ScopeDenialWording.message("upd-scoped", "pipeline", "create", updScopedCaps), Seq("someone"))
        val k = recheck(unscopedUpd, candidate, live, null)
        assert(k.exists(_.getStatusCode.value == 403), s"$k")
        for (r <- Seq(allScoped, full, editor, authOff)) {
            val c = recheck(r, candidate, live, null)
            assert(c.isDefined)
            sameResponse(c.get, PipelineAPIController.restoreConflict("gone"))
        }
        // Still deleted under the lock: the same refusal as the decision.
        assert(recheck(updScoped, deleted, null, null).exists(_.getStatusCode.value == 403))
        assert(recheck(allScoped, deleted, null, null).isEmpty)
    }

    test("deleted pipeline: restricted sessions keep the version 404; full keys, unrestricted sessions, auth off and a null request are unchanged") {
        for (r <- Seq(restricted(User.RoleEditor, "edit"), restricted(User.RoleViewer, "view")))
            sameResponse(refused(decide(r, deleted, null)), versionNotFound("gone"))
        for (r <- Seq(full, editor, authOff))
            assert(decide(r, deleted, null) == Right(deleted), s"$r")
        assert(EntityVersionAPIController.restoreGuardDecision(null, deleted, null, () => fail("no reads"), versionNotFound("gone")) == Right(deleted))
        assert(PipelineAPIController.recreateRefusal(null, deleted).isEmpty)
        for (r <- Seq(full, editor, authOff)) assert(PipelineAPIController.recreateRefusal(r, deleted).isEmpty)
    }

    test("POST /pipeline is unchanged: its save guard keeps the caller as owner and needs no update") {
        // A key holding scoped create and nothing else creates a new pipeline in its catalog.
        assert(saveDecision(key("creator", Seq("pipeline:create:catalog=mine", "pipeline:read:catalog=mine")), deleted).isRight)
        // An owner=self key creates a new pipeline whatever owner the body names (the save stamps the caller).
        assert(saveDecision(own, pg("new", "mine", owner = "other")).isRight)
        // keyTargetRefusal's default owner for a new name is still the caller.
        assert(PipelineAPIController.keyTargetRefusal(own, pg("new", "mine", owner = "other"), null).isEmpty)
    }
}
