package co.wetus.sdk

enum class WtsExperienceConsent { PENDING, CONTEXTUAL, PERSONALIZED, DENIED }
enum class WtsExperienceRenderMode { AUTOMATIC, MANUAL }

data class WtsExperienceOptions(
    val enabled: Boolean = false,
    val renderMode: WtsExperienceRenderMode = WtsExperienceRenderMode.AUTOMATIC,
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
    val exposureId: String,
    val placement: WtsExperiencePlacement,
    val priority: Int,
    val content: WtsExperienceContent,
    val assetUrl: String? = null,
)

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
}
