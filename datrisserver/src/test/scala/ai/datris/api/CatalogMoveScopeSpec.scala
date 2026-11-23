package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditLog
import ai.datris.auth.{CapabilityDeniedException, ScopeDenialFixtures, ScopeDenialWording}
import ai.datris.incident.RecoveryKey
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, CatalogEnableJob, CatalogEnableJobSpec, CatalogOps, TapDependencies}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletRequest
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConverters._

/** Story: Catalog rename, detach and enable-job cancel hold a scoped key to
  * where its items end up and to what it can read
  * (plans/stories/catalog-ops-scope-edges.md). Owner decisions: D1 (a), D2
  * as recommended (all or nothing for rename and detach; cascade delete keeps
  * its per-member result).
  *
  * `PUT /catalog/{name}` and `DELETE /catalog/{name}` load and write every
  * member through Mongo under the catalog lock, so this spec pins the pure
  * pre-flight the handlers must run under the lock before any write (the same
  * style as `enableOrderedRefusal`), plus the handlers' wiring of it:
  * {{{
  * object CatalogAPIController {
  *   /** assertMemberWrite on the source as today, then, for an API key whose
  *     * scope can refuse create or update (CapabilityCheck.keyScopeApplies),
  *     * the item as it would be stored, (toCatalog, owner), through the
  *     * stored-item rule (CallerVisibility.storedWriteDenial with the write
  *     * actions, then read). toCatalog null for detach. Throws
  *     * CapabilityDeniedException. */
  *   def assertMemberMove(request: HttpServletRequest, resource: String, action: String,
  *                        fromCatalog: String, toCatalog: String, owner: String): Unit
  *
  *   /** Rename (action "update", toCatalog = the new name) and detach
  *     * (action "delete", toCatalog = null), before anything is written:
  *     * every member tap then pipeline through assertMemberMove; for a
  *     * placeholder-only catalog every placeholder (today's
  *     * placeholderScopeDenial plus the same destination check). Some(403)
  *     * on the first refusal, whose message names that member only when the
  *     * caller may read it (else the shared scope-denial text alone); None
  *     * when the whole move is allowed. */
  *   def moveRefusal(request: HttpServletRequest, action: String, fromCatalog: String, toCatalog: String,
  *                   members: CatalogOps.Members, placeholders: Seq[TapConfig]): Option[ResponseEntity[String]]
  * }
  * }}}
  * Restricted-session and unchanged-caller answers that come before the lock
  * are driven through the real `renameCatalog` / `deleteCatalog` entry
  * points (a running enable job makes the handler answer 409 before any
  * Mongo read, which shows the caller got past every earlier guard). */
class CatalogMoveScopeSpec extends AnyFunSuite with BeforeAndAfterAll {

    private var savedEnv: DatrisEnvironment = _

    override def beforeAll(): Unit = {
        savedEnv = DatrisEnvironment.values
        DatrisEnvironment.values = CatalogEnableJobSpec.env
    }

    override def afterAll(): Unit = DatrisEnvironment.values = savedEnv

    // ------------------------------------------------------------ fixtures

    private def key(label: String, caps: String*): ResolvedKey = ScopeDenialFixtures.key(label, caps: _*)
    private def req(rk: ResolvedKey, method: String = "PUT", uri: String = "/api/v1/catalog/Sales"): HttpServletRequest =
        ScopeDenialFixtures.request(rk, method, uri)._1
    private def session(u: User): ResolvedKey = APIKeyValidator.resolveFromSession(u)
    private def restricted(g: (String, String)*): ResolvedKey =
        session(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants(g: _*)))
    private val admin: ResolvedKey = session(User("root", "h", User.RoleAdmin, null, null, null))
    private val editor: ResolvedKey = session(User("ed", "h", User.RoleEditor, null, null, null))
    private val legacyFull: ResolvedKey = ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true)
    private val starKey: ResolvedKey = key("star", "*:*")

    private def tap(name: String, catalog: String, owner: String): TapConfig =
        TapConfig(name = name, description = "d", targetPipeline = null, catalog = catalog, createdByKeyLabel = owner)
    private def pg(name: String, catalog: String, owner: String): PipelineConfig =
        PipelineConfig(name = name, catalog = catalog, createdByKeyLabel = owner)
    private def placeholder(catalog: String, owner: String): TapConfig =
        TapConfig(
            name = CatalogOps.Placeholder.name(catalog),
            description = "Catalog placeholder",
            targetPipeline = null,
            catalog = catalog,
            createdByKeyLabel = owner
        )

    /** A Sales catalog of other callers' items: tap `t_orders`, pipeline `p_orders`. */
    private val salesMembers = CatalogOps.Members(Seq(tap("t_orders", "Sales", "someone")), Seq(pg("p_orders", "Sales", "someone")))

    // Keys limited to the source catalog (the Acceptance keys, and the same
    // keys with read in Sales so the per-member read half does not refuse first).
    private val renameLiteralCaps = Seq("pipeline:update:catalog=Sales", "tap:update:catalog=Sales")
    private val renameSalesCaps = Seq(
        "pipeline:create:catalog=Sales",
        "pipeline:update:catalog=Sales",
        "pipeline:read:catalog=Sales",
        "tap:create:catalog=Sales",
        "tap:update:catalog=Sales",
        "tap:read:catalog=Sales"
    )
    private val detachLiteralCaps = Seq("pipeline:delete:catalog=Sales", "tap:delete:catalog=Sales")
    private val detachSalesCaps = detachLiteralCaps ++ Seq("pipeline:read:catalog=Sales", "tap:read:catalog=Sales")
    // Covers both the source and the destination name on create, update and read.
    private val bothCaps = for {
        res <- Seq("pipeline", "tap"); action <- Seq("create", "update", "read"); cat <- Seq("Sales", "Sales2")
    } yield res + ":" + action + ":catalog=" + cat
    // Limited to what it created.
    private val ownCaps = Seq(
        "pipeline:create",
        "tap:create",
        "pipeline:update:owner=self",
        "tap:update:owner=self",
        "pipeline:delete:owner=self",
        "tap:delete:owner=self",
        "pipeline:read:owner=self",
        "tap:read:owner=self"
    )
    // Unscoped capabilities for every action the catalog routes use.
    private val unscopedCaps = Seq("pipeline:update", "pipeline:delete", "pipeline:read", "tap:update", "tap:delete", "tap:read")

    private def message(r: ResponseEntity[String]): String = JsonParser.parseString(r.getBody).getAsJsonObject.get("message").getAsString

    private def assert403(r: Option[ResponseEntity[String]], what: String): ResponseEntity[String] = {
        assert(r.isDefined, what + ": expected a 403 refusal, got none (the move would proceed)")
        assert(r.get.getStatusCode.value == 403, what + ": " + r.get.getStatusCode + " " + r.get.getBody)
        val o = JsonParser.parseString(r.get.getBody).getAsJsonObject
        assert(o.keySet.asScala.toSet == ScopeDenialWording.BodyKeys, r.get.getBody)
        assert(o.get("error").getAsString == ScopeDenialWording.Error, r.get.getBody)
        assert(o.get("errorKind").getAsString == ScopeDenialWording.ErrorKind, r.get.getBody)
        r.get
    }

    /** The message is the shared scope-denial text for `label` on `resource`
      * and one of `actions`, optionally followed by text naming a member. */
    private def assertSharedPrefix(msg: String, label: String, resource: String, caps: Seq[String], actions: Seq[String]): Unit =
        assert(actions.exists(a => msg.startsWith(ScopeDenialWording.message(label, resource, a, caps))), msg)

    /** Today's per-member rule on every member, as the rename/detach loops apply it. */
    private def todayEveryMemberPasses(r: HttpServletRequest, action: String, from: String, members: CatalogOps.Members): Boolean =
        try {
            members.taps.foreach(t => CatalogAPIController.assertMemberWrite(r, "tap", action, from, t.createdByKeyLabel))
            members.pipelines.foreach(p => CatalogAPIController.assertMemberWrite(r, "pipeline", action, from, p.createdByKeyLabel))
            true
        } catch { case _: CapabilityDeniedException => false }

    // ------------------------------------------------------------ Acceptance bullet 1: rename

    test("bullet 1: a key limited to the source catalog cannot rename it out of its scope; refused before any member, placeholder or version is written") {
        for ((label, caps) <- Seq("sales-mover" -> renameLiteralCaps, "sales-editor" -> renameSalesCaps)) {
            val r = req(key(label, caps: _*))
            val refused = assert403(CatalogAPIController.moveRefusal(r, "update", "Sales", "Sales2", salesMembers, Nil), label)
            ScopeDenialWording.assertHides(refused.getBody, Seq("someone"))
            assert(AuditLog.scopeDenials(r).nonEmpty, label + ": the refusal is noted for the audit log")
        }
    }

    test("bullet 1: a source-only key may not move even one item out (single-member catalog), matching the single-item move rule") {
        val k = key("sales-editor", renameSalesCaps: _*)
        val one = CatalogOps.Members(Seq(tap("t_orders", "Sales", "sales-editor")), Nil)
        assert403(CatalogAPIController.moveRefusal(req(k), "update", "Sales", "Sales2", one, Nil), "own tap, out of scope")
        // the same answer the save guard gives the key for the one-item move
        val stored = tap("t_orders", "Sales", "sales-editor")
        val single = TapAPIController.saveGuardDecision(req(k, "POST", "/api/v1/tap"), stored.copy(catalog = "Sales2"), stored)
        assert(single.isLeft && single.left.get.getStatusCode.value == 403, single)
    }

    test("bullet 1: assertMemberMove refuses the destination for a source-only key and keeps the source rule as assertMemberWrite") {
        val k = key("sales-editor", renameSalesCaps: _*)
        for (res <- Seq("tap", "pipeline")) {
            val e = intercept[CapabilityDeniedException](CatalogAPIController.assertMemberMove(req(k), res, "update", "Sales", "Sales2", "someone"))
            assertSharedPrefix(e.getMessage, "sales-editor", res, renameSalesCaps, Seq("create", "update", "read"))
            ScopeDenialWording.assertHides(e.getMessage, Seq("someone"))
            // inside the scope: passes
            CatalogAPIController.assertMemberMove(req(k), res, "update", "Sales", "Sales", "someone")
        }
        // source refusal is unchanged (the read half of assertMemberWrite)
        val lit = key("sales-mover", renameLiteralCaps: _*)
        val src = intercept[CapabilityDeniedException](CatalogAPIController.assertMemberMove(req(lit), "tap", "update", "Sales", "Sales", "someone"))
        assert(src.getMessage == ScopeDenialWording.message("sales-mover", "tap", "read", renameLiteralCaps), src.getMessage)
    }

    test("bullet 1: a key whose scope covers the source and the destination on create, update and read renames as today") {
        val r = req(key("two-cats", bothCaps: _*))
        assert(todayEveryMemberPasses(r, "update", "Sales", salesMembers))
        assert(CatalogAPIController.moveRefusal(r, "update", "Sales", "Sales2", salesMembers, Nil).isEmpty)
        // placeholder-only catalog too
        assert(CatalogAPIController.moveRefusal(r, "update", "Sales", "Sales2", CatalogOps.Members(Nil, Nil), Seq(placeholder("Sales", "someone"))).isEmpty)
    }

    test("bullet 1: a placeholder-only catalog cannot be renamed out of a source-only key's scope") {
        val k = key("sales-editor", renameSalesCaps: _*)
        val empty = CatalogOps.Members(Nil, Nil)
        for (owner <- Seq("someone", "sales-editor")) {
            val refused = assert403(CatalogAPIController.moveRefusal(req(k), "update", "Sales", "Sales2", empty, Seq(placeholder("Sales", owner))), owner)
            assertSharedPrefix(message(refused), "sales-editor", "tap", renameSalesCaps, Seq("create", "update", "read"))
        }
    }

    test("bullet 1: an owner=self key renaming a catalog that holds only its own items renames as today") {
        val r = req(key("own", ownCaps: _*))
        val mine = CatalogOps.Members(Seq(tap("t_mine", "Sales", "own")), Seq(pg("p_mine", "Sales", "own")))
        assert(todayEveryMemberPasses(r, "update", "Sales", mine))
        assert(CatalogAPIController.moveRefusal(r, "update", "Sales", "Sales2", mine, Nil).isEmpty)
        assert(CatalogAPIController.moveRefusal(r, "update", "Sales", "Sales2", CatalogOps.Members(Nil, Nil), Seq(placeholder("Sales", "own"))).isEmpty)
    }

    // ------------------------------------------------------------ D2: all or nothing

    test("D2: one member an owner=self key may not write and may not read refuses the whole rename with the shared text, naming nothing") {
        val r = req(key("own", ownCaps: _*))
        val mixed = CatalogOps.Members(Seq(tap("t_mine", "Sales", "own"), tap("t_theirs", "Sales", "someone")), Seq(pg("p_mine", "Sales", "own")))
        val refused = assert403(CatalogAPIController.moveRefusal(r, "update", "Sales", "Sales2", mixed, Nil), "mixed catalog")
        ScopeDenialWording.assertBody(
            refused.getBody,
            ScopeDenialWording.message("own", "tap", "update", ownCaps),
            Seq("t_theirs", "t_mine", "p_mine", "someone")
        )
        // detach: the same
        val d = assert403(CatalogAPIController.moveRefusal(req(key("own", ownCaps: _*), "DELETE"), "delete", "Sales", null, mixed, Nil), "mixed detach")
        ScopeDenialWording.assertBody(d.getBody, ScopeDenialWording.message("own", "tap", "delete", ownCaps), Seq("t_theirs", "t_mine", "p_mine", "someone"))
    }

    test("D2: when the caller may read the first refused member the refusal names it, and only it") {
        // may read every item, may write only its own
        val caps = Seq("pipeline:update:owner=self", "tap:update:owner=self", "pipeline:read", "tap:read")
        val r = req(key("reader", caps: _*))
        val mixed = CatalogOps.Members(
            Seq(tap("t_mine", "Sales", "reader"), tap("t_theirs", "Sales", "someone"), tap("t_also_theirs", "Sales", "other")),
            Seq(pg("p_theirs", "Sales", "someone"))
        )
        val msg = message(assert403(CatalogAPIController.moveRefusal(r, "update", "Sales", "Sales2", mixed, Nil), "readable member"))
        assert(msg.startsWith(ScopeDenialWording.message("reader", "tap", "update", caps)), msg)
        assert(msg.contains("t_theirs"), "names the first refused member: " + msg)
        Seq("t_also_theirs", "p_theirs", "t_mine", "someone", "other").foreach(h => assert(!msg.contains(h), h + " in " + msg))

        // destination refusal of a readable member: named too
        val k = key("sales-editor", renameSalesCaps: _*)
        val dmsg = message(assert403(CatalogAPIController.moveRefusal(req(k), "update", "Sales", "Sales2", salesMembers, Nil), "destination"))
        assertSharedPrefix(dmsg, "sales-editor", "tap", renameSalesCaps, Seq("create", "update", "read"))
        assert(dmsg.contains("t_orders"), dmsg)
        assert(!dmsg.contains("p_orders") && !dmsg.contains("someone"), dmsg)
    }

    test("D2: the story's source-only rename key (no read) gets the shared read text and no member name") {
        val r = req(key("sales-mover", renameLiteralCaps: _*))
        val refused = assert403(CatalogAPIController.moveRefusal(r, "update", "Sales", "Sales2", salesMembers, Nil), "literal key")
        ScopeDenialWording.assertBody(
            refused.getBody,
            ScopeDenialWording.message("sales-mover", "tap", "read", renameLiteralCaps),
            Seq("t_orders", "p_orders", "someone")
        )
    }

    // ------------------------------------------------------------ Acceptance bullet 2: detach and cascade

    test("bullet 2: a key limited to the source catalog cannot detach its items to Uncataloged; nothing detached") {
        for ((label, caps) <- Seq("sales-deleter" -> detachLiteralCaps, "sales-detacher" -> detachSalesCaps)) {
            val r = req(key(label, caps: _*), "DELETE")
            val refused = assert403(CatalogAPIController.moveRefusal(r, "delete", "Sales", null, salesMembers, Nil), label)
            ScopeDenialWording.assertHides(refused.getBody, Seq("someone"))
            assert(AuditLog.scopeDenials(r).nonEmpty)
        }
        // with read in Sales the per-member rule alone lets every member through today
        assert(todayEveryMemberPasses(req(key("sales-detacher", detachSalesCaps: _*), "DELETE"), "delete", "Sales", salesMembers))
        // placeholder-only catalog
        val k = key("sales-detacher", detachSalesCaps: _*)
        assert403(
            CatalogAPIController.moveRefusal(req(k, "DELETE"), "delete", "Sales", null, CatalogOps.Members(Nil, Nil), Seq(placeholder("Sales", "someone"))),
            "placeholder detach"
        )
    }

    test("bullet 2: an owner=self key detaching a catalog of only its own items detaches as today") {
        val r = req(key("own", ownCaps: _*), "DELETE")
        val mine = CatalogOps.Members(Seq(tap("t_mine", "Sales", "own")), Seq(pg("p_mine", "Sales", "own")))
        assert(todayEveryMemberPasses(r, "delete", "Sales", mine))
        assert(CatalogAPIController.moveRefusal(r, "delete", "Sales", null, mine, Nil).isEmpty)
    }

    test("bullet 2: cascade delete keeps today's per-member result: the source-only key deletes every member; a mixed catalog is partial") {
        // the source-only detach key passes the per-member delete rule on every member (cascade works as before)
        val k = key("sales-detacher", detachSalesCaps: _*)
        val all = CatalogOps.execute(salesMembers.names) { n =>
            val res = if (salesMembers.taps.exists(_.name == n)) "tap" else "pipeline"
            CatalogAPIController.assertMemberWrite(req(k, "DELETE"), res, "delete", "Sales", "someone")
        }
        assert(all.ok == Seq("t_orders", "p_orders") && all.failed.isEmpty, all)
        // owner=self on a mixed catalog: own members go, the other is reported failed
        val own = key("own", ownCaps: _*)
        val owners = Map("t_mine" -> "own", "t_theirs" -> "someone")
        val partial = CatalogOps.execute(owners.keys.toSeq.sorted) { n =>
            CatalogAPIController.assertMemberWrite(req(own, "DELETE"), "tap", "delete", "Sales", owners(n))
        }
        assert(partial.ok == Seq("t_mine"), partial)
        assert(partial.failed.map(_._1) == Seq("t_theirs"), partial)
    }

    // ------------------------------------------------------------ wiring

    private def source(rel: String): String = {
        val file = Seq(java.nio.file.Paths.get(rel), java.nio.file.Paths.get("datrisserver", rel))
            .find(java.nio.file.Files.isRegularFile(_)).getOrElse(fail(rel + " not found"))
        new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8)
    }

    private def methodBody(src: String, signature: String): String = {
        val i = src.indexOf(signature)
        assert(i >= 0, signature)
        val j = src.indexOf("\n    def ", i + signature.length)
        val k = src.indexOf("\n    private def ", i + signature.length)
        val end = Seq(j, k).filter(_ >= 0).sorted.headOption.getOrElse(src.length)
        src.substring(i, end)
    }

    private def lineAt(text: String, i: Int): String = {
        val s = text.lastIndexOf('\n', i) + 1
        val e = text.indexOf('\n', i)
        text.substring(s, if (e < 0) text.length else e)
    }

    private val controllerPath = "src/main/scala/ai/datris/api/CatalogAPIController.scala"
    private val call = "CatalogAPIController.moveRefusal("

    test("wiring: rename runs the all-or-nothing pre-flight under the lock, after the merge refusal, before any member, placeholder or grant is written") {
        val body = methodBody(source(controllerPath), "def renameCatalog(")
        val at = body.indexOf(call)
        assert(at >= 0, "renameCatalog does not call moveRefusal")
        assert(body.indexOf(call, at + 1) < 0, "one pre-flight")
        assert(lineAt(body, at).contains("return"), "the refusal is returned: " + lineAt(body, at))
        assert(at > body.indexOf("val (taps, pipelines) = loadAll()"), "under the lock, on the loaded members")
        assert(at > body.indexOf("already exists. Use move to merge catalogs."), "an existing target still answers 409 first")
        for (w <- Seq("TapConfigIO.writeVersioned(", "PipelineConfigIO.writeVersioned(", "removePlaceholder(", "UserStore."))
            assert(body.indexOf(w) < 0 || at < body.indexOf(w), "pre-flight must come before " + w)
    }

    test("wiring: detach runs the pre-flight before writing; the cascade branch keeps its per-member loop and no pre-flight") {
        val body = methodBody(source(controllerPath), "def deleteCatalog(")
        val at = body.indexOf(call)
        assert(at >= 0, "deleteCatalog does not call moveRefusal")
        assert(body.indexOf(call, at + 1) < 0, "one pre-flight, for detach only")
        assert(lineAt(body, at).contains("return"), lineAt(body, at))
        assert(at > body.indexOf("val (taps, pipelines) = loadAll()"))
        assert(at < body.indexOf("TapConfigIO.writeVersioned(live.copy(catalog = null)"))
        assert(at < body.indexOf("PipelineConfigIO.writeVersioned(live.copy(catalog = null)"))
        val cascadeFrom = body.indexOf("// Taps first so nothing lands in a pipeline mid-delete.")
        assert(cascadeFrom > 0, "cascade branch marker moved; update this guard")
        assert(at < cascadeFrom)
        val detachGated =
            lineAt(body, at).contains("\"detach\"") || (at > body.indexOf("if (m == \"detach\") {") && body.indexOf("if (m == \"detach\") {") >= 0)
        assert(detachGated, "the pre-flight applies to detach only: " + lineAt(body, at))
        val cascade = body.substring(cascadeFrom)
        assert(!cascade.contains("moveRefusal"))
        assert(cascade.contains("trackDenied(denied, n)"))
        assert(cascade.contains("CatalogAPIController.assertMemberWrite(request, \"tap\", \"delete\", live.catalog, live.createdByKeyLabel)"))
        assert(cascade.contains("CatalogAPIController.assertMemberWrite(request, \"pipeline\", \"delete\", live.catalog, live.createdByKeyLabel)"))
    }

    test("unchanged: a rename that goes ahead still moves members, placeholder, user grants and reports affected keys as today") {
        val body = methodBody(source(controllerPath), "def renameCatalog(")
        Seq(
            "TapConfigIO.writeVersioned(live.copy(catalog = newName), note, actor)",
            "PipelineConfigIO.writeVersioned(live.copy(catalog = newName), note, actor)",
            "UserStore.renameCatalogInGrants(name, newName)",
            "UserStore.copyCatalogInGrants(name, newName)",
            "out.add(\"renamed\", toArray(okNames))",
            "out.add(\"failed\", failedArray(failures))",
            "out.add(\"affectedKeys\", toArray(affectedKeys(name)))",
            "out.add(\"updatedUsers\", toArray(updatedUsers))",
            "out.addProperty(\"placeholder\", placeholder)",
            "catalog = newName,"
        ).foreach(s => assert(body.contains(s), "rename no longer does: " + s))
        val del = methodBody(source(controllerPath), "def deleteCatalog(")
        Seq(
            "UserStore.removeCatalogFromGrants(name)",
            "out.add(if (m == \"detach\") \"detached\" else \"deleted\", toArray(okNames))",
            "CatalogAPIController.warningsArray(pipelineResult.warnings, tapResult.warnings)"
        ).foreach(s => assert(del.contains(s), "delete no longer does: " + s))
    }

    // ------------------------------------------------------------ unchanged guards (pure seam)

    test("unchanged: admin and editor sessions, unscoped, *:*, legacy and recovery keys and keys-off pass the pre-flight wherever today's member rule passes") {
        val members = CatalogOps.Members(
            Seq(tap("t_a", "Sales", "someone"), tap("t_b", "Sales", "other")),
            Seq(pg("p_a", "Sales", "someone"), pg("p_b", "Sales", null))
        )
        val renameCallers: Seq[(String, ResolvedKey)] = Seq(
            "admin session" -> admin,
            "editor session" -> editor,
            "unscoped key" -> key("unscoped", unscopedCaps: _*),
            "*:* key" -> starKey,
            "full-access template" -> key("full", KeyTemplatesForSpecs.capabilities("full-access"): _*),
            "legacy key" -> legacyFull,
            "recovery key" -> key(RecoveryKey.Label, RecoveryKey.Capabilities: _*),
            "keys off" -> null
        )
        val detachCallers = renameCallers.filterNot(_._1 == "recovery key")
        for ((what, rk) <- renameCallers) {
            val r = req(rk)
            assert(todayEveryMemberPasses(r, "update", "Sales", members), what + ": today's rename passes")
            assert(CatalogAPIController.moveRefusal(r, "update", "Sales", "Sales2", members, Nil).isEmpty, what + " rename")
            assert(
                CatalogAPIController.moveRefusal(req(rk), "update", "Sales", "Sales2", CatalogOps.Members(Nil, Nil), Seq(placeholder("Sales", "someone"))).isEmpty,
                what
            )
        }
        for ((what, rk) <- detachCallers) {
            val r = req(rk, "DELETE")
            assert(todayEveryMemberPasses(r, "delete", "Sales", members), what + ": today's detach passes")
            assert(CatalogAPIController.moveRefusal(r, "delete", "Sales", null, members, Nil).isEmpty, what + " detach")
            assert(
                CatalogAPIController.moveRefusal(req(rk, "DELETE"), "delete", "Sales", null, CatalogOps.Members(Nil, Nil), Seq(placeholder("Sales", "someone")))
                    .isEmpty,
                what
            )
        }
    }

    test("unchanged: a restricted user owning the catalog renames it to a name outside its grants as today (its grants follow the rename)") {
        val r = req(restricted("Sales" -> "own"))
        val members = CatalogOps.Members(Seq(tap("t_a", "Sales", "session:bob")), Seq(pg("p_a", "Sales", "someone")))
        assert(todayEveryMemberPasses(r, "update", "Sales", members))
        assert(CatalogAPIController.moveRefusal(r, "update", "Sales", "Sales2", members, Nil).isEmpty)
        assert(CatalogAPIController.moveRefusal(
            req(restricted("Sales" -> "own")),
            "update",
            "Sales",
            "Sales2",
            CatalogOps.Members(Nil, Nil),
            Seq(placeholder("Sales", "session:bob"))
        ).isEmpty)
    }

    // ------------------------------------------------------------ Acceptance bullet 3: restricted sessions, real entry points

    private def withRunningJob(cat: String)(body: => Unit): Unit = {
        val release = new AtomicBoolean(false)
        val t = tap("a", cat, "spec-actor")
        val deps = new CatalogEnableJobSpec.FakeDeps(Seq(t))
        deps.runResult("a") = CatalogEnableJobSpec.result(recordCount = 1, token = "tok-" + cat)
        deps.rollupFor("tok-" + cat) = () =>
            PipelineStatusRollup(allDone = release.get(), status = if (release.get()) "success" else "processing", jobs = new java.util.ArrayList())
        CatalogEnableJob.start(cat, "spec-actor", TapDependencies.order(Seq(t.copy(lastTestRunStatus = "success"))), deps) match {
            case Left(err) => fail("start refused: " + err)
            case Right(_) =>
        }
        try body
        finally {
            CatalogEnableJob.cancel(cat)
            release.set(true)
        }
    }

    private def conflictBody(cat: String, verb: String): String = {
        val o = new com.google.gson.JsonObject
        o.addProperty(
            "error",
            "Catalog '" + cat + "' cannot be " + verb + ": an enable job in progress is walking its taps. " +
                "Wait for it to finish (or cancel it), then try again."
        )
        CatalogEnableJob.status(cat).foreach(j => o.addProperty("jobId", j.id))
        o.toString
    }

    private def rename(rk: ResolvedKey, cat: String, to: String): ResponseEntity[String] =
        new CatalogAPIController().renameCatalog(null, cat, "{\"newName\":\"" + to + "\"}", req(rk, "PUT", "/api/v1/catalog/" + cat))

    private def delete(rk: ResolvedKey, cat: String, mode: String): ResponseEntity[String] =
        new CatalogAPIController().deleteCatalog(null, cat, mode, if (mode == "cascade") cat else null, req(rk, "DELETE", "/api/v1/catalog/" + cat))

    test("bullet 3: restricted-session rename answers are byte-identical to today (view, edit, own, no grant)") {
        val cat = "cms-rs-rename"
        val grantText = PipelineAPIController
            .capabilityDenied(new DatrisException("capability denied: your grant on catalog " + cat + " does not allow this"))
            .getBody
        withRunningJob(cat) {
            for (level <- Seq("view", "edit")) {
                val r = rename(restricted(cat -> level), cat, cat + "-new")
                assert(r.getStatusCode.value == 403 && r.getBody == grantText, level + ": " + r.getBody)
            }
            val hidden = rename(restricted("Other" -> "own"), cat, cat + "-new")
            assert(hidden.getStatusCode.value == 404, hidden.getBody)
            assert(hidden.getBody == RestrictedCatalogRules.catalogNotFound(cat).getBody, hidden.getBody)
            // own passes every guard before the lock (reaches the job check)
            val own = rename(restricted(cat -> "own"), cat, cat + "-new")
            assert(own.getStatusCode.value == 409 && own.getBody == conflictBody(cat, "renamed"), own.getBody)
        }
    }

    test("bullet 3: restricted-session delete answers (detach and cascade) are byte-identical to today (view, edit, own, no grant)") {
        val cat = "cms-rs-delete"
        val adminOnly = PipelineAPIController.capabilityDenied(new DatrisException("capability denied: only an admin can delete a catalog")).getBody
        withRunningJob(cat) {
            for (mode <- Seq("detach", "cascade")) {
                for (level <- Seq("view", "edit", "own")) {
                    val r = delete(restricted(cat -> level), cat, mode)
                    assert(r.getStatusCode.value == 403 && r.getBody == adminOnly, mode + " " + level + ": " + r.getBody)
                }
                val hidden = delete(restricted("Other" -> "own"), cat, mode)
                assert(hidden.getStatusCode.value == 404 && hidden.getBody == RestrictedCatalogRules.catalogNotFound(cat).getBody, mode + ": " + hidden.getBody)
            }
        }
    }

    test("unchanged: admin and editor sessions, unscoped, legacy and scoped keys and keys-off reach the same pre-lock answers for rename and delete") {
        val cat = "cms-unchanged"
        val callers = Seq(
            admin,
            editor,
            key("unscoped", unscopedCaps: _*),
            legacyFull,
            starKey,
            key("sales-editor", renameSalesCaps: _*),
            key("own", ownCaps: _*),
            null
        )
        withRunningJob(cat) {
            for (rk <- callers) {
                val who = Option(rk).map(_.label).getOrElse("keys off")
                val r = rename(rk, cat, cat + "-new")
                assert(r.getStatusCode.value == 409 && r.getBody == conflictBody(cat, "renamed"), who + ": " + r.getBody)
                for (mode <- Seq("detach", "cascade")) {
                    val d = delete(rk, cat, mode)
                    assert(d.getStatusCode.value == 409 && d.getBody == conflictBody(cat, "deleted"), who + " " + mode + ": " + d.getBody)
                }
            }
        }
        // request validation is unchanged too
        val bad = rename(admin, cat, "Uncataloged")
        assert(bad.getStatusCode.value == 400, bad.getBody)
        val same = rename(key("sales-editor", renameSalesCaps: _*), cat, cat)
        assert(same.getStatusCode.value == 400 && same.getBody.contains("New name is the same as the current name"), same.getBody)
    }
}
