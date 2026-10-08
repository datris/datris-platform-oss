package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditLog
import ai.datris.auth.OidcLogin
import ai.datris.config.{RequiresRole, SessionAuthenticator}
import ai.datris.model.{DatrisEnvironment, User, UserContext}
import ai.datris.util.{PasswordHasher, SecretsUtil, SessionStore, UserStore}

import java.net.URI
import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter
import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonObject, JsonParser}
import jakarta.servlet.http.{Cookie, HttpServletRequest, HttpServletResponse}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import scala.collection.JavaConverters._

@RestController
@RequestMapping(Array("/api/v1/auth"))
class AuthAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[AuthAPIController])
    private val gson = new Gson
    private val sessionAuth = new SessionAuthenticator
    // Cookie max-age matches SessionStore.SessionTtlSeconds.
    private val cookieMaxAgeSeconds = SessionStore.SessionTtlSeconds.toInt

    /** UI bootstrap probe — returns the current user, or 401 if no session. */
    @GetMapping(path = Array("/me"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def me(): ResponseEntity[String] = {
        UserContext.get() match {
            case Some(u) => ResponseEntity.ok(gson.toJson(toMeResponse(u)))
            case None => ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("""{"error":"Not authenticated"}""")
        }
    }

    @PostMapping(path = Array("/login"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def login(@RequestBody body: String, response: HttpServletResponse): ResponseEntity[String] = {
        try {
            if (!DatrisEnvironment.values.useUserAuth)
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("""{"error":"User auth is not enabled on this server"}""")

            val obj = JsonParser.parseString(body).getAsJsonObject
            val username = UserStore.normalize(stringField(obj, "username"))
            val password = stringField(obj, "password")
            if (username.isEmpty)
                return ResponseEntity.badRequest().body("""{"error":"Username is required"}""")

            val userOpt = UserStore.find(username)
            if (userOpt.isEmpty) {
                logger.info("Login failed: user not found: " + username)
                return unauthorized()
            }
            val user = userOpt.get

            // Always verify the password. A null/empty stored hash used to be
            // treated as "first login, accept any password", which let anyone
            // claim an unclaimed account (notably the seeded admin) before its
            // owner. Accounts are now always seeded/created with a real hash
            // (see StartupRunner and createUser), and PasswordHasher.verify
            // returns false for a null/empty hash — so a stale null-hash account
            // simply cannot be logged into until an admin resets it.
            if (!PasswordHasher.verify(password, user.passwordHash)) {
                logger.info("Login failed: bad password for: " + username)
                return unauthorized()
            }

            val session = SessionStore.create(user.username)
            UserStore.touchLastLogin(user.username)
            response.addCookie(buildSessionCookie(session.token, cookieMaxAgeSeconds))
            ResponseEntity.ok(gson.toJson(toMeResponse(user)))
        } catch {
            case e: Exception =>
                logger.error("Error in /auth/login: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("""{"error":"Internal error"}""")
        }
    }

    // -------- OIDC single sign-on --------
    //
    // Both endpoints carry no @RequiresRole: RoleEnforcementInterceptor exempts
    // this controller's un-annotated methods, and CapabilityRoutes skip-lists
    // /api/v1/auth/**. Every failure lands on /login?ssoError=<code> with one
    // of OidcLogin.Codes; provider error text goes to the server log only.

    /** Start an SSO sign-in: remember state, nonce and the PKCE verifier in a
      * short-lived cookie and send the browser to the identity provider. */
    @GetMapping(path = Array("/oidc/login"))
    def oidcLogin(response: HttpServletResponse): ResponseEntity[String] = {
        if (!OidcLogin.active) return oidcNotEnabled()
        val env = DatrisEnvironment.values
        try {
            OidcLogin.provider(env.oidcIssuer) match {
                case Left(err) =>
                    logger.error("OIDC sign-in could not start: " + err)
                    ssoRedirect(OidcLogin.Failed)
                case Right((discovery, _)) =>
                    val state = OidcLogin.randomToken()
                    val nonce = OidcLogin.randomToken()
                    val verifier = OidcLogin.randomToken()
                    response.addCookie(buildOidcTxCookie(state + "." + nonce + "." + verifier, OidcTxMaxAgeSeconds))
                    val url = OidcLogin.authorizationUrl(
                        discovery.authorizationEndpoint,
                        env.oidcClientId,
                        env.oidcRedirectUri,
                        env.oidcScopes,
                        state,
                        nonce,
                        OidcLogin.pkceChallenge(verifier)
                    )
                    redirect(url)
            }
        } catch {
            case e: Exception =>
                logger.error("Error in /auth/oidc/login: " + Throwables.getStackTraceAsString(e))
                ssoRedirect(OidcLogin.Failed)
        }
    }

    /** The identity provider's redirect back: check state, exchange the code,
      * validate the ID token, resolve the Datris user, start a session. */
    @GetMapping(path = Array("/oidc/callback"))
    def oidcCallback(
        @RequestParam(name = "code", required = false) code: String,
        @RequestParam(name = "state", required = false) state: String,
        @RequestParam(name = "error", required = false) error: String,
        @RequestParam(name = "error_description", required = false) errorDescription: String,
        request: HttpServletRequest,
        response: HttpServletResponse
    ): ResponseEntity[String] = {
        if (!OidcLogin.active) return oidcNotEnabled()
        val env = DatrisEnvironment.values
        // The transaction is single-use: clear it whatever happens next.
        val tx = Option(readCookie(request, OidcTxCookieName)).map(_.split("\\.", -1)).filter(_.length == 3)
        response.addCookie(buildOidcTxCookie("", 0))

        def refuse(codeOut: String, logLine: String, username: String = null): ResponseEntity[String] = {
            logger.warn("OIDC sign-in refused (" + codeOut + "): " + logLine)
            val md = new JsonObject
            md.addProperty("method", "oidc")
            md.addProperty("ssoError", codeOut)
            AuditLog.record(
                auditView(request),
                "auth",
                "login",
                "user",
                username,
                outcome = if (codeOut == OidcLogin.Failed) "failure" else "denied",
                httpStatus = HttpStatus.FOUND.value(),
                metadata = md,
                errorMessage = codeOut
            )
            ssoRedirect(codeOut)
        }

        try {
            if (error != null && error.nonEmpty)
                return refuse(
                    OidcLogin.Denied,
                    "provider returned error=" + error.take(100) + Option(errorDescription).map(d => " (" + d.take(300) + ")").getOrElse("")
                )
            if (tx.isEmpty) return refuse(OidcLogin.Failed, "no sign-in transaction cookie (expired, or the sign-in was not started here)")
            val Array(txState, txNonce, txVerifier) = tx.get
            if (state == null || state.isEmpty || !java.security.MessageDigest.isEqual(state.getBytes("UTF-8"), txState.getBytes("UTF-8")))
                return refuse(OidcLogin.Failed, "state does not match the sign-in transaction")
            if (code == null || code.isEmpty) return refuse(OidcLogin.Failed, "no authorization code in the callback")

            val (discovery, jwks) = OidcLogin.provider(env.oidcIssuer) match {
                case Left(err) => return refuse(OidcLogin.Failed, err)
                case Right(p) => p
            }
            val clientSecret =
                try SecretsUtil.getSecretMap(env.oidcSecretName).flatMap(m => Option(m.get("clientSecret"))).map(_.trim).filter(_.nonEmpty)
                catch { case scala.util.control.NonFatal(_) => None }
            if (clientSecret.isEmpty) return refuse(OidcLogin.Failed, "Vault secret " + env.oidcSecretName + " has no clientSecret")

            val idToken = OidcLogin.exchangeCode(discovery.tokenEndpoint, env.oidcClientId, clientSecret.get, env.oidcRedirectUri, code, txVerifier) match {
                case Left(err) => return refuse(OidcLogin.Failed, err)
                case Right(t) => t
            }
            val claims = OidcLogin.validateIdToken(idToken, jwks, env.oidcIssuer, env.oidcClientId, txNonce, Instant.now()) match {
                case Left(err) => return refuse(OidcLogin.Failed, err)
                case Right(c) => c
            }

            val user: User = OidcLogin.resolveUser(claims, env.oidcUsernameClaim, env.oidcDefaultRole, UserStore.find) match {
                case OidcLogin.Refused(c, detail) => return refuse(c, detail, claimedUsername(claims, env.oidcUsernameClaim))
                case OidcLogin.Existing(u, subject) =>
                    if (u.oidcSubject == null || u.oidcSubject.isEmpty) UserStore.setOidcSubject(u.username, subject)
                    u
                case OidcLogin.Create(role, username, subject) =>
                    // A random password nobody is told: never a null hash.
                    val created = UserStore.create(username, PasswordHasher.hash(PasswordHasher.generateTemporary()), role, subject)
                    logger.info("OIDC sign-in created user " + username + " with role " + role)
                    created
            }

            val session = SessionStore.create(user.username)
            UserStore.touchLastLogin(user.username)
            response.addCookie(buildSessionCookie(session.token, cookieMaxAgeSeconds))
            val md = new JsonObject
            md.addProperty("method", "oidc")
            md.addProperty("role", user.role)
            AuditLog.record(auditView(request), "auth", "login", "user", user.username, httpStatus = HttpStatus.FOUND.value(), metadata = md)
            logger.info("OIDC sign-in: " + user.username + " (" + user.role + ")")
            redirect("/")
        } catch {
            case e: Exception =>
                logger.error("Error in /auth/oidc/callback: " + Throwables.getStackTraceAsString(e))
                refuse(OidcLogin.Failed, "internal error: " + e.getClass.getSimpleName)
        }
    }

    @PostMapping(path = Array("/logout"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def logout(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity[String] = {
        val token = readCookie(request, sessionAuth.SessionCookieName)
        if (token != null) SessionStore.delete(token)
        response.addCookie(buildSessionCookie("", 0))
        ResponseEntity.ok("""{"ok":true}""")
    }

    @PostMapping(path = Array("/change-password"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def changePassword(@RequestBody body: String): ResponseEntity[String] = {
        val userOpt = UserContext.get()
        if (userOpt.isEmpty)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("""{"error":"Not authenticated"}""")
        val user = userOpt.get

        try {
            val obj = JsonParser.parseString(body).getAsJsonObject
            val currentPassword = stringField(obj, "currentPassword")
            val newPassword = stringField(obj, "newPassword")
            if (newPassword == null || newPassword.length < 5)
                return ResponseEntity.badRequest().body("""{"error":"New password must be at least 5 characters"}""")

            // First-time set: skip the current-password check.
            if (!user.mustSetPassword) {
                if (!PasswordHasher.verify(currentPassword, user.passwordHash))
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("""{"error":"Current password is incorrect"}""")
            }

            UserStore.updatePasswordHash(user.username, PasswordHasher.hash(newPassword))
            ResponseEntity.ok("""{"ok":true}""")
        } catch {
            case e: Exception =>
                logger.error("Error in /auth/change-password: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("""{"error":"Internal error"}""")
        }
    }

    @GetMapping(path = Array("/users"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    @RequiresRole(Array("admin"))
    def listUsers(): ResponseEntity[String] = {
        val users = UserStore.list().map(u => {
            val obj = new JsonObject
            obj.addProperty("username", u.username)
            obj.addProperty("role", u.role)
            obj.addProperty("createdAt", formatTimestamp(u.createdAt))
            obj.addProperty("lastLoginAt", formatTimestamp(u.lastLoginAt))
            obj.addProperty("mustSetPassword", u.mustSetPassword)
            obj
        })
        val arr = new com.google.gson.JsonArray
        users.foreach(arr.add)
        ResponseEntity.ok(gson.toJson(arr))
    }

    /** Format a stored ISO instant using the application's configured dateFormat + dateTimezone.
      * Returns null/empty unchanged so the UI can show "—" for never-logged-in users. */
    private def formatTimestamp(iso: String): String = {
        if (iso == null || iso.isEmpty) return iso
        try {
            val env = DatrisEnvironment.values
            val fmt = DateTimeFormatter.ofPattern(env.dateFormat).withZone(ZoneId.of(env.dateTimezone))
            fmt.format(Instant.parse(iso))
        } catch {
            case e: Exception =>
                logger.warn("Failed to format timestamp '" + iso + "' with configured dateFormat/dateTimezone; returning raw value", e)
                iso
        }
    }

    @PostMapping(path = Array("/users"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    @RequiresRole(Array("admin"))
    def createUser(@RequestBody body: String): ResponseEntity[String] = {
        try {
            val obj = JsonParser.parseString(body).getAsJsonObject
            val username = UserStore.normalize(stringField(obj, "username"))
            val role = stringField(obj, "role")
            val password = stringField(obj, "password") // optional
            if (username.isEmpty)
                return ResponseEntity.badRequest().body("""{"error":"Username is required"}""")
            if (!username.matches("^[a-z0-9._@\\-]+$"))
                return ResponseEntity.badRequest().body("""{"error":"Invalid username — letters, digits, . _ @ - only"}""")
            if (!User.ValidRoles.contains(role))
                return ResponseEntity.badRequest().body("""{"error":"Invalid role"}""")
            if (UserStore.find(username).isDefined)
                return ResponseEntity.status(HttpStatus.CONFLICT).body("""{"error":"User already exists"}""")

            // Never create an account with a null hash — that was loginable with
            // any password. When the admin doesn't supply one, generate a random
            // temporary password and return it once so it can be handed to the
            // user out-of-band; they log in with it and change it.
            val (temporaryPassword, hash) =
                if (password == null || password.isEmpty) {
                    val temp = PasswordHasher.generateTemporary()
                    (Some(temp), PasswordHasher.hash(temp))
                } else {
                    (None, PasswordHasher.hash(password))
                }
            UserStore.create(username, hash, role)
            val resp = new JsonObject
            resp.addProperty("ok", true)
            temporaryPassword.foreach(resp.addProperty("temporaryPassword", _))
            ResponseEntity.status(HttpStatus.CREATED).body(gson.toJson(resp))
        } catch {
            case e: Exception =>
                logger.error("Error in POST /auth/users: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("""{"error":"Internal error"}""")
        }
    }

    @PatchMapping(path = Array("/users/{username}"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    @RequiresRole(Array("admin"))
    def patchUser(@PathVariable username: String, @RequestBody body: String, request: HttpServletRequest): ResponseEntity[String] = {
        try {
            val u = UserStore.normalize(username)
            val current = UserStore.find(u).getOrElse(
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("""{"error":"User not found"}""")
            )
            val obj = JsonParser.parseString(body).getAsJsonObject

            // role change
            if (obj.has("role")) {
                val newRole = obj.get("role").getAsString
                if (!User.ValidRoles.contains(newRole))
                    return ResponseEntity.badRequest().body("""{"error":"Invalid role"}""")
                // The built-in 'admin' user is locked to the admin role to guarantee a recovery account.
                if (u == "admin" && newRole != User.RoleAdmin)
                    return ResponseEntity.status(HttpStatus.CONFLICT).body("""{"error":"The 'admin' user must remain in the admin role"}""")
                // Don't let the last admin demote themselves out of the admin role.
                if (current.role == User.RoleAdmin && newRole != User.RoleAdmin && UserStore.adminCount() <= 1)
                    return ResponseEntity.status(HttpStatus.CONFLICT).body("""{"error":"Cannot demote the last admin"}""")
                UserStore.updateRole(u, newRole)
                val md = new JsonObject
                md.addProperty("role", newRole)
                md.addProperty("from", current.role)
                AuditLog.record(request, "user", "update", "user", u, metadata = md)
            }

            // reset password (admin sets back to null → user must set on next login)
            if (obj.has("resetPassword") && obj.get("resetPassword").getAsBoolean) {
                UserStore.updatePasswordHash(u, null)
                AuditLog.record(request, "user", "reset-password", "user", u)
            }

            ResponseEntity.ok("""{"ok":true}""")
        } catch {
            case e: Exception =>
                logger.error("Error in PATCH /auth/users: " + Throwables.getStackTraceAsString(e))
                // A change that already succeeded keeps its explicit row; clear the
                // recorded mark so the interceptor still writes the failure row.
                request.removeAttribute(AuditLog.RecordedAttr)
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("""{"error":"Internal error"}""")
        }
    }

    @DeleteMapping(path = Array("/users/{username}"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    @RequiresRole(Array("admin"))
    def deleteUser(@PathVariable username: String): ResponseEntity[String] = {
        try {
            val u = UserStore.normalize(username)
            val target = UserStore.find(u).getOrElse(
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("""{"error":"User not found"}""")
            )
            // The built-in 'admin' account is the recovery user and can never be deleted —
            // even if other admins exist, the operator can always reset its password via Mongo.
            if (u == "admin")
                return ResponseEntity.status(HttpStatus.CONFLICT).body("""{"error":"The 'admin' user cannot be deleted"}""")
            if (target.role == User.RoleAdmin && UserStore.adminCount() <= 1)
                return ResponseEntity.status(HttpStatus.CONFLICT).body("""{"error":"Cannot delete the last admin"}""")
            UserStore.delete(u)
            ResponseEntity.ok("""{"ok":true}""")
        } catch {
            case e: Exception =>
                logger.error("Error in DELETE /auth/users: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("""{"error":"Internal error"}""")
        }
    }

    // -------- helpers --------

    private def toMeResponse(user: User): java.util.Map[String, Object] = {
        Map[String, Object](
            "username" -> user.username,
            "role" -> user.role,
            "mustSetPassword" -> Boolean.box(user.mustSetPassword)
        ).asJava
    }

    private def unauthorized(): ResponseEntity[String] =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("""{"error":"Invalid username or password"}""")

    private def stringField(obj: JsonObject, name: String): String = {
        if (!obj.has(name) || obj.get(name).isJsonNull) ""
        else obj.get(name).getAsString
    }

    private def buildSessionCookie(value: String, maxAgeSeconds: Int): Cookie = {
        val cookie = new Cookie(sessionAuth.SessionCookieName, value)
        cookie.setHttpOnly(true)
        // Secure is env-driven: false for local HTTP dev, true (via
        // SESSION_COOKIE_SECURE) for any TLS-served deployment.
        cookie.setSecure(SessionAuthenticator.cookieSecure)
        cookie.setPath("/")
        cookie.setMaxAge(maxAgeSeconds)
        cookie.setAttribute("SameSite", "Strict")
        cookie
    }

    private val OidcTxCookieName = "datris-oidc-tx"
    private val OidcTxCookiePath = "/api/v1/auth/oidc"
    private val OidcTxMaxAgeSeconds = 600

    /** The SSO transaction cookie (state, nonce, PKCE verifier). SameSite=Lax,
      * not Strict: the callback is a navigation from the identity provider's
      * site, and a Strict cookie would not be sent on it. */
    private def buildOidcTxCookie(value: String, maxAgeSeconds: Int): Cookie = {
        val cookie = new Cookie(OidcTxCookieName, value)
        cookie.setHttpOnly(true)
        cookie.setSecure(SessionAuthenticator.cookieSecure)
        cookie.setPath(OidcTxCookiePath)
        cookie.setMaxAge(maxAgeSeconds)
        cookie.setAttribute("SameSite", "Lax")
        cookie
    }

    /** The callback request as the audit log sees it: the authorization code
      * and state are masked in the stored query string. Attributes (the
      * recorded mark) still land on the real request. */
    private def auditView(request: HttpServletRequest): HttpServletRequest =
        new jakarta.servlet.http.HttpServletRequestWrapper(request) {
            override def getQueryString: String = OidcLogin.redactCallbackQuery(super.getQueryString)
        }

    private def redirect(location: String): ResponseEntity[String] =
        ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).header("Cache-Control", "no-store").build()

    private def ssoRedirect(code: String): ResponseEntity[String] =
        redirect("/login?ssoError=" + (if (OidcLogin.Codes.contains(code)) code else OidcLogin.Failed))

    private def oidcNotEnabled(): ResponseEntity[String] =
        ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("""{"error":"Single sign-on is not enabled on this server"}""")

    /** The normalized username a refused token claimed, for the audit entry. */
    private def claimedUsername(claims: com.nimbusds.jwt.JWTClaimsSet, usernameClaim: String): String =
        claims.getClaim(Option(usernameClaim).filter(_.nonEmpty).getOrElse("email")) match {
            case s: String => UserStore.normalize(s).take(256)
            case _ => null
        }

    private def readCookie(request: HttpServletRequest, name: String): String = {
        val cookies = request.getCookies
        if (cookies == null) return null
        cookies.find(_.getName == name).map(_.getValue).orNull
    }
}
