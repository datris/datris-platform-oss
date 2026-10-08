package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.OidcLogin._
import ai.datris.model.User
import com.nimbusds.jose.crypto.{MACSigner, RSASSASigner}
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.{ImmutableJWKSet, JWKSource}
import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, PlainJWT, SignedJWT}
import org.scalatest.funsuite.AnyFunSuite

import java.net.{URI, URLDecoder}
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Date
import scala.util.{Failure, Success, Try}

/** Story: OIDC single sign-on 1 (plans/stories/oidc-sso-login.md), Step 2.
  *
  * Pure functions only: keys are generated here with nimbus `RSAKeyGenerator`
  * and tokens are checked against an `ImmutableJWKSet`; no network.
  *
  * Pinned seam (new in ai.datris.auth.OidcLogin; names from the story's Files
  * section, argument order fixed here):
  * {{{
  * object OidcLogin {
  *   def pkceChallenge(verifier: String): String
  *   def authorizationUrl(authorizationEndpoint: String, clientId: String, redirectUri: String,
  *                        scopes: String, state: String, nonce: String, codeChallenge: String): String
  *   def parseDiscovery(json: String, issuer: String): <success or rejection>
  *   def validateIdToken(token: String, jwkSource: JWKSource[SecurityContext], issuer: String,
  *                       clientId: String, nonce: String, now: java.time.Instant): <claims or rejection>
  *   def resolveUser(claims: JWTClaimsSet, usernameClaim: String, defaultRole: String,
  *                   find: String => Option[User]): <Existing | Create | Refused>
  * }
  * // result cases, either nested in OidcLogin or top-level in ai.datris.auth:
  * //   Existing(user: User, ...)   Create(role: String, ...)   Refused(code: String)
  * // User gains `oidcSubject: String = null`.
  * }}}
  *
  * parseDiscovery / validateIdToken are checked shape-agnostically: a
  * rejection is a thrown exception, `Left`, `None`, `Failure` or `false`;
  * anything else is acceptance.
  */
class OidcLoginSpec extends AnyFunSuite {

    private val Issuer = "http://host.docker.internal:8081/realms/datris"
    private val ClientId = "datris"
    private val Nonce = "n-0S6_WzA2Mj"
    private val Now = Instant.parse("2026-10-08T12:00:00Z")

    private lazy val rsaKey: RSAKey = new RSAKeyGenerator(2048).keyID("k1").generate()
    private lazy val otherKey: RSAKey = new RSAKeyGenerator(2048).keyID("k1").generate()
    private lazy val jwks: JWKSource[SecurityContext] =
        new ImmutableJWKSet[SecurityContext](new JWKSet(rsaKey.toPublicJWK))

    private def claims(
        iss: String = Issuer,
        aud: String = ClientId,
        exp: Instant = Now.plusSeconds(300),
        nonce: String = Nonce,
        sub: String = "sub-alice",
        email: String = "alice@example.com"
    ): JWTClaimsSet = new JWTClaimsSet.Builder()
        .issuer(iss)
        .audience(aud)
        .subject(sub)
        .expirationTime(Date.from(exp))
        .issueTime(Date.from(Now.minusSeconds(10)))
        .claim("nonce", nonce)
        .claim("email", email)
        .claim("email_verified", true)
        .build()

    private def signRs256(c: JWTClaimsSet, key: RSAKey = rsaKey): String = {
        val jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID).build(), c)
        jwt.sign(new RSASSASigner(key))
        jwt.serialize()
    }

    private def accepted(r: => Any): Boolean = Try(r) match {
        case Failure(_) => false
        case Success(null) => false
        case Success(Left(_)) => false
        case Success(None) => false
        case Success(Failure(_)) => false
        case Success(b: Boolean) => b
        case Success(_) => true
    }

    private def validate(token: String, nonce: String = Nonce, now: Instant = Now): Boolean =
        accepted(OidcLogin.validateIdToken(token, jwks, Issuer, ClientId, nonce, now))

    private def query(url: String): Map[String, String] = {
        val q = new URI(url).getRawQuery
        q.split("&").map { kv =>
            val i = kv.indexOf('=')
            URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8) ->
                URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8)
        }.toMap
    }

    // ---- PKCE / authorization request ---------------------------------------

    test("pkce challenge is the base64url SHA-256 of the verifier") {
        // RFC 7636 Appendix B test vector.
        assert(
            OidcLogin.pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk") ==
                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        )
    }

    test("authorization url carries state, nonce, S256 challenge, scopes and the redirect uri") {
        val redirect = "http://localhost:4200/api/v1/auth/oidc/callback"
        val url = OidcLogin.authorizationUrl(
            s"$Issuer/protocol/openid-connect/auth",
            ClientId,
            redirect,
            "openid email profile",
            "state-123",
            "nonce-456",
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        ).toString
        assert(url.startsWith(s"$Issuer/protocol/openid-connect/auth?"), url)
        val q = query(url)
        assert(q.get("response_type").contains("code"))
        assert(q.get("client_id").contains(ClientId))
        assert(q.get("redirect_uri").contains(redirect))
        assert(q.get("scope").contains("openid email profile"))
        assert(q.get("state").contains("state-123"))
        assert(q.get("nonce").contains("nonce-456"))
        assert(q.get("code_challenge").contains("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"))
        assert(q.get("code_challenge_method").contains("S256"))
    }

    // ---- discovery -----------------------------------------------------------

    private def discovery(iss: String): String =
        s"""{"issuer":"$iss",
           |"authorization_endpoint":"$iss/protocol/openid-connect/auth",
           |"token_endpoint":"$iss/protocol/openid-connect/token",
           |"jwks_uri":"$iss/protocol/openid-connect/certs",
           |"id_token_signing_alg_values_supported":["RS256","ES256"]}""".stripMargin

    test("discovery with a different issuer is rejected") {
        assert(accepted(OidcLogin.parseDiscovery(discovery(Issuer), Issuer)), "matching issuer must be accepted")
        assert(!accepted(OidcLogin.parseDiscovery(discovery("https://evil.example.com/realms/datris"), Issuer)))
    }

    // ---- ID token validation -------------------------------------------------

    test("a valid token passes") {
        assert(validate(signRs256(claims())))
    }

    test("wrong issuer, wrong audience, expired, wrong nonce, bad signature and alg none are each rejected") {
        assert(validate(signRs256(claims())), "baseline token must pass")
        assert(!validate(signRs256(claims(iss = "https://evil.example.com/realms/datris"))), "wrong issuer")
        assert(!validate(signRs256(claims(aud = "some-other-client"))), "wrong audience")
        assert(!validate(signRs256(claims(exp = Now.minusSeconds(3600)))), "expired")
        assert(!validate(signRs256(claims(nonce = "replayed-nonce"))), "wrong nonce")
        assert(!validate(signRs256(claims(), otherKey)), "bad signature")
        assert(!validate(new PlainJWT(claims()).serialize()), "alg none")
    }

    test("an HS256 token signed with the client secret is rejected") {
        val clientSecret = "datris-local-secret-padded-to-at-least-32-bytes"
        val jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims())
        jwt.sign(new MACSigner(clientSecret.getBytes(StandardCharsets.UTF_8)))
        assert(!validate(jwt.serialize()))
    }

    // ---- identity -> user ----------------------------------------------------

    private def user(name: String, role: String, sub: String = null): User =
        User(name, "hash", role, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", null, oidcSubject = sub)

    private def idClaims(
        sub: String = "sub-alice",
        email: String = "alice@example.com",
        emailVerified: Any = true,
        extra: Map[String, AnyRef] = Map.empty
    ): JWTClaimsSet = {
        val b = new JWTClaimsSet.Builder().issuer(Issuer).audience(ClientId).subject(sub).claim("email", email)
        if (emailVerified != null) b.claim("email_verified", emailVerified)
        extra.foreach { case (k, v) => b.claim(k, v) }
        b.build()
    }

    private def users(us: User*): String => Option[User] = {
        val m = us.map(u => u.username -> u).toMap
        name => m.get(name)
    }

    test("existing user keeps their role") {
        val r = OidcLogin.resolveUser(idClaims(), "email", "viewer", users(user("alice@example.com", "editor")))
        r match {
            case e: Existing => assert(e.user.username == "alice@example.com"); assert(e.user.role == "editor")
            case other => fail(s"expected Existing, got $other")
        }
    }

    test("unknown user with empty default role is refused as sso_no_account") {
        val r = OidcLogin.resolveUser(idClaims(email = "bob@example.com", sub = "sub-bob"), "email", "", users())
        r match {
            case x: Refused => assert(x.code == "sso_no_account")
            case other => fail(s"expected Refused(sso_no_account), got $other")
        }
    }

    test("unknown user with default role viewer is created as viewer") {
        val r = OidcLogin.resolveUser(idClaims(email = "bob@example.com", sub = "sub-bob"), "email", "viewer", users())
        r match {
            case c: Create => assert(c.role == "viewer")
            case other => fail(s"expected Create(viewer), got $other")
        }
    }

    test("default role admin is treated as unset") {
        val r = OidcLogin.resolveUser(idClaims(email = "bob@example.com", sub = "sub-bob"), "email", "admin", users())
        r match {
            case x: Refused => assert(x.code == "sso_no_account")
            case other => fail(s"expected Refused(sso_no_account), got $other")
        }
    }

    test("email_verified false is refused") {
        val r = OidcLogin.resolveUser(
            idClaims(emailVerified = java.lang.Boolean.FALSE),
            "email",
            "viewer",
            users(user("alice@example.com", "editor"))
        )
        r match {
            case x: Refused => assert(x.code == "sso_unverified_email")
            case other => fail(s"expected Refused(sso_unverified_email), got $other")
        }
    }

    test("a claim that normalises to admin or fails the username pattern is refused") {
        val admin = user("admin", "admin")
        val viaClaim = OidcLogin.resolveUser(
            idClaims(extra = Map("preferred_username" -> " Admin ")),
            "preferred_username",
            "viewer",
            users(admin)
        )
        assert(viaClaim.isInstanceOf[Refused], s"' Admin ' must be refused, got $viaClaim")

        val viaEmail = OidcLogin.resolveUser(idClaims(email = "ADMIN"), "email", "viewer", users(admin))
        assert(viaEmail.isInstanceOf[Refused], s"'ADMIN' must be refused, got $viaEmail")

        val badPattern = OidcLogin.resolveUser(
            idClaims(extra = Map("preferred_username" -> "bob smith/../x")),
            "preferred_username",
            "viewer",
            users()
        )
        assert(badPattern.isInstanceOf[Refused], s"pattern failure must be refused, got $badPattern")
    }

    test("a second subject for the same username is refused as sso_identity_changed") {
        val bound = user("alice@example.com", "editor", sub = "sub-alice")
        val same = OidcLogin.resolveUser(idClaims(sub = "sub-alice"), "email", "", users(bound))
        assert(same.isInstanceOf[Existing], s"same subject must sign in, got $same")

        val r = OidcLogin.resolveUser(idClaims(sub = "sub-someone-else"), "email", "", users(bound))
        r match {
            case x: Refused => assert(x.code == "sso_identity_changed")
            case other => fail(s"expected Refused(sso_identity_changed), got $other")
        }
    }
}
