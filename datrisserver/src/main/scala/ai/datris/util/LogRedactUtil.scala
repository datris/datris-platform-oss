package ai.datris.util

import com.google.gson.{JsonArray, JsonElement, JsonObject, JsonPrimitive}

import java.util.regex.Matcher

/** Redacts credentials from JDBC URLs, JSON documents, and query strings
  * before they reach logs, persisted job status, or the audit log. */
object LogRedactUtil {

    val Mask = "***"

    private val userInfoPattern = "://([^/@\\s:]+):([^/@\\s]+)@".r
    private val sensitiveParamPattern = "(?i)\\b(password|sslpassword|pwd|token|accesstoken|private_key|privatekey)=([^;&\\s]*)".r

    /** Field-name pattern for JSON / query-string redaction. Deliberately a
      * substring match rather than an exact list so a new endpoint that takes
      * a `clientSecret`, `refreshToken`, or `x-api-key` still gets caught. */
    private val sensitiveFieldPattern =
        "(?i)(password|passwd|secret|token|api[_-]?key|apikey|authorization|cookie|credential|private[_-]?key)".r

    def isSensitiveField(name: String): Boolean =
        name != null && sensitiveFieldPattern.findFirstIn(name).isDefined

    def redactJdbcUrl(url: String): String = {
        if (url == null) null
        else {
            val noUserInfo = userInfoPattern.replaceAllIn(url, m => Matcher.quoteReplacement("://" + m.group(1) + ":***@"))
            sensitiveParamPattern.replaceAllIn(noUserInfo, m => Matcher.quoteReplacement(m.group(1) + "=***"))
        }
    }

    /** Deep-copy a JSON tree with every value under a sensitive-looking key
      * replaced by [[Mask]]. Arrays and nested objects are walked; primitives
      * under safe keys pass through untouched. Never mutates the input. */
    def redactJson(el: JsonElement): JsonElement = {
        if (el == null || el.isJsonNull) return el
        if (el.isJsonObject) {
            val out = new JsonObject()
            val it = el.getAsJsonObject.entrySet().iterator()
            while (it.hasNext) {
                val e = it.next()
                if (isSensitiveField(e.getKey)) out.add(e.getKey, new JsonPrimitive(Mask))
                else out.add(e.getKey, redactJson(e.getValue))
            }
            out
        } else if (el.isJsonArray) {
            val out = new JsonArray()
            val it = el.getAsJsonArray.iterator()
            while (it.hasNext) out.add(redactJson(it.next()))
            out
        } else el
    }

    /** Mask the value of any sensitive-looking parameter in a raw query
      * string (`?apiKey=abc&name=x` → `?apiKey=***&name=x`). Keeps parameter
      * order and leaves everything else byte-for-byte intact. */
    def redactQueryString(qs: String): String = {
        if (qs == null || qs.isEmpty) return qs
        qs.split("&", -1).map { part =>
            val eq = part.indexOf('=')
            if (eq <= 0) part
            else {
                val k = part.substring(0, eq)
                if (isSensitiveField(k)) k + "=" + Mask else part
            }
        }.mkString("&")
    }
}
