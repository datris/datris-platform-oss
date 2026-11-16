package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.User
import org.scalatest.funsuite.AnyFunSuite

import scala.jdk.CollectionConverters._

/** Story: Catalog-scoped user access (UI, Assistant, MCP, docs)
  * (plans/stories/catalog-scoped-user-access-ui-assistant.md), Step 6.
  *
  * `CatalogAccessPrompt.block(user)` is the Assistant prompt block for a
  * restricted session user: a data line first
  * (`Catalog access for this user: <name> (<level>), ...`), then the rules.
  * Unrestricted users (restricted=false, or any admin) get None so their
  * prompt is unchanged. Filing targets are the edit/own catalogs only; a
  * view-only catalog appears in the data line and nowhere else. The block
  * never mentions Uncataloged (the Assistant must never offer it). */
class CatalogAccessPromptSpec extends AnyFunSuite {

    private def user(role: String, restricted: Boolean, grants: (String, String)*): User = {
        val m = new java.util.LinkedHashMap[String, String]()
        grants.foreach { case (k, v) => m.put(k, v) }
        User(
            username = "bob",
            passwordHash = "x",
            role = role,
            createdAt = "2026-11-01T00:00:00Z",
            updatedAt = "2026-11-01T00:00:00Z",
            lastLoginAt = null,
            restricted = restricted,
            catalogGrants = if (grants.isEmpty) null else m
        )
    }

    test("unrestricted user yields None") {
        assert(CatalogAccessPrompt.block(user(User.RoleEditor, restricted = false)).isEmpty)
        // Stored grants are ignored while the flag is off.
        assert(CatalogAccessPrompt.block(user(User.RoleEditor, restricted = false, "Sales" -> "edit")).isEmpty)
        assert(CatalogAccessPrompt.block(user(User.RoleViewer, restricted = false, "Sales" -> "view")).isEmpty)
        // Admins are never restricted, even with the flag set.
        assert(CatalogAccessPrompt.block(user(User.RoleAdmin, restricted = true, "Sales" -> "own")).isEmpty)
    }

    test("restricted block lists each catalog with its level and names only editable ones as filing targets") {
        val u = user(User.RoleEditor, restricted = true,
            "Sales" -> "edit", "Marketing" -> "own", "Archive" -> "view")
        val block = CatalogAccessPrompt.block(u)
        assert(block.isDefined, "a restricted user must get a catalog-access block")
        val text = block.get
        val lines = text.split("\n").map(_.trim).filter(_.nonEmpty).toSeq

        // Data first: the opening line carries every catalog with its level.
        val dataIdx = lines.indexWhere(_.startsWith("Catalog access for this user:"))
        assert(dataIdx == 0, s"block must open with the data line, got: ${lines.headOption}")
        val data = lines(dataIdx)
        assert(data.contains("Sales (edit)"), data)
        assert(data.contains("Marketing (own)"), data)
        assert(data.contains("Archive (view)"), data)

        // Rules: some rule names the editable catalogs as the filing targets,
        // and the view-only catalog is never named outside the data line.
        val rules = lines.drop(dataIdx + 1)
        assert(rules.nonEmpty, "rules must follow the data line")
        assert(rules.exists(l => l.contains("Sales") && l.contains("Marketing")),
            s"a rule must name the editable catalogs (Sales, Marketing) as filing targets:\n$text")
        assert(!rules.exists(_.contains("Archive")),
            s"a view-only catalog must not be offered as a filing target:\n$text")
    }

    test("block never contains the word Uncataloged") {
        val cases = Seq(
            user(User.RoleEditor, restricted = true, "Sales" -> "edit"),
            user(User.RoleEditor, restricted = true, "Sales" -> "edit", "Marketing" -> "own", "Archive" -> "view"),
            user(User.RoleViewer, restricted = true, "Archive" -> "view"),
            user(User.RoleEditor, restricted = true)
        )
        cases.foreach { u =>
            val grants = Option(u.catalogGrants).map(_.asScala.toMap).getOrElse(Map.empty)
            val text = CatalogAccessPrompt.block(u)
            assert(text.isDefined, s"restricted user with grants $grants must get a block")
            assert(!text.get.toLowerCase.contains("uncataloged"), s"grants $grants:\n${text.get}")
        }
    }
}
