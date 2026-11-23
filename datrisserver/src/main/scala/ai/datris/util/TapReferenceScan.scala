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

    /** The script a tap runs, as a comparable key (built-in or repository). */
    private def scriptKey(t: TapConfig): Option[String] =
        TapReferenceRules.effectiveBuiltIn(t).map("builtin:" + _).orElse(TapReferenceRules.effectiveRepo(t).map("repo:" + _))

    /** Pure. `resolveOwner`: an owner label to its [[TapPrincipal.Resolution]]
      * (may throw); `secretOwner`: a secret's owner (outer None: no such
      * secret; may throw); `reviewKeys`: labels from [[TapReviewAccessLog]];
      * `repoPathPrefix`: the enabled code repository's path prefix ("" for
      * none; may throw), read at most once and only when a tap stores its
      * script in a repository. An owner is resolved, and a secret read, only
      * for a tap with a secret, each distinct label and secret at most once
      * per scan; a failed lookup lists the tap under `unreadable`. Findings
      * in group order, taps in the order given. */
    def scan(
        taps: List[TapConfig],
        resolveOwner: String => TapPrincipal.Resolution,
        secretOwner: String => Option[Option[String]],
        reviewKeys: List[String],
        repoPathPrefix: () => String = () => ""
    ): Result = {
        val live = Option(taps).getOrElse(Nil).filter(t => t != null && t.name != null && !placeholder(t))
        val unreadable = mutable.ListBuffer[String]()

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
                                unreadable += t.name
                                None
                        }
                }
        }

        val sharedNames: Set[String] = live
            .flatMap(t => scriptKey(t).map(_ -> t))
            .groupBy(_._1)
            .values
            .filter(g => g.map { case (_, t) => (t.catalog, t.createdByKeyLabel) }.distinct.size > 1)
            .flatMap(_.map(_._2.name))
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
                            ownerOf(secret).map {
                                case Some(owner) => !rk.grants("tap", "read", CatalogOps.scopeContext(t.catalog, owner.orNull))
                                case None => false
                            }
                        case _ => Success(false)
                    }
                    checked match {
                        case Success(true) => Some(Finding(SecretOutsideOwner, t.name))
                        case Success(false) => None
                        case Failure(_) =>
                            if (!unreadable.contains(t.name)) unreadable += t.name
                            None
                    }
            }
        }

        val review = Option(reviewKeys).getOrElse(Nil).filter(l => l != null && l.nonEmpty).distinct.map(l => Finding(ReviewWithoutUpdate, l))
        Result(outside ++ shared ++ secretFindings ++ review, unreadable.toList)
    }

    /** Scan every stored tap. Listing the taps throws (the doctor check turns
      * that into a "could not check" warning). Other reads, each at most once
      * per scan and only when a tap with a secret needs it: the user list (on
      * the first `session:` owner), the API key store and its metadata
      * ([[APIKeyValidator.keyDirectory]], on the first key owner), and each
      * distinct secret of a tap whose owner's tap read is scoped, and the
      * repository configuration (on the first tap stored in a repository). */
    def liveScan(): Result = {
        val env = DatrisEnvironment.current
        val taps = TapConfigIO.readAll(env.tapTableName)
        lazy val users: Try[Map[String, User]] = Try(UserStore.list().filter(_ != null).map(u => UserStore.normalize(u.username) -> u).toMap)
        lazy val keys = APIKeyValidator.keyDirectory()
        scan(
            taps,
            label => TapPrincipal.resolveWith(label, n => users.get.get(UserStore.normalize(n)), l => keys.byLabel(l)),
            n => TapAPIController.liveSecretOwner(n),
            TapReviewAccessLog.labels(),
            () => TapAPIController.liveRepoPathPrefix()
        )
    }
}
