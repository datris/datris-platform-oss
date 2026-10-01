package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

/** A presented-but-unresolved key (revoked/unknown) must be denied rather
  * than fall into the "no key" pass-through. */
class CapabilityInterceptorRejectedKeySpec extends AnyFunSuite {

    import CapabilityInterceptor.denyPresentedButUnresolved

    test("no key at all passes through") {
        assert(!denyPresentedButUnresolved(presentedKey = false, resolved = false, rejection = None))
    }

    test("resolved key is left to the capability check") {
        assert(!denyPresentedButUnresolved(presentedKey = true, resolved = true, rejection = None))
    }

    test("presented and rejected key is denied") {
        assert(denyPresentedButUnresolved(presentedKey = true, resolved = false, rejection = Some("API key 'k' is revoked")))
    }

    test("presented key with no recorded rejection is not denied") {
        assert(!denyPresentedButUnresolved(presentedKey = true, resolved = false, rejection = None))
    }

    test("blank header counts as not presented") {
        assert(!TenantInterceptor.presented(null))
        assert(!TenantInterceptor.presented("  "))
        assert(TenantInterceptor.presented("abc"))
    }

    test("metadata outage maps to 503, any other rejection to 401") {
        val (s503, b503) = CapabilityInterceptor.rejectionResponse(ai.datris.util.APIKeyValidator.MetadataUnavailableMessage)
        assert(s503 == 503 && b503 == "{\"error\":\"API key metadata unavailable\"}")
        val (s503b, b503b) = CapabilityInterceptor.rejectionResponse(ai.datris.util.APIKeyValidator.KeyStoreUnavailableMessage)
        assert(s503b == 503 && b503b == "{\"error\":\"API key store unavailable\"}")
        val (s401, b401) = CapabilityInterceptor.rejectionResponse("API key 'k' is revoked")
        assert(s401 == 401 && b401 == "{\"error\":\"API key is revoked or invalid\"}")
    }
}
