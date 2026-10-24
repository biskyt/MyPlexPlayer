package com.biskyt.myplexplayer.core

import java.io.File
import org.junit.Assert.assertTrue

/**
 * The tests run on the JVM, whose regex engine forgives a bare "{" or "}". Android's (ICU) doesn't: it throws when the
 * pattern is compiled, which crashed every M3U sync on real devices (0.9.0–0.9.5) while every test passed. So every
 * regex in the sources is checked as ICU would: outside a [class], braces are a count ("{2}", "{1,3}") or escaped.
 * Each module checks its own sources with [assertPortable], so a change runs only that module's check again.
 */
object RegexPortability {
    private val raw = Regex("Regex\\(\\s*\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL)
    private val quoted = Regex("Regex\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val count = Regex("\\{\\d+(,\\d*)?}")

    /** Where [pattern] has a brace ICU would reject, or null. */
    fun problem(pattern: String): Int? {
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                // An escaped character is skipped, and so is a property's name ("\p{L}").
                c == '\\' -> i += if ((pattern.getOrNull(i + 1) ?: ' ') in "pP" && pattern.getOrNull(i + 2) == '{') {
                    pattern.indexOf('}', i).takeIf { it > 0 }?.minus(i) ?: 1
                } else {
                    1
                }
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '{' -> count.matchAt(pattern, i)?.let { i += it.value.length - 1 } ?: return i
                c == '}' -> return i
            }
            i++
        }
        return null
    }

    /** Every regex in the module's own sources ([dir]), of which there are at least [atLeast], is one ICU compiles. */
    fun assertPortable(atLeast: Int, dir: File = File("src/main")) {
        val patterns = dir.walk().filter { it.extension == "kt" }.flatMap { file ->
            val text = file.readText()
            raw.findAll(text).map { file.name to it.groupValues[1] } +
                quoted.findAll(text).map { file.name to it.groupValues[1].replace("\\\\", "\\").replace("\\\"", "\"") }
        }.toList()
        assertTrue("found only ${patterns.size} regexes: is the scan looking in the right place?", patterns.size >= atLeast)
        val bad = patterns.mapNotNull { (file, p) -> problem(p)?.let { "$file: $p (at $it)" } }
        assertTrue("Braces Android's regex engine rejects:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
