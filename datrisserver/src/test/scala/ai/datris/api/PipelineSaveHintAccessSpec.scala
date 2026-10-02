package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

/** The save-time Unity Catalog hint names the secret's workspace host, so it
  * must only resolve a secret the caller may read. */
class PipelineSaveHintAccessSpec extends AnyFunSuite {

    private val host = "dbc-a1b2c3d4-e5f6.cloud.databricks.com"

    test("a readable secret resolves to its host") {
        val hostOf = PipelineAPIController.hintHostOf(_ => true, _ => Some(host))
        assert(hostOf("dbx") == Some(host))
    }

    test("an unreadable secret gives no host and is never resolved") {
        var resolved = false
        val hostOf = PipelineAPIController.hintHostOf(_ => false, _ => { resolved = true; Some(host) })
        assert(hostOf("dbx-hidden").isEmpty)
        assert(!resolved)
    }

    test("readability is checked per secret name") {
        val hostOf = PipelineAPIController.hintHostOf(_ == "dbx", _ => Some(host))
        assert(hostOf("dbx") == Some(host))
        assert(hostOf("other").isEmpty)
    }
}
