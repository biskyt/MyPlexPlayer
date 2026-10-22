package com.biskyt.mytvplayer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * values-en-rGB repeats values/ for exactly the strings values-en-rUS changes (without it, Android shows US English on a
 * UK device). This keeps the copy from drifting from the original. Also checks the build keeps every language.
 */
class EnglishStringsTest {
    private val res = File("src/main/res")
    private val entry = Regex("""<(string|plurals) name="([^"]+)"[^>]*>.*?</\1>""", RegexOption.DOT_MATCHES_ALL)

    private fun entries(dir: String): Map<String, String> = File(res, dir).listFiles { f -> f.name.startsWith("strings") }
        .orEmpty()
        .flatMap { f -> entry.findAll(f.readText()).map { it.groupValues[2] to it.value } }
        .toMap()

    // Release builds keep only the resources localeFilters names: a language missing there would lose its words.
    @Test fun `the build keeps every language the app offers`() {
        val filters = Regex("""localeFilters \+= listOf\(([^)]*)\)""").find(File("build.gradle.kts").readText())!!
            .groupValues[1].split(',').map { it.trim().trim('"') }
        AppText.languages.forEach { tag ->
            val qualifier = tag.replace("-", "-r")
            assertTrue("$qualifier missing from localeFilters", qualifier in filters)
        }
    }

    @Test fun `values-en-rGB has the UK text of every string values-en-rUS changes`() {
        val uk = entries("values")
        val copy = entries("values-en-rGB")
        assertEquals(entries("values-en-rUS").keys, copy.keys)
        copy.forEach { (name, text) -> assertEquals(name, uk[name], text) }
    }
}
