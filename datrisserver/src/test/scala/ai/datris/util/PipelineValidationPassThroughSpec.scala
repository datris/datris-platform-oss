package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.api.ApiErrors
import ai.datris.model.{DatrisException, ValidationException}
import org.scalatest.funsuite.AnyFunSuite

/** Story: an invalid pipeline config is a 400, not a 500
  * (plans/stories/pipeline-save-validation-400.md), review round 1.
  *
  * Only a validator refusal (a DatrisException) becomes a 400. A failure that
  * is not a DatrisException, such as the config store being unreachable,
  * must leave `validate` unchanged and keep classifying as 500.
  * `asValidation` is the wrapper `validate` runs its body through. */
class PipelineValidationPassThroughSpec extends AnyFunSuite {

    test("a non-DatrisException raised inside validate is not converted to a ValidationException") {
        val boom = new RuntimeException("store down")
        val e = intercept[RuntimeException](PipelineValidatorUtil.asValidation[Unit](throw boom))
        assert(e eq boom)
        assert(!e.isInstanceOf[DatrisException])
        assert(ApiErrors.classify(e)._1 == 500)
    }

    test("a store outage still classifies as 500") {
        val (status, body) = ApiErrors.classify(new RuntimeException("store down"))
        assert(status == 500)
        assert(body == ApiErrors.errorBody("store down"))
    }

    test("a DatrisException becomes a ValidationException with the same message and stack") {
        val d = new DatrisException("Field 'mrn': protect.method 'fpe' is not yet supported")
        val e = intercept[ValidationException](PipelineValidatorUtil.asValidation[Unit](throw d))
        assert(e.getMessage == d.getMessage)
        assert(e.getStackTrace.toSeq == d.getStackTrace.toSeq)
        assert(ApiErrors.classify(e)._1 == 400)
    }

    test("a ValidationException passes through as the same instance") {
        val v = new ValidationException("bad")
        assert(intercept[ValidationException](PipelineValidatorUtil.asValidation[Unit](throw v)) eq v)
    }

    test("a body that succeeds returns its value") {
        assert(PipelineValidatorUtil.asValidation(42) == 42)
    }
}
