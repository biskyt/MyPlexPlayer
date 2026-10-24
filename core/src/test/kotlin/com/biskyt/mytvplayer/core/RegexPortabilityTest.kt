package com.biskyt.mytvplayer.core

import org.junit.Assert.assertTrue
import org.junit.Test

/** Core's regexes are ones Android can compile (see [RegexPortability]); the app checks its own the same way. */
class RegexPortabilityTest {
    @Test
    fun `every regex in core is one Android can compile`() = RegexPortability.assertPortable(atLeast = 15)

    @Test
    fun `the check spots a bare brace`() {
        assertTrue(RegexPortability.problem("""\$?\{(utc)(?::([^}]*))?}""") != null)
        assertTrue(RegexPortability.problem("""\$?\{(utc)(?::([^}]*))?\}""") == null)
        assertTrue(RegexPortability.problem("""[A-Za-z]{2,3}\s*x{2}""") == null)
        assertTrue(RegexPortability.problem("""\p{M}+[^\p{L}\p{N}]""") == null)
    }
}
