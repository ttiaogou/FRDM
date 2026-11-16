package com.freedom.adapters.social.momo

import org.junit.Assert.*
import org.junit.Test

class MomoAlternativeFingerprintPolicyTest {
    @Test fun routesAreOredButConditionsInsideEachRouteAreAnded() {
        val paths = listOf(
            MomoFingerprintPath("primary", listOf(true, false)),
            MomoFingerprintPath("fallback", listOf(true, true))
        )
        assertEquals("fallback", MomoAlternativeFingerprintPolicy.firstMatch(paths))
        assertTrue(MomoAlternativeFingerprintPolicy.matchesAny(paths))
    }

    @Test fun incompleteRoutesDoNotMatchAndDifferentPagesMustBeEvaluatedSeparately() {
        assertFalse(MomoFingerprintPath("empty", emptyList()).matched)
        assertFalse(MomoAlternativeFingerprintPolicy.matchesAny(listOf(
            MomoFingerprintPath("primary", listOf(true, false)),
            MomoFingerprintPath("fallback", listOf(false, true))
        )))
    }
}
