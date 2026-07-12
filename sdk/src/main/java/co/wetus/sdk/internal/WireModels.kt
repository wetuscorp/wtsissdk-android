package co.wetus.sdk.internal

import android.content.Context
import co.wetus.sdk.WtsRevenue
import co.wetus.sdk.WtsSdk
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class Metadata(
    val platform: String = "android",
    val appVersion: String? = null,
    val sdkVersion: String = WtsSdk.VERSION,
    val osVersion: String? = null,
    val locale: String? = null,
) {
    companion object {
        fun current(context: Context) = Metadata(
            appVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull(),
            osVersion = android.os.Build.VERSION.RELEASE,
            locale = Locale.getDefault().toLanguageTag(),
        )
    }
}

@Serializable
internal data class ResolveRequest(
    val schemaVersion: Int = 1,
    val clientEventId: String = UUID.randomUUID().toString(),
    val installId: String,
    val occurredAt: String = isoTimestamp(),
    val metadata: Metadata,
    val url: String,
)

@Serializable
internal data class DeferredRequest(
    val schemaVersion: Int = 1,
    val clientEventId: String = UUID.randomUUID().toString(),
    val installId: String,
    val occurredAt: String = isoTimestamp(),
    val metadata: Metadata,
    val referrer: String,
)

@Serializable
internal data class ResolveResponse(
    val match: Boolean,
    val attributionId: String,
    val isDeferred: Boolean,
    val link: Link,
) {
    @Serializable data class Link(val id: String, val path: String, val parameters: JsonObject)
}

@Serializable
internal data class EventRequest(
    val schemaVersion: Int = 1,
    val clientEventId: String = UUID.randomUUID().toString(),
    val installId: String,
    val occurredAt: String = isoTimestamp(),
    val metadata: Metadata,
    val eventKey: String,
    val properties: JsonObject,
    val revenue: RevenueWire? = null,
    val linkId: String? = null,
)

@Serializable
internal data class RevenueWire(val amount: String, val currency: String) {
    companion object { fun from(value: WtsRevenue) = RevenueWire(value.amount, value.normalizedCurrency) }
}

@Serializable internal data class EventBatch(val schemaVersion: Int = 1, val events: List<EventRequest>)

@Serializable
internal data class EventBatchResponse(
    val accepted: List<String>,
    val duplicates: List<String>,
    val rejected: List<Rejected>,
) {
    @Serializable
    data class Rejected(
        val clientEventId: String,
        val code: String,
        val message: String,
        val retryable: Boolean,
    )
}

private fun isoTimestamp(): String = SimpleDateFormat(
    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
    Locale.US,
).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
