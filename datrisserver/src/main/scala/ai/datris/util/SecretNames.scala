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
      * with no audit. The field-protection keys, the API-key stores and the
      * OIDC single sign-on client secret. */
    val ServerManaged: Set[String] = Set("field-protection", "api-keys", "api-key-metadata", "ui-api-key", "oidc")

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

    // ---- Tap secret scope (plans/stories/field-protection-8-tap-secret-scope.md)

    val TapScopeProperty = "datris.tapSecretScope"
    val TapScopeEnv = "DATRIS_TAP_SECRET_SCOPE"
    val TapScopeAny = "any"
    val TapScopeTap = "tap"

    private val warnedUnknownScope = new java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean]()

    /** "any" or "tap": which secrets a tap may use. Read at call time from the
      * `datris.tapSecretScope` system property, else DATRIS_TAP_SECRET_SCOPE;
      * trimmed and lowercased. Unset means "tap"; any other value is treated
      * as "tap" and logged once. */
    def tapScope: String = {
        val raw = sys.props.get(TapScopeProperty).orElse(sys.env.get(TapScopeEnv))
        raw.map(_.trim.toLowerCase(java.util.Locale.ROOT)) match {
            case Some(TapScopeAny) => TapScopeAny
            case Some(v) if v.nonEmpty && v != TapScopeTap =>
                if (warnedUnknownScope.putIfAbsent(v, java.lang.Boolean.TRUE) == null)
                    org.slf4j.LoggerFactory.getLogger(getClass).warn(
                        TapScopeEnv + "='" + v + "' is not 'tap' or 'any'; treating it as 'tap' (taps may only use tap secrets)"
                    )
                TapScopeTap
            case _ => TapScopeTap
        }
    }

    /** True unless DATRIS_TAP_SECRET_SCOPE=any. */
    def tapScopeEnforced: Boolean = tapScope != TapScopeAny

    def tapSecretMessage(tapName: String, secretName: String): String =
        "Tap '" + tapName + "' uses secret '" + secretName + "', which is a platform secret. Taps may only use tap secrets " +
            "(Configuration → Secrets → Tap). Create a tap secret with the fields this tap needs and select it, " +
            "or set " + TapScopeEnv + "=any to allow platform secrets."

    /** Some(message) when the scope is enforced and the secret's stored
      * `_type` is not exactly "tap" (None = untyped, which counts as a
      * platform secret). Whether the secret exists at all is the caller's
      * decision: call this only for a secret that exists, so the existing
      * "missing or empty" error still fires for one that does not. */
    def tapSecretProblem(tapName: String, secretName: String, storedType: Option[String], enforced: Boolean): Option[String] =
        if (enforced && !storedType.contains(TapScopeTap)) Some(tapSecretMessage(tapName, secretName))
        else None

    /** Throws before any Vault call when the path is not safe. */
    def requireSafePath(path: String): Unit =
        if (!isSafePath(path)) throw new DatrisException(InvalidMessage)
}
