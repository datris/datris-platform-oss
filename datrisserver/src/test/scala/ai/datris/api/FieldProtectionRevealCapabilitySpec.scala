package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.CapabilityDeniedException
import ai.datris.config.TenantInterceptor
import ai.datris.model.{Capability, ResolvedKey}
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.funsuite.AnyFunSuite

/** Field protection 5 review: the reveal and rotate endpoints enforce
  * `protect:reveal` / `protect:admin` themselves, so
  * CAPABILITY_ENFORCEMENT=log-only (the interceptor only logs) never opens
  * them. No Spring: the controller is called directly with a mocked request
  * carrying the ResolvedKey the TenantInterceptor would have set. A denied
  * call is refused before any config, key or environment read. */
class FieldProtectionRevealCapabilitySpec extends AnyFunSuite {

    private def key(caps: Seq[String], legacy: Boolean = false): ResolvedKey =
        ResolvedKey(None, "k", Capability.parseList(caps), isLegacyFullAccess = legacy)

    private def req(k: Option[ResolvedKey]): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("POST")
        when(r.getRequestURI).thenReturn("/api/v1/protect/reveal")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => k.orNull)
        r
    }

    private def template(name: String): Seq[String] =
        KeysAPIController.Templates.find(_._1 == name).map(_._3).getOrElse(fail(s"no template $name"))

    private val body = RevealRequest("patients", "email", java.util.Arrays.asList("enc:v1:AAAA"))

    private def assertDenied(status: Int, json: String): Unit = {
        assert(status == 403, json)
        val o = JsonParser.parseString(json).getAsJsonObject
        assert(o.get("error").getAsString == "capability denied")
        assert(o.get("errorKind").getAsString == "capability_denied")
    }

    test("reveal and rotate refuse a key without the protect capability, whatever the interceptor mode") {
        val c = new FieldProtectionAPIController
        Seq(template("rag-builder"), Seq("pipeline:read", "pipeline:create", "pipeline:update"), Seq("protect:admin")).foreach { caps =>
            val r = c.reveal("any", body, req(Some(key(caps))))
            assertDenied(r.getStatusCode.value, r.getBody)
        }
        Seq(template("rag-builder"), Seq("protect:reveal")).foreach { caps =>
            val r = c.rotateKey("any", req(Some(key(caps))))
            assertDenied(r.getStatusCode.value, r.getBody)
        }
    }

    test("the gate passes protect:reveal / protect:admin, wildcards and legacy keys, and is a no-op without a resolved key") {
        FieldProtectionAPIController.requireCapability(req(Some(key(Seq("protect:reveal")))), "reveal")
        FieldProtectionAPIController.requireCapability(req(Some(key(Seq("protect:admin")))), "admin")
        FieldProtectionAPIController.requireCapability(req(Some(key(Seq("*:*")))), "reveal")
        FieldProtectionAPIController.requireCapability(req(Some(key(Nil, legacy = true))), "admin")
        FieldProtectionAPIController.requireCapability(req(None), "reveal")
        intercept[CapabilityDeniedException](FieldProtectionAPIController.requireCapability(req(Some(key(Seq("protect:reveal")))), "admin"))
        intercept[CapabilityDeniedException](FieldProtectionAPIController.requireCapability(req(Some(key(Seq("protect:admin")))), "reveal"))
    }

    test("reveal counts only non-empty values as revealed; empty and null pass through") {
        val k: Array[Byte] = Array.tabulate[Byte](32)(i => i.toByte)
        val t = ai.datris.util.FieldCipher.encrypt(k, 1, "patients", "email", "jane@example.com")
        val lookup: Int => Array[Byte] = v => if (v == 1) k else null
        val (values, errors, revealed, failed) =
            FieldProtectionAPIController.decryptAll(lookup, "patients", "email", Seq("", null, t, "enc:v1:AAAA"))
        assert(revealed == 1 && failed == 1, s"revealed=$revealed failed=$failed")
        assert(values.get(0).getAsString == "" && values.get(1).isJsonNull)
        assert(values.get(2).getAsString == "jane@example.com" && values.get(3).isJsonNull)
        assert(errors.size == 1 && errors.get(0).getAsJsonObject.get("index").getAsInt == 3)
        val (_, _, r2, f2) = FieldProtectionAPIController.decryptAll(lookup, "patients", "email", Seq("", null))
        assert(r2 == 0 && f2 == 0)
    }
}
