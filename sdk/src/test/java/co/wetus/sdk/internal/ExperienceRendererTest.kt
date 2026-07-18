package co.wetus.sdk.internal

import android.app.Activity
import android.os.Looper
import co.wetus.sdk.WtsExperience
import co.wetus.sdk.WtsExperienceContent
import co.wetus.sdk.WtsExperienceLocalizedContent
import co.wetus.sdk.WtsExperiencePlacement
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ExperienceRendererTest {
    @Test
    fun automaticCloseReportsAutoClosedInsteadOfDismissed() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val dismissals = mutableListOf<ExperienceRenderDismissReason>()

        val handle = ExperienceRenderer.present(
            activity = activity,
            experience = experience(autoCloseSeconds = 0.01),
            onImpression = {},
            onAction = {},
            onDismiss = dismissals::add,
            onShown = {},
            onPresentationSkipped = {},
            canShow = { true },
        )

        assertNotNull(handle)
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)

        assertEquals(listOf(ExperienceRenderDismissReason.AUTO_CLOSED), dismissals)
    }

    @Test
    fun delayedPresentationThatFailsAdmissionDoesNotShowOrDismiss() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var shown = 0
        var skipped = 0
        var dismissed = 0

        val handle = ExperienceRenderer.present(
            activity = activity,
            experience = experience(delaySeconds = 1.0),
            onImpression = {},
            onAction = {},
            onDismiss = { dismissed += 1 },
            onShown = { shown += 1 },
            onPresentationSkipped = { skipped += 1 },
            canShow = { false },
        )

        assertNotNull(handle)
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)

        assertFalse(handle.isActive())
        assertEquals(0, shown)
        assertEquals(1, skipped)
        assertEquals(0, dismissed)
    }

    private fun experience(
        delaySeconds: Double = 0.0,
        autoCloseSeconds: Double? = null,
    ) = WtsExperience(
        campaignId = "campaign_checkout",
        campaignVersionId = "campaign_version_1",
        assignmentId = "assignment_checkout",
        variantId = "variant_primary",
        placement = WtsExperiencePlacement.MODAL,
        priority = 100,
        content = WtsExperienceContent(
            translations = mapOf(
                "en" to WtsExperienceLocalizedContent(
                    title = "Complete checkout",
                    description = "Continue securely.",
                ),
            ),
            closeable = true,
            themePreset = "light",
            delaySeconds = delaySeconds,
            autoCloseSeconds = autoCloseSeconds,
        ),
    )
}
