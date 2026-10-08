package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{CodeGenScript, DatrisException}
import org.scalatest.funsuite.AnyFunSuite

/** A stored script that cannot run because the CodeGen runner is down is not
  * the script's fault: the "regenerate it" advice applies to script failures
  * only (codegen-script-isolation E2E finding). */
class CodeGenRunnerUnavailableSpec extends AnyFunSuite {
    private val record = CodeGenScript("p1", PipelineScripts.DataQuality, "rows have an id", "print('[]')", generatedAt = "2026-10-07T00:00:00Z")
    private val resolved = PipelineScripts.Resolved(PipelineScripts.Stored, record.script, null, record)

    test("a runner transport failure passes through without the regenerate advice") {
        val e = intercept[DatrisException] {
            PipelineScripts.runResolved(resolved, "p1", PipelineScripts.DataQuality)(_ =>
                throw new CodeGenRunnerUnavailable("CodeGen runner (datris-codegen-runner at http://x:8090) is unreachable: Connection refused")
            )
        }
        assert(e.getMessage.startsWith("CodeGen runner"), e.getMessage)
        assert(!e.getMessage.contains("regenerate"), e.getMessage)
    }

    test("a script failure keeps the stored-script advice") {
        val e = intercept[DatrisException] {
            PipelineScripts.runResolved(resolved, "p1", PipelineScripts.DataQuality)(_ =>
                throw new DatrisException("CodeGen validation script failed (exit code 1): boom")
            )
        }
        assert(e.getMessage.contains("not regenerated automatically"), e.getMessage)
    }
}
