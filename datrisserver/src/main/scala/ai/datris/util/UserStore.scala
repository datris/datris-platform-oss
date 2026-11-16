package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.Gson
import ai.datris.model.{DatrisEnvironment, User}

import java.time.Instant

/** CRUD for UI users. Documents live in {env}-user, keyed on `username`.
  *
  * Independent of the x-api-key system. */
object UserStore {
    private val gson = new Gson

    /** Test override for the backing store; null = the package [[NoSQLDbUtil]]. */
    @volatile private[datris] var dbOverride: NoSQLDbUtility = null

    private def db: NoSQLDbUtility = Option(dbOverride).getOrElse(NoSQLDbUtil)

    private def tableName: String = DatrisEnvironment.current.userTableName

    def list(): List[User] = {
        db.getAllItemsAsJSON(tableName).map(json => gson.fromJson(json, classOf[User]))
    }

    def find(username: String): Option[User] = {
        Option(db.getItemJSON(tableName, "username", normalize(username), null).orNull)
            .map(json => gson.fromJson(json, classOf[User]))
    }

    /** Create a user. Throws if the username already exists. */
    def create(username: String, passwordHash: String, role: String, oidcSubject: String = null): User = {
        val u = normalize(username)
        if (find(u).isDefined)
            throw new IllegalArgumentException("User '" + u + "' already exists")
        val now = Instant.now().toString
        val user = User(
            username = u,
            passwordHash = passwordHash,
            role = role,
            createdAt = now,
            updatedAt = now,
            lastLoginAt = null,
            oidcSubject = oidcSubject
        )
        db.insertJSON(tableName, gson.toJson(user))
        user
    }

    /** Insert without checking — used by the bootstrap seeder. */
    def insert(user: User): Unit = {
        db.insertJSON(tableName, gson.toJson(user))
    }

    def updatePasswordHash(username: String, passwordHash: String): Unit = {
        val u = normalize(username)
        val current = find(u).getOrElse(throw new IllegalArgumentException("User '" + u + "' not found"))
        val updated = current.copy(passwordHash = passwordHash, updatedAt = Instant.now().toString)
        db.upsertJSON(tableName, java.util.Collections.singletonList("username"), gson.toJson(updated))
    }

    def updateRole(username: String, role: String): Unit = {
        if (!User.ValidRoles.contains(role))
            throw new IllegalArgumentException("Invalid role: " + role)
        val u = normalize(username)
        val current = find(u).getOrElse(throw new IllegalArgumentException("User '" + u + "' not found"))
        val updated = current.copy(role = role, updatedAt = Instant.now().toString)
        db.upsertJSON(tableName, java.util.Collections.singletonList("username"), gson.toJson(updated))
    }

    def touchLastLogin(username: String): Unit = {
        val u = normalize(username)
        find(u).foreach { current =>
            val updated = current.copy(lastLoginAt = Instant.now().toString, updatedAt = Instant.now().toString)
            db.upsertJSON(tableName, java.util.Collections.singletonList("username"), gson.toJson(updated))
        }
    }

    /** Bind the identity provider's `sub` to the user (first SSO sign-in). */
    def setOidcSubject(username: String, subject: String): Unit = {
        val u = normalize(username)
        val current = find(u).getOrElse(throw new IllegalArgumentException("User '" + u + "' not found"))
        val updated = current.copy(oidcSubject = subject, updatedAt = Instant.now().toString)
        db.upsertJSON(tableName, java.util.Collections.singletonList("username"), gson.toJson(updated))
    }

    /** Set a user's restricted flag and replace its catalog grants
      * (catalog -> view|edit|own). Grant names must be valid, non-reserved
      * catalog labels and levels one of [[User.ValidGrantLevels]]. */
    def updateGrants(username: String, restricted: Boolean, grants: java.util.Map[String, String]): User = {
        val cleaned = ai.datris.auth.CatalogGrants.validate(grants) match {
            case Right(g) => g
            case Left(err) => throw new IllegalArgumentException(err)
        }
        val u = normalize(username)
        val current = find(u).getOrElse(throw new IllegalArgumentException("User '" + u + "' not found"))
        if (restricted && current.role == User.RoleAdmin)
            throw new IllegalArgumentException("An admin cannot be restricted")
        val updated = current.copy(
            restricted = restricted,
            catalogGrants = if (cleaned.isEmpty) null else cleaned,
            updatedAt = Instant.now().toString
        )
        db.upsertJSON(tableName, java.util.Collections.singletonList("username"), gson.toJson(updated))
        updated
    }

    /** Move every user's grant on `old` to `newName` (a catalog rename).
      * Returns the usernames whose grants changed. If a user already holds
      * `newName`, the existing grant on `newName` is replaced. */
    def renameCatalogInGrants(old: String, newName: String): Seq[String] =
        rewriteGrants(old)(g => ai.datris.auth.CatalogGrants.renameIn(g, old, newName))

    /** Add a grant on `newName` at the same level for every user holding
      * `old`, keeping `old` (a partial rename). Returns the usernames changed. */
    def copyCatalogInGrants(old: String, newName: String): Seq[String] =
        rewriteGrants(old) { g =>
            val out = new java.util.LinkedHashMap[String, String](g)
            if (!out.containsKey(newName)) out.put(newName, g.get(old))
            out
        }

    /** Drop every user's grant on `name` (a catalog delete). Returns the
      * usernames whose grants changed. The restricted flag is kept, so losing
      * the last grant never widens access. */
    def removeCatalogFromGrants(name: String): Seq[String] =
        rewriteGrants(name)(g => ai.datris.auth.CatalogGrants.removeFrom(g, name))

    private def rewriteGrants(catalog: String)(f: java.util.Map[String, String] => java.util.Map[String, String]): Seq[String] = {
        if (catalog == null) return Seq.empty
        list().filter(u => u != null && u.catalogGrants != null && u.catalogGrants.containsKey(catalog)).map { current =>
            val next = f(current.catalogGrants)
            val updated = current.copy(
                catalogGrants = if (next.isEmpty) null else next,
                updatedAt = Instant.now().toString
            )
            db.upsertJSON(tableName, java.util.Collections.singletonList("username"), gson.toJson(updated))
            current.username
        }.distinct
    }

    def delete(username: String): Unit = {
        db.deleteItemJSON(tableName, "username", normalize(username))
    }

    def adminCount(): Int = list().count(_.role == User.RoleAdmin)

    /** Lower-cased so lookups are case-insensitive. Whitespace trimmed. */
    def normalize(username: String): String = {
        if (username == null) "" else username.trim.toLowerCase
    }
}
