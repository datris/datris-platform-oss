package ai.datris

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import ai.datris.util.MultiTenantRetired
import org.scalatest.funsuite.AnyFunSuite

/** `DatrisServiceApplication.main` ends the process with a non-zero status,
  * logging the plain message last, only when startup failed because the
  * multi-tenant setting is on. Every other startup failure is handled as
  * before (logged, no exit status chosen by main). */
class DatrisServiceApplicationExitSpec extends AnyFunSuite {

    private def refusal: DatrisException = intercept[DatrisException](MultiTenantRetired.refuseIfOn(true))

    test("the setting off does not stop startup") {
        MultiTenantRetired.refuseIfOn(false)
    }

    test("the refusal ends the process with a non-zero status and the plain message") {
        val exit = DatrisServiceApplication.startupExit(refusal)
        assert(exit.contains((MultiTenantRetired.ExitStatus, MultiTenantRetired.Message)))
        assert(MultiTenantRetired.ExitStatus != 0)
    }

    test("the refusal is recognised when Spring wraps the runner's exception") {
        val wrapped = new IllegalStateException("Failed to execute ApplicationRunner", refusal)
        assert(DatrisServiceApplication.startupExit(wrapped).contains((MultiTenantRetired.ExitStatus, MultiTenantRetired.Message)))
        val twice = new RuntimeException("outer", wrapped)
        assert(DatrisServiceApplication.startupExit(twice).map(_._1).contains(MultiTenantRetired.ExitStatus))
    }

    test("every other startup failure is handled as before") {
        val tapRunner = new DatrisException("USE_TAP_RUNNER=true but TAP_RUNNER_TOKEN is empty or the changeme default.")
        assert(DatrisServiceApplication.startupExit(tapRunner).isEmpty)
        assert(DatrisServiceApplication.startupExit(new IllegalStateException("Failed to execute ApplicationRunner", tapRunner)).isEmpty)
        assert(DatrisServiceApplication.startupExit(new RuntimeException("boom")).isEmpty)
        assert(DatrisServiceApplication.startupExit(new RuntimeException(MultiTenantRetired.Message)).isEmpty)
        assert(DatrisServiceApplication.startupExit(null).isEmpty)
    }
}
