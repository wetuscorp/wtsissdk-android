package co.wetus.sdk.internal

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class ExperienceBootstrapRequest(
    val schemaVersion: Int = 2,
    val actorId: String,
    val sessionId: String,
    val metadata: Metadata,
    val testDeviceToken: String,
)

@Serializable
internal data class ExperienceBootstrapResponse(
    val onlineKeyset: OnlineKeyset,
    /**
     * Compatibility copy from the collector. It is intentionally decoded as
     * opaque JSON and must never influence runtime behavior; only
     * `signedPayload` is verified and decoded as a Manifest.
     */
    val manifest: JsonElement,
    val signedPayload: String,
    val signature: String,
    val keyId: String,
    val expiresAt: String,
) {
    @Serializable
    data class Manifest(
        val sourceId: String,
        val sourceKey: String,
        val manifestVersion: Int,
        val environment: String = "production",
        val issuedAt: String,
        val expiresAt: String,
        val campaigns: List<Campaign>,
    )

    @Serializable
    data class Campaign(
        val campaignId: String,
        val campaignVersionId: String,
        val priority: Int,
        val placement: String,
        val trigger: JsonObject,
        val targeting: JsonObject,
        val variants: List<ExperienceDecisionResponse.Variant>,
        val requiresPersonalization: Boolean,
        val grant: String? = null,
        val assignment: Branch? = null,
    )

    @Serializable
    data class Branch(
        val assignmentId: String,
        val kind: String,
        val variantId: String? = null,
    )

    @Serializable
    data class OnlineKeyset(
        val version: Int,
        val issuedAt: String,
        val expiresAt: String,
        val keys: List<OnlineKey>,
        val signedPayload: String,
        val rootSignature: String,
    )

    @Serializable
    data class OnlineKey(
        val keyId: String,
        val algorithm: String,
        val publicKey: String,
        val notBefore: String,
        val expiresAt: String,
    )

    @Serializable
    data class OnlineKeysetPayload(
        val version: Int,
        val issuedAt: String,
        val expiresAt: String,
        val keys: List<OnlineKey>,
    )
}

@Serializable
internal data class ExperienceTriggerWire(
    val type: String,
    val match: Match? = null,
    val screenName: String? = null,
    val eventKey: String? = null,
    val conditions: List<JsonObject> = emptyList(),
) {
    @Serializable data class Match(val kind: String, val value: String? = null)
}

@Serializable
internal data class ExperienceContextWire(
    val trigger: ExperienceTriggerWire,
    val screenName: String? = null,
    val eventKey: String? = null,
    val properties: JsonObject,
    val triggerEventId: String? = null,
)

@Serializable
internal data class ExperienceDecisionRequest(
    val schemaVersion: Int = 2,
    val actorId: String,
    val sessionId: String,
    val metadata: Metadata,
    val testDeviceToken: String,
    val candidateVersionIds: List<String>,
    val context: ExperienceContextWire,
)

@Serializable
internal data class ExperienceDecisionResponse(
    val mode: String,
    val decisions: List<Decision>,
    val serverTime: String,
) {
    @Serializable
    data class Decision(
        val campaignId: String,
        val campaignVersionId: String,
        val assignmentId: String,
        val variantId: String? = null,
        val holdout: Boolean,
        val placement: String,
        val priority: Int,
        val content: Variant? = null,
        val grant: String,
    )
    @Serializable
    data class Variant(
        val id: String,
        val content: Content,
        val asset: Asset? = null,
    )
    @Serializable data class Asset(val url: String)
}

@Serializable
internal data class Content(
    val translations: Map<String, LocalizedContent>,
    val closeable: Boolean,
    val themePreset: String,
    val delaySeconds: Double,
    val autoCloseSeconds: Double? = null,
)

@Serializable
internal data class LocalizedContent(
    val title: String,
    val description: String,
    val primaryAction: Action? = null,
    val secondaryAction: Action? = null,
)

@Serializable
internal data class Action(
    val id: String,
    val label: String,
    val type: String,
    val target: String? = null,
)

@Serializable
internal data class ExperienceInteractionRequest(
    val clientInteractionId: String = UUID.randomUUID().toString(),
    val grant: String,
    val campaignId: String,
    val campaignVersionId: String,
    val assignmentId: String?,
    val variantId: String?,
    val exposureId: String?,
    val type: String,
    val actionId: String? = null,
    val actionOutcome: String? = null,
    val triggerEventId: String? = null,
    val occurredAt: String = isoTimestamp(),
    val metadata: Metadata,
    val failureCode: String? = null,
)

@Serializable
internal data class ExperienceInteractionQueue(
    val interactions: List<ExperienceInteractionRequest>,
)

@Serializable
internal data class ExperienceInteractionBatchRequest(
    val schemaVersion: Int = 2,
    val actorId: String,
    val sessionId: String,
    val interactions: List<ExperienceInteractionRequest>,
)

@Serializable
internal data class ExperienceInteractionBatchResponse(
    val accepted: List<String>,
    val duplicates: List<String>,
    val rejected: List<Rejected> = emptyList(),
) {
    @Serializable data class Rejected(val clientInteractionId: String, val retryable: Boolean)
}
