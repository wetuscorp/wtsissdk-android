@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package co.wetus.sdk.internal

import co.wetus.sdk.WtsSdk
import java.util.UUID
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class TestSessionMetadata(
    val platform: String = "android",
    val sdkFamily: String,
    val sdkVersion: String = WtsSdk.VERSION,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val appVersion: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val osVersion: String? = null,
    val locale: String,
)

@Serializable
internal data class TestSessionPairRequest(
    val schemaVersion: Int = 1,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val pairingToken: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val pairingCode: String? = null,
    val metadata: TestSessionMetadata,
)

@Serializable
internal data class TestSessionPairResponse(
    val session: Session,
    val participant: Participant,
    val sessionToken: String,
    val testProfile: TestProfile,
    val requiredSdkVersion: String,
    val testPlan: TestSessionPlan,
) {
    @Serializable data class Session(val id: String, val status: String, val expiresAt: String)
    @Serializable data class Participant(
        val id: String,
        val sourceId: String,
        val sourceType: String,
        val status: String,
    )
    @Serializable data class TestProfile(val externalUserId: String)
}

@Serializable
internal data class TestSessionCapabilities(
    val deeplink: Boolean,
    val identity: Boolean,
    val screen: Boolean,
    val experiences: Boolean,
    val offlineQueue: Boolean,
)

@Serializable
internal data class TestSessionConsent(
    val analytics: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val profile: Boolean? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val experience: String? = null,
)

@Serializable
internal data class TestSessionHandshakeRequest(
    val schemaVersion: Int = 1,
    val participantId: String,
    val sessionToken: String,
    val metadata: TestSessionMetadata,
    val capabilities: TestSessionCapabilities,
    val consent: TestSessionConsent,
)

@Serializable
internal data class TestSessionHandshakeResponse(
    val accepted: Boolean,
    val compatible: Boolean,
    val requiredSdkVersion: String,
    val checks: List<Check>,
    val testPlan: TestSessionPlan,
) {
    @Serializable data class Check(
        val key: String,
        val status: String,
        val code: String? = null,
        val message: String? = null,
    )
}

@Serializable
internal data class TestSessionPlan(
    val profile: Profile? = null,
    val events: List<Event> = emptyList(),
    val deepLink: DeepLink? = null,
    val experience: Experience? = null,
    val screen: Screen? = null,
) {
    @Serializable
    internal data class Profile(
        val selected: Boolean,
        val available: Boolean,
        val allowedMethods: List<String>,
    )

    @Serializable
    internal data class Event(
        val eventKey: String,
        val properties: List<Property>,
        val revenueEnabled: Boolean,
    )

    @Serializable
    internal data class Property(
        val key: String,
        val type: String,
        val required: Boolean,
    )

    @Serializable
    internal data class DeepLink(
        val selected: Boolean,
        val available: Boolean,
        val linkId: String? = null,
    )

    @Serializable
    internal data class Experience(
        val selected: Boolean,
        val available: Boolean,
        val campaignId: String? = null,
        val versionId: String? = null,
    )

    @Serializable
    internal data class Screen(val selected: Boolean)
}

@Serializable
internal data class TestSessionSignal(
    val clientSignalId: String = UUID.randomUUID().toString(),
    val type: String,
    val outcome: String,
    val occurredAt: String = isoTimestamp(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val method: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val eventKey: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val screenName: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val propertyKeys: List<String>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val propertyTypes: Map<String, String>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val revenue: TestSessionRevenueDescriptor? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val resultCode: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val feature: String? = null,
)

@Serializable
internal data class TestSessionRevenueDescriptor(
    val present: Boolean,
    val currency: String,
)

@Serializable
internal data class TestSessionSignalBatch(
    val schemaVersion: Int = 1,
    val participantId: String,
    val sessionToken: String,
    val signals: List<TestSessionSignal>,
)

@Serializable
internal data class TestSessionSignalBatchResponse(
    val accepted: List<String>,
    val duplicates: List<String>,
    val rejected: List<Rejected>,
) {
    @Serializable data class Rejected(
        val clientSignalId: String,
        val code: String,
        val message: String,
        val retryable: Boolean,
    )
}

@Serializable
internal data class TestSessionResolveRequest(
    val schemaVersion: Int = 1,
    val participantId: String,
    val sessionToken: String,
    val url: String,
)

@Serializable
internal data class TestSessionResolveResponse(
    val match: Boolean,
    val status: String,
    val code: String,
    val originalUrl: String,
    val fallbackUrl: String,
    val link: Link? = null,
) {
    @Serializable data class Link(
        val id: String,
        val path: String,
        val parameters: JsonObject,
    )
}

@Serializable
internal data class TestSessionExperienceDecisionRequest(
    val schemaVersion: Int = 1,
    val participantId: String,
    val sessionToken: String,
    val context: Context,
) {
    @Serializable
    internal data class Context(
        val type: String,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val pathname: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val pageName: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val screenName: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val eventKey: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val properties: JsonObject? = null,
        val locale: String,
    )
}

@Serializable
internal data class TestSessionExperienceDecisionResponse(
    val outcome: String,
    val reason: String? = null,
    val testGrant: TestGrant? = null,
    val decision: Decision? = null,
) {
    @Serializable
    internal data class TestGrant(
        val fixtureId: String,
        val expiresAt: String,
    )

    @Serializable
    internal data class Decision(
        val campaignId: String,
        val campaignVersionId: String,
        val placement: String,
        val defaultLocale: String,
        val variant: Variant? = null,
    )

    @Serializable
    internal data class Variant(
        val id: String,
        val key: String,
        val content: JsonElement,
        val asset: Asset? = null,
    )

    @Serializable
    internal data class Asset(val url: String)
}

@Serializable
internal data class TestSessionLeaveRequest(
    val schemaVersion: Int = 1,
    val participantId: String,
    val sessionToken: String,
)

@Serializable
internal data class TestSessionLeaveResponse(val accepted: Boolean)

@Serializable
internal data class PersistedTestSession(
    val sourceKey: String,
    val sessionId: String,
    val participantId: String,
    val sessionToken: String,
    val expiresAt: String,
    val compatible: Boolean,
    val requiredSdkVersion: String,
    val sdkFamily: String,
    val checks: List<TestSessionHandshakeResponse.Check>,
    val testPlan: TestSessionPlan,
    val testExperienceDecisionReady: Boolean = false,
    val pendingSignals: List<TestSessionSignal> = emptyList(),
)
