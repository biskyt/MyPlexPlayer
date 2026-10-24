package com.biskyt.myplexplayer

import com.biskyt.myplexplayer.core.RegexPortability
import org.junit.Test

/** The app's regexes are ones Android can compile (see [RegexPortability]). */
class RegexPortabilityTest {
    @Test
    fun `every regex in the app is one Android can compile`() = RegexPortability.assertPortable(atLeast = 4)
}
