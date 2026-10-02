package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import ai.datris.util.FieldProtectionAdvisor._
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ListBuffer

/** Field protection 3: AI suggestions from field names and types only
  * (plans/stories/field-protection-3-classifier.md).
  *
  * The story leaves the AI hook's shape to the implementer; this spec pins it.
  * No existing codegen spec stubs the LLM (CodeGenRuleEvaluatorSpec does not
  * exist; CodeGenTransformationEvaluatorSpec only tests pure helpers), so the
  * seam follows the injected-function style of ConfigToolExecutor's `restCall`:
  *
  * {{{
  * // FieldProtectionAdvisor.scala (types may be top-level in ai.datris.util or
  * // nested in the object; this spec imports FieldProtectionAdvisor._ so both compile)
  * case class Suggested(name: String, `type`: String, current: ProtectionPolicy,
  *                      suggested: ProtectionPolicy, reason: String)
  * case class FieldProtectionSuggestion(fields: List[Suggested], model: String)
  *
  * object FieldProtectionAdvisor {
  *     // production: AIUtil.callAIWithSystem + extractText against
  *     // DatrisEnvironment.aiConfigForCodegen, model = that config's model
  *     def suggest(fields: List[SchemaField]): FieldProtectionSuggestion
  *     // test seam: `ai(systemPrompt, userPrompt)` returns the model's already
  *     // extracted TEXT (the JSON array), `model` is reported back verbatim
  *     private[datris] def suggest(fields: List[SchemaField],
  *                                 ai: (String, String) => String,
  *                                 model: String): FieldProtectionSuggestion
  * }
  * }}}
  *
  * "No suggestion" may be represented as `suggested == null`, a policy with a
  * null method, or method "none"; the spec treats all three as none.
  */
class FieldProtectionAdvisorSpec extends AnyFunSuite {

    private val Model = "stub-model"

    private def f(name: String, t: String, p: ProtectionPolicy = null) = SchemaField(name, t, p)

    private val Input = List(
        f("mrn", "string"),
        f("email", "string"),
        f("notes", "string"),
        f("visit_count", "int")
    )

    /** Runs the advisor with a canned model answer; returns the result and the (system, user) prompts sent. */
    private def run(fields: List[SchemaField], answer: String): (FieldProtectionSuggestion, ListBuffer[(String, String)]) = {
        val calls = ListBuffer[(String, String)]()
        val ai: (String, String) => String = (sys, user) => { calls += ((sys, user)); answer }
        (FieldProtectionAdvisor.suggest(fields, ai, Model), calls)
    }

    private def method(s: Suggested): String =
        Option(s.suggested).flatMap(p => Option(p.method)).map(_.toLowerCase).filter(_ != "none").getOrElse("none")

    private def preserve(s: Suggested): String =
        Option(s.suggested).map(_.preserve).orNull

    private def byName(r: FieldProtectionSuggestion): Map[String, Suggested] = r.fields.map(s => s.name -> s).toMap

    private val GoodAnswer =
        """[
          |  {"name": "mrn", "method": "hmac", "preserve": null, "reason": "stable identifier"},
          |  {"name": "email", "method": "mask", "preserve": "domain", "reason": "contact field, domain is useful"},
          |  {"name": "notes", "method": "redact", "preserve": null, "reason": "free text"},
          |  {"name": "visit_count", "method": "none", "preserve": null, "reason": "a count"}
          |]""".stripMargin

    test("user prompt lists name:type lines and nothing else") {
        // A field already protected must not leak its policy (or anything else) into the prompt.
        val fields = Input :+ f("ssn", "string", ProtectionPolicy("mask", "last4", null))
        val (_, calls) = run(fields, GoodAnswer)
        assert(calls.size == 1, s"expected exactly one model call, got ${calls.size}")
        val user = calls.head._2
        val lines = user.split("\r?\n").map(_.trim).filter(_.nonEmpty).toList
        assert(lines == List("mrn:string", "email:string", "notes:string", "visit_count:int", "ssn:string"),
            s"user prompt must be name:type lines only, got:\n$user")
        assert(!user.contains("last4") && !user.contains("mask"), user)
    }

    test("a well-formed answer maps to suggestions with reasons") {
        val (r, _) = run(Input, GoodAnswer)
        assert(r.model == Model)
        assert(r.fields.map(_.name) == List("mrn", "email", "notes", "visit_count"))
        val m = byName(r)
        assert(method(m("mrn")) == "hmac")
        assert(method(m("email")) == "mask" && preserve(m("email")) == "domain")
        assert(method(m("notes")) == "redact")
        assert(method(m("visit_count")) == "none")
        assert(m("mrn").reason == "stable identifier")
        assert(m("email").reason == "contact field, domain is useful")
        assert(m("notes").reason == "free text")
        assert(m("visit_count").`type` == "int")
    }

    test("names not in the input are ignored") {
        val answer =
            """[
              |  {"name": "mrn", "method": "hmac", "reason": "id"},
              |  {"name": "patient_name", "method": "redact", "reason": "invented"},
              |  {"name": "email", "method": "drop", "reason": "unused"}
              |]""".stripMargin
        val (r, _) = run(Input, answer)
        val names = r.fields.map(_.name)
        assert(!names.contains("patient_name"), names)
        assert(names.toSet.subsetOf(Input.map(_.name).toSet), names)
        assert(method(byName(r)("mrn")) == "hmac")
        assert(method(byName(r)("email")) == "drop")
    }

    test("hmac suggested for a non-string field becomes none") {
        val fields = List(f("visit_count", "int"), f("amount", "double"), f("born", "date"), f("flag", "boolean"))
        val answer =
            """[
              |  {"name": "visit_count", "method": "hmac", "reason": "x"},
              |  {"name": "amount", "method": "mask", "preserve": "last4", "reason": "x"},
              |  {"name": "born", "method": "redact", "reason": "x"},
              |  {"name": "flag", "method": "drop", "reason": "never needed"}
              |]""".stripMargin
        val m = byName(run(fields, answer)._1)
        assert(method(m("visit_count")) == "none")
        assert(method(m("amount")) == "none")
        assert(method(m("born")) == "none")
        // drop is the one method a non-string field may take
        assert(method(m("flag")) == "drop")
    }

    test("preserve is kept only with mask and only from the allowed set") {
        val fields = List(f("a", "string"), f("b", "string"), f("c", "string"), f("d", "string"))
        val answer =
            """[
              |  {"name": "a", "method": "mask", "preserve": "last4", "reason": "x"},
              |  {"name": "b", "method": "mask", "preserve": "first3", "reason": "x"},
              |  {"name": "c", "method": "hmac", "preserve": "last4", "reason": "x"},
              |  {"name": "d", "method": "redact", "preserve": "domain", "reason": "x"}
              |]""".stripMargin
        val m = byName(run(fields, answer)._1)
        assert(method(m("a")) == "mask" && preserve(m("a")) == "last4")
        assert(method(m("b")) == "mask", "an invalid preserve drops the preserve, not the mask")
        assert(preserve(m("b")) == null)
        assert(method(m("c")) == "hmac" && preserve(m("c")) == null)
        assert(method(m("d")) == "redact" && preserve(m("d")) == null)
    }

    test("a reserved or unknown method becomes none") {
        val fields = List(f("a", "string"), f("b", "string"), f("c", "string"), f("d", "string"), f("e", "string"))
        val answer =
            """[
              |  {"name": "a", "method": "fpe", "reason": "x"},
              |  {"name": "b", "method": "tokenize", "reason": "x"},
              |  {"name": "c", "method": "scramble", "reason": "x"},
              |  {"name": "d", "method": null, "reason": "x"},
              |  {"name": "e", "method": "encrypt", "reason": "x"}
              |]""".stripMargin
        val r = run(fields, answer)._1
        assert(r.fields.map(_.name) == fields.map(_.name), "every input field still gets an entry")
        r.fields.foreach(s => assert(method(s) == "none", s"${s.name} -> ${s.suggested}"))
        // Whatever is suggested is always something the story-1 validator accepts.
        r.fields.flatMap(s => Option(s.suggested)).flatMap(p => Option(p.method)).filter(_ != "none")
            .foreach(mth => assert(ProtectionPolicy.Methods.contains(mth)))
    }

    test("a field with an existing protect reports it as current") {
        val existing = ProtectionPolicy("mask", "last4", null)
        val fields = List(f("ssn", "string", existing), f("mrn", "string"))
        val answer =
            """[
              |  {"name": "ssn", "method": "hmac", "reason": "stable identifier"},
              |  {"name": "mrn", "method": "hmac", "reason": "stable identifier"}
              |]""".stripMargin
        val m = byName(run(fields, answer)._1)
        assert(m("ssn").current != null, "existing protect must be reported as current")
        assert(m("ssn").current.method == "mask" && m("ssn").current.preserve == "last4")
        assert(method(m("ssn")) == "hmac", "suggested is reported next to current, not merged into it")
        assert(m("mrn").current == null || m("mrn").current.method == null)
    }
}
