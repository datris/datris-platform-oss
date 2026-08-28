package ai.datris.controller

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisEnvironment
import ai.datris.util.QueueUtil
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.{HttpStatus, ResponseEntity}
import org.springframework.web.bind.annotation.{PostMapping, RequestBody, RestController}

@RestController
class MinioWebhookController {
    private val logger = LoggerFactory.getLogger(getClass)

    // Shared secret MinIO includes as `Authorization: Bearer <token>` (its
    // notify_webhook auth_token). This endpoint is deliberately outside the
    // /api/** interceptors, so without this check anyone who can reach it could
    // enqueue a forged event and trigger ingestion of an arbitrary object —
    // and, in multi-tenant mode, pick the target tenant via the bucket name.
    private def expectedToken: String = sys.env.getOrElse("MINIO_WEBHOOK_TOKEN", "")

    @PostMapping(Array("/minio-events"))
    def handleMinioEvent(@RequestBody payload: String, request: HttpServletRequest): ResponseEntity[String] = {
        val token = expectedToken
        if (token.isEmpty) {
            // Not configured — keep working so existing deployments don't break
            // on upgrade, but make the exposure loud. Set MINIO_WEBHOOK_TOKEN
            // (and MinIO's matching auth_token) to authenticate this endpoint.
            logger.warn(
                "/minio-events is UNAUTHENTICATED — MINIO_WEBHOOK_TOKEN is not set. " +
                    "Anyone who can reach this endpoint can trigger ingestion. Configure a token to secure it."
            )
        } else {
            val provided = bearerToken(request)
            if (provided == null || !constantTimeEquals(provided, token)) {
                logger.warn("Rejected /minio-events request with missing or invalid bearer token")
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized")
            }
        }

        logger.info(s"Received MinIO event: $payload")
        QueueUtil.add(DatrisEnvironment.current.fileNotifierQueue, payload)
        auditTrigger(payload)
        ResponseEntity.ok("OK")
    }

    /** One collapsed audit record per bucket per minute ("N objects landed in
      * bucket X triggered ingestion"). Best-effort — a malformed payload just
      * records the bucket as unknown. */
    private def auditTrigger(payload: String): Unit = {
        if (!ai.datris.audit.AuditLog.enabled) return
        val bucket =
            try {
                val root = com.google.gson.JsonParser.parseString(payload).getAsJsonObject
                val records = root.getAsJsonArray("Records")
                if (records != null && records.size() > 0)
                    records.get(0).getAsJsonObject.getAsJsonObject("s3").getAsJsonObject("bucket").get("name").getAsString
                else "unknown"
            } catch { case _: Exception => "unknown" }
        val md = new com.google.gson.JsonObject()
        md.addProperty("trigger", "minio-webhook")
        ai.datris.audit.AuditLog.system("pipeline", "ingest-trigger", "bucket", bucket, md)
    }

    private def bearerToken(request: HttpServletRequest): String = {
        val header = request.getHeader("Authorization")
        if (header == null) null
        else if (header.startsWith("Bearer ")) header.substring("Bearer ".length).trim
        else header.trim
    }

    private def constantTimeEquals(a: String, b: String): Boolean = {
        java.security.MessageDigest.isEqual(
            a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            b.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )
    }
}
