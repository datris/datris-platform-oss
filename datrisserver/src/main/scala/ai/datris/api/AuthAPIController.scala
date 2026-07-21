package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.{RequiresRole, SessionAuthenticator}
import ai.datris.model.{DatrisEnvironment, User, UserContext}
import ai.datris.util.{PasswordHasher, SessionStore, UserStore}

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

            // First-login flow: passwordHash is null/empty. Accept any password (typically empty)
            // and force the client through change-password before doing anything else.
            if (!user.mustSetPassword) {
                if (!PasswordHasher.verify(password, user.passwordHash)) {
                    logger.info("Login failed: bad password for: " + username)
                    return unauthorized()
                }
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

            val hash = if (password == null || password.isEmpty) null else PasswordHasher.hash(password)
            UserStore.create(username, hash, role)
            ResponseEntity.status(HttpStatus.CREATED).body("""{"ok":true}""")
        } catch {
            case e: Exception =>
                logger.error("Error in POST /auth/users: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("""{"error":"Internal error"}""")
        }
    }

    @PatchMapping(path = Array("/users/{username}"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    @RequiresRole(Array("admin"))
    def patchUser(@PathVariable username: String, @RequestBody body: String): ResponseEntity[String] = {
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
            }

            // reset password (admin sets back to null → user must set on next login)
            if (obj.has("resetPassword") && obj.get("resetPassword").getAsBoolean) {
                UserStore.updatePasswordHash(u, null)
            }

            ResponseEntity.ok("""{"ok":true}""")
        } catch {
            case e: Exception =>
                logger.error("Error in PATCH /auth/users: " + Throwables.getStackTraceAsString(e))
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
        cookie.setSecure(false) // dev: HTTP. Prod ingress (nginx) terminates TLS and rewrites.
        cookie.setPath("/")
        cookie.setMaxAge(maxAgeSeconds)
        cookie.setAttribute("SameSite", "Strict")
        cookie
    }

    private def readCookie(request: HttpServletRequest, name: String): String = {
        val cookies = request.getCookies
        if (cookies == null) return null
        cookies.find(_.getName == name).map(_.getValue).orNull
    }
}
