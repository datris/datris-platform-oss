package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, User}
import ai.datris.util.UserStore
import com.google.gson.{JsonObject, JsonParser}
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.{JWKSource, JWKSourceBuilder}
import com.nimbusds.jose.proc.{JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jose.util.DefaultResourceRetriever
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTProcessor

import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.time.{Duration, Instant}
import java.util.Base64
import scala.collection.JavaConverters._

/** OIDC single sign-on for the UI (authorization code flow with PKCE).
  *
  * The pure half (PKCE, the authorization URL, discovery parsing, ID-token
  * validation, identity → user resolution) is unit-tested in OidcLoginSpec.
  * The I/O half (discovery fetch, JWKS source, token request) is used only by
  * the two `/api/v1/auth/oidc/...` endpoints in AuthAPIController.
  *
  * Error codes handed to the login page (`/login?ssoError=<code>`) are fixed
  * strings; provider error text goes to the server log only. */
object OidcLogin {
    // ---- result codes --------------------------------------------------------

    /** The provider refused, or the person's identity cannot be a Datris user
      * (the recovery account, a username outside the allowed pattern). */
    val Denied = "sso_denied"

    /** Technical failure: discovery, token request, token validation, state. */
    val Failed = "sso_failed"

    /** No Datris user and no default role to create one with. */
    val NoAccount = "sso_no_account"

    /** `email_verified: false` while the username claim is `email`. */
    val UnverifiedEmail = "sso_unverified_email"

    /** The username is already bound to a different `sub`. */
    val IdentityChanged = "sso_identity_changed"

    val Codes: Set[String] = Set(Denied, Failed, NoAccount, UnverifiedEmail, IdentityChanged)

    /** Same pattern AuthAPIController.createUser enforces. */
    val UsernamePattern = "^[a-z0-9._@\\-]+$"

    /** The recovery account can never be reached through SSO. */
    val RecoveryUsername = "admin"

    /** Roles an unknown person may be created with. `admin` is deliberately
      * absent: admins are promoted on the Users screen. */
    val CreatableRoles: Set[String] = Set(User.RoleViewer, User.RoleEditor)

    /** Signature algorithms accepted on an ID token. Never `none`, never HMAC. */
    val AcceptedAlgorithms: Set[JWSAlgorithm] = Set(JWSAlgorithm.RS256, JWSAlgorithm.ES256)

    /** Clock skew tolerated on `exp`. */
    val ClockSkewSeconds = 60L

    val CallbackPath = "/api/v1/auth/oidc/callback"

    // ---- identity resolution -------------------------------------------------

    sealed trait Resolution

    /** A Datris user already exists for this identity; their role is kept. */
    case class Existing(user: User, subject: String) extends Resolution

    /** No user yet; create `username` with `role` and bind `subject`. */
    case class Create(role: String, username: String, subject: String) extends Resolution

    /** Sign-in refused; `code` is one of [[Codes]], `detail` is for the server log. */
    case class Refused(code: String, detail: String = "") extends Resolution

    // ---- discovery ------------------------------------------------------------

    case class Discovery(issuer: String, authorizationEndpoint: String, tokenEndpoint: String, jwksUri: String)

    // ---- pure functions --------------------------------------------------------

    private val b64url = Base64.getUrlEncoder.withoutPadding()
    private val random = new SecureRandom()

    /** RFC 7636 S256: base64url(SHA-256(ASCII(verifier))), no padding. */
    def pkceChallenge(verifier: String): String =
        b64url.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)))

    /** 32 random bytes, base64url without padding (43 characters). Used for
      * the state, the nonce and the PKCE verifier. */
    def randomToken(): String = {
        val bytes = new Array[Byte](32)
        random.nextBytes(bytes)
        b64url.encodeToString(bytes)
    }

    private def enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")

    def authorizationUrl(
        authorizationEndpoint: String,
        clientId: String,
        redirectUri: String,
        scopes: String,
        state: String,
        nonce: String,
        codeChallenge: String
    ): String = {
        val params = Seq(
            "response_type" -> "code",
            "client_id" -> clientId,
            "redirect_uri" -> redirectUri,
            "scope" -> scopes.trim.split("\\s+").filter(_.nonEmpty).mkString(" "),
            "state" -> state,
            "nonce" -> nonce,
            "code_challenge" -> codeChallenge,
            "code_challenge_method" -> "S256"
        )
        val query = params.map { case (k, v) => enc(k) + "=" + enc(v) }.mkString("&")
        val sep = if (authorizationEndpoint.contains("?")) "&" else "?"
        authorizationEndpoint + sep + query
    }

    /** Parse an OpenID provider configuration document. The document's
      * `issuer` must equal the configured issuer exactly (OIDC Discovery 4.3). */
    def parseDiscovery(json: String, issuer: String): Either[String, Discovery] = {
        try {
            val el = JsonParser.parseString(json)
            if (!el.isJsonObject) return Left("discovery document is not a JSON object")
            val obj = el.getAsJsonObject
            def str(name: String): Option[String] =
                Option(obj.get(name)).filter(e => e.isJsonPrimitive && e.getAsJsonPrimitive.isString).map(_.getAsString.trim).filter(_.nonEmpty)
            val docIssuer = str("issuer").getOrElse(return Left("discovery document has no issuer"))
            if (docIssuer != issuer)
                return Left("discovery issuer '" + docIssuer + "' does not match the configured issuer '" + issuer + "'")
            val auth = str("authorization_endpoint").getOrElse(return Left("discovery document has no authorization_endpoint"))
            val token = str("token_endpoint").getOrElse(return Left("discovery document has no token_endpoint"))
            val jwks = str("jwks_uri").getOrElse(return Left("discovery document has no jwks_uri"))
            Right(Discovery(docIssuer, auth, token, jwks))
        } catch {
            case e: Exception => Left("discovery document could not be parsed: " + e.getMessage)
        }
    }

    /** Validate an ID token: signature against the provider's keys (RS256 or
      * ES256 only; `none` and HMAC are refused), then `iss`, `aud`, `azp` (when
      * present, and always when there are several audiences), `exp` against
      * `now`, `nonce`, `sub`. */
    def validateIdToken(
        token: String,
        jwkSource: JWKSource[SecurityContext],
        issuer: String,
        clientId: String,
        nonce: String,
        now: Instant
    ): Either[String, JWTClaimsSet] = {
        try {
            val processor = new DefaultJWTProcessor[SecurityContext]()
            processor.setJWSKeySelector(new JWSVerificationKeySelector[SecurityContext](AcceptedAlgorithms.asJava, jwkSource))
            // Claims are checked below against the caller's clock, not nimbus's.
            processor.setJWTClaimsSetVerifier(null)
            val claims = processor.process(token, null)

            if (claims.getIssuer != issuer) return Left("ID token issuer mismatch")
            val aud = Option(claims.getAudience).map(_.asScala.toList).getOrElse(Nil)
            if (!aud.contains(clientId)) return Left("ID token audience does not include the client id")
            // OIDC Core 3.1.3.7: azp is required to equal the client id when
            // there are several audiences, and must equal it whenever present.
            val azp = Option(claims.getClaim("azp")).map(_.toString)
            if (aud.size > 1 && !azp.contains(clientId)) return Left("ID token azp does not match the client id")
            if (azp.exists(_ != clientId)) return Left("ID token azp does not match the client id")
            val exp = Option(claims.getExpirationTime).getOrElse(return Left("ID token has no exp"))
            if (!exp.toInstant.plusSeconds(ClockSkewSeconds).isAfter(now)) return Left("ID token expired")
            val tokenNonce = Option(claims.getClaim("nonce")).map(_.toString).orNull
            if (nonce == null || nonce.isEmpty || tokenNonce != nonce) return Left("ID token nonce mismatch")
            if (claims.getSubject == null || claims.getSubject.isEmpty) return Left("ID token has no sub")
            Right(claims)
        } catch {
            case e: Exception => Left("ID token rejected: " + e.getMessage)
        }
    }

    /** Map validated ID-token claims to a Datris user.
      *
      * The username is the `usernameClaim` value through UserStore.normalize;
      * it must match [[UsernamePattern]] and may never be the recovery account.
      * With `usernameClaim = email`, `email_verified: false` is refused. An
      * existing user keeps their role, unless the account is bound to another
      * `sub`. An unknown user is created only when `defaultRole` is viewer or
      * editor (`admin` and anything else count as unset). */
    def resolveUser(claims: JWTClaimsSet, usernameClaim: String, defaultRole: String, find: String => Option[User]): Resolution = {
        val claimName = Option(usernameClaim).map(_.trim).filter(_.nonEmpty).getOrElse("email")
        val raw = claims.getClaim(claimName) match {
            case s: String => s
            case _ => return Refused(Denied, "ID token has no string '" + claimName + "' claim")
        }
        val username = UserStore.normalize(raw)
        if (username.isEmpty) return Refused(Denied, "ID token '" + claimName + "' claim is empty")
        if (username == RecoveryUsername) return Refused(Denied, "the recovery account cannot sign in through SSO")
        if (!username.matches(UsernamePattern)) return Refused(Denied, "'" + claimName + "' value does not fit the username pattern")

        if (claimName == "email") {
            val verified = claims.getClaim("email_verified") match {
                case b: java.lang.Boolean => b.booleanValue()
                case s: String => !s.trim.equalsIgnoreCase("false")
                case _ => true // absent: the provider does not report it
            }
            if (!verified) return Refused(UnverifiedEmail, "email_verified is false for " + username)
        }

        val subject = claims.getSubject
        if (subject == null || subject.isEmpty) return Refused(Failed, "ID token has no sub")

        find(username) match {
            case Some(u) =>
                if (u.oidcSubject != null && u.oidcSubject.nonEmpty && u.oidcSubject != subject)
                    Refused(IdentityChanged, "user " + username + " is bound to a different subject")
                else Existing(u, subject)
            case None =>
                val role = Option(defaultRole).map(_.trim.toLowerCase(java.util.Locale.ROOT)).getOrElse("")
                if (CreatableRoles.contains(role)) Create(role, username, subject)
                else Refused(NoAccount, "no Datris user " + username + " and no default role")
        }
    }

    /** Callback query parameters whose values never reach the audit log:
      * the one-time authorization code and the state. */
    val RedactedCallbackParams: Set[String] = Set("code", "state")

    /** The callback's raw query string with the values of
      * [[RedactedCallbackParams]] masked (`code=***&state=***`). Parameter
      * order and everything else are kept byte-for-byte. */
    def redactCallbackQuery(qs: String): String = {
        if (qs == null || qs.isEmpty) return qs
        qs.split("&", -1)
            .map { kv =>
                val i = kv.indexOf('=')
                val name = if (i < 0) kv else kv.substring(0, i)
                val decoded =
                    try java.net.URLDecoder.decode(name, StandardCharsets.UTF_8)
                    catch { case _: Exception => name }
                if (RedactedCallbackParams.contains(decoded.trim.toLowerCase(java.util.Locale.ROOT))) name + "=***" else kv
            }
            .mkString("&")
    }

    /** Settings problem that switches SSO off, or None when the settings are
      * usable. Does not touch the network or Vault. */
    def configProblem(issuer: String, clientId: String, redirectUri: String, secretName: String): Option[String] = {
        def blank(s: String) = s == null || s.trim.isEmpty
        def httpUrl(s: String) =
            try {
                val u = new URI(s.trim)
                (u.getScheme == "http" || u.getScheme == "https") && u.getHost != null
            } catch { case _: Exception => false }
        if (blank(issuer)) Some("OIDC_ISSUER is not set")
        else if (!httpUrl(issuer)) Some("OIDC_ISSUER is not an http(s) URL: " + issuer)
        else if (blank(clientId)) Some("OIDC_CLIENT_ID is not set")
        else if (blank(redirectUri)) Some("OIDC_REDIRECT_URI is not set")
        else if (!httpUrl(redirectUri)) Some("OIDC_REDIRECT_URI is not an http(s) URL: " + redirectUri)
        else if (blank(secretName)) Some("oidc.secretName is not set")
        else None
    }

    // ---- I/O ---------------------------------------------------------------------

    val ConnectTimeout: Duration = Duration.ofSeconds(5)
    val RequestTimeout: Duration = Duration.ofSeconds(10)
    val DiscoveryTtl: Duration = Duration.ofHours(1)

    private lazy val http: HttpClient = HttpClient
        .newBuilder()
        .connectTimeout(ConnectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    private case class Provider(discovery: Discovery, jwks: JWKSource[SecurityContext], fetchedAt: Instant)

    @volatile private var cached: Option[Provider] = None

    /** True when SSO is effectively on for this server. */
    def active: Boolean = {
        val env = DatrisEnvironment.values
        env != null && env.useUserAuth && env.oidcEnabled
    }

    /** The provider's discovery document and key source, fetched lazily and
      * cached for an hour. */
    def provider(issuer: String): Either[String, (Discovery, JWKSource[SecurityContext])] = synchronized {
        cached match {
            case Some(p) if p.discovery.issuer == issuer && p.fetchedAt.plus(DiscoveryTtl).isAfter(Instant.now()) =>
                Right((p.discovery, p.jwks))
            case _ =>
                fetchDiscovery(issuer).map { d =>
                    val retriever = new DefaultResourceRetriever(ConnectTimeout.toMillis.toInt, RequestTimeout.toMillis.toInt)
                    val jwks = JWKSourceBuilder.create[SecurityContext](new java.net.URL(d.jwksUri), retriever).build()
                    cached = Some(Provider(d, jwks, Instant.now()))
                    (d, jwks)
                }
        }
    }

    private def fetchDiscovery(issuer: String): Either[String, Discovery] = {
        val url = issuer.stripSuffix("/") + "/.well-known/openid-configuration"
        try {
            val req = HttpRequest.newBuilder(URI.create(url)).timeout(RequestTimeout).header("Accept", "application/json").GET().build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) Left("discovery " + url + " returned HTTP " + resp.statusCode())
            else parseDiscovery(resp.body(), issuer)
        } catch {
            case e: Exception => Left("discovery " + url + " failed: " + e.getClass.getSimpleName + ": " + e.getMessage)
        }
    }

    /** Exchange the authorization code for tokens (client_secret_basic + PKCE
      * verifier) and return the raw ID token. */
    def exchangeCode(
        tokenEndpoint: String,
        clientId: String,
        clientSecret: String,
        redirectUri: String,
        code: String,
        codeVerifier: String
    ): Either[String, String] = {
        try {
            val form = Seq(
                "grant_type" -> "authorization_code",
                "code" -> code,
                "redirect_uri" -> redirectUri,
                "code_verifier" -> codeVerifier
            ).map { case (k, v) => enc(k) + "=" + enc(v) }.mkString("&")
            // RFC 6749 2.3.1: client id and secret are form-encoded before Basic.
            val basic = Base64.getEncoder.encodeToString((enc(clientId) + ":" + enc(clientSecret)).getBytes(StandardCharsets.UTF_8))
            val req = HttpRequest
                .newBuilder(URI.create(tokenEndpoint))
                .timeout(RequestTimeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("Authorization", "Basic " + basic)
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            val body = Option(resp.body()).getOrElse("")
            if (resp.statusCode() != 200) {
                val err = errorText(body)
                Left("token endpoint returned HTTP " + resp.statusCode() + err.map(": " + _).getOrElse(""))
            } else {
                val obj = JsonParser.parseString(body).getAsJsonObject
                Option(obj.get("id_token")).filter(_.isJsonPrimitive).map(_.getAsString) match {
                    case Some(t) if t.nonEmpty => Right(t)
                    case _ => Left("token response carries no id_token")
                }
            }
        } catch {
            case e: Exception => Left("token request failed: " + e.getClass.getSimpleName + ": " + e.getMessage)
        }
    }

    private def errorText(body: String): Option[String] =
        try {
            val obj: JsonObject = JsonParser.parseString(body).getAsJsonObject
            val e = Option(obj.get("error")).map(_.getAsString).getOrElse("")
            val d = Option(obj.get("error_description")).map(_.getAsString).getOrElse("")
            Some((e + " " + d).trim.take(300)).filter(_.nonEmpty)
        } catch { case _: Exception => None }

    /** Drop the cached discovery document (tests, settings change). */
    def clearCache(): Unit = synchronized { cached = None }
}
