package co.wetus.sdk

import android.net.Uri
import kotlinx.serialization.json.JsonElement

/**
 * An explicit, short-lived pairing credential issued by the wts.is dashboard.
 * Pairing credentials are never written to the normal SDK event queue.
 */
data class WtsTestSessionPairing(
    val pairingToken: String? = null,
    val pairingCode: String? = null,
) {
    init {
        require((pairingToken != null) xor (pairingCode != null)) {
            "Provide exactly one pairingToken or pairingCode."
        }
    }

    companion object {
        fun from(value: String): WtsTestSessionPairing {
            val trimmed = value.trim()
            require(trimmed.isNotEmpty()) { "A pairing token or code is required." }
            val token = runCatching {
                val uri = Uri.parse(trimmed)
                val canonicalPath = uri.path == "/_wts/test/pair"
                val legacyPath = uri.host in setOf("wts.is", "www.wts.is") &&
                    uri.path == "/sdk-test/pair"
                if (uri.scheme == "https" && (canonicalPath || legacyPath)) {
                    uri.getQueryParameter("pairing")?.trim()
                } else {
                    null
                }
            }.getOrNull()
            if (!token.isNullOrEmpty()) return WtsTestSessionPairing(pairingToken = token)
            return if (trimmed.uppercase().matches(Regex("^[A-Z2-9]{16}$"))) {
                WtsTestSessionPairing(pairingCode = trimmed.uppercase())
            } else {
                WtsTestSessionPairing(pairingToken = trimmed)
            }
        }
    }
}

enum class WtsSdkFamily(val wireValue: String) {
    ANDROID("android"),
    FLUTTER("flutter"),
    REACT_NATIVE("react_native"),
}

enum class WtsTestSessionExperienceInteraction(val wireSignalType: String) {
    IMPRESSION("experience_impression"),
    ACTION("experience_action"),
}

data class WtsTestSessionCheck(
    val key: String,
    val status: String,
    val code: String? = null,
    val message: String? = null,
)

data class WtsTestSessionJoinResult(
    val accepted: Boolean,
    val joined: Boolean,
    val compatible: Boolean,
    val requiredSdkVersion: String? = null,
    val checks: List<WtsTestSessionCheck> = emptyList(),
    val sessionId: String? = null,
    val expiresAt: String? = null,
    /** Available only to the explicit caller; never included in test observations or logs. */
    val testProfileExternalUserId: String? = null,
    val errorCode: String? = null,
)

data class WtsTestSessionDiagnostics(
    val joined: Boolean,
    val compatible: Boolean,
    val sessionId: String? = null,
    val expiresAt: String? = null,
    val requiredSdkVersion: String? = null,
    val checks: List<WtsTestSessionCheck> = emptyList(),
    val pendingSignals: Int = 0,
    val lastErrorCode: String? = null,
)

data class WtsTestSessionProbeResult(
    val match: Boolean,
    val status: String,
    val code: String,
    val originalUrl: String,
    val fallbackUrl: String,
    val link: WtsTestSessionProbeLink? = null,
)

data class WtsTestSessionProbeLink(
    val id: String,
    val path: String,
    val parameters: Map<String, WtsValue>,
)

data class WtsTestSessionProbeRunResult(
    val accepted: Boolean,
    val emitted: List<String>,
    val skipped: List<String>,
    val pendingSignals: Int,
    /**
     * A test-only manual Experience decision. The SDK never adds this decision
     * to the normal Experience runtime or renders it automatically.
     */
    val experienceDecision: WtsTestSessionExperienceDecision? = null,
)

data class WtsTestSessionExperienceDecision(
    val outcome: String,
    val reason: String? = null,
    val testGrant: WtsTestSessionExperienceGrant? = null,
    val decision: WtsTestSessionExperienceCampaign? = null,
)

data class WtsTestSessionExperienceGrant(
    val fixtureId: String,
    val expiresAt: String,
)

data class WtsTestSessionExperienceCampaign(
    val campaignId: String,
    val campaignVersionId: String,
    val placement: String,
    val defaultLocale: String,
    val variant: WtsTestSessionExperienceVariant? = null,
)

data class WtsTestSessionExperienceVariant(
    val id: String,
    val key: String,
    val content: JsonElement,
    val assetUrl: String? = null,
)
