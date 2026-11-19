package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.CapabilityScopes
import ai.datris.model.Capability
import com.google.gson.JsonObject
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Story: API keys, a scope the server does not apply can no longer be put on
  * a key, and stored ones stop granting (plans/stories/api-key-inert-scopes.md),
  * Acceptance, `KeyIssueScopeSpec`.
  *
  * `issueKey`, `listKeys` and the rest read and write the key secrets through
  * `ai.datris.util.SecretsUtil` (a Vault-backed lazy val), so they are not
  * driven here: a valid capability would reach the real secret store. The
  * 400 at issue is covered by CapabilityScopesSpec (`issueProblem`) and the
  * story's Verify step 1.
  *
  * Pinned seam (does not exist at d3e13515; the implementation adds it):
  * {{{
  * // ai.datris.api.KeysAPIController companion: the list row builder moved
  * // out of the class (no secret access), used by listKeys.
  * private[api] def buildListRow(label: String, metaJsonOpt: Option[String]): JsonObject
  * // the row gains `inertCapabilities`: a JSON array of raw strings, empty when none
  * // ai.datris.auth.CapabilityScopes
  * def inertOf(rawCaps: Seq[String]): Seq[String]
  * }}}
  */
class KeyIssueScopeSpec extends AnyFunSuite {

    private def template(name: String): (String, String, Seq[String]) =
        KeysAPIController.Templates.find(_._1 == name).getOrElse(fail("no template " + name))

    private def strs(o: JsonObject, field: String): Seq[String] = {
        assert(o.has(field) && o.get(field).isJsonArray, field + " missing or not an array: " + o)
        o.getAsJsonArray(field).asScala.map(_.getAsString).toList
    }

    test("no template carries a scope the server does not apply") {
        assert(KeysAPIController.Templates.nonEmpty)
        KeysAPIController.Templates.foreach { case (name, _, caps) =>
            caps.foreach(raw => assert(CapabilityScopes.issueProblem(raw).isEmpty, s"$name: $raw -> ${CapabilityScopes.issueProblem(raw)}"))
            val (kept, inert) = CapabilityScopes.partition(Capability.parseList(caps))
            assert(inert.isEmpty, s"$name: ${inert.map(_.raw)}")
            assert(kept.map(_.raw) == caps, s"$name keeps every capability as issued")
        }
    }

    test("the rag-builder description does not say 'in one catalog' and the ops description does not say it runs pipelines") {
        val (_, rag, ragCaps) = template("rag-builder")
        assert(!rag.toLowerCase.contains("in one catalog"), rag)
        val (_, ops, opsCaps) = template("ops")
        val o = ops.toLowerCase
        Seq("tap or pipeline", "pipeline or tap", "run pipelines", "runs pipelines", "run any pipeline", "run a pipeline", "taps and pipelines")
            .foreach(p => assert(!o.contains(p), "'" + p + "' in: " + ops))
        // D5: descriptions only; the capability lists are unchanged.
        assert(
            ragCaps == Seq(
                "pipeline:create",
                "pipeline:read",
                "pipeline:run:owner=self",
                "tap:create",
                "tap:read",
                "tap:run:owner=self",
                "document:upload",
                "search:vector",
                "secret:read:_type=tap",
                "secret:write:_type=tap",
                "job:read"
            ),
            ragCaps
        )
        assert(opsCaps == Seq("pipeline:read", "pipeline:run", "tap:read", "tap:run", "job:read", "job:kill"), opsCaps)
    }

    test("a list row for a key with job:read:owner=self carries it under inertCapabilities; a rag-builder key's row has an empty array") {
        val inertMeta = """{"capabilities":["job:read:owner=self","pipeline:read"],"revoked":false,"keyId":"k_1"}"""
        val row = KeysAPIController.buildListRow("ik_old", Some(inertMeta))
        assert(row.get("label").getAsString == "ik_old")
        assert(strs(row, "capabilities") == Seq("job:read:owner=self", "pipeline:read"), "capabilities stay as stored: " + row)
        assert(strs(row, "inertCapabilities") == Seq("job:read:owner=self"), row)
        assert(!row.get("isLegacyFullAccess").getAsBoolean && !row.get("revoked").getAsBoolean, row)
        assert(CapabilityScopes.inertOf(Seq("job:read:owner=self", "pipeline:read")) == Seq("job:read:owner=self"))

        val rag = template("rag-builder")._3
        val ragMeta = "{\"capabilities\":[" + rag.map("\"" + _ + "\"").mkString(",") + "],\"revoked\":false}"
        val ragRow = KeysAPIController.buildListRow("rag", Some(ragMeta))
        assert(strs(ragRow, "capabilities") == rag)
        assert(strs(ragRow, "inertCapabilities").isEmpty, ragRow)

        // every row carries the field, a legacy key (no metadata) included
        val legacy = KeysAPIController.buildListRow("legacy", None)
        assert(legacy.get("isLegacyFullAccess").getAsBoolean)
        assert(strs(legacy, "inertCapabilities").isEmpty, legacy)
    }
}

/** Test-only bridge to the `private[api]` key templates, so specs outside
  * `ai.datris.api` (CapabilityScopesSpec) can read them. */
object KeyTemplates {
    def all: Seq[(String, String, Seq[String])] = KeysAPIController.Templates
}
