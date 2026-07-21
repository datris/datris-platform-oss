package ai.datris.controller

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.springframework.http.{HttpStatus, ResponseEntity}
import org.springframework.web.bind.annotation._

@RestController
@RequestMapping(Array("/api/v1"))
class RestEndpointCallbackController {

    @PostMapping(Array("/restendpoint/callback"))
    def handleCallback(@RequestBody body: String): ResponseEntity[String] = {
        val gson = new com.google.gson.Gson()
        val map = gson.fromJson(body, classOf[java.util.Map[String, Any]])
        val pipelineToken = map.get("pipelineToken").asInstanceOf[String]
        val pipelineName = map.get("pipelineName").asInstanceOf[String]

        if (pipelineToken == null)
            return new ResponseEntity[String]("Missing pipelineToken", HttpStatus.BAD_REQUEST)
        if (pipelineName == null)
            return new ResponseEntity[String]("Missing pipelineName", HttpStatus.BAD_REQUEST)

        RestEndpointCallbackRegistry.complete(pipelineToken, body)

        new ResponseEntity[String]("OK", HttpStatus.OK)
    }
}
