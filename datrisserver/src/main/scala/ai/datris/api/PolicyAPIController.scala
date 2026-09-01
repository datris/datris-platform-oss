package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditActor
import ai.datris.config.RequiresRole
import ai.datris.model.DatrisEnvironment
import ai.datris.policy.{AgentPolicy, PendingActionIO, PolicyActor, PolicyIO, PolicyRoutes}
import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonArray, JsonObject}
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import scala.collection.JavaConverters._

/** The agent policy document: what agents may do on their own, what waits
  * for a person, what is refused. Anyone may read it (agents use it to know
  * what will queue before they act); only an admin — never an agent — may
  * change it. */
@RestController
@RequestMapping(Array("/api/v1/policy"))
class PolicyAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[PolicyAPIController])
    private val gson = new Gson()

    @GetMapping(produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def get(): ResponseEntity[String] = {
        try {
            val out = new JsonObject()
            out.addProperty("enabled", PolicyIO.enabled)
            if (PolicyIO.enabled) {
                out.add("policy", PolicyIO.current.toJson)
                out.addProperty("pendingCount", PendingActionIO.countPendingAll())
            } else {
                out.add("policy", AgentPolicy.Empty.toJson)
                out.addProperty("pendingCount", 0)
            }
            out.add("recommended", AgentPolicy.Recommended.toJson)
            val actions = new JsonArray()
            PolicyRoutes.catalog.foreach(actions.add)
            out.add("actions", actions)
            new ResponseEntity[String](gson.toJson(out), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error reading agent policy: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](errorJson(e.getMessage))
        }
    }

    @PutMapping(consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    @RequiresRole(Array("admin"))
    def put(@RequestBody body: String, request: HttpServletRequest): ResponseEntity[String] = {
        try {
            if (!PolicyIO.enabled)
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body[String](
                    "{\"error\":\"agent policy is disabled\",\"hint\":\"set USE_AGENT_POLICY=true and recreate the datris container\"}"
                )
            if (PolicyActor.isAgent(request))
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body[String](errorJson("agents may not change the agent policy"))
            AgentPolicy.fromJson(body) match {
                case Left(err) => ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](errorJson(err))
                case Right(parsed) =>
                    // A client that doesn't know about the recovery settings
                    // (or the recovery overrides) must not silently reset them
                    // by omission: merge the stored values in unless the body
                    // spoke about them explicitly.
                    val bodyObj = com.google.gson.JsonParser.parseString(body).getAsJsonObject
                    val bodySetRecovery = bodyObj.has("recovery")
                    val bodySetRecoveryOverrides =
                        bodyObj.has("overrides") && bodyObj.get("overrides").isJsonObject &&
                            bodyObj.getAsJsonObject("overrides").entrySet().asScala.exists(e =>
                                e.getValue.isJsonObject && e.getValue.getAsJsonObject.has("recovery")
                            )
                    val current = PolicyIO.current
                    val p = parsed.copy(
                        recovery = if (bodySetRecovery) parsed.recovery else current.recovery,
                        recoveryOverrides = if (bodySetRecovery || bodySetRecoveryOverrides) parsed.recoveryOverrides else current.recoveryOverrides
                    )
                    val saved = PolicyIO.write(p, AuditActor.resolve(request).label)
                    logger.info("Agent policy updated to version " + saved.version + " by " + saved.updatedBy.getOrElse("?"))
                    val out = new JsonObject()
                    out.addProperty("enabled", true)
                    out.add("policy", saved.toJson)
                    new ResponseEntity[String](gson.toJson(out), HttpStatus.OK)
            }
        } catch {
            case e: Exception =>
                logger.error("Error updating agent policy: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](errorJson(e.getMessage))
        }
    }

    private def errorJson(msg: String): String =
        "{\"error\":\"" + Option(msg).getOrElse("").replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"}"
}
