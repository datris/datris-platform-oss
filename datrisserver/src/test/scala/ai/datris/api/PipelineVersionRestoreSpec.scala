package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, SecretNames}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.{Gson, JsonParser}
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

import scala.collection.JavaConverters._

/** Story: pipeline version restore is checked like a save of that version
  * (plans/stories/version-restore-validates.md).
  *
  * Pure part: the restore candidate (D2), the guard decision taken from ONE
  * live read (`restoreGuardDecision`) and its re-check under the catalog lock
  * (`restoreRecheck`).
  *
  * Controller part: `EntityVersionAPIController.restorePipelineSnapshot` is
  * called directly, no Spring. API keys are off in the installed environment,
  * and every snapshot here fails `PipelineValidatorUtil.validate`, which runs
  * before any Postgres probe, lock or config-store write; the environment has
  * no stores, so a 400 proves nothing was written.
  *
  * Tests named "D2 catalog: ..." pin that a restore keeps the LIVE pipeline's
  * catalog (decision D2, catalog part, awaiting the owner's confirmation).
  * They are kept apart so they can be dropped alone if the owner refuses it. */
class PipelineVersionRestoreSpec extends AnyFunSuite with BeforeAndAfterEach {

    // ------------------------------------------------------------ environment

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

    private def session(g: java.util.Map[String, String]): HttpServletRequest =
        req(APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = g)))

    private def bobMine = session(grants("mine" -> "edit"))
    private def mineKey = req(
        ResolvedKey(
            None,
            "mine-builder",
            Capability.parseList(Seq("pipeline:read:catalog=mine", "pipeline:create:catalog=mine", "pipeline:update:catalog=mine")),
            isLegacyFullAccess = false
        )
    )
    private def full = req(ResolvedKey(None, "full", Nil, isLegacyFullAccess = true))
    private def editor = req(APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null)))
    private def authOff = req(null)

    // --------------------------------------------------------------- fixtures

    private def pg(name: String, catalog: String, prev: Seq[String] = null, owner: String = "someone", table: String = null): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = owner,
            destination = Destination(database =
                Database(dbName = "datris", schema = "public", table = Option(table).getOrElse(name + "_t"), usePostgres = true)
            ),
            previousNames = if (prev == null) null else new java.util.ArrayList[String](prev.asJava)
        )

    private def list(xs: String*): java.util.List[String] = new java.util.ArrayList[String](xs.asJava)

    private val hiddenLive = pg("mkt_old", "secret")
    private val hiddenRenamed = pg("mkt_new", "secret", prev = Seq("mkt_old"))

    /** The version 404 a hidden pipeline's history answers (the restore's
      * `notFound("pipeline", name, version)`). */
    private def versionNotFound(name: String, version: Int = 1): ResponseEntity[String] =
        ResponseEntity.status(404).body[String]("{\"error\": \"No version " + version + " for pipeline '" + name + "'\"}")

    /** A readAll that counts its calls. */
    private class Reader(configs: Seq[PipelineConfig]) {
        var calls = 0
        val fn: () => Seq[PipelineConfig] = () => { calls += 1; configs }
    }

    private def sameResponse(a: ResponseEntity[String], b: ResponseEntity[String]): Unit =
        assert(a.getStatusCode == b.getStatusCode && a.getBody == b.getBody, s"$a vs $b")

    private def refused(d: Either[ResponseEntity[String], PipelineConfig]): ResponseEntity[String] = {
        assert(d.isLeft, s"expected a refusal, got $d")
        d.left.get
    }

    private def decide(r: HttpServletRequest, candidate: PipelineConfig, stored: PipelineConfig, reader: Reader) =
        EntityVersionAPIController.restoreGuardDecision(r, candidate, stored, reader.fn, versionNotFound(candidate.name))

    private def recheck(r: HttpServletRequest, candidate: PipelineConfig, decided: PipelineConfig, fresh: PipelineConfig, reader: Reader) =
        EntityVersionAPIController.restoreRecheck(r, candidate, decided, fresh, reader.fn, versionNotFound(candidate.name))

    // ------------------------------------------------------------- candidate

    // Acceptance 1 (name, owner, earlier names; the catalog part is the D2
    // catalog test below).
    test("the candidate keeps the live name and owner (and earlier names) and takes the rest from the snapshot") {
        val snap = pg("inner_other_name", "Old", owner = "other", table = "snap_t").copy(
            tags = list("from-snapshot"),
            previousNames = list("snap_prev")
        )
        val live = pg("p", "mine", prev = Seq("p0", "p1"), owner = "live-owner", table = "live_t").copy(tags = list("from-live"))
        val c = EntityVersionAPIController.pipelineRestoreCandidate("p", snap, live)
        assert(c.name == "p")
        assert(c.createdByKeyLabel == "live-owner")
        assert(c.previousNames == list("p0", "p1"))
        // Everything else is the snapshot's definition.
        assert(c.destination == snap.destination)
        assert(c.tags == snap.tags)
        assert(
            c.copy(catalog = snap.catalog) ==
                snap.copy(name = "p", createdByKeyLabel = "live-owner", previousNames = list("p0", "p1")),
            s"$c"
        )
    }

    // Acceptance 1, D2 catalog part (owner to confirm).
    test("D2 catalog: the candidate of a live pipeline keeps the live catalog, not the snapshot's") {
        val snap = pg("inner_other_name", "Old", owner = "other")
        val live = pg("p", "mine", owner = "live-owner")
        val c = EntityVersionAPIController.pipelineRestoreCandidate("p", snap, live)
        assert(c.catalog == "mine", s"catalog ${c.catalog}")
        assert(c == snap.copy(name = "p", catalog = "mine", createdByKeyLabel = "live-owner", previousNames = null), s"$c")
    }

    // Acceptance 2.
    test("the candidate of a deleted pipeline is the snapshot under the requested name") {
        val snap = pg("inner_other_name", "Old", owner = "other").copy(tags = list("t"))
        val c = EntityVersionAPIController.pipelineRestoreCandidate("p", snap, null)
        assert(c.name == "p")
        assert(c.catalog == "Old")
        assert(c.createdByKeyLabel == "other")
        // previousNames is server-maintained: the save core carries the
        // stored value (none for a deleted pipeline), so it is not pinned here.
        assert(c.copy(previousNames = snap.previousNames) == snap.copy(name = "p"), s"$c")
    }

    // --------------------------------------------------------- guard decision

    // Acceptance 3.
    test("a restricted session gets the version 404 when the live pipeline is missing or outside its grants, and no configs are read") {
        val reader = new Reader(Seq(hiddenRenamed))
        val missing = decide(bobMine, pg("gone", "mine"), null, reader)
        sameResponse(refused(missing), versionNotFound("gone"))
        val outside = decide(bobMine, pg("mkt_old", "secret"), hiddenLive, reader)
        sameResponse(refused(outside), versionNotFound("mkt_old"))
        assert(reader.calls == 0, "configs read for a restricted session's hidden history")
        // Inside its grants: proceeds.
        val mine = pg("p", "mine")
        assert(decide(bobMine, mine, mine, reader).isRight)
        assert(reader.calls == 0)
    }

    // Acceptance 4 (first half).
    test("a scoped key outside the live pipeline's scope gets the 403 pipelineRestoreScopeRefusal gives") {
        val candidate = pg("mkt_old", "secret")
        val reader = new Reader(Nil)
        val r = refused(decide(mineKey, candidate, hiddenLive, reader))
        val expected = EntityVersionAPIController.pipelineRestoreScopeRefusal(mineKey, candidate, hiddenLive)
        assert(expected.isDefined)
        sameResponse(r, expected.get)
        assert(r.getStatusCode.value == 403)
        assert(reader.calls == 0)
        // In scope: proceeds.
        val own = pg("p", "mine", owner = "mine-builder")
        assert(decide(mineKey, own, own, reader).isRight)
    }

    // Acceptance 4 (second half).
    test("for a deleted pipeline the snapshot's scope decides") {
        val outside = pg("gone", "secret")
        val reader = new Reader(Nil)
        val r = refused(decide(mineKey, outside, null, reader))
        sameResponse(r, EntityVersionAPIController.pipelineRestoreScopeRefusal(mineKey, outside, null).get)
        assert(r.getStatusCode.value == 403)
        assert(reader.calls == 0, "the scope refusal comes before the previous-name check")
        val inside = pg("gone", "mine", owner = "mine-builder")
        val ok = decide(mineKey, inside, null, new Reader(Nil))
        assert(ok.isRight, s"$ok")
    }

    // Acceptance 5.
    test("a deleted name a hidden pipeline lists in previousNames: 404 for a session, 403 for a key; unreadable configs are 500 'nothing was restored'") {
        val sessionCandidate = pg("mkt_old", "mine")
        val s = refused(decide(bobMine, sessionCandidate, null, new Reader(Seq(hiddenRenamed))))
        sameResponse(s, versionNotFound("mkt_old"))
        assert(s.getStatusCode.value == 404)

        val keyCandidate = pg("mkt_old", "mine", owner = "mine-builder")
        val reader = new Reader(Seq(hiddenRenamed))
        val k = refused(decide(mineKey, keyCandidate, null, reader))
        val expected = EntityVersionAPIController.pipelineRestoreAliasRefusal(mineKey, keyCandidate, Seq(hiddenRenamed), versionNotFound("mkt_old"))
        assert(expected.isDefined)
        sameResponse(k, expected.get)
        assert(k.getStatusCode.value == 403)
        assert(reader.calls == 1)

        val down = EntityVersionAPIController.restoreGuardDecision(
            mineKey,
            keyCandidate,
            null,
            () => throw new RuntimeException("down"),
            versionNotFound("mkt_old")
        )
        val d = refused(down)
        assert(d.getStatusCode.value == 500, s"$d")
        assert(d.getBody.contains("nothing was restored"), d.getBody)
        sameResponse(d, PipelineAPIController.aliasCheckUnavailable("restored"))
    }

    // Acceptance 6 (D2 catalog: owner to confirm).
    test("D2 catalog: a restricted session restoring a version from a catalog outside its grants proceeds in the live catalog") {
        val snap = pg("p", "Old")
        val live = pg("p", "mine")
        val candidate = EntityVersionAPIController.pipelineRestoreCandidate("p", snap, live)
        val d = decide(bobMine, candidate, live, new Reader(Nil))
        assert(d.isRight, s"$d")
        assert(d.right.get.catalog == "mine", s"${d.right.get.catalog}")
    }

    // Acceptance 7.
    test("full keys, unrestricted sessions and auth off proceed and read no configs") {
        val reader = new Reader(Seq(hiddenRenamed))
        val candidate = pg("mkt_old", "secret")
        Seq(full, editor, authOff).foreach { r =>
            Seq(hiddenLive, null).foreach { stored =>
                val d = decide(r, candidate, stored, reader)
                assert(d.isRight, s"$d")
                assert(d.right.get == candidate)
            }
        }
        assert(reader.calls == 0)
    }

    // --------------------------------------------------------------- re-check

    // Acceptance 8.
    test("restoreRecheck: a refusal on the fresh config wins") {
        val mine = pg("p", "mine")
        val movedAway = pg("p", "secret")
        val s = recheck(bobMine, mine, mine, movedAway, new Reader(Nil))
        assert(s.isDefined)
        sameResponse(s.get, refused(decide(bobMine, mine, movedAway, new Reader(Nil))))
        assert(s.get.getStatusCode.value == 404, "the fresh refusal, not the 409")

        val own = pg("p", "mine", owner = "mine-builder")
        val k = recheck(mineKey, own, own, movedAway, new Reader(Nil))
        assert(k.isDefined)
        sameResponse(k.get, refused(decide(mineKey, own, movedAway, new Reader(Nil))))
        assert(k.get.getStatusCode.value == 403)

        // A hidden pipeline renamed into a deleted name after the decision.
        val keyCandidate = pg("mkt_old", "mine", owner = "mine-builder")
        val renamedInto = recheck(mineKey, keyCandidate, null, null, new Reader(Seq(hiddenRenamed)))
        assert(renamedInto.exists(_.getStatusCode.value == 403), s"$renamedInto")
    }

    // Acceptance 9.
    test("restoreRecheck: appeared, disappeared, moved catalog, changed owner or changed previousNames is 409 'nothing was restored', for a full key too") {
        val stored = pg("p", "mine", prev = Seq("p0"), owner = "mine-builder")
        val candidate = stored
        val conflict = PipelineAPIController.restoreConflict("p")
        assert(conflict.getStatusCode.value == 409)
        val err = JsonParser.parseString(conflict.getBody).getAsJsonObject.get("error").getAsString
        assert(err == "Pipeline 'p' changed while it was being restored; nothing was restored. Try again.", err)

        val cases: Seq[(String, PipelineConfig, PipelineConfig)] = Seq(
            ("appeared", null, stored),
            ("disappeared", stored, null),
            ("moved catalog", stored, stored.copy(catalog = "other")),
            ("changed owner", stored, stored.copy(createdByKeyLabel = "someone-else")),
            ("changed previousNames", stored, stored.copy(previousNames = list("p0", "q")))
        )
        cases.foreach { case (what, decided, fresh) =>
            val r = recheck(full, candidate, decided, fresh, new Reader(Nil))
            assert(r.isDefined, what)
            sameResponse(r.get, conflict)
            assert(r.get.getBody.contains("nothing was restored"), what)
        }
        // A scoped key whose fresh config is still in scope: the same 409.
        val k = recheck(mineKey, candidate, stored, stored.copy(previousNames = list("p0", "q")), new Reader(Nil))
        assert(k.isDefined)
        sameResponse(k.get, conflict)
    }

    // Acceptance 10.
    test("restoreRecheck: an unchanged stored config writes") {
        val stored = pg("p", "mine", prev = Seq("p0"), owner = "mine-builder")
        Seq(full, editor, authOff, mineKey, bobMine).foreach { r =>
            assert(recheck(r, stored, stored, stored.copy(tags = list("x")), new Reader(Nil)).isEmpty)
        }
        Seq(full, editor, authOff).foreach { r =>
            assert(recheck(r, pg("gone", "mine"), null, null, new Reader(Nil)).isEmpty)
        }
    }

    // ------------------------------------------------------ controller level

    private val gson = new Gson()
    private val postgres = """"database":{"dbName":"datris","schema":"public","table":"vr_t","usePostgres":true}"""
    private val csvSource = """"source":{"fileAttributes":{"csvAttributes":{"header":true}},"schemaProperties":{"fields":[{"name":"id","type":"string"}]}}"""

    private def config(json: String): PipelineConfig = gson.fromJson(json, classOf[PipelineConfig])

    private def restore(name: String, snap: PipelineConfig, live: PipelineConfig, r: HttpServletRequest): (Int, String) = {
        val out = new EntityVersionAPIController().restorePipelineSnapshot(name, 1, snap, live, r)
        (out.getStatusCode.value, out.getBody)
    }

    private def assert400(status: Int, body: String, expected: String): Unit = {
        assert(status == 400, s"status $status, body $body")
        val o = JsonParser.parseString(body).getAsJsonObject
        assert(o.has("error"), body)
        assert(o.get("error").getAsString == expected, body)
    }

    private val reservedProtect =
        s"""{"name":"vr_probe","source":{"schemaProperties":{"fields":[{"name":"mrn","type":"string","protect":{"method":"fpe"}}]},"fileAttributes":{"csvAttributes":{"header":true}}},"destination":{$postgres}}"""
    private val badTable =
        s"""{"name":"vr_probe",$csvSource,"destination":{"database":{"dbName":"datris","schema":"public","table":"x; drop table y","usePostgres":true}}}"""
    private val managedSecret =
        s"""{"name":"vr_probe",$csvSource,"destination":{"database":{"dbName":"datris","schema":"public","table":"vr_t","usePostgres":true,"credentialsSecret":"field-protection"}}}"""
    private val twoEngines =
        s"""{"name":"vr_probe",$csvSource,"destination":{"database":{"dbName":"datris","schema":"public","table":"vr_t","usePostgres":true,"useMongoDB":true}}}"""

    test("a version with a reserved protect method is refused 400 with the validator's message") {
        val (status, body) = restore("vr_probe", config(reservedProtect), null, authOff)
        assert400(status, body, "Field 'mrn': protect.method 'fpe' is not yet supported")
    }

    test("a version whose table name is not a plain identifier is refused 400") {
        // A live pipeline exists: the candidate is built from it (D2), and the
        // snapshot's table still fails validation.
        val live = config(s"""{"name":"vr_probe","catalog":"VrOne",$csvSource,"destination":{$postgres}}""")
        val (status, body) = restore("vr_probe", config(badTable), live, authOff)
        assert400(status, body, "'destination.database.table' value 'x; drop table y' is invalid. Valid characters are a-z, A-Z, 0-9 and _")
    }

    test("a version naming a server-managed secret is refused 400") {
        val (status, body) = restore("vr_probe", config(managedSecret), null, authOff)
        assert400(status, body, SecretNames.serverManagedMessage("field-protection"))
    }

    test("a version with two engine flags is refused 400") {
        val (status, body) = restore("vr_probe", config(twoEngines), null, authOff)
        assert400(
            status,
            body,
            "For the 'destination.database' section, you must select exactly one of usePostgres, useMongoDB, useSnowflake, useDatabricks, or useSqlServer — multiple are currently set"
        )
    }

    test("the refusal is the body POST /pipeline gives for the same config") {
        Seq(reservedProtect, badTable, managedSecret, twoEngines).foreach { json =>
            val restored = new EntityVersionAPIController().restorePipelineSnapshot("vr_probe", 1, config(json), null, authOff)
            val saved = new PipelineAPIController().putPipeline(null, null, config(json), authOff)
            assert(saved.getStatusCode.value == 400, s"save: ${saved.getBody}")
            assert(restored.getStatusCode == saved.getStatusCode, s"${restored.getBody} vs ${saved.getBody}")
            assert(restored.getBody == saved.getBody, s"${restored.getBody} vs ${saved.getBody}")
        }
    }

    test("validation applies with no key and with a full key") {
        Seq(reservedProtect, badTable, managedSecret, twoEngines).foreach { json =>
            val noKey = restore("vr_probe", config(json), null, authOff)
            val fullKey = restore("vr_probe", config(json), null, full)
            assert(noKey._1 == 400, s"no key: $noKey")
            assert(fullKey == noKey, s"full key $fullKey vs no key $noKey")
        }
    }
}
