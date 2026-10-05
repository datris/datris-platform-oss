package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisException, ValidationException}
import ai.datris.util.{APIKeyValidator, RevokedKeyException}
import com.google.gson.JsonParser
import org.scalatest.funsuite.AnyFunSuite

/** Story: an invalid pipeline config is a 400, not a 500
  * (plans/stories/pipeline-save-validation-400.md), Step 2.
  *
  * `ApiErrors.classify` maps a `ValidationException` to
  * `(400, {"error": <first line of message>})`; every other exception keeps
  * its existing classification (ApiErrorsSpec pins the full matrix). */
class ApiErrorsValidationSpec extends AnyFunSuite {

    private val presetMessage = "Preset 'hipaa-safe-harbor': field 'phone' looks like phone and has no protection."

    private def errorOf(body: String): String = JsonParser.parseString(body).getAsJsonObject.get("error").getAsString

    test("a ValidationException classifies as 400 with the message") {
        val (status, body) = ApiErrors.classify(new ValidationException(presetMessage))
        assert(status == 400, body)
        assert(errorOf(body) == presetMessage)
        assert(body == ApiErrors.errorBody(presetMessage))
    }

    test("a ValidationException body carries the first line only") {
        val (status, body) = ApiErrors.classify(new ValidationException("Duplicate field name(s) found in destination schema: id\n\tat ai.datris.X(X.scala:1)"))
        assert(status == 400)
        assert(errorOf(body) == "Duplicate field name(s) found in destination schema: id")
        assert(!body.contains("\tat "))
    }

    test("other exceptions still classify as before") {
        val (s500, b500) = ApiErrors.classify(new DatrisException(presetMessage))
        assert(s500 == 500 && errorOf(b500) == presetMessage, "a plain DatrisException is still a 500")
        val (sRt, bRt) = ApiErrors.classify(new RuntimeException("boom: disk full\n\tat x"))
        assert(sRt == 500 && bRt == "{\"error\":\"boom: disk full\"}")
        assert(ApiErrors.classify(new RevokedKeyException("reader"))._1 == 401)
        assert(ApiErrors.classify(new DatrisException(APIKeyValidator.MissingKeyMessage))._1 == 401)
        assert(ApiErrors.classify(new DatrisException(APIKeyValidator.KeyStoreUnavailableMessage))._1 == 503)
    }
}
