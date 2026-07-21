package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** A logged-in browser session. Stored in Mongo with a TTL index on `expiresAt`
  * so expired rows are auto-purged by the server. */
case class UserSession(
    token: String,
    username: String,
    expiresAt: String,
    createdAt: String
)
