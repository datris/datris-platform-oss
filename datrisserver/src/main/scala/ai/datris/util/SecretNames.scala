package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException

/** Secret names and Vault secret paths that can only ever address the secret
  * they name. A denylist, so existing names with unusual but harmless
  * characters (spaces, dots, underscores) keep working. */
object SecretNames {

    val InvalidMessage = "Invalid secret name"

    private def unsafeSegment(segment: String): Boolean =
        segment == "." || segment == ".." ||
            segment.exists(c => c == '?' || c == '#' || c == '%' || c == '\\' || Character.isISOControl(c))

    /** A Vault path (`<env>/<name>`, possibly nested): no `//`, and no
      * segment that is `.`/`..` or contains `?`, `#`, `%`, a backslash or a
      * control character. */
    def isSafePath(path: String): Boolean =
        path != null && !path.contains("//") && !path.split("/", -1).exists(unsafeSegment)

    /** A single secret name from a request: a safe, non-empty path with no `/`. */
    def isSafe(name: String): Boolean =
        name != null && name.nonEmpty && !name.contains('/') && isSafePath(name)

    /** Secrets the server issues and owns that nothing user-authored (a tap's
      * secretName, a pipeline's credentials or connection secret) may
      * reference: their values would be handed to a script or a destination
      * with no audit. The field-protection keys and the API-key stores. */
    val ServerManaged: Set[String] = Set("field-protection", "api-keys", "api-key-metadata", "ui-api-key")

    /** True when `name` (a bare name or an `<env>/<name>` path; trimmed,
      * case-insensitive) is one of [[ServerManaged]]. */
    def isServerManaged(name: String): Boolean =
        name != null && {
            val last = name.trim.split("/").lastOption.getOrElse("").trim.toLowerCase(java.util.Locale.ROOT)
            ServerManaged.contains(last)
        }

    def serverManagedMessage(name: String): String =
        "'" + name + "' is a platform secret and cannot be used as a tap or pipeline secret"

    /** Throws when a tap or pipeline references a server-managed secret. */
    def requireNotServerManaged(name: String): Unit =
        if (isServerManaged(name)) throw new DatrisException(serverManagedMessage(name))

    /** Throws before any Vault call when the path is not safe. */
    def requireSafePath(path: String): Unit =
        if (!isSafePath(path)) throw new DatrisException(InvalidMessage)
}
