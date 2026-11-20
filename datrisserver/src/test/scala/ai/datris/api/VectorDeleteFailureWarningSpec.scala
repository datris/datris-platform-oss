package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.{PipelineDeletePlan, VectorContainers, VectorStoreDrop}
import com.google.gson.JsonParser
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Story: Pipeline delete: a vector collection that could not be removed is
  * reported, never logged as deleted
  * (plans/stories/vector-store-delete-reports-failure.md), Acceptance,
  * `VectorDeleteFailureWarningSpec`.
  *
  * `cleanupDestinationData` reads the store secrets through the Vault-backed
  * `SecretsUtil`, which has no test override, so the delete path itself is
  * not driven here: the warning text, the response body, the ledger rule and
  * the cascade rendering go through their pure seams, and where the
  * controller wires them together a source pin checks the wiring (as
  * MongoReservedDestinationSpec does for its delete warning). */
class VectorDeleteFailureWarningSpec extends AnyFunSuite {

    private val full = PipelineDeletePlan.of("true", "true")
    private val reset = PipelineDeletePlan.of("true", "false")

    private def key(store: String, name: String): VectorContainers.Key = VectorContainers.key(store, name, None).get

    private def source(rel: String): String =
        new String(
            java.nio.file.Files.readAllBytes(
                Seq(java.nio.file.Paths.get("src/main/scala/" + rel), java.nio.file.Paths.get("datrisserver/src/main/scala/" + rel))
                    .find(java.nio.file.Files.exists(_))
                    .get
            ),
            java.nio.charset.StandardCharsets.UTF_8
        )

    private lazy val controller = source("ai/datris/api/PipelineAPIController.scala")

    /** The text of `def <name>(` up to the next member definition at the same indent. */
    private def method(name: String): String = {
        val start = controller.indexOf("    def " + name + "(") match {
            case -1 => controller.indexOf("    private[api] def " + name + "(") match {
                    case -1 => controller.indexOf("    private def " + name + "(")
                    case i => i
                }
            case i => i
        }
        assert(start >= 0, s"$name not found")
        val next = "\n    (private |private\\[api\\] |override )?(def|val|lazy val|var) ".r
        val bodyFrom = controller.indexOf("\n", start) + 1
        val end = next.findFirstMatchIn(controller.substring(bodyFrom)).map(m => bodyFrom + m.start).getOrElse(controller.length)
        controller.substring(start, end)
    }

    /** The rest of a call's argument list from `from` up to its closing parenthesis. */
    private def argument(text: String, from: Int): String = {
        var depth = 0
        var i = from
        while (i < text.length && !(text.charAt(i) == ')' && depth == 0)) {
            if (text.charAt(i) == '(') depth += 1
            if (text.charAt(i) == ')') depth -= 1
            i += 1
        }
        text.substring(from, i).trim
    }

    test("no failure adds no warning and deleteResponseBody equals today's body") {
        // A drop that removed the collection, found it absent or sent nothing is not a failure.
        Seq(VectorStoreDrop.Removed, VectorStoreDrop.Absent, VectorStoreDrop.NotSent).foreach { o =>
            assert(!o.isInstanceOf[VectorStoreDrop.Failed], o)
        }
        // Today's bodies, byte for byte.
        assert(PipelineAPIController.deleteResponseBody(Nil, None) == "{\"warnings\":[]}")
        val kept = VectorContainers.keptOnDeleteMessage(key("qdrant", "shared_c"), Some(Seq("other")))
        assert(PipelineAPIController.deleteResponseBody(Seq(kept), None) == "{\"warnings\":[\"" + kept + "\"]}")
        assert(PipelineAPIController.deleteResponseBody(Nil, Some(Nil)) == "{\"warnings\":[],\"kept\":[]}")

        // Wiring: the inline store calls are gone, every vector store goes
        // through VectorStoreDrop.drop, and only a failure reaches warnings.
        val cleanup = method("cleanupDestinationData")
        val ownConnection = cleanup.contains("HttpURLConnection")
        assert(!ownConnection, "cleanupDestinationData still opens its own HttpURLConnection")
        // The store calls live in the extracted dropVectorContainers, which
        // cleanupDestinationData calls (behaviour: VectorDropContainersSpec).
        assert(cleanup.contains("PipelineAPIController.dropVectorContainers("), "cleanupDestinationData never calls dropVectorContainers")
        val extracted = method("dropVectorContainers")
        assert(!extracted.contains("HttpURLConnection"), "dropVectorContainers opens its own HttpURLConnection")
        val drops = extracted.split(java.util.regex.Pattern.quote("VectorStoreDrop.drop("), -1).length - 1
        assert(drops >= 1, "dropVectorContainers never calls VectorStoreDrop.drop")
        val addsWarning = method("deletePipelineOutcome").contains("VectorStoreDrop.warning(")
        assert(addsWarning, "deletePipelineOutcome never adds a drop-failure warning")
    }

    test("one failure per store yields one warning each, after the Kept warnings, in store order") {
        val reason = "the store could not be reached"
        val keys = Seq(key("qdrant", "vd_q"), key("weaviate", "VdW"), key("milvus", "vd_m"), key("chroma", "vd_c"))
        val ws = keys.map(k => VectorStoreDrop.warning(k, reason, configDeleted = true))
        assert(ws.distinct.size == 4, ws)
        ws.zip(keys).foreach { case (w, k) =>
            assert(w.startsWith("Could not remove " + VectorContainers.label(k) + ": "), w)
            keys.filterNot(_ == k).foreach(o => assert(!w.contains(" " + o.name + ":"), s"$w names ${o.name}"))
        }
        val kept = VectorContainers.keptOnDeleteMessage(key("pgvector", "shared_t"), None)
        val body = JsonParser.parseString(PipelineAPIController.deleteResponseBody(kept +: ws, None)).getAsJsonObject
        assert(body.get("warnings").getAsJsonArray.asScala.map(_.getAsString).toList == (kept +: ws).toList, body)

        // Store order in the delete: qdrant, weaviate, milvus, chroma.
        val cleanup = method("cleanupDestinationData")
        val order = Seq("qdrantSecretName", "weaviateSecretName", "milvusSecretName", "chromaSecretName").map(cleanup.indexOf(_))
        assert(order.forall(_ >= 0) && order == order.sorted, s"store order in cleanupDestinationData: $order")
        // The failure warnings are added after the Kept warnings and before the ledger decision.
        val outcome = method("deletePipelineOutcome")
        val keptAt = outcome.indexOf("PipelineAPIController.vectorDeleteWarnings(")
        val failAt = outcome.indexOf("VectorStoreDrop.warning(")
        val ledgerAt = outcome.indexOf("clearTapLedgersForPlan(")
        assert(keptAt >= 0 && failAt > keptAt && ledgerAt > failAt, s"kept=$keptAt failure=$failAt ledgers=$ledgerAt")
    }

    test("a reset with a failed drop keeps the tap ledgers; a full delete with a failed drop clears them; a reset with no failure clears them") {
        // The ledger rule is fed "kept a shared container OR a drop failed".
        assert(!PipelineAPIController.clearTapLedgersForPlan(reset, keptSharedVector = true))
        assert(PipelineAPIController.clearTapLedgersForPlan(full, keptSharedVector = true))
        assert(PipelineAPIController.clearTapLedgersForPlan(reset, keptSharedVector = false))

        val outcome = method("deletePipelineOutcome")
        val at = outcome.indexOf("clearTapLedgersForPlan(plan,")
        assert(at >= 0, "clearTapLedgersForPlan(plan, ...) not found in deletePipelineOutcome")
        val arg = argument(outcome, at + "clearTapLedgersForPlan(plan,".length)
        // The argument is the disjunction itself, or a local val defined as it.
        val definition =
            if (arg.matches("[A-Za-z_][A-Za-z0-9_]*"))
                ("val " + arg + "\\s*(:\\s*Boolean\\s*)?=([^\\n]*)").r.findFirstMatchIn(outcome).map(_.group(2)).getOrElse(arg)
            else arg
        assert(definition.contains("sharedVector.nonEmpty") && definition.contains("||"), s"ledger argument ignores drop failures: $arg / $definition")
    }

    test("the failure warning names the weaviate class as configured: deletePipelineOutcome passes the display key") {
        // The delete itself needs Vault (store secrets), so the wiring is pinned in source.
        val outcome = method("deletePipelineOutcome")
        val at = outcome.indexOf("VectorStoreDrop.warning(")
        assert(at >= 0)
        val arg = argument(outcome, at + "VectorStoreDrop.warning(".length)
        assert(arg.startsWith("VectorContainers.displayKey("), s"warning key is not the display key: $arg")
        assert(arg.contains(", config)"), arg)
    }

    test("the cascade renders a failed drop as {pipeline, message}") {
        val w = VectorStoreDrop.warning(key("qdrant", "vd_q"), "the store could not be reached", configDeleted = true)
        val arr = CatalogAPIController.warningsArray(Seq("vd_q" -> w), Nil)
        assert(arr.size == 1, arr)
        val o = arr.get(0).getAsJsonObject
        assert(o.keySet.asScala.toSet == Set("pipeline", "message"), o)
        assert(o.get("pipeline").getAsString == "vd_q")
        assert(
            o.get("message").getAsString ==
                "Could not remove qdrant collection vd_q: the store could not be reached. Its pipeline configuration was deleted; remove it in the store.",
            o
        )
    }
}
