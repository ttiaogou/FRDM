package com.freedom.adapters.social.momo

import org.junit.Assert.*
import org.junit.Test

class MomoSayHiProfileParserTest {
    @Test fun parsesExplicitProfileEvidence() {
        val profile = MomoSayHiProfileParser.parse(
            listOf("萧荧", "在线 · 0.59km", "女", "30岁", "他月收入1万元，身高165cm，旅游四方")
        )
        assertEquals(EvidenceStatus.CONFIRMED, profile.age.status)
        assertEquals(30, profile.age.value)
        assertEquals(EvidenceStatus.CONFIRMED, profile.distanceKm.status)
        assertEquals(0.59, profile.distanceKm.value!!, 0.0001)
        assertEquals(true, profile.online.value)
        assertEquals(Gender.FEMALE, profile.gender.value)
        assertTrue(profile.biography.value!!.contains("月收入"))
    }

    @Test fun parsesExplicitListMetadataAndConvertsMetersToKilometers() {
        val metadata = MomoSayHiProfileParser.parseListMetadata(
            listOf("♀28", "500m", "在线")
        )

        assertEquals(28, metadata.age.value)
        assertEquals(Gender.FEMALE, metadata.gender.value)
        assertEquals(0.5, metadata.distanceKm.value!!, 0.0001)
        assertEquals(true, metadata.online.value)
    }

    @Test fun reportsConflictingOnlineEvidenceInsteadOfChoosingOneState() {
        val metadata = MomoSayHiProfileParser.parseListMetadata(listOf("在线", "离线"))

        assertEquals(EvidenceStatus.CONFLICT, metadata.online.status)
        assertNull(metadata.online.value)
    }

    @Test fun doesNotGuessMissingGenderOrOnlineState() {
        val profile = MomoSayHiProfileParser.parse(listOf("某用户", "30岁", "0.59km"))
        assertEquals(EvidenceStatus.UNKNOWN, profile.gender.status)
        assertEquals(EvidenceStatus.UNKNOWN, profile.online.status)
    }

    @Test fun conflictingAgesAreNotSilentlyResolved() {
        val profile = MomoSayHiProfileParser.parse(listOf("30岁", "31岁"))
        assertEquals(EvidenceStatus.CONFLICT, profile.age.status)
        assertNull(profile.age.value)
    }

    @Test fun classifiesOnlyObservedActivitiesAndViewIds() {
        assertEquals(
            MomoSayHiPageKind.UNKNOWN,
            MomoSayHiPageContract.classify(
                MomoSayHiPageContract.LIST_ACTIVITY,
                setOf(MomoSayHiPageContract.LIST_VIEW_ID)
            )
        )
        assertEquals(
            MomoSayHiPageKind.RECEIVED_GREETINGS_LIST,
            MomoSayHiPageContract.classify(
                MomoSayHiPageContract.LIST_ACTIVITY,
                setOf(MomoSayHiPageContract.LIST_VIEW_ID),
                receivedGreetingsListFingerprintConfirmed = true
            )
        )
        assertEquals(
            MomoSayHiPageKind.GREETING_PROFILE_CARD,
            MomoSayHiPageContract.classify(
                MomoSayHiPageContract.CARD_ACTIVITY,
                setOf(MomoSayHiPageContract.CARD_CONTENT_VIEW_ID)
            )
        )
        assertEquals(
            MomoSayHiPageKind.UNKNOWN,
            MomoSayHiPageContract.classify("unknown", emptySet())
        )
    }
}
