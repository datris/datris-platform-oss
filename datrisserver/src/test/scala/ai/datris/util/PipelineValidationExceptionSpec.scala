package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisException, PipelineConfig, ValidationException}
import com.google.gson.Gson
import org.scalatest.funsuite.AnyFunSuite

/** Story: an invalid pipeline config is a 400, not a 500
  * (plans/stories/pipeline-save-validation-400.md).
  *
  * `PipelineValidatorUtil.validate` wraps its body so any refusal leaves as
  * `ai.datris.model.ValidationException(message) extends DatrisException`,
  * carrying exactly the message the validator produced before the change
  * (story Out of scope: no validator message changes). The expected strings
  * below were captured from the validator on this branch before the change.
  * Configs are built with Gson the same way PipelineValidatorUtilSpec does;
  * every one fails before the existing-pipeline lookup, so no live store is
  * needed. */
class PipelineValidationExceptionSpec extends AnyFunSuite {

    private val gson = new Gson()

    private def parse(json: String): PipelineConfig = gson.fromJson(json, classOf[PipelineConfig])

    private val postgres = """"database":{"dbName":"datris","schema":"public","table":"fp","usePostgres":true}"""

    private def csvSource(fields: String): String =
        s""""source":{"fileAttributes":{"csvAttributes":{"header":true}},"schemaProperties":{"fields":$fields}}"""

    private def refusal(cfg: PipelineConfig): Throwable =
        try { PipelineValidatorUtil.validate(cfg); fail("validate accepted an invalid config") }
        catch { case e: Throwable => e }

    private def assertValidation(cfg: PipelineConfig, expected: String): Unit = {
        val e = refusal(cfg)
        assert(e.isInstanceOf[ValidationException], s"expected ValidationException, got ${e.getClass.getName}: ${e.getMessage}")
        assert(e.isInstanceOf[DatrisException], s"a ValidationException must still be a DatrisException: ${e.getClass.getName}")
        assert(e.getMessage == expected)
    }

    test("a refusal is a ValidationException and still a DatrisException with the same message: unknown protect method") {
        assertValidation(
            parse(s"""{"name":"fp",${csvSource("""[{"name":"mrn","type":"string","protect":{"method":"scramble"}}]""")},"destination":{$postgres}}"""),
            "Field 'mrn': unknown protect.method 'scramble' (hmac, mask, redact, drop, encrypt)"
        )
    }

    test("a refusal is a ValidationException and still a DatrisException with the same message: reserved protect method") {
        assertValidation(
            parse(s"""{"name":"fp",${csvSource("""[{"name":"mrn","type":"string","protect":{"method":"fpe"}}]""")},"destination":{$postgres}}"""),
            "Field 'mrn': protect.method 'fpe' is not yet supported"
        )
    }

    test("a refusal is a ValidationException and still a DatrisException with the same message: duplicate destination field") {
        assertValidation(
            parse(
                s"""{"name":"fp",${csvSource("""[{"name":"id","type":"string"},{"name":"mrn","type":"string"}]""")},
                   |"destination":{$postgres,"schemaProperties":{"fields":[{"name":"id","type":"string"},{"name":"ID","type":"string"}]}}}""".stripMargin
            ),
            "Duplicate field name(s) found in destination schema: id"
        )
    }

    test("a refusal is a ValidationException and still a DatrisException with the same message: preset with an unprotected identifier") {
        val cfg = parse(
            s"""{"name":"fp",${csvSource(
                    """[{"name":"mrn","type":"string","protect":{"method":"hmac"}},{"name":"phone","type":"string"},{"name":"visit_count","type":"int"}]"""
                )},
               |"destination":{$postgres},"protection":{"preset":"hipaa-safe-harbor"}}""".stripMargin
        )
        assert(cfg.protection != null && cfg.protection.preset != null, "fixture must parse protection.preset")
        assertValidation(
            cfg,
            "Preset 'hipaa-safe-harbor': field 'phone' looks like phone and has no protection. Add protect to it or list it under protection.presetExempt"
        )
    }

    test("a refusal is a ValidationException and still a DatrisException with the same message: missing name") {
        assertValidation(parse("{}"), "pipeline 'name' is not defined in the JSON")
    }

    test("existing DatrisException handlers still catch a refusal") {
        val caught =
            try { PipelineValidatorUtil.validate(parse("{}")); None }
            catch { case d: DatrisException => Some(d) }
        assert(caught.exists(_.isInstanceOf[ValidationException]), s"got: $caught")
    }

    test("ValidationException is constructed from a message and is a DatrisException") {
        val v: DatrisException = new ValidationException("bad config")
        assert(v.getMessage == "bad config")
    }

    test("a valid config still passes validate") {
        val ok = parse(s"""{"name":"fp",${csvSource("""[{"name":"id","type":"string"},{"name":"mrn","type":"string"}]""")},"destination":{$postgres}}""")
        PipelineValidatorUtil.validate(ok)
    }
}
