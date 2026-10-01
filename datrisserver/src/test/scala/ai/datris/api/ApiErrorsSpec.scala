package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import ai.datris.util.{APIKeyValidator, RevokedKeyException}
import org.scalatest.funsuite.AnyFunSuite

class ApiErrorsSpec extends AnyFunSuite {

    test("revoked and unknown keys → 401 without the value or label in the body") {
        val (s1, b1) = ApiErrors.classify(new RevokedKeyException("reader"))
        assert(s1 == 401 && b1 == "{\"error\":\"API key is revoked or invalid\"}")
        val (s2, b2) = ApiErrors.classify(new DatrisException(APIKeyValidator.InvalidKeyMessage))
        assert(s2 == 401 && b2 == b1)
    }

    test("missing key → 401 Authentication required") {
        val (s, b) = ApiErrors.classify(new DatrisException(APIKeyValidator.MissingKeyMessage))
        assert(s == 401 && b == "{\"error\":\"Authentication required\"}")
    }

    test("secret-store outage → 503") {
        assert(ApiErrors.classify(new DatrisException(APIKeyValidator.MetadataUnavailableMessage))._1 == 503)
        assert(ApiErrors.classify(new DatrisException(APIKeyValidator.KeyStoreUnavailableMessage))._1 == 503)
    }

    test("any other exception → 500 JSON with the first line of the message, no stack trace") {
        val e = new RuntimeException("boom: disk full\n\tat ai.datris.Something.run(Something.scala:12)")
        val (s, b) = ApiErrors.classify(e)
        assert(s == 500)
        assert(b == "{\"error\":\"boom: disk full\"}")
        assert(!b.contains("\tat "))
    }

    test("no message → class name; quotes are escaped") {
        assert(ApiErrors.classify(new IllegalStateException())._2 == "{\"error\":\"IllegalStateException\"}")
        assert(ApiErrors.classify(new RuntimeException("say \"hi\""))._2 == "{\"error\":\"say \\\"hi\\\"\"}")
    }
}
