package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import java.util.Locale

/** Upper- and lower-casing of query text in time proportional to its length.
  * On Java 17, `String.toUpperCase` / `toLowerCase` regrow and recopy the
  * result once for every character whose case form is longer than itself, so
  * text made of such characters takes time in the square of its length. Here
  * the text is read one code point at a time into one builder: ASCII is
  * converted directly and every other code point by the JDK on that code
  * point alone, which takes constant time.
  *
  * [[upper]] gives exactly `toUpperCase(Locale.ROOT)`, which has no context
  * rules. [[lower]] gives `toLowerCase(Locale.ROOT)` except for its one
  * context rule: a capital sigma at the end of a word becomes the medial
  * small sigma, not the final one. Both are non-ASCII letters of one UTF-16
  * unit, so no pattern here reads them differently. */
object QueryTextCase {

    def upper(s: String): String = convert(s, upperCase = true)

    def lower(s: String): String = convert(s, upperCase = false)

    /** [[upper]] of the first `codePoints` code points of `s` (all of `s`
      * when shorter). Upper-casing has no context rules and maps every code
      * point to at least one unit, so this is a prefix of `upper(s)` at least
      * `codePoints` units long: enough to test whether `upper(s)` starts with
      * a word of up to that many characters. */
    def upperPrefix(s: String, codePoints: Int): String = {
        var end = 0
        var taken = 0
        while (end < s.length && taken < codePoints) {
            end += Character.charCount(s.codePointAt(end))
            taken += 1
        }
        upper(s.substring(0, end))
    }

    private def convert(s: String, upperCase: Boolean): String = {
        val n = s.length
        val out = new java.lang.StringBuilder(n + 16)
        var i = 0
        while (i < n) {
            val c = s.charAt(i)
            if (c < 128) {
                out.append(
                    if (upperCase && c >= 'a' && c <= 'z') (c - 32).toChar
                    else if (!upperCase && c >= 'A' && c <= 'Z') (c + 32).toChar
                    else c
                )
                i += 1
            } else {
                val count = Character.charCount(s.codePointAt(i))
                val one = s.substring(i, i + count)
                out.append(if (upperCase) one.toUpperCase(Locale.ROOT) else one.toLowerCase(Locale.ROOT))
                i += count
            }
        }
        out.toString
    }
}
