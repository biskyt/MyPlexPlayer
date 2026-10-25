package com.biskyt.myplexplayer.ui.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every text style the app uses is one its theme defines: Material's others have no colour of their own, so they draw
 * black on the dark background (the sign-in code did, with displayMedium).
 */
class TypographyTest {
    @Test
    fun `every text style used is one the theme defines`() {
        val defined = Regex("""(\w+) = style\(""").findAll(File("src/main/java/com/biskyt/myplexplayer/ui/theme/Theme.kt").readText())
            .map { it.groupValues[1] }.toSet()
        assertTrue("the theme's styles weren't found", defined.size > 10)
        val used = File("src/main").walk().filter { it.extension == "kt" }
            .flatMap { file -> Regex("""typography\.(\w+)""").findAll(file.readText()).map { it.groupValues[1] to file.name } }
            .toList()
        assertTrue(used.isNotEmpty())
        assertEquals(emptyList<Pair<String, String>>(), used.filter { it.first !in defined }.distinct())
    }
}
