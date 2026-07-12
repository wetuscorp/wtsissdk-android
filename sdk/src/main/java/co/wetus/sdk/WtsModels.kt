package co.wetus.sdk

import android.net.Uri

sealed interface WtsValue {
    data class StringValue(val value: String) : WtsValue
    data class NumberValue(val value: Double) : WtsValue
    data class BooleanValue(val value: Boolean) : WtsValue

    companion object {
        fun of(value: String): WtsValue = StringValue(value)
        fun of(value: Number): WtsValue = NumberValue(value.toDouble())
        fun of(value: Boolean): WtsValue = BooleanValue(value)
    }
}

data class WtsDeepLink(
    val path: String,
    val parameters: Map<String, WtsValue>,
    val linkId: String,
    val attributionId: String,
    val isDeferred: Boolean,
)

data class WtsRevenue(val amount: String, val currency: String) {
    init {
        require(amount.matches(Regex("^-?\\d{1,12}(?:\\.\\d{1,6})?$"))) {
            "Revenue amount must be a decimal string."
        }
        require(currency.matches(Regex("^[A-Za-z]{3}$"))) {
            "Revenue currency must be an ISO-4217 code."
        }
    }

    val normalizedCurrency: String = currency.uppercase()
}

enum class WtsLogLevel { OFF, ERROR, DEBUG }

data class WtsOptions(
    val apiBaseUrl: String = "https://api.wts.is/api/v1",
    val requestTimeoutMillis: Long = 2_000,
    val cacheTtlMillis: Long = 60_000,
    val logLevel: WtsLogLevel = WtsLogLevel.OFF,
)

sealed class WtsSdkException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    open val fallbackUri: Uri? = null
    data object NotConfigured : WtsSdkException("WtsSdk is not configured.")
    data object InvalidAppKey : WtsSdkException("The wts.is app key is invalid.")
    data class InvalidUrl(override val fallbackUri: Uri? = null) : WtsSdkException("The deep link URL is invalid.")
    data class NoMatch(override val fallbackUri: Uri) : WtsSdkException("No active deep link matched the URL.")
    data class Timeout(override val fallbackUri: Uri? = null) : WtsSdkException("The wts.is request timed out.")
    data class Network(override val fallbackUri: Uri? = null, val error: Throwable? = null) : WtsSdkException("The wts.is request failed.", error)
    data class Server(val statusCode: Int, override val fallbackUri: Uri? = null) : WtsSdkException("The wts.is API returned HTTP $statusCode.")
    data class InvalidResponse(override val fallbackUri: Uri? = null) : WtsSdkException("The wts.is API response was invalid.")
    data class InvalidEvent(val reason: String) : WtsSdkException(reason)
    data object Storage : WtsSdkException("The wts.is local event queue could not be persisted.")
}
