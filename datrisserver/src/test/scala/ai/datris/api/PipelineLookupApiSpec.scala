package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.Gson
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

import scala.collection.JavaConverters._

/** Story: Delete keeps a hand-edited repository script and warns; missing
  * pipeline GET is 404 (plans/stories/pipeline-delete-drift-warning-and-404.md).
  * No Spring and no Mongo: GET /api/v1/pipeline builds its response from the
  * config it read through a pure helper.
  *
  * Pinned seam:
  * {{{
  * object PipelineAPIController {
  *     /** null config: 404 with QueryAPIController.errorBody of
  *       * "Pipeline: <name> is not configured in the NoSQL database";
  *       * otherwise 200 with new Gson().toJson(config). */
  *     def pipelineResponse(config: PipelineConfig, name: String): ResponseEntity[String]
  * }
  * }}}
  */
class PipelineLookupApiSpec extends AnyFunSuite {

    test("GET /pipeline for a missing pipeline is 404 with the NoSQL error body") {
        val r: ResponseEntity[String] = PipelineAPIController.pipelineResponse(null, "nope")
        assert(r.getStatusCode.value == 404, r.getBody)
        assert(r.getBody == "{\"error\": \"Pipeline: nope is not configured in the NoSQL database\"}", r.getBody)
    }

    test("GET /pipeline for an existing pipeline is 200 with the Gson JSON of the config") {
        val cfg = PipelineConfig(
            name = "orders",
            source = Source(
                schemaProperties = SchemaProperties(null, List(SchemaField("full_name", "string"), SchemaField("age", "int")).asJava),
                fileAttributes = FileAttributes(csvAttributes = CsvAttributes(delimiter = ","))
            ),
            destination = Destination(database = Database(dbName = "d", schema = "public", table = "t", usePostgres = true))
        )
        val r: ResponseEntity[String] = PipelineAPIController.pipelineResponse(cfg, "orders")
        assert(r.getStatusCode.value == 200, r.getBody)
        assert(r.getBody == new Gson().toJson(cfg), r.getBody)
    }

    // Part 2: DELETE /api/v1/pipeline for a missing pipeline answers with
    // object PipelineAPIController.pipelineNotFound(name): ResponseEntity[String].
    test("pipelineNotFound is 404 with the NoSQL error body") {
        val r: ResponseEntity[String] = PipelineAPIController.pipelineNotFound("nope")
        assert(r.getStatusCode.value == 404, r.getBody)
        assert(r.getBody == "{\"error\": \"Pipeline: nope is not configured in the NoSQL database\"}", r.getBody)
    }
}
