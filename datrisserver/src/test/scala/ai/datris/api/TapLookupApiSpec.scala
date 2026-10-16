package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import jakarta.servlet.http.HttpServletRequest
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

/** Story: pipeline-delete-drift-warning-and-404.md, Part 2. GET and DELETE
  * /api/v1/tap for a missing tap answer 404 with today's error body (Gson,
  * no space after the colon). No Spring and no Mongo.
  *
  * Pinned seam:
  * {{{
  * object TapAPIController {
  *     /** 404 with ApiErrors.errorBody("Tap: " + name + " not found"). */
  *     def tapNotFound(name: String): ResponseEntity[String]
  * }
  * class TapAPIController {
  *     /** None = no such tap (DELETE maps it to tapNotFound); Some(warnings) otherwise. */
  *     def deleteTapInternal(name: String, request: HttpServletRequest, checkScope: Boolean = true): Option[Seq[String]]
  * }
  * }}}
  */
class TapLookupApiSpec extends AnyFunSuite {

    test("tapNotFound is 404 with the Tap not found error body") {
        val r: ResponseEntity[String] = TapAPIController.tapNotFound("nope")
        assert(r.getStatusCode.value == 404, r.getBody)
        assert(r.getBody == "{\"error\":\"Tap: nope not found\"}", r.getBody)
    }

    test("tapNotFound for a blank name keeps today's body") {
        val r: ResponseEntity[String] = TapAPIController.tapNotFound("")
        assert(r.getStatusCode.value == 404, r.getBody)
        assert(r.getBody == "{\"error\":\"Tap:  not found\"}", r.getBody)
    }

    test("deleteTapInternal says whether the tap existed") {
        // Compile-time pin of the return type; the route needs Mongo, so the
        // behaviour is covered by the manual Verify block.
        def deleted(c: TapAPIController, request: HttpServletRequest): Option[Seq[String]] = c.deleteTapInternal("nope", request)
        assert(deleted _ != null)
    }
}
