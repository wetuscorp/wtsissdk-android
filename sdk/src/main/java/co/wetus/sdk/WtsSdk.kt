package co.wetus.sdk

import android.content.Context
import android.net.Uri
import co.wetus.sdk.internal.DeferredRequest
import co.wetus.sdk.internal.EventBatch
import co.wetus.sdk.internal.EventBatchResponse
import co.wetus.sdk.internal.EventRequest
import co.wetus.sdk.internal.EventStore
import co.wetus.sdk.internal.IdentityContext
import co.wetus.sdk.internal.IdentityMutationBatch
import co.wetus.sdk.internal.IdentityMutationBatchResponse
import co.wetus.sdk.internal.IdentityMutationRequest
import co.wetus.sdk.internal.IdentityMutationStore
import co.wetus.sdk.internal.Metadata
import co.wetus.sdk.internal.PlayReferrerSource
import co.wetus.sdk.internal.PreferencesEventStore
import co.wetus.sdk.internal.PreferencesIdentityMutationStore
import co.wetus.sdk.internal.ReferrerSource
import co.wetus.sdk.internal.ResolveCache
import co.wetus.sdk.internal.ResolveRequest
import co.wetus.sdk.internal.ResolveResponse
import co.wetus.sdk.internal.RevenueWire
import co.wetus.sdk.internal.ReportedAttributionWire
import co.wetus.sdk.internal.UserUpdateWire
import java.io.IOException
import java.net.SocketTimeoutException
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
            "sdk/v2/resolve",
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
                "sdk/v2/deferred-resolve",
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
                metadata = Metadata.current(appContext),
                eventKey = eventKey,
                properties = properties.toJson(),
                revenue = revenue?.let(RevenueWire::from),
                linkId = linkId,
            ))
            while (size > 100 || encodedSize(this) > 1_048_576) removeAt(0)
        }
        if (!store.save(queue)) throw WtsSdkException.Storage
        scheduleFlush(0)
    }

    fun setProfileConsent(consent: WtsProfileConsent) {
        profileConsentGranted = consent == WtsProfileConsent.GRANTED
        if (!profileConsentGranted && !identityStore.save(emptyList())) {
            throw WtsSdkException.Storage
        }
    }

    suspend fun identify(
        externalUserId: String,
        attributes: Map<String, WtsUserValue> = emptyMap(),
    ) {
        requireProfileConsent()
        val normalized = externalUserId.trim()
        if (normalized.isEmpty() || normalized.length > 128) {
            throw WtsSdkException.InvalidProfile(
                "externalUserId must contain 1 to 128 characters.",
            )
        }
        validateAttributes(attributes)
        enqueueIdentity(
            type = "identify",
            externalUserId = normalized,
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
        val queued = store.load()
        if (queued.isEmpty()) { retryAttempt = 0; return }
        val batch = queued.take(50).toMutableList()
        while (batch.size > 1 && encodedBatchSize(batch) > 65_536) batch.removeAt(batch.lastIndex)
        try {
            val response: EventBatchResponse = post(
                "sdk/v2/events/batch",
                json.encodeToString(EventBatch.serializer(), EventBatch(events = batch)),
                EventBatchResponse.serializer(),
                null,
            )
            val terminal = (response.accepted + response.duplicates +
                response.rejected.filterNot { it.retryable }.map { it.clientEventId }).toSet()
            if (!store.save(queued.filterNot { it.clientEventId in terminal })) throw WtsSdkException.Storage
            retryAttempt = 0
            if (queued.size > batch.size) scheduleFlush(0)
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
            if (!identityStore.save(queued.filterNot { it.clientMutationId in terminal })) {
                throw WtsSdkException.Storage
            }
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
        if (properties.size > 20) throw WtsSdkException.InvalidEvent("Events support at most 20 properties.")
        if (properties.values.any { it is WtsValue.StringValue && it.value.length > 512 }) {
            throw WtsSdkException.InvalidEvent("String event properties cannot exceed 512 characters.")
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

    private suspend fun enqueueIdentity(
        type: String,
        externalUserId: String? = null,
        attributes: JsonObject? = null,
        operations: UserUpdateWire? = null,
        attribution: ReportedAttributionWire? = null,
    ) {
        val queue = identityStore.load().toMutableList().apply {
            add(
                IdentityMutationRequest(
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
                ),
            )
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
        const val VERSION = "0.2.0-alpha.1"
        private const val PREFERENCES = "co.wetus.wts-sdk"
        private const val INSTALL_ID = "install-id"
        private const val DEFERRED_TERMINAL = "deferred-terminal-v1"
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
