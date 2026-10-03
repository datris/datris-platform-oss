package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException

/** Secret names and Vault secret paths that can only ever address the secret
  * they name. A denylist, so existing names with unusual but harmless
  * characters (dots, underscores, hyphens, colons) keep working. Whitespace
  * and the characters java.net.URI refuses in a path never worked against
  * Vault (the driver failed with URISyntaxException), so they are refused
  * up front instead of surfacing as a 500. */
object SecretNames {

    val InvalidMessage = "Invalid secret name"

    private val UnsafeChars: Set[Char] = "?#%\\\"<>|^`{}[]".toSet

    private def unsafeChar(c: Char): Boolean =
        UnsafeChars.contains(c) || Character.isISOControl(c) || Character.isWhitespace(c) || Character.isSpaceChar(c) ||
            c == '\u200b' || c == '\u200c' || c == '\u200d' || c == '\u2060' || c == '\ufeff'

    private def unsafeSegment(segment: String): Boolean =
        segment == "." || segment == ".." || segment.exists(unsafeChar)

    /** A Vault path (`<env>/<name>`, possibly nested): no `//`, and no
      * segment that is `.`/`..` or contains `?`, `#`, `%`, a backslash, a quote,
      * `<>|^`{}[]`, whitespace (including no-break and zero-width spaces) or a
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

    /** Why a tap or pipeline may not reference `name`: an unsafe path
      * ("Invalid secret name '<name>'") or a server-managed secret. */
    def referenceProblem(name: String): Option[String] =
        if (name == null) None
        else if (!isSafePath(name)) Some(InvalidMessage + " '" + name + "'")
        else if (isServerManaged(name)) Some(serverManagedMessage(name))
        else None

    /** Throws when a tap or pipeline references a server-managed secret. */
    def requireNotServerManaged(name: String): Unit =
        if (isServerManaged(name)) throw new DatrisException(serverManagedMessage(name))

    /** Throws before any Vault call when the path is not safe. */
    def requireSafePath(path: String): Unit =
        if (!isSafePath(path)) throw new DatrisException(InvalidMessage)
}
