package ai.datris.controller

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisEnvironment
import ai.datris.util.QueueUtil
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.{PostMapping, RequestBody, RestController}

@RestController
class MinioWebhookController {
    private val logger = LoggerFactory.getLogger(getClass)

    @PostMapping(Array("/minio-events"))
    def handleMinioEvent(@RequestBody payload: String): String = {
        logger.info(s"Received MinIO event: $payload")
        QueueUtil.add(DatrisEnvironment.current.fileNotifierQueue, payload)
        "OK"
    }
}
