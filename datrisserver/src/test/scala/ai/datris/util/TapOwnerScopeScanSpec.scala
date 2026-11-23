package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.api.TapAPIController
import ai.datris.auth.{ScopeDenialFixtures, TapPrincipal}
import ai.datris.auth.TapPrincipal.{Full, Resolution, Resolved, Unresolved}
import ai.datris.model.{Capability, PipelineConfig, ResolvedKey, TapConfig, User}
import ai.datris.util.InMemoryUserDb.grants
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable

/** Story: Tap runs and owner access, report-only release
  * (plans/stories/tap-runs-hold-owner-scope.md).
  *
  * Part 1, Acceptance bullet 2 (`TapOwnerScopeScanSpec`): which stored taps
  * the doctor names, by group. A tap whose owner may load into its target is
  * not listed; one whose owner may not is listed with tap, pipeline and
  * owner; a missing target, an unresolved owner and a limited owner whose
  * last run used the callback each land in their group; an administrator-
  * owned tap lands in none.
  *
  * Part 2: the pure "would this be refused in the next release?" decision
  * for each of the story's six rules (the table under "Rules, classified"),
  * with cases that would be refused and cases that would not. Nothing here is
  * enforced in this release; these decisions only drive the doctor row and
  * the report lines.
  *
  * Pinned seam (does not exist at 4f38ae31; the implementation adds it):
  * {{{
  * // datrisserver/src/main/scala/ai/datris/util/TapOwnerScopeScan.scala
  * object TapOwnerScopeScan {
  *   val FeedOutsideOwner = "feedOutsideOwner"
  *   val TargetMissing    = "targetMissing"
  *   val OwnerUnresolved  = "ownerUnresolved"
  *   val ReadsNarrowed    = "readsNarrowed"
  *
  *   // owner = the tap's createdByKeyLabel as stored (may be null)
  *   case class Finding(group: String, tap: String, pipeline: String, owner: String)
  *   // `unreadable`: names of taps that could not be checked (an owner or
  *   // pipeline read failed), so "could not check" is never "nothing to report".
  *   case class Result(findings: List[Finding], unreadable: List[String])
  *   object Result { val empty: Result }
  *
  *   // Pure. readPipeline: null = not stored (may throw); resolveOwner: an owner or
  *   // last-editor label -> TapPrincipal.Resolution (may throw); lastEditor: tap
  *   // name -> the actor label of the tap's latest version, or null.
  *   // Each distinct label is resolved at most once per scan. Findings in tap order.
  *   def scan(taps: List[TapConfig], readPipeline: String => PipelineConfig,
  *            resolveOwner: String => TapPrincipal.Resolution, lastEditor: String => String): Result
  *   def liveScan(): Result        // every stored tap; throws when the taps cannot be listed
  *
  *   // The six rules, pure. "Limited" is CallerVisibility.limited; a load is
  *   // decided by CallerVisibility.loadChecked / loadDenial on the stored target.
  *   def callerReadsNarrowed(caller: Option[ResolvedKey], usedCallback: Boolean): Boolean                 // rule 1
  *   def unattendedReadsNarrowed(owner: Resolution, lastEditor: Resolution, usedCallback: Boolean): Boolean // rule 2
  *   def feedOutsideOwner(owner: Resolution, target: PipelineConfig): Boolean                              // rule 3
  *   def ownerUnresolved(owner: Resolution): Boolean                                                      // rule 4
  *   def saveTargetMissing(caller: Option[ResolvedKey], tap: TapConfig, target: PipelineConfig): Boolean  // rule 5
  *   def scriptTargetRefused(caller: Option[ResolvedKey], tap: TapConfig, target: PipelineConfig): Boolean // rule 6
  * }
  * // model/TapConfig.scala: lastRunUsedCallback: Boolean = false (additive; absent reads false)
  * }}}
  */
class TapOwnerScopeScanSpec extends AnyFunSuite {

    import TapOwnerScopeScan._

    // ---------------------------------------------------------------- identities

    private def key(label: String, caps: String*): ResolvedKey =
        ResolvedKey(None, label, Capability.parseList(caps), isLegacyFullAccess = false)

    private def session(u: User): ResolvedKey = APIKeyValidator.resolveFromSession(u)

    /** A restricted editor granted edit on Sales only. */
    private val rita: ResolvedKey =
        session(User("rita", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit")))
    private val admin: ResolvedKey = session(User("ada", "h", User.RoleAdmin, null, null, null))
    private val editor: ResolvedKey = session(User("ed", "h", User.RoleEditor, null, null, null))

    /** A key that loads into Sales only (rag-builder-like, catalog scope). */
    private val salesKey: ResolvedKey =
        key(
            "sales-agent",
            "pipeline:read:catalog=Sales",
            "pipeline:run:catalog=Sales",
            "tap:read:catalog=Sales",
            "tap:create:catalog=Sales",
            "tap:update:catalog=Sales"
        )

    /** A key that loads only into what it created. */
    private val ownKey: ResolvedKey =
        key("own-agent", "pipeline:read:owner=self", "pipeline:run:owner=self", "tap:read:owner=self", "tap:create:owner=self", "tap:update:owner=self")

    /** Not limited: no scoped pipeline or tap capability. */
    private val monitorKey: ResolvedKey = key("monitor", "job:read", "metadata:read")

    private val fullKey: ResolvedKey = ResolvedKey(None, "root", Seq(Capability.FullAccess), isLegacyFullAccess = true)

    // ---------------------------------------------------------------- stored items

    private val salesOrders = PipelineConfig(name = "sales-orders", catalog = "Sales", createdByKeyLabel = "session:ada")
    private val financeLedger = PipelineConfig(name = "finance-ledger", catalog = "Finance", createdByKeyLabel = "session:ada")
    private val ownPipeline = PipelineConfig(name = "own-pipe", catalog = "Finance", createdByKeyLabel = "own-agent")
    private val pipelines = Seq(salesOrders, financeLedger, ownPipeline).map(p => p.name -> p).toMap
    private val readPipeline: String => PipelineConfig = n => pipelines.get(n).orNull

    private def tap(name: String, owner: String, target: String, usedCallback: Boolean = false, catalog: String = "Sales"): TapConfig =
        TapConfig(name = name, description = "d", targetPipeline = target, catalog = catalog, createdByKeyLabel = owner, lastRunUsedCallback = usedCallback)

    private val owners: Map[String, Resolution] = Map(
        "session:rita" -> Resolved(rita),
        "sales-agent" -> Resolved(salesKey),
        "own-agent" -> Resolved(ownKey),
        "session:ada" -> Resolved(admin),
        "session:ed" -> Resolved(editor),
        "old-agent" -> Resolved(fullKey),
        "gone-agent" -> Unresolved,
        "session:departed" -> Unresolved,
        "ui" -> Full,
        "anonymous" -> Full
    )
    private val resolveOwner: String => Resolution = l => if (l == null) Full else owners.getOrElse(l, Unresolved)

    private def scanOf(taps: List[TapConfig], editors: Map[String, String] = Map.empty): Result =
        scan(taps, readPipeline, resolveOwner, n => editors.get(n).orNull)

    private def groupOf(r: Result, g: String): List[(String, String, String)] =
        r.findings.filter(_.group == g).map(f => (f.tap, f.pipeline, f.owner))

    // ================================================================ Part 1: the scan

    test("a tap whose owner may load into its target is not listed") {
        val r = scanOf(List(
            tap("rita-sales", "session:rita", "sales-orders"),
            tap("key-sales", "sales-agent", "sales-orders"),
            tap("own-own", "own-agent", "own-pipe")
        ))
        assert(r.findings.isEmpty, r.toString)
        assert(r.unreadable.isEmpty, r.toString)
    }

    test("a tap whose owner may not load into its target is listed with tap, pipeline and owner") {
        val r = scanOf(List(
            tap("rita-finance", "session:rita", "finance-ledger"),
            tap("rita-sales", "session:rita", "sales-orders"),
            tap("key-finance", "sales-agent", "finance-ledger"),
            tap("own-other", "own-agent", "finance-ledger")
        ))
        assert(
            groupOf(r, FeedOutsideOwner) == List(
                ("rita-finance", "finance-ledger", "session:rita"),
                ("key-finance", "finance-ledger", "sales-agent"),
                ("own-other", "finance-ledger", "own-agent")
            ),
            r.toString
        )
        assert(!r.findings.exists(_.tap == "rita-sales"), r.toString)
    }

    test("a limited owner's tap whose target is not stored lands in targetMissing") {
        val r = scanOf(List(tap("rita-early", "session:rita", "not-created-yet"), tap("key-early", "sales-agent", "not-created-yet")))
        assert(
            groupOf(r, TargetMissing) == List(("rita-early", "not-created-yet", "session:rita"), ("key-early", "not-created-yet", "sales-agent")),
            r.toString
        )
        assert(groupOf(r, FeedOutsideOwner).isEmpty, r.toString)
    }

    test("a tap whose owner no longer resolves lands in ownerUnresolved") {
        val r = scanOf(List(tap("orphan-key", "gone-agent", "sales-orders"), tap("orphan-user", "session:departed", "finance-ledger")))
        assert(
            groupOf(r, OwnerUnresolved) == List(("orphan-key", "sales-orders", "gone-agent"), ("orphan-user", "finance-ledger", "session:departed")),
            r.toString
        )
    }

    test("a limited owner's tap whose last run used the callback lands in readsNarrowed; one that did not, does not") {
        val r = scanOf(List(tap("rita-reads", "session:rita", "sales-orders", usedCallback = true), tap("rita-quiet", "session:rita", "sales-orders")))
        assert(groupOf(r, ReadsNarrowed) == List(("rita-reads", "sales-orders", "session:rita")), r.toString)
        assert(!r.findings.exists(_.tap == "rita-quiet"), r.toString)
    }

    test("a tap whose last editor is limited and whose last run used the callback lands in readsNarrowed (owner's read narrowed by the last editor's)") {
        val r = scanOf(List(tap("ada-edited-by-rita", "session:ada", "sales-orders", usedCallback = true)), Map("ada-edited-by-rita" -> "session:rita"))
        assert(groupOf(r, ReadsNarrowed).map(_._1) == List("ada-edited-by-rita"), r.toString)
    }

    test("an administrator-owned tap lands in no group, whatever it targets and reads") {
        val taps = List(
            tap("ada-finance", "session:ada", "finance-ledger", usedCallback = true),
            tap("ada-early", "session:ada", "not-created-yet", usedCallback = true),
            tap("legacy-finance", "old-agent", "finance-ledger", usedCallback = true),
            tap("ui-finance", "ui", "finance-ledger", usedCallback = true),
            tap("anon-finance", "anonymous", "finance-ledger", usedCallback = true),
            tap("nobody-finance", null, "finance-ledger", usedCallback = true),
            tap("editor-finance", "session:ed", "finance-ledger", usedCallback = true)
        )
        val r = scanOf(taps)
        assert(r.findings.isEmpty, r.toString)
    }

    test("a tap with no target pipeline is not in a feed group") {
        val r = scanOf(List(tap("rita-none", "session:rita", null), tap("rita-blank", "session:rita", "")))
        assert(groupOf(r, FeedOutsideOwner).isEmpty && groupOf(r, TargetMissing).isEmpty, r.toString)
    }

    test("a failed owner or pipeline read lists the tap as unreadable and in no group") {
        val flakyOwner: String => Resolution = {
            case "flaky-agent" => throw new IllegalStateException("API key metadata unavailable")
            case l => resolveOwner(l)
        }
        val flakyPipeline: String => PipelineConfig = {
            case "broken" => throw new IllegalStateException("mongo down")
            case n => readPipeline(n)
        }
        val r = scan(
            List(
                tap("t-flaky", "flaky-agent", "finance-ledger"),
                tap("t-broken", "session:rita", "broken"),
                tap("rita-finance", "session:rita", "finance-ledger")
            ),
            flakyPipeline,
            flakyOwner,
            _ => null
        )
        assert(r.unreadable.toSet == Set("t-flaky", "t-broken"), r.toString)
        assert(!r.findings.exists(f => f.tap == "t-flaky" || f.tap == "t-broken"), r.toString)
        assert(groupOf(r, FeedOutsideOwner).map(_._1) == List("rita-finance"), "the taps that could be checked are still reported: " + r)
    }

    test("each distinct owner label is resolved at most once per scan") {
        val seen = mutable.ListBuffer[String]()
        val counting: String => Resolution = l => { seen += String.valueOf(l); resolveOwner(l) }
        val taps = (1 to 20).toList.map(i => tap("t" + i, if (i % 2 == 0) "session:rita" else "sales-agent", "finance-ledger"))
        scan(taps, readPipeline, counting, _ => null)
        assert(seen.groupBy(identity).forall(_._2.size == 1), seen.toString)
    }

    test("Result.empty has nothing to report") {
        assert(Result.empty.findings.isEmpty && Result.empty.unreadable.isEmpty)
    }

    // ================================================================ Part 2: the six rules

    test("rule 1 (test and manual run): reported when the caller is limited and the script used the callback") {
        assert(callerReadsNarrowed(Some(rita), usedCallback = true))
        assert(callerReadsNarrowed(Some(salesKey), usedCallback = true))
        assert(callerReadsNarrowed(Some(ownKey), usedCallback = true))
        // would not be refused
        assert(!callerReadsNarrowed(Some(rita), usedCallback = false), "a script that never called back reads nothing")
        assert(!callerReadsNarrowed(Some(editor), usedCallback = true), "an unrestricted editor is not limited")
        assert(!callerReadsNarrowed(Some(admin), usedCallback = true))
        assert(!callerReadsNarrowed(Some(fullKey), usedCallback = true))
        assert(!callerReadsNarrowed(Some(monitorKey), usedCallback = true), "a key with no scoped pipeline or tap capability is not limited")
        assert(!callerReadsNarrowed(None, usedCallback = true), "no identity (auth off)")
    }

    test("rule 2 (unattended run): reported when the owner or the last editor is limited and the last run used the callback") {
        assert(unattendedReadsNarrowed(Resolved(rita), Full, usedCallback = true))
        assert(unattendedReadsNarrowed(Resolved(salesKey), Full, usedCallback = true))
        assert(unattendedReadsNarrowed(Resolved(admin), Resolved(rita), usedCallback = true), "narrowed by the last editor's read (D1b)")
        assert(unattendedReadsNarrowed(Full, Resolved(salesKey), usedCallback = true))
        // would not be refused
        assert(!unattendedReadsNarrowed(Resolved(rita), Full, usedCallback = false))
        assert(!unattendedReadsNarrowed(Resolved(admin), Resolved(editor), usedCallback = true))
        assert(!unattendedReadsNarrowed(Full, Full, usedCallback = true))
        assert(!unattendedReadsNarrowed(Resolved(fullKey), Resolved(admin), usedCallback = true))
    }

    test("rule 3 (unattended feed): reported when the owner is limited and may not load into the stored target") {
        assert(feedOutsideOwner(Resolved(rita), financeLedger))
        assert(feedOutsideOwner(Resolved(salesKey), financeLedger))
        assert(feedOutsideOwner(Resolved(ownKey), financeLedger), "an owner=self key did not create that pipeline")
        // would not be refused
        assert(!feedOutsideOwner(Resolved(rita), salesOrders))
        assert(!feedOutsideOwner(Resolved(salesKey), salesOrders))
        assert(!feedOutsideOwner(Resolved(ownKey), ownPipeline), "an owner=self key feeds what it created")
        assert(!feedOutsideOwner(Resolved(admin), financeLedger))
        assert(!feedOutsideOwner(Resolved(editor), financeLedger))
        assert(!feedOutsideOwner(Resolved(fullKey), financeLedger))
        assert(!feedOutsideOwner(Full, financeLedger))
        assert(!feedOutsideOwner(Resolved(rita), null), "a target not stored is rule 5's case, not this one")
    }

    test("rule 4 (owner no longer resolves): reported only for an unresolved owner") {
        assert(ownerUnresolved(Unresolved))
        assert(!ownerUnresolved(Full))
        assert(!ownerUnresolved(Resolved(rita)))
        assert(!ownerUnresolved(Resolved(admin)))
    }

    test("rule 5 (save of a tap whose target does not exist): reported for a key whose loads are checked") {
        val early = tap("t", "sales-agent", "not-created-yet")
        assert(saveTargetMissing(Some(salesKey), early, null))
        assert(saveTargetMissing(Some(ownKey), early, null))
        // would not be refused
        assert(!saveTargetMissing(Some(salesKey), tap("t", "sales-agent", "sales-orders"), salesOrders), "the target exists")
        assert(!saveTargetMissing(Some(fullKey), early, null), "full access is never load-checked")
        assert(!saveTargetMissing(Some(admin), early, null))
        assert(!saveTargetMissing(Some(editor), early, null), "an unrestricted editor holds unscoped pipeline run and read")
        assert(!saveTargetMissing(None, early, null), "no identity (auth off)")
        assert(!saveTargetMissing(Some(salesKey), tap("t", "sales-agent", null), null), "no target at all")
        assert(!saveTargetMissing(Some(salesKey), tap("t", "sales-agent", "  "), null), "a blank target")
    }

    test("rule 6 (script upload): reported exactly when the save-time target rule (targetPipelineRefusal) would refuse the stored target") {
        val cases: Seq[(Option[ResolvedKey], TapConfig, Boolean)] = Seq(
            (Some(salesKey), tap("t", "sales-agent", "finance-ledger"), true),
            (Some(ownKey), tap("t", "own-agent", "finance-ledger"), true),
            (Some(rita), tap("t", "session:rita", "finance-ledger"), true),
            (Some(rita), tap("t", "session:rita", "not-created-yet"), true), // a restricted session's target must exist and be in its grants
            // would not be refused
            (Some(salesKey), tap("t", "sales-agent", "sales-orders"), false),
            (Some(ownKey), tap("t", "own-agent", "own-pipe"), false),
            (Some(rita), tap("t", "session:rita", "sales-orders"), false),
            (Some(salesKey), tap("t", "sales-agent", "not-created-yet"), false), // left to the save (rule 5)
            (Some(fullKey), tap("t", "root", "finance-ledger"), false),
            (Some(editor), tap("t", "session:ed", "finance-ledger"), false),
            (None, tap("t", null, "finance-ledger"), false),
            (Some(salesKey), tap("t", "sales-agent", null), false)
        )
        for ((caller, t, expected) <- cases) {
            val target = Option(t.targetPipeline).map(readPipeline).orNull
            assert(scriptTargetRefused(caller, t, target) == expected, s"${caller.map(_.label)} -> ${t.targetPipeline}")
            // the same rule the save path applies today, so the next release's refusal is the one reported here
            val request = ScopeDenialFixtures.request(caller.orNull, "POST", "/api/v1/tap/script")._1
            assert(
                TapAPIController.targetPipelineRefusal(request, t, readPipeline).isDefined == expected,
                s"targetPipelineRefusal ${caller.map(_.label)} -> ${t.targetPipeline}"
            )
        }
    }
}
