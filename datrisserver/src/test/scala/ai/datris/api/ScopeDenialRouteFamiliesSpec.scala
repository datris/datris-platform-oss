package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.{CapabilityCheck, CapabilityDeniedException, ScopeDenialFixtures, ScopeDenialWording}
import ai.datris.model._
import ai.datris.util.CatalogOps
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: scoped API keys, a refusal no longer names the hidden resource's
  * catalog or owner (plans/stories/scoped-key-refusal-hides-scope.md).
  *
  * The story's audit of every route family that hands the scope-refusal text
  * to a client, driven through the public entry points that build the 403
  * without a store: pipeline save overwrite, version restore (through
  * `EntityVersionAPIController.restoreGuardDecision`, the path
  * `savePipelineCore` takes), a hidden previous-name claim (`readScopeDenied`),
  * taps (`TapAPIController.scopeRefusal`), catalog operations
  * (`CatalogOps.capabilityDeniedBody`, also the text of per-item
  * `failed[].error`) and field protection (`reveal`). Each body has exactly
  * error, errorKind and message, the new wording, and names neither the
  * target's catalog nor its owner. Secrets build their body inside handlers
  * that read Vault; those handlers share `SecretsAPIController.capabilityDenied`,
  * driven here with the exception `assertScope` throws. */
class ScopeDenialRouteFamiliesSpec extends AnyFunSuite with BeforeAndAfterEach {

    import ScopeDenialFixtures.key

    private val ScopedCaps = Seq("pipeline:read:catalog=CiSales", "pipeline:create:catalog=CiSales", "tap:read:owner=self")
    private val scoped = key("ci_scoped_w", ScopedCaps: _*)
    private val Hidden = Seq("CiHR", "vscode-claude")

    private var saved: DatrisEnvironment = _
    private val captured = mutable.ArrayBuffer[AuditEntry]()

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        captured.clear()
        AuditLog.sinkOverride = (e: AuditEntry) => captured.synchronized { captured += e; () }
        ScopeDenialFixtures.install(ScopeDenialFixtures.auditEnv())
    }

    override def afterEach(): Unit = {
        AuditLog.sinkOverride = null
        ScopeDenialFixtures.install(saved)
    }

    private def req(rk: ResolvedKey, method: String = "POST", uri: String = "/api/v1/pipeline") =
        ScopeDenialFixtures.request(rk, method, uri)._1

    private def pg(name: String, catalog: String, owner: String = "vscode-claude", prev: Seq[String] = null): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = owner,
            destination = Destination(database = Database(dbName = "datris", schema = "public", table = name + "_t", usePostgres = true)),
            previousNames = if (prev == null) null else new java.util.ArrayList[String](prev.asJava)
        )

    private val hrPipeline = pg("hr_payroll", "CiHR")

    private def forbidden(r: Option[ResponseEntity[String]]): String = {
        assert(r.isDefined, "expected a refusal")
        assert(r.get.getStatusCode.value == 403, r.get)
        r.get.getBody
    }

    test("pipelines: overwriting a pipeline outside the key's scope") {
        val body = forbidden(PipelineAPIController.overwriteRefusal(req(scoped), hrPipeline, PipelineAPIController.SaveOverwriteActions))
        val action = PipelineAPIController.SaveOverwriteActions.last
        ScopeDenialWording.assertBody(body, ScopeDenialWording.message("ci_scoped_w", "pipeline", action, ScopedCaps), Hidden)
    }

    test("pipelines: version restore of a pipeline outside the key's scope (restoreGuardDecision)") {
        val notFound = ResponseEntity.status(404).body[String]("{\"error\": \"not found\"}")
        val r = EntityVersionAPIController.restoreGuardDecision(req(scoped), hrPipeline, hrPipeline, () => Seq(hrPipeline), notFound)
        assert(r.isLeft, r)
        val body = forbidden(r.left.toOption)
        val action = PipelineAPIController.RestoreOverwriteActions.last
        ScopeDenialWording.assertBody(body, ScopeDenialWording.message("ci_scoped_w", "pipeline", action, ScopedCaps), Hidden)
        // a deleted pipeline whose name a hidden pipeline still lists
        val snap = pg("hr_old", "CiSales", owner = "ci_scoped_w")
        val renamed = pg("hr_v2", "CiHR", prev = Seq("hr_old"))
        val alias = EntityVersionAPIController.pipelineRestoreAliasRefusal(req(scoped), snap, Seq(renamed), notFound)
        ScopeDenialWording.assertBody(forbidden(alias), ScopeDenialWording.message("ci_scoped_w", "pipeline", action, ScopedCaps), Hidden)
    }

    test("pipelines: a hidden pipeline's previous name, refused on read (readScopeDenied)") {
        val caps = Seq("pipeline:create", "pipeline:update", "pipeline:read:catalog=CiSales")
        val writer = key("ci_writer", caps: _*)
        val renamed = pg("hr_v2", "CiHR", prev = Seq("hr_old"))
        val body = forbidden(
            PipelineAPIController.hiddenAliasSaveRefusal(req(writer), pg("hr_old", "CiSales"), Seq(renamed), PipelineAPIController.SaveOverwriteActions)
        )
        ScopeDenialWording.assertBody(
            body,
            "capability denied: key 'ci_writer' does not hold capability 'pipeline:read' for the targeted resource. " +
                "The key's pipeline capabilities are: pipeline:create, pipeline:read:catalog=CiSales, pipeline:update. The target is outside that scope.",
            Hidden
        )
    }

    test("taps: a tap outside the key's owner and catalog scope") {
        val tap = TapConfig(name = "hr_tap", description = "d", targetPipeline = "hr_payroll", catalog = "CiHR", createdByKeyLabel = "vscode-claude")
        Seq("read", "update", "run", "delete").foreach { action =>
            val body = forbidden(TapAPIController.scopeRefusal(req(scoped, "GET", "/api/v1/tap"), tap, action))
            ScopeDenialWording.assertBody(body, ScopeDenialWording.message("ci_scoped_w", "tap", action, ScopedCaps), Hidden)
        }
        assert(
            ScopeDenialWording.message("ci_scoped_w", "tap", "read", ScopedCaps) ==
                "capability denied: key 'ci_scoped_w' does not hold capability 'tap:read' for the targeted resource. " +
                "The key's tap capabilities are: tap:read:owner=self. The target is outside that scope."
        )
    }

    test("catalog operations: the 403 body and the per-item failed[] error text") {
        val (r, _) = ScopeDenialFixtures.request(scoped, "DELETE", "/api/v1/catalog/CiHR")
        Seq("pipeline" -> "delete", "tap" -> "update", "tap" -> "run").foreach { case (resource, action) =>
            val e = intercept[CapabilityDeniedException](
                CapabilityCheck.assertScope(r, resource, action, CatalogOps.scopeContext("CiHR", "vscode-claude"))
            )
            val expected = ScopeDenialWording.message("ci_scoped_w", resource, action, ScopedCaps)
            // failed[].error carries e.getMessage verbatim
            assert(e.getMessage == expected, e.getMessage)
            ScopeDenialWording.assertHides(e.getMessage, Hidden)
            ScopeDenialWording.assertBody(CatalogOps.capabilityDeniedBody(e.getMessage), expected, Hidden)
        }
    }

    test("field protection: the body has the new wording; the audit row keeps the detail") {
        val c = new FieldProtectionAPIController
        val (r, _) = ScopeDenialFixtures.request(scoped, "POST", "/api/v1/protect/reveal")
        val res = c.reveal("any", RevealRequest("hr_payroll", "email", java.util.Arrays.asList("enc:v1:AAAA")), r)
        assert(res.getStatusCode.value == 403, res)
        ScopeDenialWording.assertBody(
            res.getBody,
            "capability denied: key 'ci_scoped_w' does not hold capability 'protect:reveal' for the targeted resource. The key holds no protect capability.",
            Nil
        )
        val rows = captured.synchronized(captured.toList).filter(e => e.category == "security" && e.action == "denied")
        assert(rows.size == 1, captured)
        assert(
            rows.head.errorMessage.contains(
                "capability denied: key 'ci_scoped_w' does not hold capability 'protect:reveal' for the targeted resource (scope: —)"
            ),
            rows.head.errorMessage
        )
        assert(rows.head.metadata.exists(_.get("required").getAsString == "protect:reveal"), rows.head.metadata)
    }

    test("secrets: the 403 body for a tap-scoped key refused a platform secret, valid JSON for a backslash in the key's capability") {
        def refusal(rk: ResolvedKey): String = {
            val e = intercept[CapabilityDeniedException] {
                CapabilityCheck.assertScope(req(rk, "GET", "/api/v1/secrets/ai-primary"), "secret", "read", Map("_type" -> "platform"))
            }
            val r = SecretsAPIController.capabilityDenied(e)
            assert(r.getStatusCode.value == 403, r)
            r.getBody
        }
        val caps = Seq("secret:read:_type=tap", "pipeline:read")
        ScopeDenialWording.assertBody(
            refusal(key("ci_tap_secrets", caps: _*)),
            ScopeDenialWording.message("ci_tap_secrets", "secret", "read", caps),
            Seq("platform")
        )

        // A capability string with a backslash (and a quote) is escaped, not
        // spliced: the body parses and its message is the exact text.
        val odd = Seq("secret:read:_type=ta\\p\"x")
        val body = refusal(key("ci_odd", odd: _*))
        ScopeDenialWording.assertBody(body, ScopeDenialWording.message("ci_odd", "secret", "read", odd), Seq("platform"))
    }
}
