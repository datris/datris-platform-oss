package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Capability, ResolvedKey, User}
import ai.datris.util.{APIKeyValidator, CatalogOps}

import scala.collection.JavaConverters._

/** Catalog-scoped user access: pure functions turning a restricted user's
  * catalog grants (catalog -> view|edit|own) into a capability bundle, and
  * reading those grants back off a resolved session.
  *
  * A restricted bundle holds `pipeline:<a>:catalog=<c>` and `tap:<a>:catalog=<c>`
  * for each granted catalog, intersected with what the role allows, plus the
  * role's non-catalog capabilities minus the surfaces that cannot be
  * catalog-filtered (free SQL / Mongo / natural-language queries and table
  * metadata). Uncataloged items (blank catalog) never match a `catalog=` scope,
  * so a restricted user never sees them. */
object CatalogGrants {

    private val CatalogResources: Seq[String] = Seq("pipeline", "tap")

    /** Role capabilities a restricted bundle drops: free-form queries and
      * table listings cannot be narrowed to a catalog. */
    val RefusedForRestricted: Set[(String, String)] =
        Set("query" -> "postgres", "query" -> "mongodb", "query" -> "natural", "metadata" -> "read")

    /** Actions a grant level allows on the catalog's pipelines and taps. */
    def levelActions(level: String): Seq[String] = level match {
        case User.GrantView => Seq("read")
        case User.GrantEdit => Seq("read", "create", "update", "run")
        case User.GrantOwn => Seq("read", "create", "update", "run", "delete")
        case _ => Seq.empty
    }

    /** Grants as an ordered Scala map; null -> empty. Invalid names or levels are skipped. */
    def grantsOf(grants: java.util.Map[String, String]): Seq[(String, String)] =
        if (grants == null) Seq.empty
        else
            grants.asScala.toSeq.filter { case (c, l) =>
                c != null && !CatalogOps.isReserved(c) && l != null && User.ValidGrantLevels.contains(l)
            }

    def capabilitiesFor(role: String, grants: java.util.Map[String, String]): Seq[Capability] = {
        if (role == User.RoleAdmin) return Seq(Capability.FullAccess)
        val bundle = APIKeyValidator.roleToCapabilities(role)
        val scoped = for {
            (catalog, level) <- grantsOf(grants)
            resource <- CatalogResources
            action <- levelActions(level)
            if bundle.exists(_.matchesResourceAction(resource, action))
        } yield Capability.parse(resource + ":" + action + ":catalog=" + catalog)
        val carried = bundle.filterNot { c =>
            CatalogResources.contains(c.resource) || RefusedForRestricted.contains(c.resource -> c.action)
        }
        scoped ++ carried
    }

    /** Catalogs the session holds a `catalog=` scope for, with the actions held. */
    private def catalogActions(rk: ResolvedKey): Seq[(String, Set[String])] = {
        val pairs = rk.capabilities.filter(_.resource == "pipeline").flatMap(c => c.scope.get("catalog").map(_ -> c.action))
        pairs.map(_._1).distinct.map(c => c -> pairs.filter(_._1 == c).map(_._2).toSet)
    }

    /** Catalogs the restricted session can read (view, edit or own). */
    def visibleCatalogs(rk: ResolvedKey): Seq[String] = catalogActions(rk).filter(_._2.contains("read")).map(_._1)

    /** Catalogs the restricted session can create into (edit or own). */
    def editableCatalogs(rk: ResolvedKey): Seq[String] = catalogActions(rk).filter(_._2.contains("create")).map(_._1)

    /** Catalogs the restricted session owns (delete/move/rename). */
    def ownedCatalogs(rk: ResolvedKey): Seq[String] = catalogActions(rk).filter(_._2.contains("delete")).map(_._1)

    /** The catalog a blank-catalog create lands in. Right(None) for an
      * unrestricted identity (no defaulting), Right(Some(c)) when exactly one
      * catalog is editable, Left(refusal) otherwise. */
    def defaultCatalogFor(rk: ResolvedKey): Either[String, Option[String]] = {
        if (rk == null || !rk.restrictedSession) return Right(None)
        editableCatalogs(rk) match {
            case Seq(one) => Right(Some(one))
            case Seq() => Left("No catalog you can edit")
            case several => Left("Choose a catalog: " + several.mkString(", "))
        }
    }

    /** A copy of `grants` with `old` renamed to `newName` (order kept). */
    def renameIn(grants: java.util.Map[String, String], old: String, newName: String): java.util.Map[String, String] = {
        val out = new java.util.LinkedHashMap[String, String]()
        if (grants != null) grants.asScala.foreach { case (k, v) => out.put(if (k == old) newName else k, v) }
        out
    }

    /** A copy of `grants` without `name`. */
    def removeFrom(grants: java.util.Map[String, String], name: String): java.util.Map[String, String] = {
        val out = new java.util.LinkedHashMap[String, String]()
        if (grants != null) grants.asScala.foreach { case (k, v) => if (k != name) out.put(k, v) }
        out
    }

    /** Validate a grants map from a request: Left(error) or Right(cleaned copy). */
    def validate(grants: java.util.Map[String, String]): Either[String, java.util.Map[String, String]] = {
        val out = new java.util.LinkedHashMap[String, String]()
        if (grants != null) grants.asScala.foreach { case (c, l) =>
            if (c == null || !CatalogOps.isValidLabel(c)) return Left("Invalid catalog name: " + c)
            if (CatalogOps.isReserved(c)) return Left("Catalog '" + c + "' is reserved and cannot be granted")
            if (l == null || !User.ValidGrantLevels.contains(l))
                return Left("Invalid grant level for '" + c + "': " + l + " (expected view, edit or own)")
            out.put(c, l)
        }
        Right(out)
    }
}
