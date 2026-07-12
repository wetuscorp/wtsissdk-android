package co.wetus.sdk

import android.content.Context
import android.net.Uri
import co.wetus.sdk.internal.DeferredRequest
import co.wetus.sdk.internal.EventBatch
import co.wetus.sdk.internal.EventBatchResponse
import co.wetus.sdk.internal.EventRequest
import co.wetus.sdk.internal.EventStore
import co.wetus.sdk.internal.Metadata
import co.wetus.sdk.internal.PlayReferrerSource
import co.wetus.sdk.internal.PreferencesEventStore
import co.wetus.sdk.internal.ReferrerSource
import co.wetus.sdk.internal.ResolveCache
import co.wetus.sdk.internal.ResolveRequest
import co.wetus.sdk.internal.ResolveResponse
import co.wetus.sdk.internal.RevenueWire
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
            "sdk/resolve",
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
                "sdk/deferred-resolve",
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

    suspend fun flush() {
        val queued = store.load()
        if (queued.isEmpty()) { retryAttempt = 0; return }
        val batch = queued.take(50).toMutableList()
        while (batch.size > 1 && encodedBatchSize(batch) > 65_536) batch.removeAt(batch.lastIndex)
        try {
            val response: EventBatchResponse = post(
                "sdk/events/batch",
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
        const val VERSION = "0.1.0-alpha.1"
        private const val PREFERENCES = "co.wetus.wts-sdk"
        private const val INSTALL_ID = "install-id"
        private const val DEFERRED_TERMINAL = "deferred-terminal-v1"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
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
                        PlayReferrerSource(context.applicationContext),
                    ).also { sdk -> instance = sdk; sdk.scheduleFlush(0) }
                }
            }
        }

        fun shared(): WtsSdk = instance ?: throw WtsSdkException.NotConfigured
    }
}
