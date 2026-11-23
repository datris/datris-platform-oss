package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.api.TapAPIController
import ai.datris.auth.ScopeDenialFixtures
import ai.datris.auth.TapPrincipal.{Full, Resolution, Resolved}
import ai.datris.model._
import ai.datris.util.DoctorService.{TapOwnerScopeCheck, TapReferencesCheck}
import ai.datris.util.InMemoryUserDb.grants
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

/** Story: Change a tap's owner (plans/stories/tap-change-owner.md),
  * Acceptance bullet 1 (`TapOwnerChangeSpec`): the pure rules behind
  * `POST /api/v1/tap/owner`.
  *
  * Who may become the owner (O5): a stored user (by its stored username) or
  * an API key that resolves today. Both fields, neither field, an unknown
  * user, an unknown or revoked key and the platform's reserved labels are
  * refused with a reason. `ui` is accepted only when it is a stored key.
  *
  * Whether the tap may be handed to that owner (O2): an administrator or a
  * full-access key always may. Any other owner is refused when it could not
  * write the tap where it is stored (the stored-tap write rule evaluated for
  * the new owner as owner), or when it may not load into the stored target
  * pipeline. Everything the doctor rows cannot be exact about is allowed and
  * returned as warnings in the doctor rows' own words: a missing target,
  * narrowed callback reads, and a secret outside the owner's tap access. A
  * store failure is an error, never an acceptance.
  *
  * Names the implementation must provide:
  * {{{
  * object TapOwnerChange {
  *   sealed trait Decision
  *   final case class Accepted(warnings: List[String]) extends Decision
  *   final case class Refused(reason: String) extends Decision   // answered 409, nothing written
  *
  *   /** The new owner a body names: exactly one of `user`, `key` (null when
  *     * absent). Left(reason) is a 400. Right((owner label, its resolution)).
  *     * Store reads may throw; the throw propagates. */
  *   def newOwner(user: String, key: String,
  *                findUser: String => Option[User],
  *                keyByLabel: String => Option[ResolvedKey]): Either[String, (String, TapPrincipal.Resolution)]
  *
  *   /** Eligibility of `newLabel` (resolved as `resolution`) for `tap`, whose
  *     * stored target pipeline is `storedTarget` (null: not stored or none).
  *     * `secretOwner` as TapReferenceScan reads it; may throw. */
  *   def decide(tap: TapConfig, newLabel: String, resolution: TapPrincipal.Resolution,
  *              storedTarget: PipelineConfig,
  *              secretOwner: String => Option[Option[String]]): Decision
  * }
  * }}}
  */
class TapOwnerChangeSpec extends AnyFunSuite with BeforeAndAfterEach {

    import TapOwnerChange.{Accepted, Decision, Refused}

    private var saved: DatrisEnvironment = _

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        ScopeDenialFixtures.install(ScopeDenialFixtures.auditEnv().copy(useAuditLog = false))
    }

    override def afterEach(): Unit = ScopeDenialFixtures.install(saved)

    // ---------------------------------------------------------------- identities

    private def key(label: String, caps: String*): ResolvedKey = ScopeDenialFixtures.key(label, caps: _*)

    private val ada = User("ada", "h", User.RoleAdmin, null, null, null)
    private val ed = User("ed", "h", User.RoleEditor, null, null, null)
    private val vic = User("vic", "h", User.RoleViewer, null, null, null)
    private val ritaEdit = User("rita", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit"))
    private val ritaView = User("rita", "h", User.RoleViewer, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "view"))

    private val fullKey = key("full-agent", "*:*")
    private val legacyKey = ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true)

    /** Limited to what it created; may load into any pipeline it reads. */
    private val builder = key("builder", "pipeline:read", "tap:read:owner=self", "tap:create", "tap:update:owner=self", "tap:run:owner=self")

    /** Limited to catalog Sales for taps and loads. */
    private val salesKey = key(
        "sales-agent",
        "pipeline:read:catalog=Sales",
        "pipeline:run:catalog=Sales",
        "tap:read:catalog=Sales",
        "tap:create:catalog=Sales",
        "tap:update:catalog=Sales",
        "tap:run:catalog=Sales"
    )
    private val monitorKey = key("monitor", "job:read", "pipeline:read", "tap:read")
    private val uiKey = key("ui", "*:*")

    private val users: Map[String, User] = Seq(ada, ed, vic).map(u => u.username -> u).toMap
    private val keys: Map[String, ResolvedKey] = Seq(fullKey, legacyKey, builder, salesKey, monitorKey).map(k => k.label -> k).toMap

    private val findUser: String => Option[User] = n => users.get(UserStore.normalize(n))
    private val keyByLabel: String => Option[ResolvedKey] = l => keys.get(l)

    private def session(u: User): ResolvedKey = APIKeyValidator.resolveFromSession(u)

    // ---------------------------------------------------------------- stored items

    private val salesOrders = PipelineConfig(name = "sales-orders", catalog = "Sales", createdByKeyLabel = "session:ada")
    private val financeLedger = PipelineConfig(name = "finance-ledger", catalog = "Finance", createdByKeyLabel = "session:ada")

    private val tap = TapConfig(
        name = "orders-feed",
        description = "d",
        targetPipeline = "sales-orders",
        catalog = "Sales",
        createdByKeyLabel = "old-agent",
        scriptPath = "tap-scripts/orders-feed.py"
    )

    private val noSecret: String => Option[Option[String]] = n => fail("no secret read expected: " + n)

    private def decide(t: TapConfig, label: String, r: Resolution, target: PipelineConfig, secretOwner: String => Option[Option[String]] = noSecret): Decision =
        TapOwnerChange.decide(t, label, r, target, secretOwner)

    private def warningsOf(d: Decision): List[String] = d match {
        case Accepted(w) => w
        case other => fail("expected accepted, got " + other)
    }

    private def refusedReason(d: Decision): String = d match {
        case Refused(reason) =>
            assert(reason != null && reason.trim.nonEmpty, "a refusal says why")
            reason
        case other => fail("expected refused, got " + other)
    }

    // ================================================================ the new owner

    test("a user value gives session:<stored username>, resolved to that user's session identity") {
        assert(TapOwnerChange.newOwner("ed", null, findUser, keyByLabel) == Right(("session:ed", Resolved(session(ed)))))
        // the label is built from the username as stored, not as typed
        val typed = TapOwnerChange.newOwner("Ed", null, findUser, keyByLabel)
        assert(typed.isRight && typed.right.get._1 == "session:ed", typed.toString)
        assert(TapOwnerChange.newOwner(null, "builder", findUser, keyByLabel) == Right(("builder", Resolved(builder))))
    }

    test("a key value gives that key's label") {
        for (k <- Seq(fullKey, builder, salesKey, monitorKey)) {
            val r = TapOwnerChange.newOwner(null, k.label, findUser, keyByLabel)
            assert(r.isRight && r.right.get._1 == k.label, k.label + ": " + r)
        }
    }

    test("both fields, or neither, are refused with a reason") {
        for ((u, k) <- Seq(("ed", "builder"), (null, null), ("", ""))) {
            val r = TapOwnerChange.newOwner(u, k, findUser, keyByLabel)
            assert(r.isLeft && r.left.get.trim.nonEmpty, s"($u, $k): $r")
        }
    }

    test("an unknown user is refused with a reason") {
        val r = TapOwnerChange.newOwner("nobody", null, findUser, keyByLabel)
        assert(r.isLeft && r.left.get.trim.nonEmpty, r.toString)
    }

    test("an unknown key and a revoked key (neither resolves) are refused with a reason") {
        for (label <- Seq("never-issued", "revoked-agent")) {
            val r = TapOwnerChange.newOwner(null, label, findUser, _ => None)
            assert(r.isLeft && r.left.get.trim.nonEmpty, label + ": " + r)
        }
    }

    test("each reserved value is refused as a key, even when the key store would answer it") {
        val answersAnything: String => Option[ResolvedKey] = l => Some(key(l, "*:*"))
        for (label <- Seq("anonymous", "recovery-agent", "system", "agent", "session:ed", "session:", "tap:orders-feed", "tap:")) {
            val r = TapOwnerChange.newOwner(null, label, findUser, answersAnything)
            assert(r.isLeft && r.left.get.trim.nonEmpty, label + ": " + r)
        }
    }

    test("ui is accepted as the new owner only when it is a stored key") {
        val stored = TapOwnerChange.newOwner(null, "ui", findUser, l => if (l == "ui") Some(uiKey) else None)
        assert(stored.isRight && stored.right.get._1 == "ui", stored.toString)
        val notStored = TapOwnerChange.newOwner(null, "ui", findUser, _ => None)
        assert(notStored.isLeft && notStored.left.get.trim.nonEmpty, notStored.toString)
    }

    test("a failing user or key store is an error, never an owner") {
        intercept[Exception](TapOwnerChange.newOwner("ed", null, _ => throw new IllegalStateException("user store down"), keyByLabel))
        intercept[Exception](TapOwnerChange.newOwner(null, "builder", findUser, _ => throw new IllegalStateException("key store down")))
    }

    // ================================================================ eligibility

    test("an administrator or a full-access key is accepted with no warnings, whatever the tap") {
        // Every condition that warns or refuses a limited owner, all at once.
        val awkward = tap.copy(
            catalog = "Ops",
            targetPipeline = "finance-ledger",
            secretName = "weather",
            lastRunUsedCallback = true
        )
        val missing = awkward.copy(targetPipeline = "not-created-yet")
        val secretOfSomeoneElse: String => Option[Option[String]] = _ => Some(Some("someone-else"))
        for ((label, r) <- Seq("session:ada" -> Resolved(session(ada)), "full-agent" -> Resolved(fullKey), "legacy" -> Resolved(legacyKey), "ui" -> Full)) {
            assert(decide(awkward, label, r, financeLedger, secretOfSomeoneElse) == Accepted(Nil), label)
            assert(decide(missing, label, r, null, secretOfSomeoneElse) == Accepted(Nil), label)
        }
    }

    test("an owner that may write the tap where it is and load into its target is accepted with no warnings") {
        assert(decide(tap, "builder", Resolved(builder), salesOrders) == Accepted(Nil))
        assert(decide(tap, "sales-agent", Resolved(salesKey), salesOrders) == Accepted(Nil))
        assert(decide(tap, "session:ed", Resolved(session(ed)), salesOrders) == Accepted(Nil))
        assert(decide(tap, "session:rita", Resolved(session(ritaEdit)), salesOrders) == Accepted(Nil))
    }

    test("an owner that cannot write the tap in its catalog is refused") {
        val inOps = tap.copy(catalog = "Ops", targetPipeline = null)
        refusedReason(decide(inOps, "sales-agent", Resolved(salesKey), null))
        refusedReason(decide(inOps, "session:rita", Resolved(session(ritaEdit)), null))
    }

    test("a read-only identity is refused as the owner") {
        val noTarget = tap.copy(targetPipeline = null)
        refusedReason(decide(noTarget, "session:vic", Resolved(session(vic)), null))
        refusedReason(decide(noTarget, "session:rita", Resolved(session(ritaView)), null))
        refusedReason(decide(noTarget, "monitor", Resolved(monitorKey), null))
    }

    test("the write half of the rule is the stored-tap write rule, evaluated with the new owner as the tap's owner") {
        val identities = Seq(
            "session:ed" -> session(ed),
            "session:vic" -> session(vic),
            "session:rita" -> session(ritaEdit),
            "builder" -> builder,
            "sales-agent" -> salesKey,
            "monitor" -> monitorKey
        )
        for (catalog <- Seq("Sales", "Ops"); (label, rk) <- identities) {
            val t = tap.copy(catalog = catalog, targetPipeline = null)
            val request = ScopeDenialFixtures.request(rk, "POST", "/api/v1/tap")._1
            val mayWrite = TapAPIController.mayWriteStoredTap(request, t.copy(createdByKeyLabel = label))
            decide(t, label, Resolved(rk), null) match {
                case Accepted(_) => assert(mayWrite, s"$label in $catalog accepted but may not write the tap")
                case Refused(_) => assert(!mayWrite, s"$label in $catalog refused but may write the tap")
            }
        }
    }

    test("an owner that may not load into the stored target pipeline is refused, for a reason other than the write rule") {
        val feedsFinance = tap.copy(targetPipeline = "finance-ledger")
        val feed = refusedReason(decide(feedsFinance, "sales-agent", Resolved(salesKey), financeLedger))
        val write = refusedReason(decide(tap.copy(catalog = "Ops", targetPipeline = null), "sales-agent", Resolved(salesKey), null))
        assert(feed != write, "each refusal says which rule refused it: " + feed)
        // a restricted user whose grants do not reach the target's catalog
        refusedReason(decide(feedsFinance, "session:rita", Resolved(session(ritaEdit)), financeLedger))
    }

    test("a missing target is a warning in the doctor row's words, not a refusal") {
        val missing = tap.copy(targetPipeline = "not-created-yet")
        val w = warningsOf(decide(missing, "sales-agent", Resolved(salesKey), null))
        val words = TapOwnerScopeCheck.Groups.toMap.apply(TapOwnerScopeScan.TargetMissing)
        assert(w.exists(_.contains(words)), w.toString)
    }

    test("narrowed callback reads are a warning in the doctor row's words, not a refusal") {
        val reads = tap.copy(lastRunUsedCallback = true)
        val w = warningsOf(decide(reads, "builder", Resolved(builder), salesOrders))
        val words = TapOwnerScopeCheck.Groups.toMap.apply(TapOwnerScopeScan.ReadsNarrowed)
        assert(w.exists(_.contains(words)), w.toString)
        // a tap that has not read through the callback since the upgrade gives no such warning
        assert(warningsOf(decide(tap, "builder", Resolved(builder), salesOrders)).isEmpty)
    }

    test("a secret outside the new owner's tap access is a warning in the doctor row's words, not a refusal") {
        val withSecret = tap.copy(secretName = "weather")
        val w = warningsOf(decide(withSecret, "builder", Resolved(builder), salesOrders, _ => Some(Some("someone-else"))))
        val words = TapReferencesCheck.Groups.toMap.apply(TapReferenceScan.SecretOutsideOwner)
        assert(w.exists(_.contains(words)), w.toString)
        // the owner's own secret, or one that no longer exists, gives none
        assert(warningsOf(decide(withSecret, "builder", Resolved(builder), salesOrders, _ => Some(Some("builder")))).isEmpty)
        assert(warningsOf(decide(withSecret, "builder", Resolved(builder), salesOrders, _ => None)).isEmpty)
    }

    test("a failing secret lookup is an error, never an acceptance") {
        val withSecret = tap.copy(secretName = "weather")
        intercept[Exception](decide(withSecret, "builder", Resolved(builder), salesOrders, _ => throw new IllegalStateException("vault down")))
    }
}
