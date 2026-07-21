package co.wetus.sdk.internal

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.Button
import co.wetus.sdk.WtsExperience
import co.wetus.sdk.WtsExperienceAction
import co.wetus.sdk.WtsExperienceActionType
import co.wetus.sdk.WtsExperienceContent
import co.wetus.sdk.WtsExperienceLocalizedContent
import co.wetus.sdk.WtsExperiencePlacement
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

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
            onAction = { _, completion -> completion(true) },
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
            onAction = { _, completion -> completion(true) },
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

    @Test
    fun unhandledActionKeepsExperienceOpen() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val handle = ExperienceRenderer.present(
            activity = activity,
            experience = experience(withAction = true),
            onImpression = {},
            onAction = { _, completion -> completion(false) },
            onDismiss = {},
            onShown = {},
            onPresentationSkipped = {},
            canShow = { true },
        )
        assertNotNull(handle)
        shadowOf(Looper.getMainLooper()).idle()
        val buttons = arrayListOf<View>()
        ShadowDialog.getLatestDialog().window?.decorView?.findViewsWithText(
            buttons,
            "Continue",
            View.FIND_VIEWS_WITH_TEXT,
        )
        buttons.filterIsInstance<Button>().single().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(handle.isActive())
    }

    private fun experience(
        delaySeconds: Double = 0.0,
        autoCloseSeconds: Double? = null,
        withAction: Boolean = false,
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
                    primaryAction = if (withAction) {
                        WtsExperienceAction(
                            id = "continue",
                            label = "Continue",
                            type = WtsExperienceActionType.CUSTOM_CALLBACK,
                            target = "continue",
                        )
                    } else {
                        null
                    },
                ),
            ),
            closeable = true,
            themePreset = "light",
            delaySeconds = delaySeconds,
            autoCloseSeconds = autoCloseSeconds,
        ),
    )
}
