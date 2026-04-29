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

    private def tableName: String = DatrisEnvironment.current.userTableName

    def list(): List[User] = {
        NoSQLDbUtil.getAllItemsAsJSON(tableName).map(json => gson.fromJson(json, classOf[User]))
    }

    def find(username: String): Option[User] = {
        Option(NoSQLDbUtil.getItemJSON(tableName, "username", normalize(username), null).orNull)
            .map(json => gson.fromJson(json, classOf[User]))
    }

    /** Create a user. Throws if the username already exists. */
    def create(username: String, passwordHash: String, role: String): User = {
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
            lastLoginAt = null
        )
        NoSQLDbUtil.insertJSON(tableName, gson.toJson(user))
        user
    }

    /** Insert without checking — used by the bootstrap seeder. */
    def insert(user: User): Unit = {
        NoSQLDbUtil.insertJSON(tableName, gson.toJson(user))
    }

    def updatePasswordHash(username: String, passwordHash: String): Unit = {
        val u = normalize(username)
        val current = find(u).getOrElse(throw new IllegalArgumentException("User '" + u + "' not found"))
        val updated = current.copy(passwordHash = passwordHash, updatedAt = Instant.now().toString)
        NoSQLDbUtil.upsertJSON(tableName, java.util.Collections.singletonList("username"), gson.toJson(updated))
    }

    def updateRole(username: String, role: String): Unit = {
        if (!User.ValidRoles.contains(role))
            throw new IllegalArgumentException("Invalid role: " + role)
        val u = normalize(username)
        val current = find(u).getOrElse(throw new IllegalArgumentException("User '" + u + "' not found"))
        val updated = current.copy(role = role, updatedAt = Instant.now().toString)
        NoSQLDbUtil.upsertJSON(tableName, java.util.Collections.singletonList("username"), gson.toJson(updated))
    }

    def touchLastLogin(username: String): Unit = {
        val u = normalize(username)
        find(u).foreach { current =>
            val updated = current.copy(lastLoginAt = Instant.now().toString, updatedAt = Instant.now().toString)
            NoSQLDbUtil.upsertJSON(tableName, java.util.Collections.singletonList("username"), gson.toJson(updated))
        }
    }

    def delete(username: String): Unit = {
        NoSQLDbUtil.deleteItemJSON(tableName, "username", normalize(username))
    }

    def adminCount(): Int = list().count(_.role == User.RoleAdmin)

    /** Lower-cased so lookups are case-insensitive. Whitespace trimmed. */
    def normalize(username: String): String = {
        if (username == null) "" else username.trim.toLowerCase
    }
}
