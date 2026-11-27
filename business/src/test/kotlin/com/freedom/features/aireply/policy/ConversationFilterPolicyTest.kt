package com.freedom.features.aireply.policy

import org.junit.Assert.*
import org.junit.Test

class ConversationFilterPolicyTest {
    @Test fun defaultIsSixtyAndInclusive() {
        val settings = ConversationFilterSettings()
        assertEquals(60, settings.maxLastActiveMinutes)
        for (minutes in listOf(0L, 1L, 38L, 60L)) {
            assertEquals(ConversationFilterResult.ALLOW,
                ConversationFilterPolicy.evaluate(settings, ConversationFilterEvidence(lastActiveMinutes = minutes)).result)
        }
        assertEquals(ConversationFilterResult.EXCLUDE,
            ConversationFilterPolicy.evaluate(settings, ConversationFilterEvidence(lastActiveMinutes = 61)).result)
        assertEquals(ConversationFilterResult.UNKNOWN,
            ConversationFilterPolicy.evaluate(settings, ConversationFilterEvidence()).result)
    }

    @Test fun oneMinuteAndDisabledFilters() {
        val settings = ConversationFilterSettings(maxLastActiveMinutes = 1)
        for (minutes in listOf(0L, 1L)) {
            assertEquals(ConversationFilterResult.ALLOW,
                ConversationFilterPolicy.evaluate(settings, ConversationFilterEvidence(lastActiveMinutes = minutes)).result)
        }
        assertEquals(ConversationFilterResult.EXCLUDE,
            ConversationFilterPolicy.evaluate(settings, ConversationFilterEvidence(lastActiveMinutes = 2)).result)
        assertEquals(ConversationFilterResult.ALLOW,
            ConversationFilterPolicy.evaluate(ConversationFilterSettings(maxLastActiveMinutes = 0), ConversationFilterEvidence()).result)
    }

    @Test fun explicitExclusionTakesPriorityOverMissingEvidence() {
        val settings = ConversationFilterSettings(maxDistanceKm = 10, minAge = 25, maxAge = 40)
        assertEquals(ConversationFilterReason.DISTANCE_EXCEEDED,
            ConversationFilterPolicy.evaluate(settings, ConversationFilterEvidence(distanceKm = 10.01)).reason)
        assertEquals(ConversationFilterReason.AGE_BELOW_MINIMUM,
            ConversationFilterPolicy.evaluate(settings, ConversationFilterEvidence(age = 24)).reason)
        assertEquals(ConversationFilterReason.AGE_ABOVE_MAXIMUM,
            ConversationFilterPolicy.evaluate(settings, ConversationFilterEvidence(age = 41)).reason)
        assertEquals(ConversationFilterResult.ALLOW,
            ConversationFilterPolicy.evaluate(settings, ConversationFilterEvidence(60, 10.0, 25)).result)
        assertEquals(ConversationFilterResult.ALLOW,
            ConversationFilterPolicy.evaluate(settings, ConversationFilterEvidence(60, 10.0, 40)).result)
        assertEquals(ConversationFilterResult.UNKNOWN,
            ConversationFilterPolicy.evaluate(settings, ConversationFilterEvidence(60, null, 30)).result)
    }

    @Test fun invalidSettingsAndEvidenceNeverSilentlyAllow() {
        assertEquals(ConversationFilterReason.INVALID_SETTINGS,
            ConversationFilterPolicy.evaluate(ConversationFilterSettings(minAge = 40, maxAge = 25), ConversationFilterEvidence()).reason)
        assertEquals(ConversationFilterResult.UNKNOWN,
            ConversationFilterPolicy.evaluate(ConversationFilterSettings(0, 1), ConversationFilterEvidence(distanceKm = Double.NaN)).result)
        assertEquals(ConversationFilterResult.UNKNOWN,
            ConversationFilterPolicy.evaluate(ConversationFilterSettings(0, minAge = 18), ConversationFilterEvidence(age = 0)).result)
    }
    @Test fun profileEvidenceFillsOnlyMissingFieldsAndCombinedPolicyStaysInclusive() {
        val profile = ConversationFilterEvidence(lastActiveMinutes = 60, age = 30)
        val returnedRow = ConversationFilterEvidence(distanceKm = 10.0, age = 99)
        val evidence = profile.supplementedBy(returnedRow)
        assertEquals(ConversationFilterEvidence(60, 10.0, 30), evidence)
        assertEquals(ConversationFilterResult.ALLOW,
            ConversationFilterPolicy.evaluate(ConversationFilterSettings(60, 10, 25, 40), evidence).result)
        assertEquals(ConversationFilterResult.UNKNOWN,
            ConversationFilterPolicy.evaluate(ConversationFilterSettings(), returnedRow).result)
        assertFalse(ConversationFilterSettings(maxLastActiveMinutes = 0).hasEnabledConditions)
        assertTrue(ConversationFilterSettings(maxLastActiveMinutes = 0, minAge = 18).hasEnabledConditions)
        assertTrue(ConversationFilterSettings(maxLastActiveMinutes = -1).hasEnabledConditions)
    }

}
