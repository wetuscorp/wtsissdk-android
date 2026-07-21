package co.wetus.sdk.internal

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import co.wetus.sdk.WtsExperience
import co.wetus.sdk.WtsExperienceAction
import co.wetus.sdk.WtsExperiencePlacement
import java.util.Locale
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** The terminal reason is kept separate from the public SDK model. */
internal enum class ExperienceRenderDismissReason { DISMISSED, AUTO_CLOSED }

internal class ExperienceActivityTracker(application: Application) :
    Application.ActivityLifecycleCallbacks {
    private val current = AtomicReference<Activity?>()

    init {
        application.registerActivityLifecycleCallbacks(this)
    }

    fun resumedActivity(): Activity? = current.get()?.takeUnless { it.isFinishing || it.isDestroyed }
    override fun onActivityResumed(activity: Activity) { current.set(activity) }
    override fun onActivityPaused(activity: Activity) { current.compareAndSet(activity, null) }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) { current.compareAndSet(activity, null) }
}

internal class ExperienceRenderHandle(
    private val dialog: Dialog,
    private val handler: Handler,
    private val onDismiss: (ExperienceRenderDismissReason) -> Unit,
) {
    private val active = AtomicBoolean(true)
    private val completed = AtomicBoolean(false)
    private val dismissReason = AtomicReference(ExperienceRenderDismissReason.DISMISSED)
    private var showAction: Runnable? = null

    internal fun bindShowAction(action: Runnable) {
        showAction = action
    }

    internal fun isActive(): Boolean = active.get()

    internal fun didDismiss() {
        finish(notify = true)
    }

    fun dismiss(
        reason: ExperienceRenderDismissReason = ExperienceRenderDismissReason.DISMISSED,
        notify: Boolean = true,
    ) {
        dismissReason.set(reason)
        active.set(false)
        showAction?.let(handler::removeCallbacks)
        if (dialog.isShowing) {
            if (!notify) completed.set(true)
            dialog.dismiss()
        } else {
            finish(notify)
        }
    }

    private fun finish(notify: Boolean) {
        active.set(false)
        if (completed.compareAndSet(false, true) && notify) onDismiss(dismissReason.get())
    }
}

internal object ExperienceRenderer {
    fun present(
        activity: Activity,
        experience: WtsExperience,
        onImpression: () -> Unit,
        onAction: (WtsExperienceAction, (Boolean) -> Unit) -> Unit,
        onDismiss: (ExperienceRenderDismissReason) -> Unit,
        onShown: () -> Unit,
        onPresentationSkipped: () -> Unit,
        canShow: () -> Boolean,
    ): ExperienceRenderHandle? {
        if (activity.isFinishing || activity.isDestroyed) return null
        val content = localizedContent(experience)
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 24), dp(activity, 28), dp(activity, 24), dp(activity, 24))
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 20).toFloat()
                setColor(if (experience.content.themePreset == "dark") Color.rgb(7, 17, 32) else Color.WHITE)
            }
        }
        val foreground =
            if (experience.content.themePreset == "dark") Color.WHITE else Color.rgb(11, 18, 32)
        experience.assetUrl?.let { assetUrl ->
            val imageView = ImageView(activity).apply {
                visibility = View.GONE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(activity, 180),
                ).apply { bottomMargin = dp(activity, 14) }
            }
            card.addView(imageView)
            loadAsset(activity, imageView, assetUrl) { !activity.isFinishing && !activity.isDestroyed }
        }
        card.addView(TextView(activity).apply {
            text = content.title
            textSize = 22f
            setTextColor(foreground)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        })
        card.addView(TextView(activity).apply {
            text = content.description
            textSize = 15f
            setTextColor(foreground)
            setPadding(0, dp(activity, 12), 0, dp(activity, 12))
        })
        listOfNotNull(content.primaryAction, content.secondaryAction).forEach { action ->
            card.addView(Button(activity).apply {
                text = action.label
                isAllCaps = false
                setOnClickListener {
                    isEnabled = false
                    onAction(action) { handled ->
                        post {
                            isEnabled = true
                            if (handled) dialog.dismiss()
                        }
                    }
                }
            })
        }
        dialog.setContentView(card)
        dialog.setCancelable(experience.content.closeable)
        val handler = Handler(Looper.getMainLooper())
        val handle = ExperienceRenderHandle(dialog, handler, onDismiss)
        dialog.setOnDismissListener { handle.didDismiss() }
        dialog.setOnShowListener {
            onShown()
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                val width = if (experience.placement == WtsExperiencePlacement.BOTTOM_SHEET) {
                    WindowManager.LayoutParams.MATCH_PARENT
                } else {
                    minOf(activity.resources.displayMetrics.widthPixels - dp(activity, 32), dp(activity, 520))
                }
                setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
                setGravity(
                    if (experience.placement == WtsExperiencePlacement.BOTTOM_SHEET) {
                        Gravity.BOTTOM
                    } else {
                        Gravity.CENTER
                    },
                )
                if (experience.placement == WtsExperiencePlacement.MODAL) {
                    addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    attributes = attributes.apply { dimAmount = 0.5f }
                }
            }
            card.postDelayed({
                if (dialog.isShowing && isAtLeastHalfVisible(card)) onImpression()
            }, 1_000)
        }
        val showAction = Runnable {
                if (!handle.isActive()) return@Runnable
                if (activity.isFinishing || activity.isDestroyed || !canShow()) {
                    handle.dismiss(notify = false)
                    onPresentationSkipped()
                    return@Runnable
                }
                dialog.show()
                experience.content.autoCloseSeconds?.let { seconds ->
                    handler.postDelayed(
                        {
                            if (handle.isActive()) {
                                handle.dismiss(ExperienceRenderDismissReason.AUTO_CLOSED)
                            }
                        },
                        (seconds * 1_000).toLong(),
                    )
                }
            }
        handle.bindShowAction(showAction)
        handler.postDelayed(showAction, (experience.content.delaySeconds * 1_000).toLong())
        return handle
    }

    private fun localizedContent(experience: WtsExperience) =
        experience.content.translations[Locale.getDefault().toLanguageTag()]
            ?: experience.content.translations[Locale.getDefault().language]
            ?: requireNotNull(experience.content.translations.values.firstOrNull())

    private fun dp(activity: Activity, value: Int) =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun isAtLeastHalfVisible(view: View): Boolean {
        if (!view.isShown || view.alpha <= 0f || view.width <= 0 || view.height <= 0) return false
        val visible = Rect()
        if (!view.getGlobalVisibleRect(visible)) return false
        val totalArea = view.width.toLong() * view.height.toLong()
        val visibleArea = visible.width().toLong() * visible.height().toLong()
        return totalArea > 0 && visibleArea * 2 >= totalArea
    }

    private fun loadAsset(
        activity: Activity,
        imageView: ImageView,
        value: String,
        isActive: () -> Boolean,
    ) {
        thread(name = "wts-experience-image", isDaemon = true) {
            val bitmap = runCatching {
                val url = URL(value)
                require(url.protocol.equals("https", ignoreCase = true))
                val connection = url.openConnection().apply {
                    connectTimeout = 2_000
                    readTimeout = 2_000
                    useCaches = true
                }
                connection.getInputStream().use(BitmapFactory::decodeStream)
            }.getOrNull() ?: return@thread
            activity.runOnUiThread {
                if (isActive()) {
                    imageView.setImageBitmap(bitmap)
                    imageView.visibility = View.VISIBLE
                } else {
                    bitmap.recycle()
                }
            }
        }
    }
}
