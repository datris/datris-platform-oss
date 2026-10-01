package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.User
import ai.datris.util.APIKeyValidator
import org.scalatest.funsuite.AnyFunSuite

/** The Search tab offers Object Store, Databricks and Snowflake queries; the
  *  Editor/Viewer session bundles and the read-only / reporting key templates
  *  must carry the matching query capabilities so those options can run. */
class QueryCapabilityBundlesSpec extends AnyFunSuite {

    private val QueryCaps = Seq("query:postgres", "query:mongodb", "query:objectstore", "query:snowflake", "query:databricks")

    private def sessionCaps(role: String): Seq[String] =
        APIKeyValidator.resolveFromSession(User("u", "h", role, null, null, null)).capabilities.map(_.raw)

    Seq(User.RoleEditor, User.RoleViewer).foreach { role =>
        test(s"$role session bundle grants every structured query capability") {
            val caps = sessionCaps(role)
            QueryCaps.foreach(c => assert(caps.contains(c), s"$role missing $c: $caps"))
        }
    }

    Seq("read-only", "reporting").foreach { name =>
        test(s"$name key template grants every structured query capability") {
            val caps = KeysAPIController.Templates.find(_._1 == name).map(_._3).getOrElse(fail(s"template $name not found"))
            QueryCaps.foreach(c => assert(caps.contains(c), s"$name missing $c: $caps"))
        }
    }

    test("viewer bundle stays read-only (no create/update/delete/run/kill)") {
        val caps = sessionCaps(User.RoleViewer)
        assert(!caps.exists(c => Seq(":create", ":update", ":delete", ":run", ":kill", ":write").exists(c.endsWith)), caps)
    }
}
