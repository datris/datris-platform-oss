package ai.datris.incident

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.{AiSampleValues, AiSampleValuesMarkers}
import com.google.gson.JsonObject
import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant

/** Field protection 9, review follow-up: the recovery agent's diagnosis
  * prompt carries a pipeline incident's error scrubbed when
  * DATRIS_AI_SAMPLE_VALUES=false; the stored incident is untouched. */
class IncidentTriggerScrubSpec extends AnyFunSuite with AiSampleValuesMarkers {

    private def incident(resourceType: String): Incident = {
        val t = new JsonObject()
        t.addProperty("pipelineToken", "tok")
        t.addProperty(
            "error",
            "Aborting processing this pipeline, 1 error(s) were found while performing data quality rules:\nData quality CodeGen failure, row: 0, reason: ZQX-SSN-2"
        )
        Incident("i1", Incident.KindPipelineFailure, resourceType, "people", Instant.now(), "open", t)
    }

    test("off: a pipeline incident's error is scrubbed for the model, the stored trigger is not") {
        withheld {
            val inc = incident("pipeline")
            val forModel = IncidentRunner.RealDiagnoser.triggerForModel(inc).toString
            assertNoMarker(forModel)
            assert(forModel.contains("tok") && forModel.contains(AiSampleValues.DetailsWithheld), forModel)
            assert(inc.trigger.toString.contains("ZQX-SSN-2"), "stored incident keeps the full text")
        }
    }

    test("on: the trigger goes as it is") {
        sampled {
            val inc = incident("pipeline")
            assert(IncidentRunner.RealDiagnoser.triggerForModel(inc) eq inc.trigger)
        }
    }
}
