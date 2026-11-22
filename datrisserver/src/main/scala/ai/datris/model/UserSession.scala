package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** A logged-in browser session. Stored in Mongo with a TTL index on
  * `expiresAtDate` so expired rows are auto-purged by the server.
  * `tokenHash` is the hex SHA-256 of the browser's `datris-session` cookie
  * value (see [[ai.datris.util.SessionStore.hash]]); the cookie value itself
  * is never stored. */
case class UserSession(
    tokenHash: String,
    username: String,
    expiresAt: String,
    createdAt: String
)
