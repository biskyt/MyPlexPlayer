package com.biskyt.mytvplayer.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tests run on the JVM, whose regex engine forgives a bare "{" or "}". Android's (ICU) doesn't: it throws when the
 * pattern is compiled, which crashed every M3U sync on real devices (0.9.0–0.9.5) while every test passed. So every
 * regex in the sources is checked as ICU would: outside a [class], braces are a count ("{2}", "{1,3}") or escaped.
 */
class RegexPortabilityTest {
    private val raw = Regex("Regex\\(\\s*\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL)
    private val quoted = Regex("Regex\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val count = Regex("\\{\\d+(,\\d*)?}")

    private fun sources() = listOf(File("src/main"), File("../app/src/main"))
        .flatMap { dir -> dir.walk().filter { it.extension == "kt" }.toList() }

    /** Where [pattern] has a brace ICU would reject, or null. */
    private fun problem(pattern: String): Int? {
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

    @Test
    fun `every regex in the app is one Android can compile`() {
        val patterns = sources().flatMap { file ->
            val text = file.readText()
            raw.findAll(text).map { file.name to it.groupValues[1] } +
                quoted.findAll(text).map { file.name to it.groupValues[1].replace("\\\\", "\\").replace("\\\"", "\"") }
        }
        assertTrue("found only ${patterns.size} regexes: is the scan looking in the right place?", patterns.size > 20)
        val bad = patterns.mapNotNull { (file, p) -> problem(p)?.let { "$file: $p (at $it)" } }
        assertTrue("Braces Android's regex engine rejects:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `the check spots a bare brace`() {
        assertTrue(problem("""\$?\{(utc)(?::([^}]*))?}""") != null)
        assertTrue(problem("""\$?\{(utc)(?::([^}]*))?\}""") == null)
        assertTrue(problem("""[A-Za-z]{2,3}\s*x{2}""") == null)
        assertTrue(problem("""\p{M}+[^\p{L}\p{N}]""") == null)
    }
}
