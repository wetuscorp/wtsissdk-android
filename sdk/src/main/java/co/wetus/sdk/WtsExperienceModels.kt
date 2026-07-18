package co.wetus.sdk

enum class WtsExperienceConsent { PENDING, CONTEXTUAL, PERSONALIZED, DENIED }
enum class WtsExperienceRenderMode { AUTOMATIC, MANUAL }

data class WtsExperienceOptions(
    val enabled: Boolean = false,
    val renderMode: WtsExperienceRenderMode = WtsExperienceRenderMode.AUTOMATIC,
    /**
     * Trusted Ed25519 public keys, indexed by the manifest key id (`kid`).
     *
     * Each value must be a base64-encoded SPKI DER public key. Experiences
     * fail closed when this map does not contain the `kid` returned by the
     * collector or when signature verification fails.
     */
    val manifestVerificationKeys: Map<String, String> = emptyMap(),
    val allowedInternalRoutes: Set<String> = emptySet(),
    val allowedCallbackKeys: Set<String> = emptySet(),
    val allowedDeepLinkHosts: Set<String> = emptySet(),
    val allowedDeepLinkSchemes: Set<String> = emptySet(),
    val allowedWebOrigins: Set<String> = emptySet(),
)

enum class WtsExperiencePlacement { MODAL, BOTTOM_SHEET }
enum class WtsExperienceActionType {
    DISMISS,
    OPEN_INTERNAL_ROUTE,
    OPEN_DEEP_LINK,
    OPEN_WEB_URL,
    COPY_CODE,
    CUSTOM_CALLBACK,
}

data class WtsExperienceAction(
    val id: String,
    val label: String,
    val type: WtsExperienceActionType,
    val target: String? = null,
)

data class WtsExperienceLocalizedContent(
    val title: String,
    val description: String,
    val primaryAction: WtsExperienceAction? = null,
    val secondaryAction: WtsExperienceAction? = null,
)

data class WtsExperienceContent(
    val translations: Map<String, WtsExperienceLocalizedContent>,
    val closeable: Boolean,
    val themePreset: String,
    val delaySeconds: Double,
    val autoCloseSeconds: Double?,
)

data class WtsExperience(
    val campaignId: String,
    val campaignVersionId: String,
    val assignmentId: String,
    val variantId: String,
    val placement: WtsExperiencePlacement,
    val priority: Int,
    val content: WtsExperienceContent,
    val assetUrl: String? = null,
)

/**
 * An SDK-issued identifier for one manual Experience presentation.
 *
 * It deliberately contains no interaction grant. Lifecycle calls validate the
 * identifier against the SDK's current in-memory presentation state.
 */
class WtsExperiencePresentationHandle private constructor(
    val exposureId: String,
) {
    companion object {
        /**
         * Recreates a handle after crossing a Flutter or React Native bridge.
         * A recreated or forged value cannot authorize an interaction: the SDK
         * still requires it to match a current manual presentation.
         */
        @JvmStatic
        fun fromExposureId(exposureId: String): WtsExperiencePresentationHandle {
            require(exposureId.isNotBlank()) { "Experience exposure id must not be blank." }
            return WtsExperiencePresentationHandle(exposureId)
        }

        internal fun issued(exposureId: String) = WtsExperiencePresentationHandle(exposureId)
    }

    override fun equals(other: Any?): Boolean =
        other is WtsExperiencePresentationHandle && exposureId == other.exposureId

    override fun hashCode(): Int = exposureId.hashCode()

    override fun toString(): String = "WtsExperiencePresentationHandle(opaque)"
}

data class WtsExperienceManualPresentation(
    val experience: WtsExperience,
    val handle: WtsExperiencePresentationHandle,
)

/** Result returned by manual Experience lifecycle operations. */
data class WtsExperienceLifecycleOutcome(
    val accepted: Boolean,
    val idempotent: Boolean = false,
    val code: String? = null,
) {
    companion object {
        internal fun accepted(idempotent: Boolean = false) =
            WtsExperienceLifecycleOutcome(accepted = true, idempotent = idempotent)

        internal fun rejected(code: String) =
            WtsExperienceLifecycleOutcome(accepted = false, code = code)
    }
}

enum class WtsExperienceDismissReason { DISMISSED, AUTO_CLOSED, RENDER_FAILED }

data class WtsExperienceDiagnostics(
    val enabled: Boolean,
    val consent: WtsExperienceConsent,
    val queued: Int,
    val presenting: Boolean,
    val testDeviceToken: String,
    val lastErrorCode: String?,
)

enum class WtsExperienceResult {
    ACCEPTED,
    FEATURE_DISABLED,
    MANIFEST_VERIFICATION_FAILED,
}
