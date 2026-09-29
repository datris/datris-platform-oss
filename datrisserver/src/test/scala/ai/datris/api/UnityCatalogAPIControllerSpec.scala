package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

/** Story: Unity Catalog 2: discovery — browse must not reveal whether a
  * secret the caller cannot read exists (review follow-up 1). */
class UnityCatalogAPIControllerSpec extends AnyFunSuite {

    private def fields(kv: (String, String)*): java.util.Map[String, String] = {
        val m = new java.util.HashMap[String, String]()
        kv.foreach { case (k, v) => m.put(k, v) }
        m
    }

    private val dbx = fields("host" -> "dbc-a1b2c3d4-e5f6.cloud.databricks.com", "token" -> "dapi-test")
    private val tapScoped = fields("host" -> "dbc-a1b2c3d4-e5f6.cloud.databricks.com", "token" -> "dapi-test", "_type" -> "team")
    private val notDbx = fields("user" -> "u", "password" -> "p")
    private val secrets = List("dbx" -> dbx, "dbx-hidden" -> tapScoped, "pg" -> notDbx)
    private val canRead: java.util.Map[String, String] => Boolean = f => f.get("_type") == null

    test("a readable Databricks secret resolves to its fields") {
        assert(UnityCatalogAPIController.lookupSecret("dbx", secrets, canRead) == Right(dbx))
    }

    test("unknown, non-Databricks and unreadable secrets are all Left — unreadable is audited as denied") {
        assert(UnityCatalogAPIController.lookupSecret("nope", secrets, canRead) == Left("failure"))
        assert(UnityCatalogAPIController.lookupSecret("pg", secrets, canRead) == Left("failure"))
        assert(UnityCatalogAPIController.lookupSecret("dbx-hidden", secrets, canRead) == Left("denied"))
    }

    test("the not-found message names the secret and never mentions access") {
        val msg = UnityCatalogAPIController.notFoundMessage("dbx-hidden")
        assert(msg.contains("'dbx-hidden'"))
        assert(!msg.toLowerCase.contains("denied") && !msg.toLowerCase.contains("capability"))
    }
}
