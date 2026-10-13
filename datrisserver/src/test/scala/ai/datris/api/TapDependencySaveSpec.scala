package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.JsonParser
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Story: Tap dependencies (plans/stories/tap-dependency-order.md), Acceptance
  * bullet 5: `POST /tap` with `dependsOn: ["nope"]` -> 400 naming it; with a
  * cycle -> 409 whose body has `cycle`; `inferredDependsOn` in a POST body is
  * ignored and re-stamped from the script.
  *
  * `saveTapInternal` reads Mongo and the script store, so this spec pins the
  * two pure seams it calls (same style as `tapSecretSaveProblem`):
  *
  * {{{
  * object TapAPIController {
  *   /** The refusal `POST /tap` returns for the tap's `dependsOn`, or None.
  *     * `catalogTaps` = the stored taps of the tap's catalog; a stored copy of
  *     * the incoming tap is replaced by it before the cycle check.
  *     * 400 {"error": "...<unknown name>..."} for a name that is not a tap in
  *     * the catalog (or the tap itself); 409 {"error": "...", "cycle": [...]}. */
  *   def dependencySaveProblem(tap: TapConfig, catalogTaps: Seq[TapConfig]): Option[ResponseEntity[String]]
  *
  *   /** The tap as stored: any incoming `inferredDependsOn` is discarded and
  *     * replaced by `TapDependencies.infer` over `script` (None, e.g. an HTTP
  *     * tap -> null). */
  *   def stampInferredDependsOn(tap: TapConfig, script: Option[String],
  *                              pipelines: Seq[PipelineConfig], taps: Seq[TapConfig]): TapConfig
  * }
  * }}}
  * The live round trip is also in the story's manual Verify (E2E).
  */
class TapDependencySaveSpec extends AnyFunSuite {

    private def jl(xs: String*): java.util.List[String] = new java.util.ArrayList[String](xs.asJava)

    private def tap(name: String, dependsOn: java.util.List[String] = null, target: String = null, catalog: String = "sales"): TapConfig =
        TapConfig(name = name, description = "d", targetPipeline = target, catalog = catalog, dependsOn = dependsOn)

    private val stored = Seq(tap("a", target = "pa"), tap("b", dependsOn = jl("a"), target = "pb"))

    test("dependsOn naming an unknown tap is refused with 400 naming it") {
        val r = TapAPIController.dependencySaveProblem(tap("c", dependsOn = jl("a", "nope")), stored)
        assert(r.isDefined, "unknown dependsOn must be refused")
        assert(r.get.getStatusCode.value() == 400)
        assert(r.get.getBody.contains("nope"), r.get.getBody)
    }

    test("dependsOn naming the tap itself is refused with 400") {
        val r = TapAPIController.dependencySaveProblem(tap("c", dependsOn = jl("c")), stored)
        assert(r.map(_.getStatusCode.value()).contains(400), r)
    }

    test("a dependsOn that closes a cycle is refused with 409 whose body has cycle") {
        // b already depends on a; saving a with dependsOn [b] closes a -> b -> a.
        val r = TapAPIController.dependencySaveProblem(tap("a", dependsOn = jl("b"), target = "pa"), stored)
        assert(r.isDefined, "cycle must be refused")
        assert(r.get.getStatusCode.value() == 409)
        val body = JsonParser.parseString(r.get.getBody).getAsJsonObject
        assert(body.has("error"))
        assert(body.has("cycle"), r.get.getBody)
        val cyc = body.getAsJsonArray("cycle").asScala.map(_.getAsString).toSet
        assert(cyc == Set("a", "b"), cyc)
    }

    test("a declared cycle is refused even when another cycle sorts first") {
        // a <-> b is an existing (inferred) cycle; y infers z; saving z with
        // dependsOn [y] closes a second cycle that must still be refused.
        val catalog = Seq(
            tap("a").copy(inferredDependsOn = jl("b")),
            tap("b").copy(inferredDependsOn = jl("a")),
            tap("y").copy(inferredDependsOn = jl("z"))
        )
        val r = TapAPIController.dependencySaveProblem(tap("z", dependsOn = jl("y")), catalog)
        assert(r.map(_.getStatusCode.value()).contains(409), r)
        val cyc = JsonParser.parseString(r.get.getBody).getAsJsonObject.getAsJsonArray("cycle").asScala.map(_.getAsString).toSet
        assert(cyc == Set("y", "z"), cyc)
    }

    test("valid or absent dependsOn passes") {
        assert(TapAPIController.dependencySaveProblem(tap("c", dependsOn = jl("a", "b")), stored).isEmpty)
        assert(TapAPIController.dependencySaveProblem(tap("c"), stored).isEmpty)
        assert(TapAPIController.dependencySaveProblem(tap("c", dependsOn = jl()), stored).isEmpty)
    }

    private val pipelines = Seq(
        PipelineConfig(
            name = "pa",
            catalog = "sales",
            destination = Destination(database = Database(dbName = "datris", schema = "public", table = "orders", usePostgres = true))
        ),
        PipelineConfig(name = "pc", catalog = "sales")
    )
    private val all = stored :+ tap("c", target = "pc")

    test("inferredDependsOn in the body is ignored and re-stamped from the script") {
        val incoming = tap("c", target = "pc").copy(inferredDependsOn = jl("bogus"))
        val script = "requests.post(base + '/api/v1/query/postgres', json={'sql': 'SELECT * FROM public.orders'})"
        val saved = TapAPIController.stampInferredDependsOn(incoming, Some(script), pipelines, all)
        assert(saved.inferredDependsOn == jl("a"), saved.inferredDependsOn)
    }

    test("no script (HTTP tap) stamps null, whatever the body said") {
        val incoming = tap("c", target = "pc").copy(inferredDependsOn = jl("bogus"))
        assert(TapAPIController.stampInferredDependsOn(incoming, None, pipelines, all).inferredDependsOn == null)
    }

    test("the explicit dependsOn is left as sent") {
        val incoming = tap("c", dependsOn = jl(), target = "pc")
        val script = "requests.post(base + '/api/v1/query/postgres', json={'sql': 'SELECT * FROM orders'})"
        val saved = TapAPIController.stampInferredDependsOn(incoming, Some(script), pipelines, all)
        assert(saved.dependsOn == jl())
        assert(saved.inferredDependsOn == jl("a"))
    }
}
