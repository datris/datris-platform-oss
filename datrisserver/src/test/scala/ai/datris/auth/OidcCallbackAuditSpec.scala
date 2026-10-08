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
