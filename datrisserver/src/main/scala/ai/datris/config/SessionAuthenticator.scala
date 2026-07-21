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
@Component
class SessionAuthenticator extends HandlerInterceptor {

    val SessionCookieName = "datris-session"

    override def preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean = {
        if (!DatrisEnvironment.values.useUserAuth) return true

        val token = readCookie(request, SessionCookieName)
        if (token == null || token.isEmpty) return true

        SessionStore.renew(token).foreach { session =>
            UserStore.find(session.username).foreach(UserContext.set)
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
