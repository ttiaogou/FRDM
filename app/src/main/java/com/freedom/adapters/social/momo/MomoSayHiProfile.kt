package com.freedom.adapters.social.momo

/**
 * Evidence-first model for the Momo "received greetings" flow.
 *
 * Values are only populated when their source text explicitly supports them. In particular,
 * gender is never inferred from avatar, nickname, pronouns in a biography, or profile imagery.
 */
internal data class MomoSayHiProfile(
    val displayName: FieldEvidence<String>,
    val age: FieldEvidence<Int>,
    val gender: FieldEvidence<Gender>,
    val distanceKm: FieldEvidence<Double>,
    // Online is derived only from explicit online/offline evidence. The row's right-side red dot
    // means unread invitation and must never be interpreted as presence/online status.
    val online: FieldEvidence<Boolean>,
    val biography: FieldEvidence<String>,
    val otherVisibleText: List<String>,
    /** Exact resource-ID keyed text captured only inside the confirmed foreground card. */
    val nodeTextFields: Map<String, MomoSayHiNodeTextFieldReadout> = emptyMap()
)

/**
 * Read-only evidence for one exact resource ID inside a confirmed profile-card container.
 * Raw text remains in memory; ordinary logs and structural node dumps must not emit it.
 */
internal data class MomoSayHiNodeTextFieldReadout(
    val viewId: String,
    val candidateCount: Int,
    val evidence: FieldEvidence<String>
)

internal enum class Gender { FEMALE, MALE, OTHER }

internal enum class EvidenceStatus {
    CONFIRMED,
    UNKNOWN,
    MISSING,
    EMPTY,
    MULTIPLE_MATCHES,
    CONFLICT,
    INVALID
}

internal data class FieldEvidence<T>(
    val value: T?,
    val rawText: String?,
    val status: EvidenceStatus,
    val source: String = "ACCESSIBILITY_NODE_TEXT"
)


internal data class MomoSayHiListMetadata(
    val age: FieldEvidence<Int>,
    val gender: FieldEvidence<Gender>,
    val distanceKm: FieldEvidence<Double>,
    // The red dot is a separate unread-invitation signal, not an online indicator.
    val online: FieldEvidence<Boolean>
)

internal enum class MomoSayHiPageKind {
    RECEIVED_GREETINGS_LIST,
    GREETING_PROFILE_CARD,
    UNKNOWN
}

/** Observed Activity hints and resource IDs; page identity is established by the structural fingerprint. */
internal object MomoSayHiPageContract {
    const val LIST_ACTIVITY =
        "com.immomo.momo.message.sayhi.activity.reflect.NewSayHiSessionListActivity"
    const val CARD_ACTIVITY =
        "com.immomo.momo.message.sayhi.activity.reflect.NewSayHiCardActivity"
    const val PROFILE_ACTIVITY =
        "com.immomo.momo.personalprofile.presentation.activity.PersonalProfileActivityK"
    /** The outer `listview` RecyclerView is the vertically scrolling received-greetings page. */
    const val LIST_VIEW_ID = "com.immomo.momo:id/listview"
    /** `card_list` is nested inside the list header and only contains the horizontal recommendation cards. */
    const val RECOMMENDATION_CAROUSEL_VIEW_ID = "com.immomo.momo:id/card_list"
    const val CARD_CONTENT_VIEW_ID = "com.immomo.momo:id/rv_card_content"
    const val CARD_CLOSE_VIEW_ID = "com.immomo.momo:id/close_container"

    fun classify(
        activityClass: String,
        visibleViewIds: Set<String>,
        receivedGreetingsListFingerprintConfirmed: Boolean = false
    ): MomoSayHiPageKind = when {
        // The card layout has not yet been supplied as a complete structural fixture. Retain its
        // Activity as auxiliary evidence, but still require its exact content/close anchors.
        activityClass == CARD_ACTIVITY &&
            (CARD_CONTENT_VIEW_ID in visibleViewIds || CARD_CLOSE_VIEW_ID in visibleViewIds) ->
            MomoSayHiPageKind.GREETING_PROFILE_CARD
        // The list page is now identity-confirmed by its component hierarchy, not its Activity.
        receivedGreetingsListFingerprintConfirmed -> MomoSayHiPageKind.RECEIVED_GREETINGS_LIST
        else -> MomoSayHiPageKind.UNKNOWN
    }
}

/**
 * Parses only explicit textual evidence from a single profile card.
 * Pass node texts from one card/container, never the whole screen or a neighboring list row.
 */
internal object MomoSayHiProfileParser {
    private val agePattern = Regex("(?<!\\d)([0-9]{1,3})\\s*岁")
    private val ageBadgePattern = Regex("([♀♂])\\s*([0-9]{1,3})")
    private val distancePattern = Regex("(?<![0-9.])([0-9]+(?:\\.[0-9]+)?)\\s*(公里|千米|km|m)(?![a-z])", RegexOption.IGNORE_CASE)
    private val onlineTokenPattern = Regex("(^|[\\s·,:：])在线($|[\\s·,:：])")
    private val offlineTokenPattern = Regex("(^|[\\s·,:：])离线($|[\\s·,:：])")
    private val femaleTokenPattern = Regex("(^|[\\s·,:：])女(?:性)?($|[\\s·,:：])")
    private val maleTokenPattern = Regex("(^|[\\s·,:：])男(?:性)?($|[\\s·,:：])")

    /** A complete, independently delimited distance field, not arbitrary profile text. */
    fun parseDistanceToken(token: String): Double? {
        val match = distancePattern.matchEntire(token.trim()) ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        val km = if (match.groupValues[2].equals("m", ignoreCase = true)) amount / 1_000.0 else amount
        return km.takeIf { it.isFinite() && it >= 0.0 }
    }

    /** Parses only explicit metadata found inside one already-isolated greeting row. */
    fun parseListMetadata(nodeTexts: List<String>): MomoSayHiListMetadata {
        val texts = nodeTexts.map(String::trim).filter(String::isNotBlank).distinct()
        val ageMatches = texts.flatMap { text ->
            val explicit = agePattern.findAll(text).mapNotNull { match ->
                match.groupValues[1].toIntOrNull()?.takeIf { it in 1..120 }?.let { it to text }
            }.toList()
            val badge = ageBadgePattern.findAll(text).mapNotNull { match ->
                match.groupValues[2].toIntOrNull()?.takeIf { it in 1..120 }?.let { it to text }
            }.toList()
            explicit + badge
        }
        val genderMatches = texts.flatMap { text ->
            buildList {
                if (text.contains("♀") || femaleTokenPattern.containsMatchIn(text)) add(Gender.FEMALE to text)
                if (text.contains("♂") || maleTokenPattern.containsMatchIn(text)) add(Gender.MALE to text)
                if (text == "其他性别") add(Gender.OTHER to text)
            }
        }
        val distanceMatches = texts.mapNotNull { text ->
            distancePattern.find(text)?.let { match ->
                val amount = match.groupValues[1].toDoubleOrNull()
                val unit = match.groupValues[2].lowercase()
                val km = when (unit) {
                    "m" -> amount?.div(1_000.0)
                    else -> amount
                }
                km?.let { it to match.value }
            }
        }
        val onlineMatches = texts.flatMap { text ->
            buildList {
                if (onlineTokenPattern.containsMatchIn(text)) add(true to text)
                if (offlineTokenPattern.containsMatchIn(text)) add(false to text)
            }
        }
        fun <T> evidence(matches: List<Pair<T, String>>): FieldEvidence<T> = when {
            matches.isEmpty() -> FieldEvidence(null, null, EvidenceStatus.UNKNOWN)
            matches.map { it.first }.distinct().size > 1 ->
                FieldEvidence(null, matches.joinToString(" | ") { it.second }, EvidenceStatus.CONFLICT)
            else -> FieldEvidence(matches.first().first, matches.joinToString(" | ") { it.second }, EvidenceStatus.CONFIRMED)
        }
        return MomoSayHiListMetadata(
            age = evidence(ageMatches),
            gender = evidence(genderMatches),
            distanceKm = evidence(distanceMatches),
            online = evidence(onlineMatches)
        )
    }

    fun parse(nodeTexts: List<String>): MomoSayHiProfile {
        val texts = nodeTexts.map(String::trim).filter(String::isNotBlank).distinct()
        fun <T> evidenceOrUnknown(raw: String? = null): FieldEvidence<T> =
            FieldEvidence(null, raw, EvidenceStatus.UNKNOWN)

        val name = texts.firstOrNull { candidate ->
            candidate.length in 2..20 &&
                !candidate.contains(Regex("[0-9]")) &&
                !candidate.contains("在线") &&
                !candidate.contains("公里") &&
                !candidate.contains("km", true) &&
                !candidate.contains("岁") &&
                !candidate.contains("♀") && !candidate.contains("♂") &&
                candidate !in setOf("男", "女", "男性", "女性", "其他性别", "离线")
        }
        val ageMatches = texts.flatMap { text ->
            val explicitAge = agePattern.findAll(text).mapNotNull { match ->
                match.groupValues[1].toIntOrNull()?.takeIf { it in 1..120 }?.let { it to text }
            }.toList()
            val badgeAge = ageBadgePattern.findAll(text).mapNotNull { match ->
                match.groupValues[2].toIntOrNull()?.takeIf { it in 1..120 }?.let { it to text }
            }.toList()
            explicitAge + badgeAge
        }
        val distanceMatches = texts.mapNotNull { text ->
            distancePattern.find(text)?.let { match ->
                val raw = match.value
                val amount = match.groupValues[1].toDoubleOrNull()
                val unit = match.groupValues[2].lowercase()
                val km = when (unit) {
                    "m" -> amount?.div(1000.0)
                    else -> amount
                }
                km?.let { it to raw }
            }
        }
        val onlineMatches = texts.filter { it == "在线" || it.startsWith("在线·") || it.startsWith("在线 ") }
        val genderMatches = texts.mapNotNull { text ->
            when {
                text == "女" || text == "女性" || text.contains("♀") -> Gender.FEMALE
                text == "男" || text == "男性" || text.contains("♂") -> Gender.MALE
                text == "其他性别" -> Gender.OTHER
                else -> null
            }?.let { it to text }
        }
        val bios = texts.filter { it.length >= 8 && (
            it.contains("，") || it.contains("。") || it.contains("收入") ||
                it.contains("身高") || it.contains("职业") || it.contains("喜欢")
        ) }

        fun <T> result(matches: List<Pair<T, String>>): FieldEvidence<T> = when {
            matches.isEmpty() -> FieldEvidence(null, null, EvidenceStatus.UNKNOWN)
            matches.map { it.first }.distinct().size > 1 ->
                FieldEvidence(null, matches.joinToString(" | ") { it.second }, EvidenceStatus.CONFLICT)
            else -> FieldEvidence(matches.first().first, matches.joinToString(" | ") { it.second }, EvidenceStatus.CONFIRMED)
        }

        return MomoSayHiProfile(
            displayName = name?.let { FieldEvidence(it, it, EvidenceStatus.CONFIRMED) }
                ?: evidenceOrUnknown(),
            age = result(ageMatches),
            gender = result(genderMatches),
            distanceKm = result(distanceMatches),
            online = when {
                onlineMatches.isNotEmpty() -> FieldEvidence(true, onlineMatches.joinToString(" | "), EvidenceStatus.CONFIRMED)
                texts.any { it == "离线" || it.startsWith("离线·") } ->
                    FieldEvidence(false, texts.first { it == "离线" || it.startsWith("离线·") }, EvidenceStatus.CONFIRMED)
                else -> FieldEvidence(null, null, EvidenceStatus.UNKNOWN)
            },
            biography = when {
                bios.isEmpty() -> evidenceOrUnknown()
                bios.distinct().size > 1 -> FieldEvidence(null, bios.joinToString(" | "), EvidenceStatus.CONFLICT)
                else -> FieldEvidence(bios.first(), bios.first(), EvidenceStatus.CONFIRMED)
            },
            otherVisibleText = texts.filterNot { it == name || it in bios ||
                agePattern.containsMatchIn(it) || distancePattern.containsMatchIn(it) ||
                it in onlineMatches || it in genderMatches.map { pair -> pair.second }
            }
        )
    }
}
