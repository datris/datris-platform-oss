package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Capability, User}
import ai.datris.util.APIKeyValidator
import ai.datris.util.InMemoryUserDb.grants
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Story: Catalog-scoped user access (server)
  * (plans/stories/catalog-scoped-user-access.md), Acceptance bullet 1.
  *
  * Pinned API (pure, no Mongo):
  * {{{
  * object CatalogGrants {
  *   // grants = User.catalogGrants (catalog -> view|edit|own); null = none
  *   def capabilitiesFor(role: String, grants: java.util.Map[String, String]): Seq[Capability]
  *   // Left = refusal text; Right(Some(c)) = the one editable catalog
  *   def defaultCatalogFor(rk: ResolvedKey): Either[String, Option[String]]
  *   def renameIn(grants: java.util.Map[String, String], old: String, newName: String): java.util.Map[String, String]
  * }
  * case class User(..., restricted: Boolean = false, catalogGrants: java.util.Map[String, String] = null)
  * }}}
  */
class CatalogGrantsSpec extends AnyFunSuite {

    private val PipelineTap = Set("pipeline", "tap")
    private val AllActions = Seq("read", "create", "update", "run", "delete")

    private def raw(caps: Seq[Capability]): Set[String] = caps.map(_.raw).toSet

    private def restricted(name: String, role: String, g: java.util.Map[String, String]): User =
        User(name, "h", role, null, null, null, restricted = true, catalogGrants = g)

    test("editor + {Sales: own} yields pipeline/tap read,create,update,run,delete scoped catalog=Sales and no unscoped pipeline/tap capability") {
        val caps = CatalogGrants.capabilitiesFor(User.RoleEditor, grants("Sales" -> "own"))
        val expected = for (r <- PipelineTap; a <- AllActions) yield s"$r:$a:catalog=Sales"
        val pt = caps.filter(c => PipelineTap.contains(c.resource))
        assert(raw(pt) == expected, s"pipeline/tap capabilities: ${raw(pt)}")
        assert(pt.forall(_.scope == Map("catalog" -> "Sales")), s"every pipeline/tap capability is catalog-scoped: ${raw(pt)}")
        assert(!caps.exists(c => c.resource == "*" || c.action == "*"), s"no wildcard: ${raw(caps)}")
        // Scope semantics: Sales grants, another catalog and Uncataloged (blank) do not.
        assert(caps.exists(_.grants("pipeline", "delete", Map("catalog" -> "Sales"), "session:bob")))
        assert(!caps.exists(_.grants("pipeline", "read", Map("catalog" -> "HR"), "session:bob")))
        assert(!caps.exists(_.grants("tap", "read", Map.empty, "session:bob")))
    }

    test("edit level gives read,create,update,run but not delete") {
        val caps = CatalogGrants.capabilitiesFor(User.RoleEditor, grants("Sales" -> "edit"))
        val pt = raw(caps.filter(c => PipelineTap.contains(c.resource)))
        assert(pt == (for (r <- PipelineTap; a <- Seq("read", "create", "update", "run")) yield s"$r:$a:catalog=Sales"), pt)
    }

    test("viewer + {Sales: own} is read-only") {
        val caps = CatalogGrants.capabilitiesFor(User.RoleViewer, grants("Sales" -> "own"))
        val pt = raw(caps.filter(c => PipelineTap.contains(c.resource)))
        assert(pt == Set("pipeline:read:catalog=Sales", "tap:read:catalog=Sales"), pt)
        Seq("create", "update", "run", "delete").foreach { a =>
            assert(!caps.exists(_.matchesResourceAction("pipeline", a)), s"viewer must not hold pipeline:$a")
            assert(!caps.exists(_.matchesResourceAction("tap", a)), s"viewer must not hold tap:$a")
        }
    }

    test("restricted bundle omits query:postgres, query:mongodb, query:natural, metadata:read") {
        val refused = Seq("query" -> "postgres", "query" -> "mongodb", "query" -> "natural", "metadata" -> "read")
        Seq(User.RoleEditor, User.RoleViewer).foreach { role =>
            val caps = CatalogGrants.capabilitiesFor(role, grants("Sales" -> "edit"))
            refused.foreach { case (r, a) =>
                assert(!caps.exists(_.matchesResourceAction(r, a)), s"$role restricted bundle must not hold $r:$a: ${raw(caps)}")
            }
            // Non-catalog capabilities of the role bundle are carried.
            assert(caps.exists(_.matchesResourceAction("job", "read")), s"$role keeps job:read")
            assert(caps.exists(_.matchesResourceAction("query", "objectstore")), s"$role keeps query:objectstore")
        }
    }

    test("admin ignores grants") {
        assert(CatalogGrants.capabilitiesFor(User.RoleAdmin, grants("Sales" -> "view")) == Seq(Capability.FullAccess))
        assert(CatalogGrants.capabilitiesFor(User.RoleAdmin, null) == Seq(Capability.FullAccess))
    }

    test("defaultCatalogFor: one editable -> Some, several -> Left listing them, only view -> Left") {
        val one = APIKeyValidator.resolveFromSession(restricted("bob", User.RoleEditor, grants("Sales" -> "edit", "HR" -> "view")))
        assert(CatalogGrants.defaultCatalogFor(one) == Right(Some("Sales")))

        val several = APIKeyValidator.resolveFromSession(restricted("bob", User.RoleEditor, grants("Sales" -> "edit", "Ops" -> "own")))
        CatalogGrants.defaultCatalogFor(several) match {
            case Left(text) =>
                assert(text.contains("Choose a catalog"), text)
                assert(text.contains("Sales") && text.contains("Ops"), text)
            case other => fail(s"several editable catalogs must refuse, got $other")
        }

        val viewOnly = APIKeyValidator.resolveFromSession(restricted("bob", User.RoleEditor, grants("Sales" -> "view")))
        CatalogGrants.defaultCatalogFor(viewOnly) match {
            case Left(text) => assert(text.contains("No catalog you can edit"), text)
            case other => fail(s"view-only grants must refuse, got $other")
        }
    }

    test("renameIn moves the level to the new name") {
        val out = CatalogGrants.renameIn(grants("Sales" -> "own", "HR" -> "view"), "Sales", "Revenue").asScala.toMap
        assert(out == Map("Revenue" -> "own", "HR" -> "view"), out)
        val untouched = CatalogGrants.renameIn(grants("HR" -> "view"), "Sales", "Revenue").asScala.toMap
        assert(untouched == Map("HR" -> "view"), untouched)
    }
}
