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

    /** Throws before any Vault call when the path is not safe. */
    def requireSafePath(path: String): Unit =
        if (!isSafePath(path)) throw new DatrisException(InvalidMessage)
}
