package com.freedom.features.conversation.domain.contact

import com.freedom.features.conversation.ports.ConversationRepository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/**
 * Resolves per-snapshot row identities and durable contact IDs.
 *
 * A display name is not a person identifier. Momo's ordinary inbox currently exposes no stable
 * platform user ID, so name-only rows receive snapshot-scoped temporary IDs and are never
 * persisted as contacts. Durable IDs are created/reused only for an explicitly verified platform
 * user ID (for example, one read from the profile page).
 */
class ContactIdentityManager(
    private val repository: ConversationRepository,
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {
    @Synchronized
    fun resolveSnapshot(
        snapshotId: String,
        observations: List<ContactIdentityObservation>,
        observedAtMillis: Long
    ): List<ContactRowIdentity> {
        require(snapshotId.isNotBlank()) { "snapshotId must not be blank" }

        val normalizedNames = observations.map { observation ->
            if (observation.isPrivateChat && observation.platformUserId.isNullOrBlank()) {
                normalizeName(observation.displayName)
            } else ""
        }
        val nameCounts = normalizedNames.filter(String::isNotBlank).groupingBy { it }.eachCount()
        val profilesToPersist = LinkedHashMap<String, ContactProfile>()

        val resolved = observations.mapIndexed { index, observation ->
            val temporaryId = "$snapshotId-U${(index + 1).toString().padStart(3, '0')}"
            if (!observation.isPrivateChat) {
                return@mapIndexed ContactRowIdentity(
                    snapshotId, temporaryId, null, ContactIdentityConfidence.NOT_A_PRIVATE_CHAT
                )
            }

            val platformId = observation.platformUserId?.trim()?.takeIf(String::isNotEmpty)
            val normalizedName = normalizedNames[index]
            if (platformId == null && normalizedName.isBlank()) {
                return@mapIndexed ContactRowIdentity(
                    snapshotId, temporaryId, null, ContactIdentityConfidence.UNRESOLVED
                )
            }
            if (platformId == null && nameCounts[normalizedName] != 1) {
                return@mapIndexed ContactRowIdentity(
                    snapshotId, temporaryId, null, ContactIdentityConfidence.AMBIGUOUS_DUPLICATE_NAME
                )
            }

            // Never promote a unique nickname to a reusable contact ID. The name can be shared,
            // changed, or recycled by the platform. It is diagnostic/display evidence only.
            if (platformId == null) {
                return@mapIndexed ContactRowIdentity(
                    snapshotId, temporaryId, null, ContactIdentityConfidence.DISPLAY_NAME_PROVISIONAL
                )
            }

            val identityKeyHash = sha256("momo:platform:$platformId")
            val matches = repository.findContactsByIdentityKeyHash(identityKeyHash)
            if (matches.size > 1) {
                return@mapIndexed ContactRowIdentity(
                    snapshotId, temporaryId, null, ContactIdentityConfidence.UNRESOLVED
                )
            }
            val existing = matches.singleOrNull()
            if (existing?.confidence != null &&
                existing.confidence != ContactIdentityConfidence.PLATFORM_ID_VERIFIED
            ) {
                return@mapIndexed ContactRowIdentity(
                    snapshotId, temporaryId, null, ContactIdentityConfidence.UNRESOLVED
                )
            }
            val profile = ContactProfile(
                contactId = existing?.contactId ?: "contact-${idFactory()}",
                identityKeyHash = identityKeyHash,
                displayName = observation.displayName.orEmpty().trim(),
                identitySource = "PLATFORM_USER_ID",
                confidence = ContactIdentityConfidence.PLATFORM_ID_VERIFIED,
                firstSeenAtMillis = existing?.firstSeenAtMillis ?: observedAtMillis,
                lastSeenAtMillis = maxOf(existing?.lastSeenAtMillis ?: 0L, observedAtMillis)
            )
            if (existing == null || existing.displayName != profile.displayName ||
                existing.confidence != profile.confidence ||
                profile.lastSeenAtMillis - existing.lastSeenAtMillis >= LAST_SEEN_WRITE_INTERVAL_MS
            ) {
                profilesToPersist[profile.contactId] = profile
            }
            ContactRowIdentity(
                snapshotId, temporaryId, profile.contactId,
                ContactIdentityConfidence.PLATFORM_ID_VERIFIED
            )
        }
        repository.upsertContacts(profilesToPersist.values.toList())
        return resolved
    }

    private companion object {
        const val LAST_SEEN_WRITE_INTERVAL_MS = 60_000L
    }

    private fun normalizeName(value: String?): String = Normalizer.normalize(
        value.orEmpty(), Normalizer.Form.NFKC
    ).trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
