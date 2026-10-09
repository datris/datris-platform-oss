package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.LogRedactUtil
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant
import java.util.Date

/** Review follow-ups on OIDC single sign-on 1: the authorization code and
  * state never reach the audit log, and a present `azp` must equal the
  * client id even on a single-audience token. */
class OidcCallbackAuditSpec extends AnyFunSuite {

    test("callback query stored in the audit log masks code and state") {
        val raw = "state=abc123&session_state=s1&code=AUTHZ-CODE-xyz&iss=http%3A%2F%2Fidp"
        val stored = LogRedactUtil.redactQueryString(OidcLogin.redactCallbackQuery(raw))
        assert(stored == "state=***&session_state=s1&code=***&iss=http%3A%2F%2Fidp")
        assert(!stored.contains("AUTHZ-CODE"))
        assert(OidcLogin.redactCallbackQuery("error=access_denied") == "error=access_denied")
        assert(OidcLogin.redactCallbackQuery(null) == null)
    }

    test("a present azp that is not the client id is rejected on a single-audience token") {
        val now = Instant.parse("2026-10-08T12:00:00Z")
        val key = new RSAKeyGenerator(2048).keyID("k1").generate()
        val jwks = new ImmutableJWKSet[SecurityContext](new JWKSet(key.toPublicJWK))
        def token(azp: Option[String]): String = {
            val b = new JWTClaimsSet.Builder()
                .issuer("http://idp")
                .audience("datris")
                .subject("sub-1")
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("nonce", "n1")
            azp.foreach(b.claim("azp", _))
            val jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), b.build())
            jwt.sign(new RSASSASigner(key))
            jwt.serialize()
        }
        assert(OidcLogin.validateIdToken(token(None), jwks, "http://idp", "datris", "n1", now).isRight)
        assert(OidcLogin.validateIdToken(token(Some("datris")), jwks, "http://idp", "datris", "n1", now).isRight)
        assert(OidcLogin.validateIdToken(token(Some("other-client")), jwks, "http://idp", "datris", "n1", now).isLeft)
    }
}

/** E2E adversarial follow-up: provider- and browser-supplied text (the
  * callback's error / error_description, discovery and token-endpoint error
  * bodies) cannot forge extra server log lines. */
class OidcLogSafeSpec extends AnyFunSuite {

    test("CR, LF and other control characters are escaped before logging") {
        val forged = "line1\r\n2026-10-08T00:00:00Z ERROR FAKE forged"
        val safe = OidcLogin.logSafe(forged)
        assert(!safe.exists(c => c == '\r' || c == '\n'))
        assert(safe == "line1\\r\\n2026-10-08T00:00:00Z ERROR FAKE forged")

        val others = "a\tb" + 0x1b.toChar + "[31m" + 0x85.toChar + "c" + 0x2028.toChar + "d" + 0x7f.toChar
        val safeOthers = OidcLogin.logSafe(others)
        assert(!safeOthers.exists(c => Character.isISOControl(c) || c.toInt == 0x2028), safeOthers)
        assert(safeOthers == "a\\tb\\" + "u001b[31m\\" + "u0085c\\" + "u2028d\\" + "u007f")

        assert(OidcLogin.logSafe("access_denied (User cancelled)") == "access_denied (User cancelled)")
        assert(OidcLogin.logSafe(null) == null)
    }
}

/** Review round 3: discovery endpoints that are not absolute http(s) URLs
  * are rejected in parseDiscovery, without echoing the value, so the URL /
  * URI constructors downstream never throw with provider text in the message. */
class OidcDiscoveryEndpointSpec extends AnyFunSuite {

    private val Issuer = "http://idp/realms/datris"

    private def doc(auth: String, token: String, jwks: String): String = {
        val o = new com.google.gson.JsonObject
        o.addProperty("issuer", Issuer)
        o.addProperty("authorization_endpoint", auth)
        o.addProperty("token_endpoint", token)
        o.addProperty("jwks_uri", jwks)
        o.toString
    }

    private val ok = (s"$Issuer/auth", s"$Issuer/token", s"$Issuer/certs")

    test("a discovery doc with a non-http jwks_uri is rejected without echoing it") {
        assert(OidcLogin.parseDiscovery(doc(ok._1, ok._2, ok._3), Issuer).isRight)
        val r = OidcLogin.parseDiscovery(doc(ok._1, ok._2, "jwks\r\nFORGED LINE"), Issuer)
        assert(r.isLeft)
        assert(!r.left.get.contains("FORGED"), r)
        assert(OidcLogin.parseDiscovery(doc(ok._1, ok._2, "file:///etc/passwd"), Issuer).isLeft)
    }

    test("authorization and token endpoints with whitespace or control characters are rejected") {
        val a = OidcLogin.parseDiscovery(doc("http://idp/auth \r\nFORGED", ok._2, ok._3), Issuer)
        assert(a.isLeft && !a.left.get.contains("FORGED"), a)
        val t = OidcLogin.parseDiscovery(doc(ok._1, "ftp://idp/token", ok._3), Issuer)
        assert(t.isLeft, t)
    }
}
