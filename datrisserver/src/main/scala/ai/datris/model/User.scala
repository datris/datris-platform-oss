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
  * `sub` is refused (see ai.datris.auth.OidcLogin.resolveUser).
  *
  * restricted + catalogGrants narrow a non-admin user to specific data
  * catalogs (catalog -> view|edit|own; see ai.datris.auth.CatalogGrants).
  * Documents stored before these fields existed read as false/null, which
  * keeps today's role-wide access. Admins are never restricted. */
case class User(
    username: String,
    passwordHash: String,
    role: String,
    createdAt: String,
    updatedAt: String,
    lastLoginAt: String,
    oidcSubject: String = null,
    restricted: Boolean = false,
    catalogGrants: java.util.Map[String, String] = null
) {
    def mustSetPassword: Boolean = passwordHash == null || passwordHash.isEmpty

    /** True when this user's session is narrowed to its catalog grants. Admins never are. */
    def isRestricted: Boolean = restricted && role != User.RoleAdmin
}

object User {
    val RoleAdmin = "admin"
    val RoleEditor = "editor"
    val RoleViewer = "viewer"

    val ValidRoles: Set[String] = Set(RoleAdmin, RoleEditor, RoleViewer)

    val GrantView = "view"
    val GrantEdit = "edit"
    val GrantOwn = "own"

    /** Catalog grant levels: view (read), edit (+ create/update/run), own (+ delete/move). */
    val ValidGrantLevels: Set[String] = Set(GrantView, GrantEdit, GrantOwn)
}
