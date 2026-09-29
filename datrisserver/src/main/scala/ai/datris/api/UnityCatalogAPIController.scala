package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditLog
import ai.datris.auth.CapabilityCheck
import ai.datris.model.{DatrisEnvironment, DatrisException, PipelineConfig}
import ai.datris.util.{APIKeyValidator, CredentialResolver, PipelineConfigIO, SecretsRetrieverUtil, UnityCatalogDiscovery}
import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonObject}
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

/** Read-only Unity Catalog discovery (`browse_unity_catalog`): walk the
  * catalogs → schemas → tables → columns a Databricks Platform secret can
  * see, marking tables a Datris pipeline loaded (the `datris_pipeline` tag).
  * Classified `metadata:read`; the caller must also be able to read the
  * named secret (the same predicate list_platform_secrets applies). */
@RestController
@RequestMapping(Array("/api/v1/unity-catalog"))
class UnityCatalogAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[UnityCatalogAPIController])

    @GetMapping(path = Array("/browse"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def browse(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam(name = "secret", required = false) secret: String,
        @RequestParam(name = "warehouse", required = false) warehouse: String,
        @RequestParam(name = "catalog", required = false) catalog: String,
        @RequestParam(name = "schema", required = false) schema: String,
        @RequestParam(name = "table", required = false) table: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        val secretName = Option(secret).map(_.trim).orNull
        val md = new JsonObject()
        Option(catalog).foreach(md.addProperty("catalog", _))
        Option(schema).foreach(md.addProperty("schema", _))
        Option(table).foreach(md.addProperty("table", _))

        def respond(status: HttpStatus, body: String, error: String = null): ResponseEntity[String] = {
            val outcome =
                if (status.value() == 401 || status.value() == 403) "denied"
                else if (status.is2xxSuccessful) "success"
                else "failure"
            try AuditLog.record(request, "unity-catalog", "browse", "secret", secretName, outcome, status.value(), md, error)
            catch { case e: Exception => logger.warn("audit record failed: " + e.getMessage) }
            ResponseEntity.status(status).body[String](body)
        }
        def fail(status: HttpStatus, message: String): ResponseEntity[String] =
            respond(status, QueryAPIController.errorBody(new DatrisException(message)), message)

        try {
            logger.info("API endpoint GET /unity-catalog/browse called, secret=" + secretName)
            try APIKeyValidator.validate(apiKey)
            catch { case e: DatrisException => return fail(HttpStatus.UNAUTHORIZED, e.getMessage) }

            if (secretName == null || secretName.isEmpty)
                return fail(HttpStatus.BAD_REQUEST, "secret is required — the name of a Databricks Platform secret (see list_platform_secrets)")
            def given(s: String): Boolean = s != null && s.trim.nonEmpty
            if (given(schema) && !given(catalog))
                return fail(HttpStatus.BAD_REQUEST, "schema requires catalog")
            if (given(table) && !given(schema))
                return fail(HttpStatus.BAD_REQUEST, "table requires catalog and schema")

            val fields = SecretsRetrieverUtil.platformSecrets().find(_._1 == secretName).map(_._2)
                .filter(CredentialResolver.hasDatabricksCredentials)
                .getOrElse(return fail(
                    HttpStatus.NOT_FOUND,
                    "No Databricks Platform secret named '" + secretName + "' (it must exist on Configuration → Secrets → Platform " +
                        "with host and clientId/clientSecret or token). Use list_platform_secrets to see the available names."
                ))

            if (!UnityCatalogAPIController.canRead(request, fields)) {
                val message = "this key may not read secret '" + secretName + "'"
                return respond(
                    HttpStatus.FORBIDDEN,
                    "{\"error\":\"capability denied\",\"errorKind\":\"capability_denied\",\"message\":" + new Gson().toJson(message) + "}",
                    message
                )
            }

            val pipelines: List[PipelineConfig] =
                try PipelineConfigIO.readAll(DatrisEnvironment.current.pipelineTableName)
                catch { case _: Exception => Nil }
            val wh = UnityCatalogDiscovery.resolveWarehouse(secretName, fields, Option(warehouse), pipelines) match {
                case Right(w) => w
                case Left(message) => return fail(HttpStatus.BAD_REQUEST, message)
            }

            val result =
                try UnityCatalogDiscovery.browse(secretName, wh, Option(catalog), Option(schema), Option(table))
                catch {
                    case e: IllegalArgumentException => return fail(HttpStatus.BAD_REQUEST, e.getMessage)
                    case e: Exception =>
                        val message = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
                        logger.info("Unity Catalog browse failed for secret '" + secretName + "': " + message)
                        return fail(HttpStatus.BAD_GATEWAY, message)
                }
            respond(HttpStatus.OK, new Gson().toJson(result))
        } catch {
            case e: Exception =>
                logger.error("Error in Unity Catalog browse: " + Throwables.getStackTraceAsString(e))
                fail(HttpStatus.INTERNAL_SERVER_ERROR, Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
        }
    }
}

object UnityCatalogAPIController {

    /** The list_platform_secrets read predicate for one secret. */
    private[api] def canRead(request: HttpServletRequest, fields: java.util.Map[String, String]): Boolean = {
        val t = Option(fields.get("_type")).getOrElse("")
        val ctx = if (t.nonEmpty) Map("_type" -> t) else Map.empty[String, String]
        CapabilityCheck.grants(request, "secret", "read", ctx)
    }

    /** Platform secrets holding Databricks credentials that this caller
      * could read — the set `find_data includeUnityCatalog` searches. */
    private[api] def visibleDatabricksSecrets(request: HttpServletRequest): List[(String, java.util.Map[String, String])] =
        SecretsRetrieverUtil.platformSecrets()
            .filter { case (_, f) => CredentialResolver.hasDatabricksCredentials(f) && canRead(request, f) }
}
