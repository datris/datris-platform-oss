package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.CallerVisibility
import ai.datris.model.{ResolvedKey, TapConfig}
import ai.datris.util.{CatalogOps, MinioCodeStore}

import scala.util.Try

/** The script reference and secret rules of a tap save, test, version
  * restore, run and script generation
  * (plans/stories/tap-save-references-in-scope.md). Pure: every store read
  * comes through [[Lookups]], each made at most once per [[judge]] call and
  * only when a rule needs it.
  *
  * A "limited caller" is a restricted user's session or an API key limited
  * for taps ([[CallerVisibility.limitedTaps]]). A reference is "new or
  * changed" when the candidate carries a `scriptPath` or `scriptRepoPath`
  * that differs from the stored tap's; a candidate that carries neither keeps
  * the stored one (the save carries it over) and is unchanged.
  *
  * The tap script area: on built-in storage an object key under
  * `tap-scripts/` exactly (same case, no leading `/`, no `..` step, not the
  * area itself); in a code repository a path under the repository's path
  * prefix, not under its `pipelines/` folder, with no `..` step and nothing
  * that starts with `/` after the prefix.
  *
  * Rules and where they apply:
  *  - 3: a limited caller's new or changed reference must be in the tap
  *    script area and must not be the current script of a tap the caller may
  *    not read. [[Enforced]] on save and test; [[Reported]] on version
  *    restore.
  *  - 4: review by an identity holding no `tap:update`
  *    ([[reviewWouldBeRefused]]; reported at the review route).
  *  - 5: a limited caller's new or changed built-in reference names neither
  *    this tap (`tap-scripts/<tap>_...`) nor a tap it may read, and is not the
  *    current script of a tap it may read. Reported (save, test, restore).
  *  - 6: any caller: the built-in reference used (the candidate's, or the
  *    stored one it keeps) is outside the tap script area. Reported (save,
  *    test, restore, run).
  *  - 7: save and generate naming a secret that the caller's tap read does
  *    not cover (`tap:read` on the tap's catalog with the secret's owner as
  *    owner), for a caller whose tap read is scoped. Reported.
  *  - 8: a limited caller's run of a stored tap whose secret its tap read does
  *    not cover. Reported. */
object TapReferenceRules {

    sealed trait Mode
    case object Enforced extends Mode
    case object Reported extends Mode

    /** One rule that would refuse: `reason` names nothing of another tap. */
    case class Finding(rule: Int, mode: Mode, reason: String)

    /** `allTaps`: every stored tap (read only for a limited caller with a new
      * or changed reference). `secretOwner`: outer None = no such secret,
      * inner = its `createdByKeyLabel`. `repoPathPrefix`: the enabled code
      * repository's path prefix, "" for none. */
    case class Lookups(
        allTaps: () => Seq[TapConfig],
        secretOwner: String => Option[Option[String]],
        repoPathPrefix: () => String
    )

    val Save = "save"
    val Test = "test"
    val Restore = "restore"
    val Run = "run"
    val Generate = "generate"

    /** The built-in tap script area. */
    val TapArea: String = MinioCodeStore.TapPrefix

    private def nonEmpty(s: String): Option[String] = Option(s).filter(_.nonEmpty)

    private def hasParentStep(p: String): Boolean = p.split("/", -1).contains("..")

    /** True when `p` is an object key inside the built-in tap script area. */
    def builtInInArea(p: String): Boolean =
        p != null && p.startsWith(TapArea) && p.length > TapArea.length && !hasParentStep(p)

    /** `prefix` as the repository store writes it: "" or ending in `/`. */
    def normalizePrefix(prefix: String): String = {
        val p = Option(prefix).getOrElse("")
        if (p.isEmpty || p.endsWith("/")) p else p + "/"
    }

    /** True when `p` is a repository path inside the tap script area for
      * the path prefix `prefix`. */
    def repoInArea(p: String, prefix: String): Boolean = {
        val pre = normalizePrefix(prefix)
        p != null && p.startsWith(pre) && {
            val rest = p.substring(pre.length)
            rest.nonEmpty && !rest.startsWith("/") && !hasParentStep(p) && !rest.startsWith("pipelines/")
        }
    }

    /** The built-in script a tap runs: its `scriptPath` unless it is an HTTP
      * tap or stored in a code repository. */
    def effectiveBuiltIn(t: TapConfig): Option[String] =
        if (t == null || t.isHttp || t.scriptStorage == "github") None else nonEmpty(t.scriptPath)

    /** The repository script a tap runs, when it is stored in a code repository. */
    def effectiveRepo(t: TapConfig): Option[String] =
        if (t == null || t.isHttp || t.scriptStorage != "github") None else nonEmpty(t.scriptRepoPath)

    /** A restricted session, or an API key limited for taps. */
    def limited(rk: ResolvedKey): Boolean = rk != null && (rk.restrictedSession || CallerVisibility.limitedTaps(rk))

    /** A caller whose tap read is scoped (the callers `POST /tap/test`
      * checks a secret for): a restricted session, or an API key whose scope
      * can refuse a tap read. */
    def scopedTapRead(rk: ResolvedKey): Boolean =
        rk != null && (rk.restrictedSession || CallerVisibility.keyScopeApplies(rk, "tap", "read"))

    /** Rule 4: an identity holding no `tap:update` anywhere. False for no identity. */
    def reviewWouldBeRefused(caller: Option[ResolvedKey]): Boolean =
        caller.exists(rk => rk != null && !CallerVisibility.holdsAny(rk, "tap", "update"))

    private def mayRead(rk: ResolvedKey, t: TapConfig): Boolean =
        CallerVisibility.mayReadStored(Option(rk), "tap", t.catalog, t.createdByKeyLabel)

    private def namesTap(p: String, name: String): Boolean = name != null && name.nonEmpty && p.startsWith(TapArea + name + "_")

    /** The findings for `candidate` (the tap as sent, after the save's
      * catalog rules) over `stored` (the stored tap of that name, or null)
      * at `site`. Placeholder taps have none. A lookup a [[Reported]]
      * finding needs that fails drops that finding; a lookup rule 3 needs on
      * save or test that fails is thrown (the request fails as a failed
      * store read does). */
    def judge(caller: Option[ResolvedKey], site: String, candidate: TapConfig, stored: TapConfig, lookups: Lookups): Seq[Finding] = {
        if (candidate == null || CatalogOps.Placeholder.is(candidate.name)) return Nil
        val rk = caller.orNull
        val isLimited = limited(rk)
        val sets = site == Save || site == Test || site == Restore
        val carries = nonEmpty(candidate.scriptPath).isEmpty && nonEmpty(candidate.scriptRepoPath).isEmpty
        lazy val taps: Seq[TapConfig] =
            Option(lookups.allTaps()).getOrElse(Nil).filter(t => t != null && !CatalogOps.Placeholder.is(t.name))
        lazy val prefix: String = Option(lookups.repoPathPrefix()).getOrElse("")
        val owners = scala.collection.mutable.HashMap[String, Option[Option[String]]]()
        def ownerOf(secret: String): Option[Option[String]] = owners.getOrElseUpdate(secret, lookups.secretOwner(secret))
        val out = scala.collection.mutable.ListBuffer[Finding]()

        // Rules 3 and 5: a limited caller's new or changed reference.
        if (isLimited && sets && !candidate.isHttp && !carries) {
            val changedPath = nonEmpty(candidate.scriptPath).filter(p => stored == null || !nonEmpty(stored.scriptPath).contains(p))
            val changedRepo = nonEmpty(candidate.scriptRepoPath).filter(p => stored == null || !nonEmpty(stored.scriptRepoPath).contains(p))
            if (changedPath.isDefined || changedRepo.isDefined) {
                val mode3 = if (site == Restore) Reported else Enforced
                def outsideArea(): Boolean = changedPath.exists(p => !builtInInArea(p)) || changedRepo.exists(p => !repoInArea(p, prefix))
                val outside = if (mode3 == Enforced) outsideArea() else Try(outsideArea()).getOrElse(false)
                def hiddenScript(): Boolean =
                    taps.exists { t =>
                        !mayRead(rk, t) &&
                        (changedPath.exists(p => nonEmpty(t.scriptPath).contains(p)) || changedRepo.exists(p => nonEmpty(t.scriptRepoPath).contains(p)))
                    }
                val hidden =
                    if (outside) false
                    else if (mode3 == Enforced) hiddenScript()
                    else Try(hiddenScript()).getOrElse(false)
                if (outside)
                    out += Finding(3, mode3, "a new script reference outside the tap script area, by a caller limited to a catalog or to what it created")
                else if (hidden)
                    out += Finding(3, mode3, "a new script reference to the current script of a tap the caller may not read")
                else
                    changedPath.foreach { p =>
                        val known =
                            Try(namesTap(p, candidate.name) || taps.exists(t => mayRead(rk, t) && (namesTap(p, t.name) || nonEmpty(t.scriptPath).contains(p))))
                        if (known.isSuccess && !known.get)
                            out += Finding(5, Reported, "a new script reference that names neither this tap nor a tap the caller may read")
                    }
            }
        }

        // Rule 6: the built-in script used is outside the tap script area.
        if (sets || site == Run) {
            val used = if (carries && site != Restore) effectiveBuiltIn(stored) else effectiveBuiltIn(candidate)
            if (used.exists(p => !builtInInArea(p)) && !out.exists(_.mode == Enforced))
                out += Finding(6, Reported, "a script outside the tap script area")
        }

        // Rules 7 and 8: a secret the caller's tap read does not cover.
        val secret = Option(candidate.secretName).map(_.trim).filter(_.nonEmpty)
        val secretSite = site == Save || site == Generate || (site == Run && isLimited)
        if (secret.isDefined && secretSite && scopedTapRead(rk)) {
            val catalog = nonEmpty(candidate.catalog).orElse(Option(stored).flatMap(s => nonEmpty(s.catalog))).orNull
            Try(ownerOf(secret.get)).toOption.flatten.foreach { owner =>
                if (!rk.grants("tap", "read", CatalogOps.scopeContext(catalog, owner.orNull))) {
                    if (site == Run) out += Finding(8, Reported, "a run with a secret the caller's tap access does not cover")
                    else out += Finding(7, Reported, "a secret the caller's tap access does not cover")
                }
            }
        }
        out.toList
    }
}
