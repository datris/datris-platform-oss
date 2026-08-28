package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.build.sbt.BuildInfo
import ai.datris.model.DatrisEnvironment
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import scala.collection.JavaConverters._

@RestController
@RequestMapping(Array("/api/v1"))
class VersionAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[VersionAPIController])

    @GetMapping(path = Array("/version"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getVersion(@RequestHeader(name = "x-api-key", required = false) apiKey: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /api/v1/version called")
            // No `validate()` call: /version is public infrastructure. The UI
            // calls this BEFORE the user pastes a key (to render server status
            // and decide whether to show the login screen vs the API-key prompt),
            // so requiring auth here creates 500-error noise during the load.
            // The RoleEnforcementInterceptor already skips this path; the
            // capability interceptor's skip-list also includes it. The
            // apiKey parameter is retained for forward compatibility but ignored.
            // postgresDatabase and mongodbDatabase are the canonical, server-configured database names.
            // UI must treat these as authoritative and not allow users to edit them.
            val mongodbDatabase =
                if (DatrisEnvironment.current.multiTenant) DatrisEnvironment.current.environment
                else DatrisEnvironment.current.mongoDbConfig.database
            val map = Map(
                "version" -> BuildInfo.version,
                "environment" -> DatrisEnvironment.current.environment,
                "multiTenant" -> DatrisEnvironment.current.multiTenant.toString,
                "hosted" -> DatrisEnvironment.current.hosted.toString,
                "useUserAuth" -> DatrisEnvironment.values.useUserAuth.toString,
                "useApiKeys" -> DatrisEnvironment.values.useApiKeys.toString,
                "useAuditLog" -> DatrisEnvironment.values.useAuditLog.toString,
                "postgresDatabase" -> DatrisEnvironment.current.postgresDatabase,
                "mongodbDatabase" -> mongodbDatabase,
                "useTapRunner" -> ai.datris.util.TapScriptRunner.useTapRunner.toString
            ).asJava
            val gson = new Gson
            new ResponseEntity[String](gson.toJson(map), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }
}
