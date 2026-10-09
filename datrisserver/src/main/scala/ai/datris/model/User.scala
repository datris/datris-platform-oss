package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** A UI user. Independent of the x-api-key system — humans log in with username + password,
  * programmatic clients (CLI, MCP) keep using x-api-key.
  *
  * passwordHash is null when the account hasn't set a password yet (default admin on first
  * boot, or any user created without an initial password). Login allows an empty password
  * against a null hash, which forces the user through the set-password flow.
  *
  * oidcSubject is the identity provider's `sub` bound on the first single
  * sign-on (OIDC); null for accounts that never signed in through SSO and for
  * documents stored before SSO existed. A later SSO sign-in with a different
  * `sub` is refused (see ai.datris.auth.OidcLogin.resolveUser). */
case class User(
    username: String,
    passwordHash: String,
    role: String,
    createdAt: String,
    updatedAt: String,
    lastLoginAt: String,
    oidcSubject: String = null
) {
    def mustSetPassword: Boolean = passwordHash == null || passwordHash.isEmpty
}

object User {
    val RoleAdmin = "admin"
    val RoleEditor = "editor"
    val RoleViewer = "viewer"

    val ValidRoles: Set[String] = Set(RoleAdmin, RoleEditor, RoleViewer)
}
