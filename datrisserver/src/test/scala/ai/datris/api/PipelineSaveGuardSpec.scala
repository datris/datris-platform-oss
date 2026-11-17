package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.APIKeyValidator
import ai.datris.util.InMemoryUserDb.grants
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

import scala.collection.JavaConverters._

/** POST /pipeline for callers who cannot see or write every pipeline: the
  * save is decided from ONE stored-config read (scope check, previous-name
  * check, catalog rules), and re-checked under the catalog lock before the
  * write, so a concurrent rename cannot turn a refused save into a create. */
class PipelineSaveGuardSpec extends AnyFunSuite {

    private def req(rk: ResolvedKey): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("POST")
        when(r.getRequestURI).thenReturn("/api/v1/pipeline")
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def session(g: java.util.Map[String, String]): HttpServletRequest =
        req(APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = g)))

    private val bobMine = session(grants("mine" -> "edit"))
    private val mineKey = req(
        ResolvedKey(
            None,
            "mine-builder",
            Capability.parseList(Seq("pipeline:read:catalog=mine", "pipeline:create:catalog=mine", "pipeline:update:catalog=mine")),
            isLegacyFullAccess = false
        )
    )
    private val full = req(ResolvedKey(None, "full", Nil, isLegacyFullAccess = true))
    private val editor = req(APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null)))
    private val authOff = req(null)

    private def pg(name: String, catalog: String, prev: Seq[String] = null, owner: String = "someone"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = owner,
            destination = Destination(database = Database(dbName = "datris", schema = "public", table = name + "_t", usePostgres = true)),
            previousNames = if (prev == null) null else new java.util.ArrayList[String](prev.asJava)
        )

    private val hiddenLive = pg("mkt_old", "secret")
    private val hiddenRenamed = pg("mkt_new", "secret", prev = Seq("mkt_old"))

    /** A readAll that counts its calls. */
    private class Reader(configs: Seq[PipelineConfig]) {
        var calls = 0
        val fn: () => Seq[PipelineConfig] = () => { calls += 1; configs }
    }

    private def same(a: Either[ResponseEntity[String], PipelineConfig], b: Option[ResponseEntity[String]]): Unit = {
        assert(a.isLeft && b.isDefined, s"$a / $b")
        assert(a.left.get.getStatusCode == b.get.getStatusCode && a.left.get.getBody == b.get.getBody, s"${a.left.get} vs ${b.get}")
    }

    private def decide(r: HttpServletRequest, c: PipelineConfig, stored: PipelineConfig, reader: Reader) =
        PipelineAPIController.saveGuardDecision(r, c, stored, reader.fn)

    test("saveGuardNeeded: scoped sessions and scoped keys only") {
        assert(PipelineAPIController.saveGuardNeeded(bobMine))
        assert(PipelineAPIController.saveGuardNeeded(mineKey))
        assert(!PipelineAPIController.saveGuardNeeded(full))
        assert(!PipelineAPIController.saveGuardNeeded(editor))
        assert(!PipelineAPIController.saveGuardNeeded(authOff))
    }

    test("stored non-null and hidden: the hidden-live-pipeline refusal, all configs never read") {
        val candidate = pg("mkt_old", "Sales")
        val reader = new Reader(Nil)
        val r = decide(bobMine, candidate, hiddenLive, reader)
        same(r, PipelineAPIController.catalogForSave(bobMine, candidate, hiddenLive).left.toOption)
        assert(r.left.get.getStatusCode.value == 404)
        val keyed = decide(mineKey, pg("mkt_old", "mine", owner = "mine-builder"), hiddenLive, reader)
        same(keyed, PipelineAPIController.overwriteRefusal(mineKey, hiddenLive, PipelineAPIController.SaveOverwriteActions))
        assert(keyed.left.get.getStatusCode.value == 403)
        assert(reader.calls == 0)
    }

    test("stored null and the name is an earlier name of a hidden pipeline: the same refusal") {
        val candidate = pg("mkt_old", "Sales")
        val reader = new Reader(Seq(hiddenRenamed))
        val r = decide(bobMine, candidate, null, reader)
        same(r, PipelineAPIController.catalogForSave(bobMine, candidate, hiddenLive).left.toOption)
        val keyed = decide(mineKey, pg("mkt_old", "mine", owner = "mine-builder"), null, reader)
        same(keyed, PipelineAPIController.overwriteRefusal(mineKey, hiddenLive, PipelineAPIController.SaveOverwriteActions))
        assert(reader.calls == 2)
    }

    test("stored null and a free name: proceed with the catalog rules applied to the same null") {
        val candidate = pg("fresh", null)
        val reader = new Reader(Seq(hiddenRenamed))
        val r = decide(bobMine, candidate, null, reader)
        assert(r == PipelineAPIController.catalogForSave(bobMine, candidate, null))
        assert(r.isRight && r.right.get.catalog == "mine", s"$r")
        val k = decide(mineKey, pg("fresh", "mine"), null, reader)
        assert(k.isRight)
    }

    test("stored null and the configs cannot be read: 500, nothing saved") {
        val r = PipelineAPIController.saveGuardDecision(bobMine, pg("fresh", "mine"), null, () => throw new RuntimeException("down"))
        assert(r.isLeft && r.left.get.getStatusCode.value == 500 && r.left.get.getBody.contains("nothing was saved"))
    }

    test("the decision uses the stored value it is given: a hidden row deleted since (rename finished) still refuses") {
        // The race from review: bob's single read saw hidden mkt_old; by now the
        // rename has deleted the row and the configs show only mkt_new listing
        // the old name. The decision is taken from the value read once, reads no
        // config of that name itself, and does not consult the configs at all.
        val candidate = pg("mkt_old", "Sales")
        val reader = new Reader(Seq(hiddenRenamed))
        val r = decide(bobMine, candidate, hiddenLive, reader)
        assert(r.isLeft && r.left.get.getStatusCode.value == 404)
        assert(reader.calls == 0, "configs read although the stored value decided")
    }

    test("unrestricted callers: config unchanged, nothing read") {
        val candidate = pg("mkt_old", null)
        val reader = new Reader(Seq(hiddenRenamed))
        Seq(full, editor, authOff).foreach { r =>
            val d = decide(r, candidate, hiddenLive, reader)
            assert(d.isRight && (d.right.get eq candidate))
        }
        assert(reader.calls == 0)
    }

    // ------------------------------------------------ re-check under the lock

    test("saveRecheck: a hidden pipeline renamed into the name after the decision is refused, not overwritten") {
        val candidate = pg("mkt_old", "mine")
        val r = PipelineAPIController.saveRecheck(bobMine, candidate, null, hiddenLive, new Reader(Nil).fn)
        assert(r.exists(_.getStatusCode.value == 404), s"$r")
        val keyed = PipelineAPIController.saveRecheck(mineKey, pg("mkt_old", "mine", owner = "mine-builder"), null, hiddenLive, new Reader(Nil).fn)
        assert(keyed.exists(_.getStatusCode.value == 403), s"$keyed")
    }

    test("saveRecheck: a hidden pipeline renamed away from the name after the decision is refused as before") {
        val candidate = pg("mkt_old", "mine")
        val r = PipelineAPIController.saveRecheck(bobMine, candidate, null, null, new Reader(Seq(hiddenRenamed)).fn)
        assert(r.exists(_.getStatusCode.value == 404), s"$r")
    }

    test("saveRecheck: unchanged stored state writes; a visible change is a 409, nothing saved") {
        val mine = pg("p", "mine", prev = Seq("p0"))
        assert(PipelineAPIController.saveRecheck(bobMine, pg("p", "mine"), mine, mine.copy(tags = null), new Reader(Nil).fn).isEmpty)
        assert(PipelineAPIController.saveRecheck(bobMine, pg("p", "mine"), null, null, new Reader(Nil).fn).isEmpty)
        val gone = PipelineAPIController.saveRecheck(bobMine, pg("p", "mine"), mine, null, new Reader(Nil).fn)
        assert(gone.exists(_.getStatusCode.value == 409), s"$gone")
        assert(gone.get.getBody.contains("nothing was saved"))
        val appeared = PipelineAPIController.saveRecheck(bobMine, pg("p", "mine"), null, mine, new Reader(Nil).fn)
        assert(appeared.exists(_.getStatusCode.value == 409))
        val renamedInto = PipelineAPIController.saveRecheck(bobMine, pg("p", "mine"), pg("p", "mine"), mine, new Reader(Nil).fn)
        assert(renamedInto.exists(_.getStatusCode.value == 409), "earlier names changed")
    }
}
