package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.TapConfig
import org.scalatest.funsuite.AnyFunSuite

/** Which saved taps would fail closed under the tap secret scope
  * (plans/stories/field-protection-8-tap-secret-scope.md).
  *
  * Pinned: `TapSecretScopeScan.offenders(taps: List[TapConfig],
  * typeOf: String => Option[Option[String]]): List[(String, String)]` — pure;
  * returns (tap name, secret name) in tap order. `typeOf(secretName)`: outer
  * None = the secret does not exist, inner Option = its stored `_type`. */
class TapSecretScopeScanSpec extends AnyFunSuite {

    private def tap(name: String, secret: String) =
        TapConfig(name = name, description = "d", targetPipeline = "p", secretName = secret)

    /** Secrets by bare name; tolerant of an `<env>/<name>` path. */
    private val store: Map[String, Option[String]] = Map(
        "weather-api" -> Some("tap"),
        "github-tap" -> Some("tap"),
        "ai-primary" -> Some("ai-provider"),
        "databricks" -> None,
        "legacy-hand-made" -> None
    )
    private val typeOf: String => Option[Option[String]] = n => store.get(n.split("/").last)

    test("taps without a secret are skipped") {
        val taps = List(tap("a", null), tap("b", ""))
        assert(TapSecretScopeScan.offenders(taps, typeOf).isEmpty)
    }

    test("taps on tap-typed secrets are not offenders") {
        val taps = List(tap("weather", "weather-api"), tap("gh", "github-tap"))
        assert(TapSecretScopeScan.offenders(taps, typeOf).isEmpty)
    }

    test("taps on untyped or platform-typed secrets are offenders") {
        val taps = List(
            tap("a", "ai-primary"),
            tap("weather", "weather-api"),
            tap("b", "databricks"),
            tap("c", "legacy-hand-made"),
            tap("none", null)
        )
        assert(TapSecretScopeScan.offenders(taps, typeOf) == List(("a", "ai-primary"), ("b", "databricks"), ("c", "legacy-hand-made")))
    }

    test("secrets that do not exist are skipped") {
        val taps = List(tap("ghost", "deleted-secret"), tap("a", "ai-primary"))
        assert(TapSecretScopeScan.offenders(taps, typeOf) == List(("a", "ai-primary")))
    }

    // ---- review follow-ups: a failed read is not "absent"; one read per secret

    test("a failed secret read is reported as unreadable, not skipped as absent") {
        val lookup: String => scala.util.Try[Option[Option[String]]] = {
            case "vault-down" => scala.util.Failure(new RuntimeException("503"))
            case n => scala.util.Success(typeOf(n))
        }
        val taps = List(tap("x", "vault-down"), tap("a", "ai-primary"), tap("y", "vault-down"))
        val r = TapSecretScopeScan.scan(taps, lookup)
        assert(r.offenders == List(("a", "ai-primary")))
        assert(r.unreadable == List("vault-down"))
        // A lookup that throws is a failed read too.
        val r2 = TapSecretScopeScan.scan(List(tap("z", "boom")), _ => throw new RuntimeException("boom"))
        assert(r2.unreadable == List("boom") && r2.refs.isEmpty)
    }

    test("each distinct secret is read once per scan") {
        val reads = scala.collection.mutable.ListBuffer[String]()
        val lookup: String => scala.util.Try[Option[Option[String]]] = n => { reads += n; scala.util.Success(typeOf(n)) }
        val taps = List(tap("a", "ai-primary"), tap("b", "ai-primary"), tap("w", "weather-api"), tap("c", "ai-primary"))
        val r = TapSecretScopeScan.scan(taps, lookup)
        assert(reads.toList == List("ai-primary", "weather-api"), reads.toString)
        assert(r.offenders == List(("a", "ai-primary"), ("b", "ai-primary"), ("c", "ai-primary")))
    }
}
