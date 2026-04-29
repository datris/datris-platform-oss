package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

/** Thread-local for the user authenticated to the current request, parallel to TenantContext.
  * Set by SessionAuthenticator on cookie-authenticated requests, cleared after the request. */
object UserContext {
    private val threadLocal = new ThreadLocal[User]()

    def set(user: User): Unit = threadLocal.set(user)
    def get(): Option[User] = Option(threadLocal.get())
    def clear(): Unit = threadLocal.remove()
}
