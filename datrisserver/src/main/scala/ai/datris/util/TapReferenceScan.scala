package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.api.{TapAPIController, TapReferenceRules}
import ai.datris.auth.TapPrincipal
import ai.datris.auth.TapPrincipal.{Resolution, Resolved}
import ai.datris.model.{DatrisEnvironment, TapConfig, User}

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

/** The stored taps and keys a later release holds to the script reference,
  * secret and review rules (plans/stories/tap-save-references-in-scope.md,
  * rules 4-8). Report only: nothing here refuses or changes anything. Backs
  * the `tap.references` doctor row. Catalog placeholder taps are skipped. */
object TapReferenceScan {

    /** A tap whose script sits outside the tap script area (rule 6): a
      * built-in script outside `tap-scripts/`, or a repository script
      * outside the current repository tap path. */
    val OutsideArea = "outsideArea"

    /** A tap stored in a code repository whose stored path is not its own
      * file (`<prefix><tap>.py` for the current or a recorded earlier prefix,
      * [[TapScriptOwnership.ownRepo]]): deleting it leaves the file in place,
      * and a later release stores its next upload at its own path. */
    val NotOwnScript = "notOwnScript"

    /** One script shared by taps of different catalogs or owners (rule 5). */
    val SharedScript = "sharedScript"

    /** The tap's owner has a scoped tap read that does not cover its secret (rules 7 and 8). */
    val SecretOutsideOwner = "secretOutsideOwner"

    /** An identity that used review without tap update since the server started (rule 4). */
    val ReviewWithoutUpdate = "reviewWithoutUpdate"

    /** `name`: the tap, or the key or user label for [[ReviewWithoutUpdate]]. */
    case class Finding(group: String, name: String, detail: String = null)

    /** `unreadable`: taps that could not be checked (an owner or secret
      * lookup failed), so "could not check" is never "nothing to report". */
    case class Result(findings: List[Finding], unreadable: List[String])

    object Result {
        val empty: Result = Result(Nil, Nil)
    }

    private def placeholder(t: TapConfig): Boolean = CatalogOps.Placeholder.is(t.name)

    /** The script a tap runs, as comparable keys: a built-in key as stored;
      * a repository path normalised, so two taps on one file share a key
      * ([[TapReferenceRules.sameRepoScript]]). */
    private def scriptKeys(t: TapConfig): Seq[String] =
        TapReferenceRules.effectiveBuiltIn(t).map(p => Seq("builtin:" + p)).orElse {
            TapReferenceRules.effectiveRepo(t).map(p => Seq("repo:" + TapReferenceRules.normalizedRef(p)))
        }.getOrElse(Nil)

    /** The groups of `taps` that run one script: taps sharing any key of
      * [[scriptKeys]], joined transitively. */
    private def scriptGroups(all: List[TapConfig]): Iterable[List[TapConfig]] = {
        val taps = all.toVector
        val parent = Array.tabulate(taps.size)(identity)
        def root(i: Int): Int = if (parent(i) == i) i else { parent(i) = root(parent(i)); parent(i) }
        val firstWith = mutable.HashMap[String, Int]()
        taps.zipWithIndex.foreach { case (t, i) =>
            scriptKeys(t).foreach { k =>
                firstWith.get(k) match {
                    case Some(j) => parent(root(i)) = root(j)
                    case None => firstWith(k) = i
                }
            }
        }
        taps.indices.filter(i => scriptKeys(taps(i)).nonEmpty).groupBy(root).values.map(_.map(taps).toList)
    }

    /** Pure. `resolveOwner`: an owner label to its [[TapPrincipal.Resolution]]
      * (may throw); `secretOwner`: a secret's owner (outer None: no such
      * secret; may throw); `reviewKeys`: labels from [[TapReviewAccessLog]];
      * `repoPathPrefix`: the enabled code repository's path prefix ("" for
      * none; may throw) and `repoEarlierPrefixes`: its recorded earlier path
      * prefixes (may throw), each read at most once and only when a tap
      * stores its script in a repository. `ownPrefixes`, when given, is what
      * the [[NotOwnScript]] group is judged against instead: the stored
      * repository configuration's prefix and earlier prefixes, whether or not
      * the repository is enabled (None: no stored configuration, and the
      * group is skipped; may throw), read at most once. An owner is resolved, and a secret read, only
      * for a tap with a secret, each distinct label and secret at most once
      * per scan; a failed lookup lists the tap under `unreadable`. Findings
      * in group order, taps in the order given. */
    def scan(
        taps: List[TapConfig],
        resolveOwner: String => TapPrincipal.Resolution,
        secretOwner: String => Option[Option[String]],
        reviewKeys: List[String],
        repoPathPrefix: () => String = () => "",
        repoEarlierPrefixes: () => Seq[String] = () => Nil,
        ownPrefixes: () => Option[Seq[String]] = null
    ): Result = {
        val live = Option(taps).getOrElse(Nil).filter(t => t != null && t.name != null && !placeholder(t))
        val unreadable = mutable.ListBuffer[String]()
        def markUnreadable(name: String): Unit = if (!unreadable.contains(name)) unreadable += name

        lazy val prefix: Try[String] = Try(Option(repoPathPrefix()).getOrElse(""))
        val outside = live.flatMap { t =>
            if (TapReferenceRules.effectiveBuiltIn(t).exists(p => !TapReferenceRules.builtInInArea(p))) Some(Finding(OutsideArea, t.name))
            else
                TapReferenceRules.effectiveRepo(t) match {
                    case None => None
                    case Some(p) =>
                        prefix match {
                            case Success(pre) => if (TapReferenceRules.repoInArea(p, pre)) None else Some(Finding(OutsideArea, t.name))
                            case Failure(_) =>
                                markUnreadable(t.name)
                                None
                        }
                }
        }

        lazy val earlier: Try[Seq[String]] = Try(Option(repoEarlierPrefixes()).getOrElse(Nil).filter(_ != null))
        lazy val judgedAgainst: Try[Option[Seq[String]]] =
            if (ownPrefixes == null) for (pre <- prefix; old <- earlier) yield Some(pre +: old)
            else Try(Option(ownPrefixes()).flatten.map(_.filter(_ != null)))
        val notOwn = live.flatMap { t =>
            TapReferenceRules.effectiveRepo(t).flatMap { p =>
                judgedAgainst match {
                    case Success(None) => None
                    case Success(Some(prefixes)) => if (TapScriptOwnership.ownRepo(t.name, p, prefixes)) None else Some(Finding(NotOwnScript, t.name))
                    case Failure(_) =>
                        markUnreadable(t.name)
                        None
                }
            }
        }

        val sharedNames: Set[String] = scriptGroups(live)
            .filter(g => g.map(t => (t.catalog, t.createdByKeyLabel)).distinct.size > 1)
            .flatMap(_.map(_.name))
            .toSet
        val shared = live.filter(t => sharedNames.contains(t.name)).map(t => Finding(SharedScript, t.name))

        val principals = mutable.HashMap[String, Try[Resolution]]()
        val owners = mutable.HashMap[String, Try[Option[Option[String]]]]()
        def principal(label: String): Try[Resolution] =
            if (label == null || label.trim.isEmpty) Success(TapPrincipal.Full)
            else
                principals.getOrElseUpdate(
                    label,
                    Try(resolveOwner(label)).flatMap(r => if (r == null) Failure(new IllegalStateException("owner lookup answered nothing")) else Success(r))
                )
        def ownerOf(secret: String): Try[Option[Option[String]]] = owners.getOrElseUpdate(secret, Try(secretOwner(secret)))

        val secretFindings = live.flatMap { t =>
            Option(t.secretName).map(_.trim).filter(_.nonEmpty) match {
                case None => None
                case Some(secret) =>
                    val checked: Try[Boolean] = principal(t.createdByKeyLabel).flatMap {
                        case Resolved(rk) if TapReferenceRules.scopedTapRead(rk) =>
                            ownerOf(secret).map(o => secretOutsideOwner(rk, t.catalog, () => o))
                        case _ => Success(false)
                    }
                    checked match {
                        case Success(true) => Some(Finding(SecretOutsideOwner, t.name))
                        case Success(false) => None
                        case Failure(_) =>
                            markUnreadable(t.name)
                            None
                    }
            }
        }

        val review = Option(reviewKeys).getOrElse(Nil).filter(l => l != null && l.nonEmpty).distinct.map(l => Finding(ReviewWithoutUpdate, l))
        Result(outside ++ notOwn ++ shared ++ secretFindings ++ review, unreadable.toList)
    }

    /** The [[SecretOutsideOwner]] rule for one tap in `catalog` owned by
      * `rk`: `rk`'s tap read is scoped and does not cover the tap's secret,
      * whose owner `secretOwner` reads (outer None: no such secret). The
      * secret is read only when `rk`'s tap read is scoped; a throw
      * propagates. */
    def secretOutsideOwner(rk: ai.datris.model.ResolvedKey, catalog: String, secretOwner: () => Option[Option[String]]): Boolean =
        rk != null && TapReferenceRules.scopedTapRead(rk) && (secretOwner() match {
            case Some(owner) => !rk.grants("tap", "read", CatalogOps.scopeContext(catalog, owner.orNull))
            case None => false
        })

    /** Scan every stored tap. Listing the taps throws (the doctor check turns
      * that into a "could not check" warning). Other reads, each at most once
      * per scan and only when a tap with a secret needs it: the user list (on
      * the first `session:` owner), the API key store and its metadata
      * ([[APIKeyValidator.keyDirectory]], on the first key owner), and each
      * distinct secret of a tap whose owner's tap read is scoped, and the
      * repository configuration, enabled for the script area group and as
      * stored for the not-own group, each once (on the first tap stored in a
      * repository). */
    def liveScan(): Result = {
        val env = DatrisEnvironment.current
        val taps = TapConfigIO.readAll(env.tapTableName)
        lazy val users: Try[Map[String, User]] = Try(UserStore.list().filter(_ != null).map(u => UserStore.normalize(u.username) -> u).toMap)
        lazy val keys = APIKeyValidator.keyDirectory()
        lazy val repo = Try(CodeRepoConfigIO.readEnabled())
        scan(
            taps,
            label => TapPrincipal.resolveWith(label, n => users.get.get(UserStore.normalize(n)), l => keys.byLabel(l)),
            n => TapAPIController.liveSecretOwner(n),
            TapReviewAccessLog.labels(),
            () => repo.get.map(c => TapReferenceRules.normalizePrefix(c.pathPrefix)).getOrElse(""),
            () => repo.get.map(TapScriptOwnership.earlierOf).getOrElse(Nil),
            () => storedOwnPrefixes(CodeRepoConfigIO.read())
        )
    }

    /** What the [[NotOwnScript]] group is judged against on a live install:
      * the stored repository configuration's prefix and recorded earlier
      * prefixes, whether or not the repository is enabled (both survive a
      * disable); None when nothing is stored. */
    def storedOwnPrefixes(stored: Option[ai.datris.model.CodeRepoConfig]): Option[Seq[String]] =
        stored.filter(_ != null).map(TapScriptOwnership.prefixesOf)
}
