package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.CallerVisibility
import ai.datris.auth.TapPrincipal
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
  * rule `TapAPIController.targetPipelineRefusal` applies to a caller today. */
object TapOwnerScopeScan {

    /** The owner is limited and may not load into the stored target (rule 3). */
    val FeedOutsideOwner = "feedOutsideOwner"

    /** The owner is limited and the target pipeline is not stored (rule 5's case). */
    val TargetMissing = "targetMissing"

    /** The owner no longer resolves: a revoked or unknown key, a deleted user (rule 4). */
    val OwnerUnresolved = "ownerUnresolved"

    /** The owner or the last editor is limited and the tap's last run read
      * platform data through the callback (rule 2). */
    val ReadsNarrowed = "readsNarrowed"

    /** `owner` is the tap's `createdByKeyLabel` as stored (may be null);
      * `pipeline` its `targetPipeline` (may be null). */
    case class Finding(group: String, tap: String, pipeline: String, owner: String)

    /** `unreadable`: names of taps that could not be checked (an owner,
      * last-editor or pipeline read failed), so "could not check" is never
      * "nothing to report". */
    case class Result(findings: List[Finding], unreadable: List[String])

    object Result {
        val empty: Result = Result(Nil, Nil)
    }

    // ------------------------------------------------------------ the six rules

    private def limited(r: Resolution): Boolean = r match {
        case Resolved(rk) => rk != null && CallerVisibility.limited(rk)
        case _ => false
    }

    private def hasTarget(t: TapConfig): Boolean = t != null && t.targetPipeline != null && t.targetPipeline.trim.nonEmpty

    private def contextOf(p: PipelineConfig): Map[String, String] = CatalogOps.scopeContext(p.catalog, p.createdByKeyLabel)

    /** Rule 1 (test and manual run): the caller is limited and the script
      * read platform data through the callback. */
    def callerReadsNarrowed(caller: Option[ResolvedKey], usedCallback: Boolean): Boolean =
        usedCallback && caller.exists(rk => rk != null && CallerVisibility.limited(rk))

    /** Rule 2 (unattended run): the owner or the last editor is limited and
      * the last run read platform data through the callback. */
    def unattendedReadsNarrowed(owner: Resolution, lastEditor: Resolution, usedCallback: Boolean): Boolean =
        usedCallback && (limited(owner) || limited(lastEditor))

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

    /** Pure. `readPipeline`: null = not stored (may throw); `resolveOwner`: an
      * owner or last-editor label to its [[TapPrincipal.Resolution]] (may
      * throw); `lastEditor`: tap name to the actor label of the tap's latest
      * version, or null (may throw). Each distinct label is resolved and each
      * distinct pipeline read at most once per scan; a pipeline is read only
      * for a tap whose owner is limited, and the last editor only for a tap
      * whose last run used the callback and whose owner is not limited.
      * Findings in tap order; a tap with a failed read is listed under
      * `unreadable` and in no group. */
    def scan(
        taps: List[TapConfig],
        readPipeline: String => PipelineConfig,
        resolveOwner: String => TapPrincipal.Resolution,
        lastEditor: String => String
    ): Result = {
        val labels = mutable.HashMap[Option[String], Try[Resolution]]()
        val pipelines = mutable.HashMap[String, Try[Option[PipelineConfig]]]()
        def principal(label: String): Try[Resolution] =
            if (label == null || label.trim.isEmpty) Success(TapPrincipal.Full)
            else labels.getOrElseUpdate(Some(label), Try(resolveOwner(label)).flatMap(r => notNull("owner", r)))
        def pipeline(name: String): Try[Option[PipelineConfig]] =
            pipelines.getOrElseUpdate(name, Try(Option(readPipeline(name))))

        val findings = mutable.ListBuffer[Finding]()
        val unreadable = mutable.ListBuffer[String]()
        Option(taps).getOrElse(Nil).filter(_ != null).foreach { t =>
            def finding(group: String) = Finding(group, t.name, t.targetPipeline, t.createdByKeyLabel)
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
                    val reads: Try[List[Finding]] =
                        if (!t.lastRunUsedCallback) Success(Nil)
                        else if (limited(owner)) Success(List(finding(ReadsNarrowed)))
                        else
                            Try(lastEditor(t.name)).flatMap(principal).map { editor =>
                                if (unattendedReadsNarrowed(owner, editor, usedCallback = true)) List(finding(ReadsNarrowed)) else Nil
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

    /** Scan every stored tap. Listing the taps throws (the doctor check turns
      * that into a "could not check" warning). Store reads, each at most once
      * per scan and only when a tap needs it: the tap list; the user list (one
      * read, on the first `session:` label); the API key store and its
      * metadata (one read each, on the first key label,
      * [[APIKeyValidator.labelResolver]]); each distinct target of a tap
      * whose owner is limited; and the version list of each tap whose last
      * run used the callback and whose owner is not limited. */
    def liveScan(): Result = {
        val env = DatrisEnvironment.current
        val taps = TapConfigIO.readAll(env.tapTableName)
        // One user-list read on first use; a failure is kept (not retried per label).
        lazy val userList: Try[Map[String, User]] =
            Try(UserStore.list().filter(_ != null).map(u => UserStore.normalize(u.username) -> u).toMap)
        def users: Map[String, User] = userList.get
        val keyByLabel = APIKeyValidator.labelResolver()
        val findUser: String => Option[User] = n => users.get(UserStore.normalize(n))
        scan(
            taps,
            n => PipelineConfigIO.read(env.pipelineTableName, n),
            label => TapPrincipal.resolveWith(label, findUser, keyByLabel),
            name => {
                val versions = EntityVersionIO.listVersions(env.tapVersionTableName, name)
                if (versions.isEmpty) null
                else editorLabel(versions.maxBy(_.version).createdBy, a => users.contains(UserStore.normalize(a)))
            }
        )
    }
}
