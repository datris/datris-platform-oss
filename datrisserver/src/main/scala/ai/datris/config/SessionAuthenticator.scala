package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, UserContext}
import ai.datris.util.{SessionStore, UserStore}
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/** Reads the `datris-session` cookie, looks up the session, and attaches the user
  * to UserContext for the request. Does NOT reject unauthenticated requests on its
  * own — that's `RoleEnforcementInterceptor`'s job, since some endpoints are public
  * (login, version) and others tolerate either a session or an x-api-key. */
object SessionAuthenticator {

    /** Request attribute set when the request carried a session cookie that no
      * longer resolves to a live session. Only a browser whose session expired
      * (or was revoked) can be in this state — programmatic callers never send
      * the cookie — so RoleEnforcementInterceptor turns it into a hard 401
      * instead of letting the request degrade to the anonymous legacy path. */
    val StaleSessionAttribute = "datris.staleSession"

    /** Whether to set the `Secure` flag on the session cookie. Default false so
      * local HTTP dev keeps working; set SESSION_COOKIE_SECURE=true in any
      * TLS-served deployment so the session token is never sent over plain HTTP
      * (mixed content, a stray http link, or a TLS-stripping MITM). */
    def cookieSecure: Boolean =
        sys.env.get("SESSION_COOKIE_SECURE").exists(_.equalsIgnoreCase("true"))
}

@Component
class SessionAuthenticator extends HandlerInterceptor {

    val SessionCookieName = "datris-session"

    override def preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean = {
        if (!DatrisEnvironment.values.useUserAuth) return true

        val token = readCookie(request, SessionCookieName)
        if (token == null || token.isEmpty) return true

        val session = SessionStore.renew(token)
        if (session.isEmpty)
            request.setAttribute(SessionAuthenticator.StaleSessionAttribute, java.lang.Boolean.TRUE)
        session.foreach { s =>
            UserStore.find(s.username).foreach(UserContext.set)
        }
        true
    }

    override def afterCompletion(request: HttpServletRequest, response: HttpServletResponse, handler: Any, ex: Exception): Unit = {
        UserContext.clear()
    }

    private def readCookie(request: HttpServletRequest, name: String): String = {
        val cookies = request.getCookies
        if (cookies == null) return null
        cookies.find(_.getName == name).map(_.getValue).orNull
    }
}
