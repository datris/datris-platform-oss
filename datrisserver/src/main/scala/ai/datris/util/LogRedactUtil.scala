package ai.datris.util

import java.util.regex.Matcher

/** Redacts credentials from JDBC URLs before they reach logs or persisted job status. */
object LogRedactUtil {

    private val userInfoPattern = "://([^/@\\s:]+):([^/@\\s]+)@".r
    private val sensitiveParamPattern = "(?i)\\b(password|sslpassword|pwd|token|accesstoken|private_key|privatekey)=([^;&\\s]*)".r

    def redactJdbcUrl(url: String): String = {
        if (url == null) null
        else {
            val noUserInfo = userInfoPattern.replaceAllIn(url, m => Matcher.quoteReplacement("://" + m.group(1) + ":***@"))
            sensitiveParamPattern.replaceAllIn(noUserInfo, m => Matcher.quoteReplacement(m.group(1) + "=***"))
        }
    }
}
