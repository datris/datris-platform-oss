package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.{CallerVisibility, CapabilityScopes, TapPrincipal, TapRunTokens}
import ai.datris.auth.TapPrincipal.{Resolution, Resolved, Unresolved}
import ai.datris.model.{DatrisEnvironment, PipelineConfig, ResolvedKey, TapConfig, User}

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

/** Which stored taps will read or feed less once taps are held to their
  * owner's access (plans/stories/tap-runs-hold-owner-scope.md). Report only:
  * nothing here refuses, filters or changes a run, save or read. Backs the
  * `tap.owner_scope` doctor check and the run-time report lines
  * ([[TapOwnerScopeReport]]).
  *
  * The owner of a tap is its `createdByKeyLabel` ([[TapPrincipal]]); the last
  * editor is the actor of the tap's latest version. "Limited" is
  * [[CallerVisibility.limited]]; a load into a stored pipeline is decided by
  * [[CallerVisibility.loadChecked]] and [[CallerVisibility.loadDenial]], the
  * rule `TapAPIController.targetPipelineRefusal` applies to a caller today.
  * An identity "narrows reads" ([[narrowsReads]]) when it is limited or when
  * it lacks some read the run token carries ([[TapRunTokens.Capabilities]]),
  * because the next release gives a run token only what the identity holds.
  * Catalog placeholder taps ([[CatalogOps.Placeholder]]) are never reported. */
object TapOwnerScopeScan {

    /** The owner is limited and may not load into the stored target (rule 3). */
    val FeedOutsideOwner = "feedOutsideOwner"

    /** The owner is limited and the target pipeline is not stored (rule 5's case). */
    val TargetMissing = "targetMissing"

    /** The owner no longer resolves: a revoked or unknown key, a deleted user (rule 4). */
    val OwnerUnresolved = "ownerUnresolved"

    /** The owner or the last editor narrows reads and the tap's last run read
      * platform data through the callback (rule 2). */
    val ReadsNarrowed = "readsNarrowed"

    /** `owner` is the tap's `createdByKeyLabel` as stored (may be null);
      * `pipeline` its `targetPipeline` (may be null). `reason`, on a
      * [[ReadsNarrowed]] finding, says in plain words why the reads narrow
      * ("its owner cannot query data", "its last editor 'x' is limited to
      * catalog Sales"); null otherwise. */
    case class Finding(group: String, tap: String, pipeline: String, owner: String, reason: String = null)

    /** `unreadable`: names of taps that could not be checked (an owner,
      * last-editor or pipeline read failed), so "could not check" is never
      * "nothing to report". */
    case class Result(findings: List[Finding], unreadable: List[String])

    object Result {
        val empty: Result = Result(Nil, Nil)
    }

    // ------------------------------------------------------------ what an identity lacks

    /** Per resource of the run token: what an identity that holds none of it
      * cannot do, and what one that holds part of it can do. */
    private val ShortfallWords: Map[String, (String, String)] = Map(
        "query" -> ("cannot query data", "cannot run every kind of query"),
        "search" -> ("cannot run vector search", "can run vector search only on some collections"),
        "metadata" -> ("cannot read metadata", "can read only some metadata"),
        "pipeline" -> ("cannot read pipelines", "can read only some pipelines"),
        "tap" -> ("cannot read taps", "can read only some taps")
    )

    private def actionsOf(resource: String, action: String): Seq[String] =
        if (action == "*") CapabilityScopes.Vocabulary.toMap.getOrElse(resource, Nil) else Seq(action)

    /** Plain-word phrases for each read the run token carries
      * ([[TapRunTokens.Capabilities]]) that `rk` does not hold unscoped, in
      * token order; empty for full access and for an identity holding every
      * one. A partly held query names the kinds it lacks. */
    def tokenShortfall(rk: ResolvedKey): List[String] =
        if (rk == null || rk.isFullAccess) Nil
        else
            TapRunTokens.Capabilities.toList.flatMap { c =>
                val actions = actionsOf(c.resource, c.action)
                val missing = actions.filterNot(a => CallerVisibility.holdsUnscoped(rk, c.resource, a))
                if (missing.isEmpty) None
                else {
                    val (none, some) = ShortfallWords.getOrElse(c.resource, ("cannot " + c.raw, "holds only part of " + c.raw))
                    val heldAny = actions.exists(a => CallerVisibility.holdsAny(rk, c.resource, a))
                    if (!heldAny) Some(none)
                    else if (c.action == "*") Some(some + " (" + missing.mkString(", ") + ")")
                    else Some(some)
                }
            }

    /** How `rk` is limited, in plain words, or None when it is not limited. */
    def limitedWords(rk: ResolvedKey): Option[String] =
        if (rk == null || !CallerVisibility.limited(rk)) None
        else if (rk.restrictedSession) Some("is limited to the catalogs it is granted")
        else {
            val scoped = (rk.capabilities ++ rk.inertCapabilities).filter(c => c.resource == "pipeline" || c.resource == "tap")
            val catalogs = scoped.flatMap(_.scope.get("catalog")).distinct.sorted
            val own = scoped.exists(_.scope.get("owner").contains("self"))
            val inCatalogs =
                if (catalogs.isEmpty) None
                else Some((if (catalogs.size == 1) "catalog " else "catalogs ") + catalogs.mkString(", "))
            val parts = inCatalogs.toList ++ (if (own) List("what it created") else Nil)
            Some("is limited to " + (if (parts.isEmpty) "part of the installation" else parts.mkString(" and to ")))
        }

    /** True when a run token held to `rk` would read less than today's
      * token: `rk` is limited, or lacks some read the token carries. */
    def narrowsReads(rk: ResolvedKey): Boolean = rk != null && (CallerVisibility.limited(rk) || tokenShortfall(rk).nonEmpty)

    /** Why `rk` narrows reads, in plain words without the subject ("is
      * limited to catalog Sales", "cannot query data, cannot read
      * metadata"), or None. The limited case wins. */
    def narrowingWords(rk: ResolvedKey): Option[String] =
        limitedWords(rk).orElse {
            val s = tokenShortfall(rk)
            if (s.isEmpty) None else Some(s.mkString(", "))
        }

    // ------------------------------------------------------------ the six rules

    private def limited(r: Resolution): Boolean = r match {
        case Resolved(rk) => rk != null && CallerVisibility.limited(rk)
        case _ => false
    }

    private def narrows(r: Resolution): Boolean = r match {
        case Resolved(rk) => narrowsReads(rk)
        case _ => false
    }

    private def hasTarget(t: TapConfig): Boolean = t != null && t.targetPipeline != null && t.targetPipeline.trim.nonEmpty

    private def contextOf(p: PipelineConfig): Map[String, String] = CatalogOps.scopeContext(p.catalog, p.createdByKeyLabel)

    /** True for a catalog placeholder tap, which is never reported. */
    def placeholder(t: TapConfig): Boolean = t != null && CatalogOps.Placeholder.is(t.name)

    /** Rule 1 (test and manual run): the caller narrows reads
      * ([[narrowsReads]]) and the script read platform data through the
      * callback. */
    def callerReadsNarrowed(caller: Option[ResolvedKey], usedCallback: Boolean): Boolean =
        usedCallback && caller.exists(narrowsReads)

    /** Rule 2 (unattended run): the owner or the last editor narrows reads
      * and the last run read platform data through the callback. */
    def unattendedReadsNarrowed(owner: Resolution, lastEditor: Resolution, usedCallback: Boolean): Boolean =
        usedCallback && (narrows(owner) || narrows(lastEditor))

    /** Rule 3 (unattended feed): the owner is limited and may not load into
      * the stored target. A target not stored (null) is not this rule. */
    def feedOutsideOwner(owner: Resolution, target: PipelineConfig): Boolean =
        owner match {
            case Resolved(rk) if rk != null && target != null && CallerVisibility.limited(rk) && CallerVisibility.loadChecked(rk) =>
                CallerVisibility.loadDenial(rk, contextOf(target)).isDefined
            case _ => false
        }

    /** Rule 4: the owner no longer resolves. */
    def ownerUnresolved(owner: Resolution): Boolean = owner == Unresolved

    /** Rule 5 (save): an API key whose loads are checked saves a tap whose
      * target is named but not stored (`target` null). A restricted session
      * is already refused that save today, so it is not this rule. */
    def saveTargetMissing(caller: Option[ResolvedKey], tap: TapConfig, target: PipelineConfig): Boolean =
        hasTarget(tap) && target == null && caller.exists(rk => rk != null && !rk.restrictedSession && CallerVisibility.loadChecked(rk))

    /** Rule 6 (script upload): the save-time target rule
      * (`TapAPIController.targetPipelineRefusal`) would refuse the stored
      * target for this caller. Pure: no request, no audit note. A restricted
      * session: the target is missing or its grants do not allow pipeline read
      * and run on it. An API key whose loads are checked: the stored target
      * is refused by [[CallerVisibility.loadDenial]]; a target not stored is
      * left to the save (rule 5). */
    def scriptTargetRefused(caller: Option[ResolvedKey], tap: TapConfig, target: PipelineConfig): Boolean =
        hasTarget(tap) && caller.exists { rk =>
            if (rk == null) false
            else if (rk.restrictedSession)
                target == null || !rk.grants("pipeline", "read", contextOf(target)) || !rk.grants("pipeline", "run", contextOf(target))
            else target != null && CallerVisibility.loadChecked(rk) && CallerVisibility.loadDenial(rk, contextOf(target)).isDefined
        }

    // ------------------------------------------------------------ the scan

    private def notNull[T](what: String, v: T): Try[T] =
        if (v == null) Failure(new IllegalStateException(what + " read answered nothing")) else Success(v)

    private def q(s: String): String = "'" + s + "'"

    /** Pure. `readPipeline`: null = not stored (may throw); `resolveOwner`: an
      * owner or last-editor label to its [[TapPrincipal.Resolution]] (may
      * throw); `lastEditor`: tap name to the actor label of the tap's latest
      * version, or null (may throw); `narrowingPossible`: whether any
      * identity of the install narrows reads, asked at most once and only
      * when a last editor would be looked up (a throw counts as true). Each
      * distinct label is resolved and each distinct pipeline read at most
      * once per scan; a pipeline is read only for a tap whose owner is
      * limited, and the last editor only for a tap whose last run used the
      * callback, whose owner does not narrow reads, and when
      * `narrowingPossible` is true (otherwise no last editor can narrow
      * anything). Catalog placeholder taps are skipped. Findings in tap
      * order; a tap with a failed read is listed under `unreadable` and in no
      * group. */
    def scan(
        taps: List[TapConfig],
        readPipeline: String => PipelineConfig,
        resolveOwner: String => TapPrincipal.Resolution,
        lastEditor: String => String,
        narrowingPossible: () => Boolean = () => true
    ): Result = {
        val labels = mutable.HashMap[Option[String], Try[Resolution]]()
        val pipelines = mutable.HashMap[String, Try[Option[PipelineConfig]]]()
        lazy val anyNarrowing: Boolean = Try(narrowingPossible()).getOrElse(true)
        def principal(label: String): Try[Resolution] =
            if (label == null || label.trim.isEmpty) Success(TapPrincipal.Full)
            else labels.getOrElseUpdate(Some(label), Try(resolveOwner(label)).flatMap(r => notNull("owner", r)))
        def pipeline(name: String): Try[Option[PipelineConfig]] =
            pipelines.getOrElseUpdate(name, Try(Option(readPipeline(name))))

        val findings = mutable.ListBuffer[Finding]()
        val unreadable = mutable.ListBuffer[String]()
        Option(taps).getOrElse(Nil).filter(t => t != null && !placeholder(t)).foreach { t =>
            def finding(group: String, reason: String = null) = Finding(group, t.name, t.targetPipeline, t.createdByKeyLabel, reason)
            val checked: Try[List[Finding]] = principal(t.createdByKeyLabel).flatMap {
                case Unresolved => Success(List(finding(OwnerUnresolved)))
                case owner =>
                    val feed: Try[List[Finding]] =
                        if (!hasTarget(t) || !limited(owner)) Success(Nil)
                        else
                            pipeline(t.targetPipeline).map {
                                case None => List(finding(TargetMissing))
                                case Some(p) => if (feedOutsideOwner(owner, p)) List(finding(FeedOutsideOwner)) else Nil
                            }
                    val ownerWords = owner match {
                        case Resolved(rk) => narrowingWords(rk)
                        case _ => None
                    }
                    val reads: Try[List[Finding]] =
                        if (!t.lastRunUsedCallback) Success(Nil)
                        else if (ownerWords.isDefined) Success(List(finding(ReadsNarrowed, "its owner " + ownerWords.get)))
                        else if (!anyNarrowing) Success(Nil)
                        else {
                            Try(lastEditor(t.name)).flatMap { label =>
                                principal(label).map {
                                    case Resolved(rk) if narrowsReads(rk) =>
                                        List(finding(ReadsNarrowed, "its last editor " + q(label) + " " + narrowingWords(rk).getOrElse("")))
                                    case _ => Nil
                                }
                            }
                        }
                    for (f <- feed; r <- reads) yield f ++ r
            }
            checked match {
                case Success(fs) => findings ++= fs
                case Failure(_) => unreadable += t.name
            }
        }
        Result(findings.toList, unreadable.toList)
    }

    // ------------------------------------------------------------ live wiring

    /** Version actors that name no identity (VersionActor's fallbacks and the
      * pre-versioning seed). */
    private val NoActor = Set("system", "agent")

    /** The owner-style label of a version actor: `session:<user>` stays; a
      * bare username of a stored user (VersionActor stamps the signed-in
      * username) becomes `session:<user>`; the fallbacks are null; anything
      * else is an API key label. */
    private[util] def editorLabel(actor: String, userExists: String => Boolean): String =
        if (actor == null || actor.trim.isEmpty || NoActor.contains(actor)) null
        else if (actor.startsWith("session:")) actor
        else if (userExists(actor)) "session:" + actor
        else actor

    /** The stores a scan reads, so specs can count the reads. */
    trait Stores {
        def taps(): List[TapConfig]
        def users(): List[User]
        def keys(): APIKeyValidator.KeyDirectory
        def readPipeline(name: String): PipelineConfig

        /** The `createdBy` of the tap's newest version (None: no version). */
        def latestAuthor(tapName: String): Option[String]
    }

    object LiveStores extends Stores {
        private def env = DatrisEnvironment.current
        def taps(): List[TapConfig] = TapConfigIO.readAll(env.tapTableName)
        def users(): List[User] = UserStore.list()
        def keys(): APIKeyValidator.KeyDirectory = APIKeyValidator.keyDirectory()
        def readPipeline(name: String): PipelineConfig = PipelineConfigIO.read(env.pipelineTableName, name)
        def latestAuthor(tapName: String): Option[String] = EntityVersionIO.latestAuthor(env.tapVersionTableName, tapName)
    }

    /** Scan every stored tap. Listing the taps throws (the doctor check turns
      * that into a "could not check" warning). Store reads, each at most once
      * per scan and only when a tap needs it: the tap list; the user list (one
      * read, on the first `session:` label or the first last-editor lookup);
      * the API key store and its metadata (one read each, on the first key
      * label or the first last-editor lookup, [[APIKeyValidator.keyDirectory]]);
      * each distinct target of a tap whose owner is limited; and, only when
      * some user or key of the install narrows reads, the newest version's
      * author (one document, one field) of each tap whose last run used the
      * callback and whose owner does not narrow reads. */
    def liveScan(): Result = scanWith(LiveStores)

    private[util] def scanWith(stores: Stores): Result = {
        val taps = stores.taps()
        // One user-list read on first use; a failure is kept (not retried per label).
        lazy val userList: Try[Map[String, User]] =
            Try(stores.users().filter(_ != null).map(u => UserStore.normalize(u.username) -> u).toMap)
        def users: Map[String, User] = userList.get
        val keys = stores.keys()
        val findUser: String => Option[User] = n => users.get(UserStore.normalize(n))
        def narrowingPossible(): Boolean =
            users.values.exists(u => narrowsReads(APIKeyValidator.resolveFromSession(u))) || keys.all().exists(narrowsReads)
        scan(
            taps,
            stores.readPipeline,
            label => TapPrincipal.resolveWith(label, findUser, keys.byLabel),
            name => stores.latestAuthor(name).map(a => editorLabel(a, n => users.contains(UserStore.normalize(n)))).orNull,
            () => narrowingPossible()
        )
    }
}
