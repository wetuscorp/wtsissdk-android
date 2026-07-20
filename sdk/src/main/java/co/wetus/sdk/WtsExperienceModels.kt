package co.wetus.sdk

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

enum class WtsExperienceDismissReason { DISMISSED, AUTO_CLOSED, RENDER_FAILED }

data class WtsExperienceDiagnostics(
    val enabled: Boolean,
    val consent: WtsConsentState,
    val decisionMode: String?,
    val queued: Int,
    val presenting: Boolean,
    val testDeviceToken: String,
    val lastErrorCode: String?,
)
