package ai.datris.util.aiutil

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.azure.core.credential.AccessToken
import ai.datris.model.DatrisException
import org.scalatest.funsuite.AnyFunSuite

import java.time.OffsetDateTime

class AzureEntraSupportSpec extends AnyFunSuite {

    private val sp = Map(
        "azureTenantId" -> "tenant-1",
        "azureClientId" -> "client-1",
        "azureClientSecret" -> "s3cret"
    )

    // ---- resolveMode: precedence ----

    test("a resolved API key wins over everything, including a full SP trio") {
        val mode = AzureEntraSupport.resolveMode("sk-azure", sp, multiTenant = false)
        assert(mode == AzureEntraSupport.KeyMode("sk-azure"))
    }

    test("full SP trio in the store selects service-principal mode") {
        val mode = AzureEntraSupport.resolveMode("", sp, multiTenant = false)
        assert(mode == AzureEntraSupport.ServicePrincipalMode("tenant-1", "client-1", "s3cret"))
    }

    test("SP trio works in multi-tenant mode (it's the per-tenant credential)") {
        val mode = AzureEntraSupport.resolveMode("", sp, multiTenant = true)
        assert(mode == AzureEntraSupport.ServicePrincipalMode("tenant-1", "client-1", "s3cret"))
    }

    test("blank everything falls through to the default chain in single-tenant") {
        assert(AzureEntraSupport.resolveMode("", Map.empty, multiTenant = false) == AzureEntraSupport.DefaultChainMode)
    }

    test("blank everything in multi-tenant is refused — a tenant never rides the platform identity") {
        val e = intercept[DatrisException] {
            AzureEntraSupport.resolveMode("", Map.empty, multiTenant = true)
        }
        assert(e.getMessage.contains("No Azure OpenAI credentials"))
    }

    test("partial SP trio is all-or-none, naming the missing fields") {
        val e = intercept[DatrisException] {
            AzureEntraSupport.resolveMode("", sp - "azureClientSecret", multiTenant = false)
        }
        assert(e.getMessage.contains("Client Secret"))
        assert(!e.getMessage.contains("missing Tenant ID"))
    }

    test("whitespace-only SP fields count as absent, and present fields are trimmed") {
        // Secret is whitespace-only → trio incomplete despite three map entries.
        val e = intercept[DatrisException] {
            AzureEntraSupport.resolveMode("", sp + ("azureClientSecret" -> "   "), multiTenant = false)
        }
        assert(e.getMessage.contains("Client Secret"))
        // Padded values resolve trimmed.
        val padded = sp.map { case (k, v) => k -> ("  " + v + " ") }
        assert(AzureEntraSupport.resolveMode("", padded, multiTenant = false) ==
            AzureEntraSupport.ServicePrincipalMode("tenant-1", "client-1", "s3cret"))
    }

    // ---- cachedToken: refresh behavior ----

    private def token(value: String, expiresAt: OffsetDateTime) = new AccessToken(value, expiresAt)

    test("cachedToken fetches on first use and serves from cache while fresh") {
        AzureEntraSupport.clearTokenCache()
        val now = OffsetDateTime.parse("2026-08-11T10:00:00Z")
        var fetches = 0
        def fetch(): AccessToken = { fetches += 1; token("tok-" + fetches, now.plusHours(1)) }
        assert(AzureEntraSupport.cachedToken("k1", now, () => fetch()) == "tok-1")
        assert(AzureEntraSupport.cachedToken("k1", now.plusMinutes(10), () => fetch()) == "tok-1")
        assert(fetches == 1)
    }

    test("cachedToken refreshes inside the 5-minute expiry margin") {
        AzureEntraSupport.clearTokenCache()
        val now = OffsetDateTime.parse("2026-08-11T10:00:00Z")
        var fetches = 0
        def fetch(): AccessToken = { fetches += 1; token("tok-" + fetches, now.plusHours(1)) }
        assert(AzureEntraSupport.cachedToken("k1", now, () => fetch()) == "tok-1")
        // 56 minutes in: 4 minutes to expiry — inside the margin, must refresh.
        assert(AzureEntraSupport.cachedToken("k1", now.plusMinutes(56), () => fetch()) == "tok-2")
        assert(fetches == 2)
    }

    test("cachedToken keys are independent (SP identities don't share tokens)") {
        AzureEntraSupport.clearTokenCache()
        val now = OffsetDateTime.parse("2026-08-11T10:00:00Z")
        AzureEntraSupport.cachedToken("sp:a", now, () => token("tok-a", now.plusHours(1)))
        val b = AzureEntraSupport.cachedToken("sp:b", now, () => token("tok-b", now.plusHours(1)))
        assert(b == "tok-b")
        assert(AzureEntraSupport.cachedToken("sp:a", now, () => token("tok-x", now.plusHours(1))) == "tok-a")
    }

    // ---- authHeaders ----

    test("API-key mode keeps the dual-header shape shipped in v1.15.0") {
        val headers = AzureEntraSupport.authHeaders(AzureEntraSupport.ApiKey("sk-azure")).toMap
        assert(headers("api-key") == "sk-azure")
        assert(headers("Authorization") == "Bearer sk-azure")
    }

    test("Entra mode sends ONLY the Bearer header — api-key is what disableLocalAuth rejects") {
        val headers = AzureEntraSupport.authHeaders(AzureEntraSupport.EntraToken("eyJ0"))
        assert(headers == Seq("Authorization" -> "Bearer eyJ0"))
    }
}
