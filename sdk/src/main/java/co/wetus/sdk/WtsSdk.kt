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
    private var identitySessionId = UUID.randomUUID().toString().lowercase()
    private val experienceActivityTracker =
        ExperienceActivityTracker(appContext as android.app.Application)
    private var experienceConsent = WtsExperienceConsent.PENDING
    private var experienceManifest: ExperienceBootstrapResponse.Manifest? = null
    private var experienceCandidates = emptyList<String>()
    private var experienceManifestExpiresAt = 0L
    private var experienceManifestRefreshAt = 0L
    private val experienceQueue = mutableListOf<WtsExperience>()
    private val experienceGrants = mutableMapOf<String, String>()
    private var experienceAvailableHandler: ((WtsExperience) -> Unit)? = null
    private var experienceActionHandler: ((WtsExperience, WtsExperienceAction) -> Boolean)? = null
    private var presentingExperience: WtsExperience? = null
    private var experienceDialog: ExperienceRenderHandle? = null
    private var experienceSessionImpressions = 0
    private val experienceTestDeviceToken = UUID.randomUUID().toString().lowercase()
    private var experienceLastErrorCode: String? = null

    suspend fun handle(uri: Uri): WtsDeepLink {
        val source = unwrap(uri)
        val key = source.toString()
        cache.get(key, System.currentTimeMillis())?.let { return it }
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
        if (consent == WtsExperienceConsent.PENDING || consent == WtsExperienceConsent.DENIED) {
            experienceManifest = null
            experienceCandidates = emptyList()
            experienceManifestExpiresAt = 0
            experienceManifestRefreshAt = 0
            experienceQueue.clear()
            experienceDialog?.dismiss(notify = false)
            experienceDialog = null
            presentingExperience = null
            if (!experienceInteractionStore.save(emptyList())) throw WtsSdkException.Storage
            return WtsExperienceResult.ACCEPTED
        }
        refreshExperienceManifest()
        runCatching { flushExperienceInteractions() }
            .onFailure { scheduleRetry() }
        return WtsExperienceResult.ACCEPTED
    }

    fun onExperienceAvailable(handler: ((WtsExperience) -> Unit)?) {
        experienceAvailableHandler = handler
    }

    fun onExperienceAction(handler: ((WtsExperience, WtsExperienceAction) -> Boolean)?) {
        experienceActionHandler = handler
    }

    fun presentNextExperience(): Boolean {
        if (presentingExperience != null || experienceQueue.isEmpty() ||
            experienceSessionImpressions >= 2
        ) {
            return false
        }
        val activity = experienceActivityTracker.resumedActivity() ?: return false
        val experience = experienceQueue.removeAt(0)
        presentingExperience = experience
        scope.launch { recordExperience(experience, "render_started") }
        val dialog = ExperienceRenderer.present(
            activity = activity,
            experience = experience,
            onImpression = {
                scope.launch {
                    if (presentingExperience?.exposureId == experience.exposureId) {
                        experienceSessionImpressions += 1
                        recordExperience(experience, "impression")
                    }
                }
            },
            onAction = { action ->
                scope.launch { handleExperienceAction(experience, action) }
            },
            onDismiss = {
                scope.launch {
                    if (presentingExperience?.exposureId == experience.exposureId) {
                        presentingExperience = null
                        experienceDialog = null
                        recordExperience(experience, "dismissed")
                        delay(3_000)
                        withContext(Dispatchers.Main) { presentNextExperience() }
                    }
                }
            },
        )
        if (dialog == null) {
            presentingExperience = null
            scope.launch { recordExperience(experience, "render_failed", failureCode = "PRESENTER_UNAVAILABLE") }
            return false
        }
        experienceDialog = dialog
        scope.launch { recordExperience(experience, "render_succeeded") }
        return true
    }

    fun dismissCurrentExperience(): Boolean {
        val dialog = experienceDialog ?: return false
        dialog.dismiss()
        return true
    }

    fun getExperienceDiagnostics() = WtsExperienceDiagnostics(
        enabled = options.experiences.enabled,
        consent = experienceConsent,
        queued = experienceQueue.size,
        presenting = presentingExperience != null,
        testDeviceToken = experienceTestDeviceToken,
        lastErrorCode = experienceLastErrorCode,
    )

    fun setProfileConsent(consent: WtsProfileConsent) {
        if (consent == WtsProfileConsent.GRANTED) {
            profileConsentGranted = true
            return
        }
        profileConsentGranted = false
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

    private suspend fun refreshExperienceManifest() {
        val response: ExperienceBootstrapResponse = postExperience(
            path = "experiences/v1/bootstrap",
            body = json.encodeToString(
                ExperienceBootstrapRequest.serializer(),
                ExperienceBootstrapRequest(
                    consent = experienceConsent.wireValue(),
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
        val expiry = parseExperienceTimestamp(response.expiresAt)
        if (expiry <= System.currentTimeMillis() ||
            response.manifest.expiresAt != response.expiresAt ||
            response.signature.isEmpty() ||
            response.keyId.isEmpty()
        ) {
            throw WtsSdkException.InvalidResponse()
        }
        experienceManifest = response.manifest
        experienceCandidates = response.manifest.campaigns.map { it.campaignVersionId }
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
            )
        ) {
            return
        }
        try {
            if (experienceManifestRefreshAt <= System.currentTimeMillis()) {
                refreshExperienceManifest()
            }
            val decisions = if (experienceConsent == WtsExperienceConsent.CONTEXTUAL) {
                experienceManifest?.let { contextualExperienceDecisions(it, context) }.orEmpty()
            } else {
                flushIdentity()
                if (experienceCandidates.isEmpty()) {
                    emptyList()
                } else {
                    postExperience(
                        path = "experiences/v1/decide",
                        body = json.encodeToString(
                            ExperienceDecisionRequest.serializer(),
                            ExperienceDecisionRequest(
                                consent = experienceConsent.wireValue(),
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
                if (experienceQueue.any { it.campaignVersionId == decision.campaignVersionId } ||
                    presentingExperience?.campaignVersionId == decision.campaignVersionId
                ) {
                    return@forEach
                }
                val experience = WtsExperience(
                    campaignId = decision.campaignId,
                    campaignVersionId = decision.campaignVersionId,
                    assignmentId = decision.assignmentId,
                    variantId = variantId,
                    exposureId = UUID.randomUUID().toString(),
                    placement = if (decision.placement == "bottom_sheet") {
                        WtsExperiencePlacement.BOTTOM_SHEET
                    } else {
                        WtsExperiencePlacement.MODAL
                    },
                    priority = decision.priority,
                    content = variant.content.toPublic(),
                    assetUrl = variant.asset?.url,
                )
                experienceGrants[experience.assignmentId] = decision.grant
                experienceQueue += experience
                experienceQueue.sortWith(
                    compareByDescending<WtsExperience> { it.priority }
                        .thenBy { it.campaignId },
                )
                while (experienceQueue.size > 5) experienceQueue.removeAt(experienceQueue.lastIndex)
                listOf("assigned_variant", "eligible", "queued").forEach { type ->
                    interactions += decision.toInteraction(
                        exposureId = experience.exposureId,
                        type = type,
                        triggerEventId = context.triggerEventId,
                    )
                }
                experienceAvailableHandler?.invoke(experience)
            }
            if (interactions.isNotEmpty()) {
                runCatching { sendExperienceInteractions(interactions) }
                    .onFailure { scheduleRetry() }
            }
            if (options.experiences.renderMode == WtsExperienceRenderMode.AUTOMATIC) {
                withContext(Dispatchers.Main) { presentNextExperience() }
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
                        consent = experienceConsent.wireValue(),
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
        experience: WtsExperience,
        type: String,
        actionId: String? = null,
        failureCode: String? = null,
    ) {
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
                        exposureId = experience.exposureId,
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
        experience: WtsExperience,
        action: WtsExperienceAction,
    ) {
        if (!isExperienceActionAllowed(action)) {
            experienceLastErrorCode = "EXPERIENCE_ACTION_NOT_ALLOWED"
            return
        }
        val handled = experienceActionHandler?.invoke(experience, action) == true
        if (!handled) performSafeExperienceAction(action)
        val localized = experience.content.translations.values.firstOrNull()
        recordExperience(
            experience,
            if (localized?.primaryAction?.id == action.id) "primary_action" else "secondary_action",
            actionId = action.id,
        )
        if (
            action.type == WtsExperienceActionType.OPEN_INTERNAL_ROUTE ||
            action.type == WtsExperienceActionType.OPEN_DEEP_LINK ||
            action.type == WtsExperienceActionType.OPEN_WEB_URL
        ) {
            experienceQueue.clear()
        }
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
                uri?.scheme?.lowercase() in options.experiences.allowedDeepLinkSchemes ||
                    (uri?.scheme == "https" &&
                        uri.host?.lowercase() in options.experiences.allowedDeepLinkHosts)
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
                val allowed = uri.scheme?.lowercase() in options.experiences.allowedDeepLinkSchemes ||
                    (uri.scheme == "https" &&
                        uri.host?.lowercase() in options.experiences.allowedDeepLinkHosts)
                if (allowed) launchUri(uri)
            }
        }
    }

    private fun launchUri(uri: Uri) {
        appContext.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun parseExperienceTimestamp(value: String): Long {
        if (!value.endsWith("Z")) throw WtsSdkException.InvalidResponse()
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).apply {
                isLenient = false
            }.parse("${value.dropLast(1)}+0000")?.time
        }.getOrNull() ?: throw WtsSdkException.InvalidResponse()
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
        scheduleFlush(0)
    }

    private fun requireProfileConsent() {
        if (!profileConsentGranted) throw WtsSdkException.ProfileConsentRequired
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
                consent = experienceConsent.wireValue(),
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
        const val VERSION = "0.3.0-alpha.1"
        private const val PREFERENCES = "co.wetus.wts-sdk"
        private const val INSTALL_ID = "install-id"
        private const val DEFERRED_TERMINAL = "deferred-terminal-v1"
        private const val EXPERIENCE_MANIFEST_CACHE_MS = 5 * 60_000L
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
