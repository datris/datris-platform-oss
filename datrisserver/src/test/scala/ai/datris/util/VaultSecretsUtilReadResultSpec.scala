package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

import scala.util.{Failure, Success}

/** The vault-java-driver returns a response (not an exception) for 4xx, so
  * absent-vs-failed must be decided on the HTTP status. A 403 from an
  * expired token must never read as "absent" (which would grant legacy
  * full access to every key). */
class VaultSecretsUtilReadResultSpec extends AnyFunSuite {

    private def data(kv: (String, String)*): java.util.Map[String, String] = {
        val m = new java.util.HashMap[String, String]()
        kv.foreach { case (k, v) => m.put(k, v) }
        m
    }

    test("200 with data → Some") {
        assert(VaultSecretsUtil.readResult(200, data("a" -> "1")).get.exists(_.get("a") == "1"))
    }

    test("200 with no data and 404 → absent") {
        assert(VaultSecretsUtil.readResult(200, data()) == Success(None))
        assert(VaultSecretsUtil.readResult(200, null) == Success(None))
        assert(VaultSecretsUtil.readResult(404, data()) == Success(None))
    }

    test("403 (expired token), 400, 429, 500, 503 → Failure, never absent") {
        Seq(403, 400, 429, 500, 503).foreach { st =>
            VaultSecretsUtil.readResult(st, data()) match {
                case Failure(e) => assert(e.getMessage.contains(st.toString))
                case other => fail(s"status $st must fail closed, got $other")
            }
        }
    }
}
