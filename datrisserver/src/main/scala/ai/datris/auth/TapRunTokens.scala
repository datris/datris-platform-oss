package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Capability, ResolvedKey}

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._

/** Short-lived, per-run credentials for the tap → platform callback.
  *
  * Python taps read platform data through `DATRIS_PLATFORM_HOST` with no
  * credential of their own — the isolation model exists so untrusted tap code
  * never holds a platform secret. When API keys are required, that callback
  * still has to authenticate, so `TapScriptRunner` mints one of these per run,
  * hands it to the script as `DATRIS_PLATFORM_TOKEN`, and revokes it when the
  * run ends. `APIKeyValidator` accepts a live token wherever it accepts a key,
  * resolving it to a read-only identity labeled `tap:<name>` — which is how
  * the audit log knows the caller was the tap itself. */
object TapRunTokens {

    /** Label prefix that marks a tap-run identity, parallel to `session:`. */
    val LabelPrefix = "tap:"

    /** What a tap may do on the callback: read. Taps return records; the
      * platform writes them. Query on every store, vector search, metadata,
      * and reading its own / other definitions. No writes, ever. */
    val Capabilities: Seq[Capability] = Seq(
        "query:*",
        "search:vector",
        "metadata:read",
        "pipeline:read",
        "tap:read"
    ).map(Capability.parse)

    case class TapRunToken(tapName: String, tenantEnvironment: Option[String], expiresAtMs: Long)

    private val tokens = new ConcurrentHashMap[String, TapRunToken]()
    private val random = new SecureRandom()

    /** Mint a token for one run. `ttlSeconds` should cover the script timeout
      * plus a margin — a token that outlives its run is only a window for the
      * (already isolated) script that received it. */
    def issue(tapName: String, tenantEnvironment: Option[String], ttlSeconds: Int): String = {
        sweep()
        val bytes = new Array[Byte](32)
        random.nextBytes(bytes)
        val token = "trt_" + bytes.map(b => f"${b & 0xff}%02x").mkString
        tokens.put(token, TapRunToken(tapName, tenantEnvironment, System.currentTimeMillis() + ttlSeconds.toLong * 1000L))
        token
    }

    def revoke(token: String): Unit =
        if (token != null) tokens.remove(token)

    /** The run behind a token, if it is live. */
    def lookup(token: String): Option[TapRunToken] = {
        if (token == null || !token.startsWith("trt_")) return None
        Option(tokens.get(token)).filter { t =>
            val live = t.expiresAtMs > System.currentTimeMillis()
            if (!live) tokens.remove(token)
            live
        }
    }

    def resolve(token: String): Option[ResolvedKey] =
        lookup(token).map(t => ResolvedKey(t.tenantEnvironment, LabelPrefix + t.tapName, Capabilities, isLegacyFullAccess = false))

    def isTapLabel(label: String): Boolean = label != null && label.startsWith(LabelPrefix)

    def tapName(label: String): String = if (isTapLabel(label)) label.substring(LabelPrefix.length) else label

    /** Number of live tokens — for tests and diagnostics. */
    def liveCount: Int = { sweep(); tokens.size() }

    private def sweep(): Unit = {
        val now = System.currentTimeMillis()
        tokens.entrySet().asScala.filter(_.getValue.expiresAtMs <= now).map(_.getKey).foreach(tokens.remove)
    }
}
