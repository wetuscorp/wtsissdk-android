package co.wetus.sdk

import android.content.Context
import android.net.Uri
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import co.wetus.sdk.internal.DeferredRequest
import co.wetus.sdk.internal.EventBatch
import co.wetus.sdk.internal.EventBatchResponse
import co.wetus.sdk.internal.EventRequest
import co.wetus.sdk.internal.EventStore
import co.wetus.sdk.internal.ExperienceActivityTracker
import co.wetus.sdk.internal.ExperienceBootstrapRequest
import co.wetus.sdk.internal.ExperienceBootstrapResponse
import co.wetus.sdk.internal.ExperienceContextWire
import co.wetus.sdk.internal.ExperienceDecisionRequest
import co.wetus.sdk.internal.ExperienceDecisionResponse
import co.wetus.sdk.internal.ExperienceInteractionBatchRequest
import co.wetus.sdk.internal.ExperienceInteractionBatchResponse
import co.wetus.sdk.internal.ExperienceInteractionRequest
import co.wetus.sdk.internal.ExperienceInteractionStore
import co.wetus.sdk.internal.ExperienceManifestVerifier
import co.wetus.sdk.internal.ExperienceRenderer
import co.wetus.sdk.internal.ExperienceRenderHandle
import co.wetus.sdk.internal.ExperienceSettingsWire
import co.wetus.sdk.internal.ExperienceTriggerWire
import co.wetus.sdk.internal.wireValue
import co.wetus.sdk.internal.IdentityContext
import co.wetus.sdk.internal.IdentityMutationBatch
import co.wetus.sdk.internal.IdentityMutationBatchResponse
import co.wetus.sdk.internal.IdentityMutationRequest
import co.wetus.sdk.internal.IdentityMutationStore
import co.wetus.sdk.internal.Metadata
import co.wetus.sdk.internal.PlayReferrerSource
import co.wetus.sdk.internal.PreferencesEventStore
import co.wetus.sdk.internal.PreferencesIdentityMutationStore
import co.wetus.sdk.internal.PreferencesExperienceInteractionStore
import co.wetus.sdk.internal.ReferrerSource
import co.wetus.sdk.internal.ResolveCache
import co.wetus.sdk.internal.ResolveRequest
import co.wetus.sdk.internal.ResolveResponse
import co.wetus.sdk.internal.RevenueWire
import co.wetus.sdk.internal.ReportedAttributionWire
import co.wetus.sdk.internal.PersistedTestSession
import co.wetus.sdk.internal.PreferencesTestSessionStore
import co.wetus.sdk.internal.TestSessionCapabilities
import co.wetus.sdk.internal.TestSessionConsent
import co.wetus.sdk.internal.TestSessionHandshakeRequest
import co.wetus.sdk.internal.TestSessionHandshakeResponse
import co.wetus.sdk.internal.TestSessionExperienceDecisionRequest
import co.wetus.sdk.internal.TestSessionExperienceDecisionResponse
import co.wetus.sdk.internal.TestSessionLeaveRequest
import co.wetus.sdk.internal.TestSessionLeaveResponse
import co.wetus.sdk.internal.TestSessionPairRequest
import co.wetus.sdk.internal.TestSessionPairResponse
import co.wetus.sdk.internal.TestSessionResolveRequest
import co.wetus.sdk.internal.TestSessionResolveResponse
import co.wetus.sdk.internal.TestSessionSignal
import co.wetus.sdk.internal.TestSessionSignalBatch
import co.wetus.sdk.internal.TestSessionSignalBatchResponse
import co.wetus.sdk.internal.TestSessionStore
import co.wetus.sdk.internal.UserUpdateWire
import java.io.IOException
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class WtsSdk internal constructor(
    context: Context,
    private val appKey: String,
    private val options: WtsOptions,
    private val client: OkHttpClient,
    private val store: EventStore,
    private val identityStore: IdentityMutationStore,
    private val testSessionStore: TestSessionStore,
    private val referrerSource: ReferrerSource,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val experienceInteractionStore: ExperienceInteractionStore =
        PreferencesExperienceInteractionStore(preferences, json)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ResolveCache(100)
    private val installId = preferences.getString(INSTALL_ID, null)
        ?: UUID.randomUUID().toString().lowercase().also {
            check(preferences.edit().putString(INSTALL_ID, it).commit())
        }
    private var retryAttempt = 0
    private var retryJob: Job? = null
    private var profileConsentGranted = false
    private var identityBound =
        preferences.getString(IDENTITY_BOUND_SOURCE_KEY, null) == appKey
    private var identitySessionId = UUID.randomUUID().toString().lowercase()
    private val experienceActivityTracker =
        ExperienceActivityTracker(appContext as android.app.Application)
    private var experienceConsent = WtsExperienceConsent.PENDING
    private var experienceManifest: ExperienceBootstrapResponse.Manifest? = null
    private var experienceCandidates = emptyList<String>()
    private var experienceManifestExpiresAt = 0L
    private var experienceManifestRefreshAt = 0L
    private val experienceQueue = mutableListOf<RuntimeExperience>()
    private val experienceGrants = mutableMapOf<String, String>()
    private val manualExperienceStates = mutableMapOf<String, ManualExperienceState>()
    private var offeredManualExperienceId: String? = null
    private var experienceAvailableHandler: ((WtsExperienceManualPresentation) -> Unit)? = null
    private var experienceActionHandler: ((WtsExperience, WtsExperienceAction) -> Boolean)? = null
    private var presentingExperience: RuntimeExperience? = null
    private var experienceDialog: ExperienceRenderHandle? = null
    private var experienceSessionImpressions = 0
    private val experienceTestDeviceToken = UUID.randomUUID().toString().lowercase()
    private var experienceLastErrorCode: String? = null
    private var testSession = testSessionStore.load()
    private var testSessionLastErrorCode: String? = null
    private var testSessionRetryJob: Job? = null
    private var testSessionRetryAttempt = 0

    /** Keeps delivery-only identifiers out of the public Experience model. */
    private data class RuntimeExperience(
        val experience: WtsExperience,
        val exposureId: String,
    )

    private data class ManualExperienceState(
        val runtime: RuntimeExperience,
        var renderAcknowledged: Boolean = false,
        var impressionAcknowledged: Boolean = false,
        val actionIds: MutableSet<String> = mutableSetOf(),
        var terminalReason: WtsExperienceDismissReason? = null,
    )

    init {
        if (testSession?.let(::isTestSessionExpired) == true) {
            testSession = null
            testSessionStore.clear()
        }
    }

    suspend fun handle(uri: Uri): WtsDeepLink {
        val source = unwrap(uri)
        val key = source.toString()
        cache.get(key, System.currentTimeMillis())?.let {
            recordTestSessionSignal(
                type = "deep_link_resolved",
                outcome = "observed",
                method = "handle",
                resultCode = "RESOLVED",
                feature = "deeplink",
            )
            return it
        }
        val request = ResolveRequest(
            installId = installId,
            metadata = Metadata.current(appContext),
            url = key,
        )
        val response: ResolveResponse = post(
            "sdk/v3/resolve",
            json.encodeToString(ResolveRequest.serializer(), request),
            ResolveResponse.serializer(),
            source,
        )
        if (!response.match || !response.link.path.startsWith('/')) {
            throw WtsSdkException.InvalidResponse(source)
        }
        return response.toPublic().also {
            cache.put(key, it, System.currentTimeMillis() + options.cacheTtlMillis)
            recordTestSessionSignal(
                type = "deep_link_resolved",
                outcome = "observed",
                method = "handle",
                resultCode = "RESOLVED",
                feature = "deeplink",
            )
        }
    }

    suspend fun getDeferredDeepLink(): WtsDeepLink? {
        if (preferences.getBoolean(DEFERRED_TERMINAL, false)) return null
        val referrer = referrerSource.read() ?: return null
        val request = DeferredRequest(
            installId = installId,
            metadata = Metadata.current(appContext),
            referrer = referrer,
        )
        return try {
            val response: ResolveResponse = post(
                "sdk/v3/deferred-resolve",
                json.encodeToString(DeferredRequest.serializer(), request),
                ResolveResponse.serializer(),
                null,
            )
            preferences.edit().putBoolean(DEFERRED_TERMINAL, true).commit()
            response.toPublic().copy(isDeferred = true)
        } catch (_: WtsSdkException.NoMatch) {
            preferences.edit().putBoolean(DEFERRED_TERMINAL, true).commit()
            null
        }
    }

    suspend fun track(
        eventKey: String,
        properties: Map<String, WtsValue> = emptyMap(),
        revenue: WtsRevenue? = null,
        linkId: String? = null,
    ) {
        validate(eventKey, properties)
        val queue = store.load().toMutableList().apply {
            add(EventRequest(
                installId = installId,
                sessionId = identitySessionId,
                metadata = Metadata.current(appContext),
                type = "custom",
                eventKey = eventKey,
                properties = properties.toJson(),
                revenue = revenue?.let(RevenueWire::from),
                linkId = linkId,
            ))
            while (size > 100 || encodedSize(this) > 1_048_576) removeAt(0)
        }
        if (!store.save(queue)) throw WtsSdkException.Storage
        recordTestSessionSignal(
            type = "event_recorded",
            outcome = "observed",
            eventKey = eventKey,
            propertyKeys = properties.keys.sorted(),
            propertyTypes = properties.mapValues { (_, value) -> value.testSessionType() },
            revenue = revenue?.let {
                co.wetus.sdk.internal.TestSessionRevenueDescriptor(
                    present = true,
                    currency = it.currency.uppercase(),
                )
            },
            feature = "events",
        )
        scheduleFlush(0)
        evaluateExperiences(
            ExperienceContextWire(
                trigger = ExperienceTriggerWire(
                    type = "custom_event",
                    eventKey = eventKey,
                ),
                eventKey = eventKey,
                properties = properties.toJson(),
                triggerEventId = queue.lastOrNull()?.clientEventId,
            ),
        )
    }

    suspend fun screen(
        name: String,
        properties: Map<String, WtsValue> = emptyMap(),
    ) {
        val normalized = name.trim()
        if (normalized.isEmpty() || normalized.length > 120) {
            throw WtsSdkException.InvalidEvent(
                "Screen name must contain 1 to 120 characters.",
            )
        }
        validateProperties(properties)
        val queue = store.load().toMutableList().apply {
            add(
                EventRequest(
                    installId = installId,
                    sessionId = identitySessionId,
                    metadata = Metadata.current(appContext),
                    type = "screen_view",
                    screenName = normalized,
                    properties = properties.toJson(),
                ),
            )
            while (size > 100 || encodedSize(this) > 1_048_576) removeAt(0)
        }
        if (!store.save(queue)) throw WtsSdkException.Storage
        recordTestSessionSignal(
            type = "screen_recorded",
            outcome = "observed",
            screenName = normalized,
            propertyKeys = properties.keys.sorted(),
            propertyTypes = properties.mapValues { (_, value) -> value.testSessionType() },
            feature = "screen",
        )
        scheduleFlush(0)
        evaluateExperiences(
            ExperienceContextWire(
                trigger = ExperienceTriggerWire(
                    type = "screen_view",
                    screenName = normalized,
                ),
                screenName = normalized,
                properties = properties.toJson(),
                triggerEventId = queue.lastOrNull()?.clientEventId,
            ),
        )
    }

    suspend fun setExperienceConsent(consent: WtsExperienceConsent): WtsExperienceResult {
        if (!options.experiences.enabled) return WtsExperienceResult.FEATURE_DISABLED
        if (consent == WtsExperienceConsent.PERSONALIZED && !profileConsentGranted) {
            throw WtsSdkException.ExperienceProfileConsentRequired
        }
        experienceConsent = consent
        recordTestSessionSignal(
            type = "consent",
            outcome = "observed",
            feature = "experiences",
        )
        if (consent == WtsExperienceConsent.PENDING || consent == WtsExperienceConsent.DENIED) {
            clearExperienceRuntime(clearInteractionQueue = true)
            return WtsExperienceResult.ACCEPTED
        }
        if (options.experiences.manifestVerificationKeys.isEmpty()) {
            clearExperienceRuntime(clearInteractionQueue = false)
            experienceLastErrorCode = EXPERIENCE_MANIFEST_VERIFICATION_FAILED
            return WtsExperienceResult.MANIFEST_VERIFICATION_FAILED
        }
        try {
            refreshExperienceManifest()
        } catch (_: WtsSdkException.InvalidResponse) {
            clearExperienceRuntime(clearInteractionQueue = false)
            experienceLastErrorCode = EXPERIENCE_MANIFEST_VERIFICATION_FAILED
            return WtsExperienceResult.MANIFEST_VERIFICATION_FAILED
        }
        runCatching { flushExperienceInteractions() }
            .onFailure { scheduleRetry() }
        return WtsExperienceResult.ACCEPTED
    }

    /**
     * Receives the next queued Experience only when manual rendering is active.
     * The host must acknowledge its lifecycle with the supplied opaque handle.
     */
    fun onExperienceAvailable(handler: ((WtsExperienceManualPresentation) -> Unit)?) {
        experienceAvailableHandler = handler
        notifyNextManualExperienceIfAvailable()
    }

    fun onExperienceAction(handler: ((WtsExperience, WtsExperienceAction) -> Boolean)?) {
        experienceActionHandler = handler
    }

    fun presentNextExperience(): Boolean {
        if (options.experiences.renderMode == WtsExperienceRenderMode.MANUAL) return false
        if (presentingExperience != null || experienceQueue.isEmpty() ||
            experienceSessionImpressions >= 2
        ) {
            return false
        }
        val activity = experienceActivityTracker.resumedActivity() ?: return false
        val runtime = experienceQueue.removeAt(0)
        presentingExperience = runtime
        scope.launch { recordExperience(runtime, "render_started") }
        val dialog = ExperienceRenderer.present(
            activity = activity,
            experience = runtime.experience,
            onImpression = {
                scope.launch {
                    if (presentingExperience?.exposureId == runtime.exposureId) {
                        experienceSessionImpressions += 1
                        recordExperience(runtime, "impression")
                    }
                }
            },
            onAction = { action ->
                scope.launch { handleExperienceAction(runtime, action) }
            },
            onDismiss = {
                scope.launch {
                    if (presentingExperience?.exposureId == runtime.exposureId) {
                        presentingExperience = null
                        experienceDialog = null
                        recordExperience(runtime, "dismissed")
                        delay(EXPERIENCE_QUEUE_COOLDOWN_MILLIS)
                        withContext(Dispatchers.Main) { presentNextExperience() }
                    }
                }
            },
        )
        if (dialog == null) {
            presentingExperience = null
            scope.launch {
                recordExperience(runtime, "render_failed", failureCode = "PRESENTER_UNAVAILABLE")
            }
            return false
        }
        experienceDialog = dialog
        scope.launch { recordExperience(runtime, "render_succeeded") }
        return true
    }

    fun dismissCurrentExperience(): Boolean {
        if (options.experiences.renderMode == WtsExperienceRenderMode.MANUAL) return false
        val dialog = experienceDialog ?: return false
        dialog.dismiss()
        return true
    }

    suspend fun acknowledgeExperienceRender(
        handle: WtsExperiencePresentationHandle,
    ): WtsExperienceLifecycleOutcome {
        if (!isManualExperienceRuntimeEnabled()) {
            return WtsExperienceLifecycleOutcome.rejected(manualRuntimeUnavailableCode())
        }
        val state = manualExperienceStates[handle.exposureId]
            ?: return WtsExperienceLifecycleOutcome.rejected("EXPERIENCE_PRESENTATION_NOT_FOUND")
        if (state.renderAcknowledged) return WtsExperienceLifecycleOutcome.accepted(idempotent = true)
        if (state.terminalReason != null || presentingExperience != null ||
            experienceQueue.firstOrNull()?.exposureId != handle.exposureId
        ) {
            return WtsExperienceLifecycleOutcome.rejected("EXPERIENCE_PRESENTATION_NOT_CURRENT")
        }
        experienceQueue.removeAt(0)
        presentingExperience = state.runtime
        offeredManualExperienceId = null
        state.renderAcknowledged = true
        recordExperience(state.runtime, "render_started")
        recordExperience(state.runtime, "render_succeeded")
        return WtsExperienceLifecycleOutcome.accepted()
    }

    suspend fun acknowledgeExperienceImpression(
        handle: WtsExperiencePresentationHandle,
    ): WtsExperienceLifecycleOutcome {
        if (!isManualExperienceRuntimeEnabled()) {
            return WtsExperienceLifecycleOutcome.rejected(manualRuntimeUnavailableCode())
        }
        val state = manualExperienceStates[handle.exposureId]
            ?: return WtsExperienceLifecycleOutcome.rejected("EXPERIENCE_PRESENTATION_NOT_FOUND")
        if (state.impressionAcknowledged) return WtsExperienceLifecycleOutcome.accepted(idempotent = true)
        if (!state.renderAcknowledged || presentingExperience?.exposureId != handle.exposureId ||
            state.terminalReason != null
        ) {
            return WtsExperienceLifecycleOutcome.rejected("EXPERIENCE_PRESENTATION_NOT_CURRENT")
        }
        state.impressionAcknowledged = true
        experienceSessionImpressions += 1
        recordExperience(state.runtime, "impression")
        return WtsExperienceLifecycleOutcome.accepted()
    }

    suspend fun reportExperienceAction(
        handle: WtsExperiencePresentationHandle,
        actionId: String,
    ): WtsExperienceLifecycleOutcome {
        if (!isManualExperienceRuntimeEnabled()) {
            return WtsExperienceLifecycleOutcome.rejected(manualRuntimeUnavailableCode())
        }
        val state = manualExperienceStates[handle.exposureId]
            ?: return WtsExperienceLifecycleOutcome.rejected("EXPERIENCE_PRESENTATION_NOT_FOUND")
        if (!state.renderAcknowledged || presentingExperience?.exposureId != handle.exposureId ||
            state.terminalReason != null
        ) {
            return WtsExperienceLifecycleOutcome.rejected("EXPERIENCE_PRESENTATION_NOT_CURRENT")
        }
        if (actionId in state.actionIds) return WtsExperienceLifecycleOutcome.accepted(idempotent = true)
        val action = state.runtime.experience.findAction(actionId)
            ?: return WtsExperienceLifecycleOutcome.rejected("EXPERIENCE_ACTION_INVALID")
        if (!isExperienceActionAllowed(action)) {
            experienceLastErrorCode = "EXPERIENCE_ACTION_NOT_ALLOWED"
            return WtsExperienceLifecycleOutcome.rejected("EXPERIENCE_ACTION_NOT_ALLOWED")
        }
        state.actionIds += action.id
        recordExperience(
            state.runtime,
            if (state.runtime.experience.isPrimaryAction(action.id)) {
                "primary_action"
            } else {
                "secondary_action"
            },
            actionId = action.id,
        )
        if (action.type.isNavigationAction()) clearQueuedExperiences()
        return WtsExperienceLifecycleOutcome.accepted()
    }

    suspend fun dismissExperience(
        handle: WtsExperiencePresentationHandle,
        reason: WtsExperienceDismissReason = WtsExperienceDismissReason.DISMISSED,
        failureCode: String? = null,
    ): WtsExperienceLifecycleOutcome {
        if (!isManualExperienceRuntimeEnabled()) {
            return WtsExperienceLifecycleOutcome.rejected(manualRuntimeUnavailableCode())
        }
        val state = manualExperienceStates[handle.exposureId]
            ?: return WtsExperienceLifecycleOutcome.rejected("EXPERIENCE_PRESENTATION_NOT_FOUND")
        if (state.terminalReason == reason) return WtsExperienceLifecycleOutcome.accepted(idempotent = true)
        if (!state.renderAcknowledged || presentingExperience?.exposureId != handle.exposureId ||
            state.terminalReason != null
        ) {
            return WtsExperienceLifecycleOutcome.rejected("EXPERIENCE_PRESENTATION_NOT_CURRENT")
        }
        if (reason == WtsExperienceDismissReason.RENDER_FAILED && failureCode.isNullOrBlank()) {
            return WtsExperienceLifecycleOutcome.rejected("EXPERIENCE_FAILURE_CODE_REQUIRED")
        }
        state.terminalReason = reason
        presentingExperience = null
        experienceDialog = null
        when (reason) {
            WtsExperienceDismissReason.DISMISSED -> recordExperience(state.runtime, "dismissed")
            WtsExperienceDismissReason.AUTO_CLOSED -> recordExperience(state.runtime, "auto_closed")
            WtsExperienceDismissReason.RENDER_FAILED ->
                recordExperience(state.runtime, "render_failed", failureCode = failureCode)
        }
        scope.launch {
            delay(EXPERIENCE_QUEUE_COOLDOWN_MILLIS)
            notifyNextManualExperienceIfAvailable()
        }
        return WtsExperienceLifecycleOutcome.accepted()
    }

    fun getExperienceDiagnostics() = WtsExperienceDiagnostics(
        enabled = options.experiences.enabled,
        consent = experienceConsent,
        queued = experienceQueue.size,
        presenting = presentingExperience != null,
        testDeviceToken = experienceTestDeviceToken,
        lastErrorCode = experienceLastErrorCode,
    )

    private fun clearExperienceRuntime(clearInteractionQueue: Boolean) {
        experienceManifest = null
        experienceCandidates = emptyList()
        experienceManifestExpiresAt = 0
        experienceManifestRefreshAt = 0
        experienceGrants.clear()
        clearQueuedExperiences()
        manualExperienceStates.clear()
        offeredManualExperienceId = null
        experienceDialog?.dismiss(notify = false)
        experienceDialog = null
        presentingExperience = null
        if (clearInteractionQueue && !experienceInteractionStore.save(emptyList())) {
            throw WtsSdkException.Storage
        }
    }

    private fun clearQueuedExperiences() {
        experienceQueue.forEach { manualExperienceStates.remove(it.exposureId) }
        experienceQueue.clear()
        offeredManualExperienceId = null
    }

    private fun isManualExperienceRuntimeEnabled(): Boolean =
        options.experiences.enabled &&
            options.experiences.renderMode == WtsExperienceRenderMode.MANUAL &&
            experienceConsent in setOf(
                WtsExperienceConsent.CONTEXTUAL,
                WtsExperienceConsent.PERSONALIZED,
            ) &&
            (experienceConsent != WtsExperienceConsent.PERSONALIZED || profileConsentGranted)

    private fun manualRuntimeUnavailableCode(): String = when {
        !options.experiences.enabled -> "EXPERIENCE_FEATURE_DISABLED"
        options.experiences.renderMode != WtsExperienceRenderMode.MANUAL ->
            "EXPERIENCE_MANUAL_MODE_REQUIRED"
        experienceConsent == WtsExperienceConsent.PERSONALIZED && !profileConsentGranted ->
            "EXPERIENCE_PROFILE_CONSENT_REQUIRED"
        else -> "EXPERIENCE_CONSENT_REQUIRED"
    }

    private fun notifyNextManualExperienceIfAvailable() {
        if (!isManualExperienceRuntimeEnabled() || presentingExperience != null) return
        val handler = experienceAvailableHandler ?: return
        val next = experienceQueue.firstOrNull() ?: return
        if (offeredManualExperienceId == next.exposureId) return
        offeredManualExperienceId = next.exposureId
        handler(
            WtsExperienceManualPresentation(
                experience = next.experience,
                handle = WtsExperiencePresentationHandle.issued(next.exposureId),
            ),
        )
    }

    /**
     * Explicitly joins a dashboard-created SDK Test & Validate session. Until this
     * method succeeds, no test-session request or observation is emitted.
     */
    suspend fun joinTestSession(
        pairing: WtsTestSessionPairing,
        sdkFamily: WtsSdkFamily = WtsSdkFamily.ANDROID,
    ): WtsTestSessionJoinResult {
        return try {
            validateTestPairing(pairing)
            val pair: TestSessionPairResponse = postTest(
                path = "pair",
                body = json.encodeToString(
                    TestSessionPairRequest.serializer(),
                    TestSessionPairRequest(
                        pairingToken = pairing.pairingToken,
                        pairingCode = pairing.pairingCode?.uppercase(),
                        metadata = testSessionMetadata(sdkFamily),
                    ),
                ),
                serializer = TestSessionPairResponse.serializer(),
            )
            val handshake: TestSessionHandshakeResponse = postTest(
                path = "handshake",
                body = json.encodeToString(
                    TestSessionHandshakeRequest.serializer(),
                    TestSessionHandshakeRequest(
                        participantId = pair.participant.id,
                        sessionToken = pair.sessionToken,
                        metadata = testSessionMetadata(sdkFamily),
                        capabilities = testSessionCapabilities(),
                        consent = testSessionConsent(),
                    ),
                ),
                serializer = TestSessionHandshakeResponse.serializer(),
            )
            val persisted = PersistedTestSession(
                sourceKey = appKey,
                sessionId = pair.session.id,
                participantId = pair.participant.id,
                sessionToken = pair.sessionToken,
                expiresAt = pair.session.expiresAt,
                compatible = handshake.accepted && handshake.compatible,
                requiredSdkVersion = handshake.requiredSdkVersion,
                sdkFamily = sdkFamily.wireValue,
                checks = handshake.checks,
                testPlan = handshake.testPlan,
            )
            testSession = persisted
            testSessionLastErrorCode = null
            if (!testSessionStore.save(persisted)) throw WtsSdkException.Storage
            if (persisted.compatible) {
                recordTestSessionSignal(
                    type = "sdk_connected",
                    outcome = "passed",
                    feature = "sdk_test_session",
                )
            }
            WtsTestSessionJoinResult(
                accepted = handshake.accepted,
                joined = true,
                compatible = persisted.compatible,
                requiredSdkVersion = handshake.requiredSdkVersion,
                checks = handshake.checks.toPublicChecks(),
                sessionId = pair.session.id,
                expiresAt = pair.session.expiresAt,
                testProfileExternalUserId = pair.testProfile.externalUserId,
            )
        } catch (error: Throwable) {
            clearTestSession()
            testSessionLastErrorCode = testSessionErrorCode(error)
            WtsTestSessionJoinResult(
                accepted = false,
                joined = false,
                compatible = false,
                errorCode = testSessionLastErrorCode,
            )
        }
    }

    suspend fun leaveTestSession(): Boolean {
        val active = activeTestSession() ?: return true
        if (active.compatible) {
            recordTestSessionSignal("sdk_left", "observed", feature = "sdk_test_session")
            flushTestSessionSignals()
        }
        return try {
            val response: TestSessionLeaveResponse = postTest(
                path = "leave",
                body = json.encodeToString(
                    TestSessionLeaveRequest.serializer(),
                    TestSessionLeaveRequest(
                        participantId = active.participantId,
                        sessionToken = active.sessionToken,
                    ),
                ),
                serializer = TestSessionLeaveResponse.serializer(),
            )
            if (response.accepted) clearTestSession()
            response.accepted
        } catch (error: Throwable) {
            testSessionLastErrorCode = testSessionErrorCode(error)
            persistTestSession()
            false
        }
    }

    fun getTestSessionDiagnostics(): WtsTestSessionDiagnostics {
        val active = activeTestSession()
        return WtsTestSessionDiagnostics(
            joined = active != null,
            compatible = active?.compatible == true,
            sessionId = active?.sessionId,
            expiresAt = active?.expiresAt,
            requiredSdkVersion = active?.requiredSdkVersion,
            checks = active?.checks?.toPublicChecks().orEmpty(),
            pendingSignals = active?.pendingSignals?.size ?: 0,
            lastErrorCode = testSessionLastErrorCode,
        )
    }

    suspend fun probeTestSessionUrl(url: String): WtsTestSessionProbeResult {
        require(url.length <= 2_048 && Uri.parse(url).scheme == "https") {
            "Test resolve URLs must be valid HTTPS URLs of at most 2048 characters."
        }
        val active = requireActiveTestSession()
        return try {
            val result: TestSessionResolveResponse = postTest(
                path = "resolve",
                body = json.encodeToString(
                    TestSessionResolveRequest.serializer(),
                    TestSessionResolveRequest(
                        participantId = active.participantId,
                        sessionToken = active.sessionToken,
                        url = url,
                    ),
                ),
                serializer = TestSessionResolveResponse.serializer(),
            )
            recordTestSessionSignal(
                type = "probe_completed",
                outcome = if (result.match) "passed" else "blocked",
                method = "resolve",
                resultCode = result.code,
                feature = "deeplink",
            )
            result.toPublic()
        } catch (error: Throwable) {
            testSessionLastErrorCode = testSessionErrorCode(error)
            recordTestSessionSignal(
                type = "probe_completed",
                outcome = "failed",
                method = "resolve",
                resultCode = testSessionLastErrorCode,
                feature = "deeplink",
            )
            throw error
        }
    }

    /**
     * Emits only test-protocol signals. It never writes synthetic identities,
     * events, screens, or Experience interactions to production collectors.
     */
    suspend fun runTestSessionProbes(): WtsTestSessionProbeRunResult {
        val active = requireActiveTestSession()
        val emitted = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val profile = active.testPlan.profile
        val identityMethods = if (profile?.selected == true && profile.available) {
            profile.allowedMethods
        } else {
            emptyList()
        }
        if (identityMethods.isNotEmpty()) {
            identityMethods.forEach { method ->
                recordTestSessionSignal(
                    type = "identity_recorded",
                    outcome = "passed",
                    method = method,
                    propertyKeys = if (method == "increment") listOf("sdk_test_increment") else null,
                    propertyTypes = if (method == "increment") {
                        mapOf("sdk_test_increment" to "number")
                    } else {
                        null
                    },
                    feature = "identity",
                )
            }
            emitted += "identity"
        } else {
            skipped += "identity"
        }
        val event = active.testPlan.events.firstOrNull()
        if (event != null) {
            recordTestSessionSignal(
                type = "event_recorded",
                outcome = "passed",
                eventKey = event.eventKey,
                propertyKeys = event.properties.map { it.key },
                propertyTypes = event.properties.associate { it.key to it.type },
                revenue = if (event.revenueEnabled) {
                    co.wetus.sdk.internal.TestSessionRevenueDescriptor(
                        present = true,
                        currency = "USD",
                    )
                } else {
                    null
                },
                feature = "events",
            )
            emitted += "event"
        } else {
            skipped += "event"
        }
        if (active.testPlan.screen?.selected == true) {
            recordTestSessionSignal(
                type = "screen_recorded",
                outcome = "passed",
                screenName = "sdk_test_screen",
                feature = "screen",
            )
            emitted += "screen"
        } else {
            skipped += "screen"
        }
        var experienceDecision: WtsTestSessionExperienceDecision? = null
        val experience = active.testPlan.experience
        if (!options.experiences.enabled || experience?.selected != true || !experience.available) {
            skipped += "experiences"
        } else {
            val decision = runCatching {
                val metadata = Metadata.current(appContext)
                val response: TestSessionExperienceDecisionResponse = postTest(
                    path = "experiences/decide",
                    body = json.encodeToString(
                        TestSessionExperienceDecisionRequest.serializer(),
                        TestSessionExperienceDecisionRequest(
                            participantId = active.participantId,
                            sessionToken = active.sessionToken,
                            context = TestSessionExperienceDecisionRequest.Context(
                                type = "screen_view",
                                screenName = "sdk_test_screen",
                                locale = metadata.locale ?: "en",
                            ),
                        ),
                    ),
                    serializer = TestSessionExperienceDecisionResponse.serializer(),
                )
                response
            }.getOrElse { error ->
                testSessionLastErrorCode = testSessionErrorCode(error)
                null
            }
            experienceDecision = decision?.toPublic()
            if (decision?.outcome == "ready") {
                testSession = active.copy(testExperienceDecisionReady = true)
                persistTestSession()
                emitted += "experiences"
            } else {
                skipped += "experiences"
            }
        }
        flushTestSessionSignals()
        return WtsTestSessionProbeRunResult(
            accepted = active.compatible,
            emitted = emitted,
            skipped = skipped,
            pendingSignals = activeTestSession()?.pendingSignals?.size ?: 0,
            experienceDecision = experienceDecision,
        )
    }

    /**
     * Reports a manually rendered isolated test Experience interaction. This
     * is accepted only after [runTestSessionProbes] received a `ready` result
     * from the test decision endpoint; production Experience interactions are
     * never mirrored into the test session.
     */
    suspend fun reportTestSessionExperienceInteraction(
        interaction: WtsTestSessionExperienceInteraction,
    ): Boolean {
        val active = requireActiveTestSession()
        if (!active.testExperienceDecisionReady) return false
        recordTestSessionSignal(
            type = interaction.wireSignalType,
            outcome = "observed",
            feature = "experiences",
        )
        flushTestSessionSignals()
        return true
    }

    fun setProfileConsent(consent: WtsProfileConsent) {
        recordTestSessionSignal(
            type = "consent",
            outcome = "observed",
            feature = "profile",
        )
        if (consent == WtsProfileConsent.GRANTED) {
            profileConsentGranted = true
            return
        }
        profileConsentGranted = false
        setIdentityBound(false)
        if (experienceConsent == WtsExperienceConsent.PERSONALIZED) {
            experienceConsent = WtsExperienceConsent.PENDING
            clearExperienceRuntime(clearInteractionQueue = true)
        }
        if (!identityStore.save(emptyList())) throw WtsSdkException.Storage
        enqueueIdentity(type = "reset_identity")
        identitySessionId = UUID.randomUUID().toString().lowercase()
    }

    suspend fun identify(
        externalUserId: String,
        attributes: Map<String, WtsUserValue> = emptyMap(),
    ) {
        requireProfileConsent()
        if (externalUserId.isEmpty() || externalUserId.length > 128) {
            throw WtsSdkException.InvalidProfile(
                "externalUserId must contain 1 to 128 characters.",
            )
        }
        validateAttributes(attributes)
        enqueueIdentity(
            type = "identify",
            externalUserId = externalUserId,
            attributes = attributes.takeIf { it.isNotEmpty() }?.toUserJson(),
        )
    }

    suspend fun updateUser(update: WtsUserUpdate) {
        requireProfileConsent()
        validateUpdate(update)
        enqueueIdentity(
            type = "update_user",
            operations = UserUpdateWire(
                set = update.set.takeIf { it.isNotEmpty() }?.toUserJson(),
                setOnce = update.setOnce.takeIf { it.isNotEmpty() }?.toUserJson(),
                unset = update.unset.takeIf { it.isNotEmpty() },
                increment = update.increment.takeIf { it.isNotEmpty() },
            ),
        )
    }

    suspend fun setReportedAttribution(attribution: WtsReportedAttribution) {
        requireProfileConsent()
        if (attribution.source.trim().isEmpty() || attribution.source.length > 120) {
            throw WtsSdkException.InvalidProfile(
                "Attribution source must contain 1 to 120 characters.",
            )
        }
        enqueueIdentity(
            type = "reported_attribution",
            attribution = ReportedAttributionWire.from(attribution),
        )
    }

    suspend fun resetIdentity() {
        requireProfileConsent()
        setIdentityBound(false)
        enqueueIdentity(type = "reset_identity")
        identitySessionId = UUID.randomUUID().toString().lowercase()
    }

    suspend fun flush() {
        try {
            flushIdentity()
        } catch (_: Throwable) {
            scheduleRetry()
            return
        }
        runCatching { flushExperienceInteractions() }
            .onFailure { scheduleRetry() }
        runCatching { flushTestSessionSignals() }
        val queued = store.load()
        if (queued.isEmpty()) { retryAttempt = 0; return }
        val batch = queued.take(50).toMutableList()
        while (batch.size > 1 && encodedBatchSize(batch) > 65_536) batch.removeAt(batch.lastIndex)
        try {
            val response: EventBatchResponse = post(
                "sdk/v3/events/batch",
                json.encodeToString(EventBatch.serializer(), EventBatch(events = batch)),
                EventBatchResponse.serializer(),
                null,
            )
            val terminal = (response.accepted + response.duplicates +
                response.rejected.filterNot { it.retryable }.map { it.clientEventId }).toSet()
            val remaining = queued.filterNot { it.clientEventId in terminal }
            if (!store.save(remaining)) throw WtsSdkException.Storage
            if (response.rejected.any { it.retryable }) {
                scheduleRetry()
            } else {
                retryAttempt = 0
                if (remaining.isNotEmpty()) scheduleFlush(0)
            }
        } catch (error: WtsSdkException.Server) {
            if (error.statusCode in 400..499 && error.statusCode != 429) {
                store.save(queued.drop(batch.size))
                log(WtsLogLevel.ERROR, "Discarded an invalid event batch (HTTP ${error.statusCode}).")
            } else scheduleRetry()
        } catch (_: Throwable) { scheduleRetry() }
    }

    private suspend fun flushIdentity() {
        val queued = identityStore.load()
        if (queued.isEmpty()) return
        val batch = queued.take(50).toMutableList()
        while (batch.size > 1 && encodedIdentityBatchSize(batch) > 65_536) {
            batch.removeAt(batch.lastIndex)
        }
        try {
            val response: IdentityMutationBatchResponse = post(
                "sdk/v2/identity/mutations",
                json.encodeToString(
                    IdentityMutationBatch.serializer(),
                    IdentityMutationBatch(mutations = batch),
                ),
                IdentityMutationBatchResponse.serializer(),
                null,
            )
            val terminal = (
                response.accepted +
                    response.duplicates +
                    response.rejected.filterNot { it.retryable }.map { it.clientMutationId }
                ).toSet()
            updateIdentityBinding(
                mutations = batch,
                acceptedOrDuplicate = (response.accepted + response.duplicates).toSet(),
            )
            val remaining = queued.filterNot { it.clientMutationId in terminal }
            if (!identityStore.save(remaining)) {
                throw WtsSdkException.Storage
            }
            if (response.rejected.any { it.retryable }) throw RetryableBatchRejection()
        } catch (error: WtsSdkException.Server) {
            if (error.statusCode in 400..499 && error.statusCode != 429) {
                identityStore.save(queued.drop(batch.size))
                log(
                    WtsLogLevel.ERROR,
                    "Discarded an invalid identity batch (HTTP ${error.statusCode}).",
                )
                return
            }
            throw error
        }
    }

    private fun effectiveExperienceConsent(): WtsExperienceConsent =
        if (experienceConsent == WtsExperienceConsent.PERSONALIZED && !identityBound) {
            WtsExperienceConsent.CONTEXTUAL
        } else {
            experienceConsent
        }

    private fun updateIdentityBinding(
        mutations: List<IdentityMutationRequest>,
        acceptedOrDuplicate: Set<String>,
    ) {
        mutations.filter { it.clientMutationId in acceptedOrDuplicate }.forEach { mutation ->
            when (mutation.type) {
                "identify" -> setIdentityBound(true)
                "reset_identity" -> setIdentityBound(false)
            }
        }
    }

    private fun setIdentityBound(bound: Boolean) {
        val editor = preferences.edit()
        if (bound) {
            editor.putString(IDENTITY_BOUND_SOURCE_KEY, appKey)
        } else {
            editor.remove(IDENTITY_BOUND_SOURCE_KEY)
        }
        if (!editor.commit()) throw WtsSdkException.Storage
        identityBound = bound
    }

    private suspend fun refreshExperienceManifest() {
        if (experienceConsent == WtsExperienceConsent.PERSONALIZED && !profileConsentGranted) {
            throw WtsSdkException.ExperienceProfileConsentRequired
        }
        val deliveryConsent = effectiveExperienceConsent()
        val response: ExperienceBootstrapResponse = postExperience(
            path = "experiences/v1/bootstrap",
            body = json.encodeToString(
                ExperienceBootstrapRequest.serializer(),
                ExperienceBootstrapRequest(
                    consent = deliveryConsent.wireValue(),
                    profileConsentGranted = profileConsentGranted,
                    actorId = installId,
                    sessionId = identitySessionId,
                    metadata = Metadata.current(appContext),
                    settings = experienceSettings,
                    testDeviceToken = experienceTestDeviceToken,
                ),
            ),
            serializer = ExperienceBootstrapResponse.serializer(),
        )
        val verifiedManifest = ExperienceManifestVerifier.verify(
            response = response,
            verificationKeys = options.experiences.manifestVerificationKeys,
            expectedSourceKey = appKey,
            json = json,
        ) ?: throw WtsSdkException.InvalidResponse()
        val expiry = parseExperienceTimestamp(verifiedManifest.expiresAt)
        if (expiry <= System.currentTimeMillis()) {
            throw WtsSdkException.InvalidResponse()
        }
        experienceManifest = verifiedManifest
        experienceCandidates = verifiedManifest.campaigns.map { it.campaignVersionId }
        experienceManifestExpiresAt = expiry
        experienceManifestRefreshAt = minOf(
            expiry,
            System.currentTimeMillis() + EXPERIENCE_MANIFEST_CACHE_MS,
        )
        experienceLastErrorCode = null
    }

    private suspend fun evaluateExperiences(context: ExperienceContextWire) {
        if (!options.experiences.enabled ||
            experienceConsent !in setOf(
                WtsExperienceConsent.CONTEXTUAL,
                WtsExperienceConsent.PERSONALIZED,
            ) ||
            (experienceConsent == WtsExperienceConsent.PERSONALIZED && !profileConsentGranted)
        ) {
            return
        }
        try {
            if (experienceManifestRefreshAt <= System.currentTimeMillis()) {
                refreshExperienceManifest()
            }
            if (experienceConsent == WtsExperienceConsent.PERSONALIZED && !identityBound) {
                runCatching { flushIdentity() }.onFailure { scheduleRetry() }
            }
            val deliveryConsent = effectiveExperienceConsent()
            val decisions = if (deliveryConsent == WtsExperienceConsent.CONTEXTUAL) {
                experienceManifest?.let { contextualExperienceDecisions(it, context) }.orEmpty()
            } else {
                if (experienceCandidates.isEmpty()) {
                    emptyList()
                } else {
                    postExperience(
                        path = "experiences/v1/decide",
                        body = json.encodeToString(
                            ExperienceDecisionRequest.serializer(),
                            ExperienceDecisionRequest(
                                consent = deliveryConsent.wireValue(),
                                profileConsentGranted = profileConsentGranted,
                                actorId = installId,
                                sessionId = identitySessionId,
                                metadata = Metadata.current(appContext),
                                settings = experienceSettings,
                                testDeviceToken = experienceTestDeviceToken,
                                candidateVersionIds = experienceCandidates,
                                context = context,
                            ),
                        ),
                        serializer = ExperienceDecisionResponse.serializer(),
                    ).decisions
                }
            }
            val interactions = mutableListOf<ExperienceInteractionRequest>()
            decisions.take(5).forEach { decision ->
                if (decision.holdout) {
                    interactions += decision.toInteraction(
                        exposureId = null,
                        type = "assigned_holdout",
                        triggerEventId = context.triggerEventId,
                    )
                    return@forEach
                }
                val variant = decision.content ?: return@forEach
                val variantId = decision.variantId ?: return@forEach
                if (experienceQueue.any { it.experience.campaignVersionId == decision.campaignVersionId } ||
                    presentingExperience?.experience?.campaignVersionId == decision.campaignVersionId
                ) {
                    return@forEach
                }
                val runtime = RuntimeExperience(
                    experience = WtsExperience(
                        campaignId = decision.campaignId,
                        campaignVersionId = decision.campaignVersionId,
                        assignmentId = decision.assignmentId,
                        variantId = variantId,
                        placement = if (decision.placement == "bottom_sheet") {
                            WtsExperiencePlacement.BOTTOM_SHEET
                        } else {
                            WtsExperiencePlacement.MODAL
                        },
                        priority = decision.priority,
                        content = variant.content.toPublic(),
                        assetUrl = variant.asset?.url,
                    ),
                    exposureId = UUID.randomUUID().toString(),
                )
                experienceGrants[runtime.experience.assignmentId] = decision.grant
                experienceQueue += runtime
                if (options.experiences.renderMode == WtsExperienceRenderMode.MANUAL) {
                    manualExperienceStates[runtime.exposureId] = ManualExperienceState(runtime)
                }
                experienceQueue.sortWith(
                    compareByDescending<RuntimeExperience> { it.experience.priority }
                        .thenBy { it.experience.campaignId },
                )
                while (experienceQueue.size > 5) {
                    val dropped = experienceQueue.removeAt(experienceQueue.lastIndex)
                    manualExperienceStates.remove(dropped.exposureId)
                }
                listOf("assigned_variant", "eligible", "queued").forEach { type ->
                    interactions += decision.toInteraction(
                        exposureId = runtime.exposureId,
                        type = type,
                        triggerEventId = context.triggerEventId,
                    )
                }
            }
            if (interactions.isNotEmpty()) {
                runCatching { sendExperienceInteractions(interactions) }
                    .onFailure { scheduleRetry() }
            }
            if (options.experiences.renderMode == WtsExperienceRenderMode.AUTOMATIC) {
                withContext(Dispatchers.Main) { presentNextExperience() }
            } else {
                notifyNextManualExperienceIfAvailable()
            }
        } catch (error: WtsSdkException) {
            experienceLastErrorCode = error.code
            log(WtsLogLevel.ERROR, "Experience decision failed (${error.code}).")
        } catch (_: Throwable) {
            experienceLastErrorCode = "EXPERIENCE_RUNTIME_ERROR"
            log(WtsLogLevel.ERROR, "Experience decision failed.")
        }
    }

    private fun contextualExperienceDecisions(
        manifest: ExperienceBootstrapResponse.Manifest,
        context: ExperienceContextWire,
    ): List<ExperienceDecisionResponse.Decision> = manifest.campaigns
        .asSequence()
        .filterNot { it.requiresPersonalization }
        .filter { experienceTriggerMatches(it.trigger, context) }
        .filter {
            experienceTargetMatches(
                target = it.targeting,
                manifest = manifest,
                metadata = Metadata.current(appContext),
            )
        }
        .sortedWith(
            compareByDescending<ExperienceBootstrapResponse.Campaign> { it.priority }
                .thenBy { it.campaignId },
        )
        .mapNotNull { campaign ->
            val branch = campaign.assignment ?: return@mapNotNull null
            val grant = campaign.grant ?: return@mapNotNull null
            val variant = branch.variantId?.let { variantId ->
                campaign.variants.firstOrNull { it.id == variantId }
            }
            ExperienceDecisionResponse.Decision(
                campaignId = campaign.campaignId,
                campaignVersionId = campaign.campaignVersionId,
                assignmentId = branch.assignmentId,
                variantId = branch.variantId,
                holdout = branch.kind == "holdout",
                placement = campaign.placement,
                priority = campaign.priority,
                content = variant,
                grant = grant,
            )
        }
        .toList()

    private fun experienceTriggerMatches(
        trigger: JsonObject,
        context: ExperienceContextWire,
    ): Boolean {
        return when (trigger.string("type")) {
            "screen_view" -> context.screenName == trigger.string("screenName")
            "custom_event" -> {
                if (context.eventKey != trigger.string("eventKey")) return false
                val conditions = trigger["conditions"] as? JsonArray ?: JsonArray(emptyList())
                conditions.all { element ->
                    val condition = element as? JsonObject ?: return@all false
                    experienceValueMatches(
                        current = condition.string("key")?.let(context.properties::get),
                        operator = condition.string("operator"),
                        expected = condition["value"],
                    )
                }
            }
            else -> false
        }
    }

    private fun experienceTargetMatches(
        target: JsonObject,
        manifest: ExperienceBootstrapResponse.Manifest,
        metadata: Metadata,
    ): Boolean = when (target.string("kind")) {
        "all" -> (target["conditions"] as? JsonArray)
            ?.all { (it as? JsonObject)?.let { node ->
                experienceTargetMatches(node, manifest, metadata)
            } == true } == true
        "any" -> (target["conditions"] as? JsonArray)
            ?.any { (it as? JsonObject)?.let { node ->
                experienceTargetMatches(node, manifest, metadata)
            } == true } == true
        "not" -> (target["condition"] as? JsonObject)
            ?.let { !experienceTargetMatches(it, manifest, metadata) } == true
        "condition" -> {
            val current = when (target.string("field")) {
                "platform" -> JsonPrimitive("android")
                "environment" -> JsonPrimitive(manifest.environment)
                "locale" -> metadata.locale?.let(::JsonPrimitive)
                "source_id" -> JsonPrimitive(manifest.sourceId)
                "actor_type" -> JsonPrimitive("anonymous")
                else -> null
            }
            experienceValueMatches(
                current = current,
                operator = target.string("operator"),
                expected = target["value"],
            )
        }
        else -> false
    }

    private fun experienceValueMatches(
        current: JsonElement?,
        operator: String?,
        expected: JsonElement?,
    ): Boolean {
        if (operator == "exists") return current != null
        return when (operator) {
            "equals" -> current == expected
            "not_equals" -> current != expected
            "in" -> current != null && (expected as? JsonArray)?.contains(current) == true
            "not_in" -> current != null && (expected as? JsonArray)?.contains(current) != true
            "gt", "gte", "lt", "lte" -> {
                val left = (current as? JsonPrimitive)?.doubleOrNull ?: return false
                val right = (expected as? JsonPrimitive)?.doubleOrNull ?: return false
                when (operator) {
                    "gt" -> left > right
                    "gte" -> left >= right
                    "lt" -> left < right
                    else -> left <= right
                }
            }
            else -> false
        }
    }

    private suspend fun sendExperienceInteractions(
        interactions: List<ExperienceInteractionRequest>,
    ) {
        if (interactions.isEmpty()) return
        val queue = experienceInteractionStore.load().toMutableList().apply {
            addAll(interactions)
            while (size > 100 || encodedExperienceBatchSize(this) > 1_048_576) {
                removeAt(0)
            }
        }
        if (!experienceInteractionStore.save(queue)) throw WtsSdkException.Storage
        flushExperienceInteractions()
    }

    private suspend fun flushExperienceInteractions() {
        if (experienceConsent !in setOf(
                WtsExperienceConsent.CONTEXTUAL,
                WtsExperienceConsent.PERSONALIZED,
            )
        ) {
            return
        }
        val queued = experienceInteractionStore.load()
        if (queued.isEmpty()) return
        val batch = queued.take(50).toMutableList()
        while (batch.size > 1 && encodedExperienceBatchSize(batch) > 65_536) {
            batch.removeAt(batch.lastIndex)
        }
        try {
            val response: ExperienceInteractionBatchResponse = postExperience(
                path = "experiences/v1/interactions/batch",
                body = json.encodeToString(
                    ExperienceInteractionBatchRequest.serializer(),
                    ExperienceInteractionBatchRequest(
                        consent = effectiveExperienceConsent().wireValue(),
                        profileConsentGranted = profileConsentGranted,
                        actorId = installId,
                        sessionId = identitySessionId,
                        interactions = batch,
                    ),
                ),
                serializer = ExperienceInteractionBatchResponse.serializer(),
            )
            val terminal = (
                response.accepted +
                    response.duplicates +
                    response.rejected.filterNot { it.retryable }
                        .map { it.clientInteractionId }
                ).toSet()
            if (!experienceInteractionStore.save(
                    queued.filterNot { it.clientInteractionId in terminal },
                )
            ) {
                throw WtsSdkException.Storage
            }
            if (response.rejected.any { it.retryable }) throw RetryableBatchRejection()
        } catch (error: WtsSdkException.Server) {
            if (error.statusCode in 400..499 && error.statusCode != 429) {
                experienceInteractionStore.save(queued.drop(batch.size))
                log(
                    WtsLogLevel.ERROR,
                    "Discarded an invalid Experience interaction batch " +
                        "(HTTP ${error.statusCode}).",
                )
                return
            }
            throw error
        }
    }

    private suspend fun recordExperience(
        runtime: RuntimeExperience,
        type: String,
        actionId: String? = null,
        failureCode: String? = null,
    ) {
        val experience = runtime.experience
        val grant = experienceGrants[experience.assignmentId] ?: return
        runCatching {
            sendExperienceInteractions(
                listOf(
                    ExperienceInteractionRequest(
                        grant = grant,
                        campaignId = experience.campaignId,
                        campaignVersionId = experience.campaignVersionId,
                        assignmentId = experience.assignmentId,
                        variantId = experience.variantId,
                        exposureId = runtime.exposureId,
                        type = type,
                        actionId = actionId,
                        metadata = Metadata.current(appContext),
                        failureCode = failureCode,
                    ),
                ),
            )
        }.onFailure { scheduleRetry() }
    }

    private suspend fun handleExperienceAction(
        runtime: RuntimeExperience,
        action: WtsExperienceAction,
    ) {
        if (!isExperienceActionAllowed(action)) {
            experienceLastErrorCode = "EXPERIENCE_ACTION_NOT_ALLOWED"
            return
        }
        val experience = runtime.experience
        val handled = experienceActionHandler?.invoke(experience, action) == true
        if (!handled) performSafeExperienceAction(action)
        recordExperience(
            runtime,
            if (experience.isPrimaryAction(action.id)) "primary_action" else "secondary_action",
            actionId = action.id,
        )
        if (action.type.isNavigationAction()) clearQueuedExperiences()
    }

    private fun isExperienceActionAllowed(action: WtsExperienceAction): Boolean {
        val target = action.target
        return when (action.type) {
            WtsExperienceActionType.DISMISS -> true
            WtsExperienceActionType.COPY_CODE -> !target.isNullOrEmpty()
            WtsExperienceActionType.OPEN_INTERNAL_ROUTE ->
                target != null && target in options.experiences.allowedInternalRoutes
            WtsExperienceActionType.CUSTOM_CALLBACK ->
                target != null && target in options.experiences.allowedCallbackKeys
            WtsExperienceActionType.OPEN_WEB_URL -> {
                val uri = target?.let(Uri::parse)
                uri?.scheme == "https" &&
                    "${uri.scheme}://${uri.authority}".lowercase() in
                    options.experiences.allowedWebOrigins
            }
            WtsExperienceActionType.OPEN_DEEP_LINK -> {
                val uri = target?.let(Uri::parse)
                val scheme = uri?.scheme?.lowercase()
                if (scheme == null || isUnsafeExperienceScheme(scheme)) {
                    false
                } else if (scheme == "https") {
                    uri.host?.lowercase() in options.experiences.allowedDeepLinkHosts
                } else {
                    scheme in options.experiences.allowedDeepLinkSchemes
                }
            }
        }
    }

    private fun performSafeExperienceAction(action: WtsExperienceAction) {
        val target = action.target ?: return
        when (action.type) {
            WtsExperienceActionType.DISMISS,
            WtsExperienceActionType.OPEN_INTERNAL_ROUTE,
            WtsExperienceActionType.CUSTOM_CALLBACK,
            -> Unit
            WtsExperienceActionType.COPY_CODE -> {
                val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE)
                    as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("wts.is", target))
            }
            WtsExperienceActionType.OPEN_WEB_URL -> {
                val uri = Uri.parse(target)
                val origin = "${uri.scheme}://${uri.authority}".lowercase()
                if (uri.scheme == "https" && origin in options.experiences.allowedWebOrigins) {
                    launchUri(uri)
                }
            }
            WtsExperienceActionType.OPEN_DEEP_LINK -> {
                val uri = Uri.parse(target)
                val scheme = uri.scheme?.lowercase()
                val allowed = if (scheme == null || isUnsafeExperienceScheme(scheme)) {
                    false
                } else if (scheme == "https") {
                    uri.host?.lowercase() in options.experiences.allowedDeepLinkHosts
                } else {
                    scheme in options.experiences.allowedDeepLinkSchemes
                }
                if (allowed) launchUri(uri)
            }
        }
    }

    private fun isUnsafeExperienceScheme(scheme: String): Boolean =
        scheme in setOf(
            "about",
            "blob",
            "data",
            "file",
            "filesystem",
            "http",
            "javascript",
            "vbscript",
        )

    private fun launchUri(uri: Uri) {
        appContext.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun WtsExperience.findAction(actionId: String): WtsExperienceAction? =
        content.translations.values
            .asSequence()
            .flatMap { localized ->
                sequenceOf(localized.primaryAction, localized.secondaryAction).filterNotNull()
            }
            .firstOrNull { it.id == actionId }

    private fun WtsExperience.isPrimaryAction(actionId: String): Boolean =
        content.translations.values.any { it.primaryAction?.id == actionId }

    private fun WtsExperienceActionType.isNavigationAction(): Boolean =
        this == WtsExperienceActionType.OPEN_INTERNAL_ROUTE ||
            this == WtsExperienceActionType.OPEN_DEEP_LINK ||
            this == WtsExperienceActionType.OPEN_WEB_URL

    private fun activeTestSession(): PersistedTestSession? {
        val current = testSession ?: return null
        if (current.sourceKey != appKey) {
            clearTestSession()
            return null
        }
        if (!isTestSessionExpired(current)) return current
        clearTestSession()
        return null
    }

    private fun requireActiveTestSession(): PersistedTestSession {
        val current = activeTestSession()
        if (current == null || !current.compatible) {
            throw WtsSdkException.InvalidEvent("No compatible SDK Test & Validate session is active.")
        }
        return current
    }

    private fun clearTestSession() {
        testSessionRetryJob?.cancel()
        testSessionRetryJob = null
        testSession = null
        testSessionStore.clear()
    }

    private fun persistTestSession() {
        val current = testSession ?: return
        if (!testSessionStore.save(current)) {
            testSessionLastErrorCode = WtsSdkException.Storage.code
        }
    }

    private fun isTestSessionExpired(value: PersistedTestSession): Boolean =
        runCatching { parseExperienceTimestamp(value.expiresAt) <= System.currentTimeMillis() }
            .getOrDefault(true)

    private fun validateTestPairing(pairing: WtsTestSessionPairing) {
        pairing.pairingToken?.let {
            require(it.length in 32..512) { "Pairing tokens must contain 32 to 512 characters." }
        }
        pairing.pairingCode?.let {
            require(it.uppercase().matches(Regex("^[A-Z2-9]{16}$"))) {
                "Pairing codes must contain 16 uppercase base32 characters."
            }
        }
    }

    private fun testSessionMetadata(sdkFamily: WtsSdkFamily): co.wetus.sdk.internal.TestSessionMetadata {
        val metadata = Metadata.current(appContext)
        return co.wetus.sdk.internal.TestSessionMetadata(
            sdkFamily = sdkFamily.wireValue,
            appVersion = metadata.appVersion,
            osVersion = metadata.osVersion,
            locale = metadata.locale ?: "en",
        )
    }

    private fun testSessionCapabilities() = TestSessionCapabilities(
        deeplink = true,
        identity = true,
        screen = true,
        experiences = options.experiences.enabled,
        offlineQueue = true,
    )

    private fun testSessionConsent() = TestSessionConsent(
        analytics = "granted",
        profile = profileConsentGranted,
        experience = experienceConsent.name.lowercase(),
    )

    private fun recordTestSessionSignal(
        type: String,
        outcome: String,
        method: String? = null,
        eventKey: String? = null,
        screenName: String? = null,
        propertyKeys: List<String>? = null,
        propertyTypes: Map<String, String>? = null,
        revenue: co.wetus.sdk.internal.TestSessionRevenueDescriptor? = null,
        resultCode: String? = null,
        feature: String? = null,
    ) {
        val current = activeTestSession() ?: return
        if (!current.compatible) return
        if (
            type in setOf("experience_impression", "experience_action") &&
            !current.testExperienceDecisionReady
        ) {
            return
        }
        if (!isTestSessionSignalAllowed(
                current.testPlan,
                type = type,
                method = method,
                eventKey = eventKey,
                hasRevenue = revenue != null,
            )
        ) {
            return
        }
        val signals = current.pendingSignals.toMutableList().apply {
            add(
                TestSessionSignal(
                    type = type,
                    outcome = outcome,
                    method = method,
                    eventKey = eventKey,
                    screenName = screenName,
                    propertyKeys = propertyKeys?.take(20),
                    propertyTypes = propertyTypes?.entries?.take(20)?.associate { it.toPair() },
                    revenue = revenue,
                    resultCode = resultCode,
                    feature = feature,
                ),
            )
            while (size > 50) removeAt(0)
        }
        testSession = current.copy(pendingSignals = signals)
        persistTestSession()
        scope.launch { flushTestSessionSignals() }
    }

    private fun isTestSessionSignalAllowed(
        plan: co.wetus.sdk.internal.TestSessionPlan,
        type: String,
        method: String?,
        eventKey: String?,
        hasRevenue: Boolean = false,
    ): Boolean = when (type) {
        "identity_recorded" -> plan.profile?.let {
            it.selected && it.available && method in it.allowedMethods
        } ?: false
        "event_recorded" -> eventKey != null && plan.events.any {
            it.eventKey == eventKey && (!hasRevenue || it.revenueEnabled)
        }
        "screen_recorded" -> plan.screen?.selected == true
        "deep_link_resolved", "probe_completed" -> plan.deepLink?.let {
            it.selected && it.available
        } ?: false
        "experience_impression", "experience_action" -> plan.experience?.let {
            it.selected && it.available
        } ?: false
        else -> true
    }

    private fun TestSessionExperienceDecisionResponse.toPublic() =
        WtsTestSessionExperienceDecision(
            outcome = outcome,
            reason = reason,
            testGrant = testGrant?.let {
                WtsTestSessionExperienceGrant(it.fixtureId, it.expiresAt)
            },
            decision = decision?.let { value ->
                WtsTestSessionExperienceCampaign(
                    campaignId = value.campaignId,
                    campaignVersionId = value.campaignVersionId,
                    placement = value.placement,
                    defaultLocale = value.defaultLocale,
                    variant = value.variant?.let { variant ->
                        WtsTestSessionExperienceVariant(
                            id = variant.id,
                            key = variant.key,
                            content = variant.content,
                            assetUrl = variant.asset?.url,
                        )
                    },
                )
            },
        )

    private suspend fun flushTestSessionSignals() {
        val current = activeTestSession() ?: return
        if (!current.compatible || current.pendingSignals.isEmpty()) return
        val batch = current.pendingSignals.take(50)
        try {
            val response: TestSessionSignalBatchResponse = postTest(
                path = "signals/batch",
                body = json.encodeToString(
                    TestSessionSignalBatch.serializer(),
                    TestSessionSignalBatch(
                        participantId = current.participantId,
                        sessionToken = current.sessionToken,
                        signals = batch,
                    ),
                ),
                serializer = TestSessionSignalBatchResponse.serializer(),
            )
            val terminal = (
                response.accepted + response.duplicates +
                    response.rejected.filterNot { it.retryable }.map { it.clientSignalId }
                ).toSet()
            val refreshed = activeTestSession() ?: return
            testSession = refreshed.copy(
                pendingSignals = refreshed.pendingSignals.filterNot { it.clientSignalId in terminal },
            )
            if (response.rejected.any { it.retryable }) {
                scheduleTestSessionRetry()
            } else {
                testSessionRetryAttempt = 0
            }
            persistTestSession()
        } catch (error: WtsSdkException.Server) {
            testSessionLastErrorCode = error.code
            if (error.statusCode in setOf(401, 403, 404)) {
                clearTestSession()
            } else if (error.statusCode in 400..499 && error.statusCode != 429) {
                val refreshed = activeTestSession() ?: return
                testSession = refreshed.copy(pendingSignals = refreshed.pendingSignals.drop(batch.size))
                persistTestSession()
            } else {
                scheduleTestSessionRetry()
            }
        } catch (error: Throwable) {
            testSessionLastErrorCode = testSessionErrorCode(error)
            scheduleTestSessionRetry()
        }
    }

    private fun scheduleTestSessionRetry() {
        if (testSessionRetryJob?.isActive == true || activeTestSession()?.compatible != true) return
        val base = min(2.0.pow(testSessionRetryAttempt.coerceAtMost(6)) * 1_000, 60_000.0).toLong()
        testSessionRetryAttempt = min(testSessionRetryAttempt + 1, 6)
        testSessionRetryJob = scope.launch {
            delay((base * Random.nextDouble(0.8, 1.2)).toLong())
            testSessionRetryJob = null
            flushTestSessionSignals()
        }
    }

    private suspend fun <T> postTest(
        path: String,
        body: String,
        serializer: KSerializer<T>,
    ): T = post("sdk/test/v1/$path", body, serializer, null)

    private fun testSessionErrorCode(error: Throwable): String = when (error) {
        is WtsSdkException -> error.code
        else -> "TEST_SESSION_TRANSPORT_ERROR"
    }

    private fun List<TestSessionHandshakeResponse.Check>.toPublicChecks() = map {
        WtsTestSessionCheck(
            key = it.key,
            status = it.status,
            code = it.code,
            message = it.message,
        )
    }

    private fun TestSessionResolveResponse.toPublic() = WtsTestSessionProbeResult(
        match = match,
        status = status,
        code = code,
        originalUrl = originalUrl,
        fallbackUrl = fallbackUrl,
        link = link?.let { item ->
            WtsTestSessionProbeLink(
                id = item.id,
                path = item.path,
                parameters = item.parameters.mapValues { (_, value) -> value.toWtsValue() },
            )
        },
    )

    private fun JsonElement.toWtsValue(): WtsValue {
        val primitive = this as? JsonPrimitive ?: return WtsValue.StringValue("")
        return when {
            primitive.isString -> WtsValue.StringValue(primitive.content)
            primitive.content == "true" || primitive.content == "false" ->
                WtsValue.BooleanValue(primitive.content.toBoolean())
            else -> WtsValue.NumberValue(primitive.content.toDoubleOrNull() ?: 0.0)
        }
    }

    private fun parseExperienceTimestamp(value: String): Long {
        if (!value.endsWith("Z")) throw WtsSdkException.InvalidResponse()
        val normalized = "${value.dropLast(1)}+0000"
        return listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ssZ",
        ).firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                }.parse(normalized)?.time
            }.getOrNull()
        } ?: throw WtsSdkException.InvalidResponse()
    }

    private suspend fun <T> postExperience(
        path: String,
        body: String,
        serializer: KSerializer<T>,
    ): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${options.collectorBaseUrl.trimEnd('/')}/$path")
            .header("X-WTS-Source-Key", appKey)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw WtsSdkException.Server(response.code)
                runCatching {
                    json.decodeFromString(serializer, requireNotNull(response.body).string())
                }.getOrElse { throw WtsSdkException.InvalidResponse() }
            }
        } catch (error: WtsSdkException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw WtsSdkException.Timeout()
        } catch (error: IOException) {
            throw WtsSdkException.Network(error = error)
        }
    }

    private val experienceSettings: ExperienceSettingsWire
        get() = ExperienceSettingsWire(
            allowedInternalRoutes = options.experiences.allowedInternalRoutes.sorted(),
            allowedCallbackKeys = options.experiences.allowedCallbackKeys.sorted(),
            allowedDeepLinkHosts = options.experiences.allowedDeepLinkHosts.sorted(),
            allowedDeepLinkSchemes = options.experiences.allowedDeepLinkSchemes.sorted(),
            allowedWebOrigins = options.experiences.allowedWebOrigins.sorted(),
        )

    private suspend fun <T> post(
        path: String,
        body: String,
        serializer: KSerializer<T>,
        fallback: Uri?,
    ): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${options.apiBaseUrl.trimEnd('/')}/$path")
            .header("X-WTS-App-Key", appKey)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 404 && fallback != null) throw WtsSdkException.NoMatch(fallback)
                if (!response.isSuccessful) throw WtsSdkException.Server(response.code, fallback)
                runCatching { json.decodeFromString(serializer, requireNotNull(response.body).string()) }
                    .getOrElse { throw WtsSdkException.InvalidResponse(fallback) }
            }
        } catch (error: WtsSdkException) { throw error }
        catch (error: SocketTimeoutException) { throw WtsSdkException.Timeout(fallback) }
        catch (error: IOException) { throw WtsSdkException.Network(fallback, error) }
    }

    private fun unwrap(uri: Uri): Uri {
        if (uri.scheme == "https" || uri.scheme == "http") return uri
        val wrapped = uri.getQueryParameter("url")?.let(Uri::parse)
        if (uri.host != "open" || wrapped?.scheme != "https") throw WtsSdkException.InvalidUrl()
        return wrapped
    }

    private fun validate(eventKey: String, properties: Map<String, WtsValue>) {
        if (!eventKey.matches(Regex("^[a-z][a-z0-9_]{1,63}$"))) {
            throw WtsSdkException.InvalidEvent("eventKey must use lowercase snake_case.")
        }
        validateProperties(properties)
    }

    private fun validateProperties(properties: Map<String, WtsValue>) {
        if (properties.size > 20) throw WtsSdkException.InvalidEvent("Events support at most 20 properties.")
        if (properties.keys.any { !it.matches(ATTRIBUTE_KEY) }) {
            throw WtsSdkException.InvalidEvent(
                "Event property keys must use lowercase snake_case.",
            )
        }
        if (properties.values.any { it is WtsValue.StringValue && it.value.length > 512 }) {
            throw WtsSdkException.InvalidEvent("String event properties cannot exceed 512 characters.")
        }
        if (properties.values.any { it is WtsValue.NumberValue && !it.value.isFinite() }) {
            throw WtsSdkException.InvalidEvent("Numeric event properties must be finite.")
        }
    }

    private fun Map<String, WtsValue>.toJson() = JsonObject(mapValues { (_, value) ->
        when (value) {
            is WtsValue.StringValue -> JsonPrimitive(value.value)
            is WtsValue.NumberValue -> JsonPrimitive(value.value)
            is WtsValue.BooleanValue -> JsonPrimitive(value.value)
        }
    })

    /** Test-session signals intentionally retain value types, never property values. */
    private fun WtsValue.testSessionType(): String = when (this) {
        is WtsValue.StringValue -> "string"
        is WtsValue.NumberValue -> "number"
        is WtsValue.BooleanValue -> "boolean"
    }

    private fun Map<String, WtsUserValue>.toUserJson() = JsonObject(
        mapValues { (_, value) -> value.toJson() },
    )

    private fun WtsUserValue.toJson(): JsonElement = when (this) {
        is WtsUserValue.StringValue -> JsonPrimitive(value)
        is WtsUserValue.NumberValue -> JsonPrimitive(value)
        is WtsUserValue.BooleanValue -> JsonPrimitive(value)
        is WtsUserValue.DateValue -> JsonPrimitive(value)
        is WtsUserValue.StringArrayValue -> JsonArray(value.map(::JsonPrimitive))
    }

    private fun enqueueIdentity(
        type: String,
        externalUserId: String? = null,
        attributes: JsonObject? = null,
        operations: UserUpdateWire? = null,
        attribution: ReportedAttributionWire? = null,
    ) {
        val mutation = IdentityMutationRequest(
            identity = IdentityContext(
                installId = installId,
                sessionId = identitySessionId,
            ),
            type = type,
            externalUserId = externalUserId,
            attributes = attributes,
            operations = operations,
            attribution = attribution,
            metadata = Metadata.current(appContext),
        )
        if (encodedIdentityBatchSize(listOf(mutation)) > 65_536) {
            throw WtsSdkException.InvalidProfile("Identity mutation cannot exceed 64 KiB.")
        }
        val queue = identityStore.load().toMutableList().apply {
            add(mutation)
            while (size > 100 || encodedIdentityBatchSize(this) > 1_048_576) removeAt(0)
        }
        if (!identityStore.save(queue)) throw WtsSdkException.Storage
        testSessionIdentityMethods(type, operations).forEach { method ->
            recordTestSessionSignal(
                type = "identity_recorded",
                outcome = "observed",
                method = method,
                propertyKeys = if (method == "increment") listOf("sdk_test_increment") else null,
                propertyTypes = if (method == "increment") {
                    mapOf("sdk_test_increment" to "number")
                } else {
                    null
                },
                feature = "identity",
            )
        }
        scheduleFlush(0)
    }

    private fun requireProfileConsent() {
        if (!profileConsentGranted) throw WtsSdkException.ProfileConsentRequired
    }

    private fun testSessionIdentityMethods(
        type: String,
        operations: UserUpdateWire?,
    ): List<String> = when (type) {
        "identify", "reported_attribution", "reset_identity" -> listOf(type)
        "update_user" -> buildList {
            if (!operations?.set.isNullOrEmpty()) add("update_user")
            if (!operations?.setOnce.isNullOrEmpty()) add("set_once")
            if (!operations?.increment.isNullOrEmpty()) add("increment")
            if (isEmpty()) add("update_user")
        }
        else -> emptyList()
    }

    private fun validateAttributes(attributes: Map<String, WtsUserValue>) {
        if (attributes.size > 50) {
            throw WtsSdkException.InvalidProfile(
                "A profile mutation supports at most 50 attributes.",
            )
        }
        attributes.forEach { (key, value) ->
            validateAttributeKey(key)
            when (value) {
                is WtsUserValue.StringValue ->
                    if (value.value.length > 2_048) {
                        throw WtsSdkException.InvalidProfile(
                            "String attributes cannot exceed 2048 characters.",
                        )
                    }
                is WtsUserValue.DateValue ->
                    if (!value.value.matches(ISO_DATE)) {
                        throw WtsSdkException.InvalidProfile(
                            "Date attributes must use ISO-8601.",
                        )
                    }
                is WtsUserValue.StringArrayValue ->
                    if (value.value.size > 50 || value.value.any { it.length > 512 }) {
                        throw WtsSdkException.InvalidProfile(
                            "String-array attributes support 50 values of at most 512 characters.",
                        )
                    }
                is WtsUserValue.NumberValue ->
                    if (!value.value.isFinite()) {
                        throw WtsSdkException.InvalidProfile("Number attributes must be finite.")
                    }
                is WtsUserValue.BooleanValue -> Unit
            }
        }
    }

    private fun validateUpdate(update: WtsUserUpdate) {
        val keys = update.set.keys + update.setOnce.keys + update.unset + update.increment.keys
        if (keys.isEmpty() || keys.size > 50 || keys.toSet().size != keys.size) {
            throw WtsSdkException.InvalidProfile(
                "Profile updates require 1 to 50 unique attribute operations.",
            )
        }
        validateAttributes(update.set)
        validateAttributes(update.setOnce)
        (update.unset + update.increment.keys).forEach(::validateAttributeKey)
        if (update.increment.values.any { !it.isFinite() }) {
            throw WtsSdkException.InvalidProfile("Increment values must be finite numbers.")
        }
    }

    private fun validateAttributeKey(key: String) {
        if (!key.matches(ATTRIBUTE_KEY)) {
            throw WtsSdkException.InvalidProfile(
                "Attribute keys must use lowercase snake_case.",
            )
        }
    }

    private fun ResolveResponse.toPublic() = WtsDeepLink(
        path = link.path,
        parameters = link.parameters.mapValues { (_, value) ->
            val primitive = value as? JsonPrimitive ?: return@mapValues WtsValue.StringValue("")
            when {
                primitive.isString -> WtsValue.StringValue(primitive.content)
                primitive.content == "true" || primitive.content == "false" -> WtsValue.BooleanValue(primitive.content.toBoolean())
                else -> WtsValue.NumberValue(primitive.content.toDouble())
            }
        },
        linkId = link.id,
        attributionId = attributionId,
        isDeferred = isDeferred,
    )

    private fun encodedSize(events: List<EventRequest>) = encodedBatchSize(events)
    private fun encodedBatchSize(events: List<EventRequest>) =
        json.encodeToString(EventBatch.serializer(), EventBatch(events = events)).toByteArray().size
    private fun encodedIdentityBatchSize(mutations: List<IdentityMutationRequest>) =
        json.encodeToString(
            IdentityMutationBatch.serializer(),
            IdentityMutationBatch(mutations = mutations),
        ).toByteArray().size
    private fun encodedExperienceBatchSize(interactions: List<ExperienceInteractionRequest>) =
        json.encodeToString(
            ExperienceInteractionBatchRequest.serializer(),
            ExperienceInteractionBatchRequest(
                consent = effectiveExperienceConsent().wireValue(),
                profileConsentGranted = profileConsentGranted,
                actorId = installId,
                sessionId = identitySessionId,
                interactions = interactions,
            ),
        ).toByteArray().size

    private fun scheduleRetry() {
        retryAttempt = min(retryAttempt + 1, 6)
        val base = min(2.0.pow(retryAttempt - 1) * 60_000, 3_600_000.0).toLong()
        scheduleFlush((base * Random.nextDouble(0.8, 1.2)).toLong())
    }

    private fun scheduleFlush(delayMillis: Long) {
        retryJob?.cancel()
        retryJob = scope.launch { if (delayMillis > 0) delay(delayMillis); flush() }
    }

    private fun log(level: WtsLogLevel, message: String) {
        if (options.logLevel.ordinal >= level.ordinal) android.util.Log.d("WtsSdk", message)
    }

    companion object {
        const val VERSION = "0.4.0-alpha.1"
        private const val PREFERENCES = "co.wetus.wts-sdk"
        private const val INSTALL_ID = "install-id"
        private const val IDENTITY_BOUND_SOURCE_KEY = "identity-bound-source-key-v1"
        private const val DEFERRED_TERMINAL = "deferred-terminal-v1"
        private const val EXPERIENCE_MANIFEST_CACHE_MS = 5 * 60_000L
        private const val EXPERIENCE_QUEUE_COOLDOWN_MILLIS = 3_000L
        private const val EXPERIENCE_MANIFEST_VERIFICATION_FAILED =
            "EXPERIENCE_MANIFEST_VERIFICATION_FAILED"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val ATTRIBUTE_KEY = Regex("^[a-z][a-z0-9_]{0,63}$")
        private val ISO_DATE =
            Regex("^\\d{4}-\\d{2}-\\d{2}(?:T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z)?$")
        @Volatile private var instance: WtsSdk? = null

        fun configure(context: Context, appKey: String, options: WtsOptions = WtsOptions()): WtsSdk {
            val normalized = appKey.trim()
            if (normalized.length < 8) throw WtsSdkException.InvalidAppKey
            return synchronized(this) {
                instance?.takeIf { it.appKey == normalized && it.options == options } ?: run {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(options.requestTimeoutMillis, TimeUnit.MILLISECONDS)
                        .readTimeout(options.requestTimeoutMillis, TimeUnit.MILLISECONDS)
                        .writeTimeout(options.requestTimeoutMillis, TimeUnit.MILLISECONDS)
                        .build()
                    val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
                    WtsSdk(
                        context.applicationContext,
                        normalized,
                        options,
                        client,
                        PreferencesEventStore(preferences, json),
                        PreferencesIdentityMutationStore(preferences, json),
                        PreferencesTestSessionStore(preferences, json),
                        PlayReferrerSource(context.applicationContext),
                    ).also { sdk -> instance = sdk; sdk.scheduleFlush(0) }
                }
            }
        }

        fun shared(): WtsSdk = instance ?: throw WtsSdkException.NotConfigured
    }
}

private class RetryableBatchRejection : Exception()

private fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun ExperienceDecisionResponse.Decision.toInteraction(
    exposureId: String?,
    type: String,
    triggerEventId: String?,
) = ExperienceInteractionRequest(
    grant = grant,
    campaignId = campaignId,
    campaignVersionId = campaignVersionId,
    assignmentId = assignmentId,
    variantId = variantId,
    exposureId = exposureId,
    type = type,
    triggerEventId = triggerEventId,
    metadata = Metadata(),
)

private fun co.wetus.sdk.internal.Content.toPublic() = WtsExperienceContent(
    translations = translations.mapValues { (_, value) ->
        WtsExperienceLocalizedContent(
            title = value.title,
            description = value.description,
            primaryAction = value.primaryAction?.toPublic(),
            secondaryAction = value.secondaryAction?.toPublic(),
        )
    },
    closeable = closeable,
    themePreset = themePreset,
    delaySeconds = delaySeconds,
    autoCloseSeconds = autoCloseSeconds,
)

private fun co.wetus.sdk.internal.Action.toPublic() = WtsExperienceAction(
    id = id,
    label = label,
    type = runCatching { WtsExperienceActionType.valueOf(type) }
        .getOrDefault(WtsExperienceActionType.DISMISS),
    target = target,
)
