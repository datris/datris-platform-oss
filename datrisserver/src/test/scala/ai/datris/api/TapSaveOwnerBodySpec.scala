package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.ScopeDenialFixtures
import ai.datris.model._
import ai.datris.util.SecretNames
import jakarta.servlet.http.HttpServletRequest
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: Change a tap's owner (plans/stories/tap-change-owner.md),
  * Acceptance bullet 4 (`TapSaveOwnerBodySpec`): the owner change route is
  * the only way to set a tap's owner. Every other write that saves a tap
  * ignores an owner named in its body: a create stores the caller, an update
  * keeps the stored owner, a catalog restore stores the restoring caller for
  * a new tap and keeps the stored owner over an existing one, and a version
  * restore and the recovery agent's revert check keep the live owner, so an
  * owner changed after a version was written is not undone by restoring that
  * version.
  *
  * Driven through the save core's store seam (`TapAPIController.TapSaveIO`),
  * as TapVersionRestoreSaveChecksSpec does. These pin behaviour that holds
  * today and must keep holding once the owner can be changed.
  */
class TapSaveOwnerBodySpec extends AnyFunSuite with BeforeAndAfterEach {

    private val env: DatrisEnvironment = ScopeDenialFixtures.auditEnv().copy(useAuditLog = false, useApiKeys = false, mongoDbConfig = null)
    private var savedValues: DatrisEnvironment = _
    private var savedScope: Option[String] = None

    override def beforeEach(): Unit = {
        savedValues = DatrisEnvironment.values
        DatrisEnvironment.init(env)
        ScopeDenialFixtures.install(env)
        savedScope = sys.props.get(SecretNames.TapScopeProperty)
        sys.props(SecretNames.TapScopeProperty) = "tap"
    }

    override def afterEach(): Unit = {
        ScopeDenialFixtures.install(savedValues)
        savedScope match {
            case Some(v) => sys.props(SecretNames.TapScopeProperty) = v
            case None => sys.props -= SecretNames.TapScopeProperty
        }
    }

    // ---------------------------------------------------------------- fixtures

    private def jl(xs: String*): java.util.List[String] = new java.util.ArrayList[String](xs.asJava)

    private val hidden: ResponseEntity[String] = ResponseEntity.status(404).body[String]("{\"error\": \"No version 3 for tap 't'\"}")

    private class Store(initial: Seq[TapConfig]) {
        val taps: mutable.Map[String, TapConfig] = mutable.LinkedHashMap(initial.map(t => t.name -> t): _*)
        val writes: mutable.Buffer[(TapConfig, String)] = mutable.Buffer.empty
        private val scripts = Map("tap-scripts/t.py" -> "print('live')", "tap-scripts/v1.py" -> "print('v1')")
        private def scriptKey(t: TapConfig): String = Option(t.scriptPath).filter(_.nonEmpty).getOrElse(t.scriptRepoPath)
        val io: TapAPIController.TapSaveIO = TapAPIController.TapSaveIO(
            readTap = n => taps.get(n).orNull,
            readPipeline = _ => null,
            secretType = _ => None,
            scriptExists = t => scripts.contains(scriptKey(t)),
            readScript = t => scripts.get(scriptKey(t)),
            loadAll = () => (taps.values.toSeq, Nil),
            writeVersioned = (t, note, _) => {
                writes += ((t, note))
                val saved = t.copy(version = Option(taps.get(t.name).orNull).map(_.version + 1).getOrElse(1))
                taps(t.name) = saved
                saved
            },
            secretOwner = _ => None,
            repoPathPrefix = () => ""
        )
    }

    private def req(rk: ResolvedKey, path: String): HttpServletRequest = ScopeDenialFixtures.request(rk, "POST", path)._1

    private val creatorKey = ResolvedKey(None, "creator", Seq(Capability.FullAccess), isLegacyFullAccess = true)
    private val editorKey = ResolvedKey(None, "editor-agent", Seq(Capability.FullAccess), isLegacyFullAccess = true)

    /** A stored script tap in catalog C whose owner was changed to `new-owner`. */
    private val live: TapConfig = TapConfig(
        name = "t",
        description = "live",
        targetPipeline = null,
        catalog = "C",
        createdByKeyLabel = "new-owner",
        createdAt = "2026-01-01 00:00:00",
        scriptKind = "python",
        scriptStorage = "minio",
        scriptPath = "tap-scripts/t.py",
        dependsOn = jl(),
        inferredDependsOn = jl(),
        enabled = true,
        version = 3
    )

    /** A version written before the owner change. */
    private val oldSnap: TapConfig = live.copy(description = "v1", scriptPath = "tap-scripts/v1.py", createdByKeyLabel = "old-owner")

    private def save(r: HttpServletRequest, store: Store, body: TapConfig, restored: Boolean = false) =
        new TapAPIController().saveTapInternalWith(body, null, r, restored, store.io)

    // ================================================================ POST /tap

    test("a create whose body names another owner stores the caller") {
        val store = new Store(Nil)
        val r = save(req(creatorKey, "/api/v1/tap"), store, live.copy(createdByKeyLabel = "someone-else", version = 1))
        assert(r.isRight, s"$r")
        assert(store.writes.size == 1 && store.writes.head._1.createdByKeyLabel == "creator", s"${store.writes}")
    }

    test("an update whose body names another owner keeps the stored owner") {
        val store = new Store(Seq(live))
        val r = save(req(editorKey, "/api/v1/tap"), store, live.copy(description = "edited", createdByKeyLabel = "someone-else"))
        assert(r.isRight, s"$r")
        assert(store.writes.size == 1, s"${store.writes}")
        assert(store.writes.head._1.createdByKeyLabel == "new-owner", s"${store.writes}")
        assert(store.writes.head._1.description == "edited")
    }

    // ================================================================ catalog restore

    test("a catalog restore stores the restoring caller for a new tap and keeps the stored owner over an existing one, whatever the archive says") {
        val fresh = new Store(Nil)
        assert(
            save(req(creatorKey, "/api/v1/catalog/restore"), fresh, live.copy(createdByKeyLabel = "archived-owner", enabled = false), restored = true).isRight
        )
        assert(fresh.writes.size == 1 && fresh.writes.head._1.createdByKeyLabel == "creator", s"${fresh.writes}")
        val over = new Store(Seq(live))
        assert(save(
            req(creatorKey, "/api/v1/catalog/restore"),
            over,
            live.copy(description = "archived", createdByKeyLabel = "archived-owner", enabled = false),
            restored = true
        ).isRight)
        assert(over.writes.size == 1 && over.writes.head._1.createdByKeyLabel == "new-owner", s"${over.writes}")
    }

    // ================================================================ version restore and the revert check

    test("a version restore after an owner change keeps the new owner") {
        val store = new Store(Seq(live))
        val candidate = EntityVersionAPIController.tapRestoreCandidate("t", oldSnap, live)
        assert(candidate.createdByKeyLabel == "new-owner", candidate.toString)
        val r = new TapAPIController().restoreTapInternal(candidate, live, 1, hidden, req(creatorKey, "/api/v1/tap/version/restore"), store.io)
        assert(r.isRight, s"$r")
        assert(store.writes.size == 1, s"${store.writes}")
        val w = store.writes.head._1
        assert(w.description == "v1" && w.scriptPath == "tap-scripts/v1.py", s"$w")
        assert(w.createdByKeyLabel == "new-owner", s"$w")
    }

    test("a version restore decided before an owner change and written after it is refused, not written with either owner") {
        // Decided on the tap before the change; the store holds the changed tap under the lock.
        val before = live.copy(createdByKeyLabel = "old-owner")
        val store = new Store(Seq(live))
        val candidate = EntityVersionAPIController.tapRestoreCandidate("t", oldSnap, before)
        val r = new TapAPIController().restoreTapInternal(candidate, before, 1, hidden, req(creatorKey, "/api/v1/tap/version/restore"), store.io)
        assert(r.isLeft && r.left.get.getStatusCode.value == 409, s"$r")
        assert(store.writes.isEmpty)
    }

    test("the recovery agent's revert check after an owner change keeps the new owner, and its write onto the stored tap does too") {
        val store = new Store(Seq(live))
        val candidate = EntityVersionAPIController.tapRestoreCandidate("t", oldSnap, live)
        val checked = new TapAPIController().checkTapRevert(candidate, live, 1, store.io)
        assert(checked.isRight, s"$checked")
        assert(checked.right.get.createdByKeyLabel == "new-owner", s"$checked")
        assert(store.writes.isEmpty, "the check writes nothing")
        // the revert writes the checked config onto the tap it re-reads under the lock
        val onto = EntityVersionAPIController.tapRestoreWriteConfig(checked.right.get.copy(createdByKeyLabel = "old-owner"), live)
        assert(onto.createdByKeyLabel == "new-owner", s"$onto")
    }
}
