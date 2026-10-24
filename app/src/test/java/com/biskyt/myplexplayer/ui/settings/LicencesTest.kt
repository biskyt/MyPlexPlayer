package com.biskyt.myplexplayer.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LicencesTest {
    private val toml = File("../gradle/libs.versions.toml").readText()

    /** The version catalogue's versions, by name. */
    private val versions = Regex("""(?m)^(\w[\w-]*)\s*=\s*"([^"]+)"""")
        .findAll(toml.substringAfter("[versions]").substringBefore("[libraries]"))
        .associate { it.groupValues[1] to it.groupValues[2] }

    /** The version catalogue's libraries: alias (as `libs.a.b` names it) → module and version. */
    private val catalogue: Map<String, Pair<String, String?>> =
        Regex("""(?m)^([\w-]+)\s*=\s*\{\s*module\s*=\s*"([^"]+)"(?:,\s*version\.ref\s*=\s*"([^"]+)")?""")
            .findAll(toml.substringAfter("[libraries]").substringBefore("[plugins]"))
            .associate { m -> m.groupValues[1].replace('-', '.') to (m.groupValues[2] to versions[m.groupValues[3]]) }

    /** Libraries shipped in the app: the app's `implementation` and core's `api`/`implementation` ones. */
    private val shipped = listOf("../app/build.gradle.kts", "../core/build.gradle.kts").flatMap { path ->
        Regex("""(?m)^\s*(?:implementation|api)\(libs\.([\w.]+)\)""").findAll(File(path).readText()).map { it.groupValues[1] }.toList()
    }.filter { it != "compose.bom" }

    @Test
    fun `every library in the app is listed, at the version it's built with`() {
        assertTrue(shipped.size > 20)
        shipped.forEach { alias ->
            val (module, version) = catalogue.getValue(alias)
            val notice = openSourceNotices.singleOrNull { module in it.modules }
            assertTrue("$module isn't on the licences page", notice != null)
            if (version != null) assertEquals(module, version, notice!!.version)
        }
    }

    @Test
    fun `Kotlin and Compose are listed at the versions the app is built with`() {
        assertEquals(versions.getValue("kotlin"), openSourceNotices.single { it.name == "Kotlin" }.version)
        assertEquals("BOM " + versions.getValue("composeBom"), openSourceNotices.single { it.name == "Jetpack Compose" }.version)
    }

    @Test
    fun `nothing listed that the app doesn't use`() {
        val modules = shipped.map { catalogue.getValue(it).first }.toSet()
        openSourceNotices.flatMap { it.modules }.forEach { assertTrue("$it isn't in the build", it in modules) }
    }
}
