package co.wetus.sdk.sample

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import co.wetus.sdk.WtsSdk
import co.wetus.sdk.WtsSdkException
import co.wetus.sdk.WtsTestSessionPairing
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).also { setContentView(it) }
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent) {
        lifecycleScope.launch {
            if (!SampleConfiguration.isConfigured) {
                status.text = "Set SampleConfiguration.appKey to test deep links and deferred links."
                return@launch
            }
            val uri = intent.data
            if (uri != null && isTestSessionPairing(uri)) {
                val pairing = runCatching { WtsTestSessionPairing.from(uri.toString()) }
                    .getOrElse {
                        status.text = "SDK Test & Validate pairing is invalid"
                        return@launch
                    }
                val result = WtsSdk.shared().joinTestSession(pairing)
                status.text = if (result.joined) {
                    val checks = result.checks.joinToString(separator = "\n") {
                        "${it.key}: ${it.status}"
                    }
                    "SDK Test & Validate session joined\n$checks"
                } else {
                    "SDK Test & Validate pairing failed: ${result.errorCode ?: "unknown_error"}"
                }
                return@launch
            }

            val sdk = WtsSdk.shared()
            val direct = uri?.let { runCatching { sdk.handle(it) }.getOrNull() }
            val deferred = if (direct == null) {
                runCatching { sdk.getDeferredDeepLink() }
                    .onFailure { error -> status.text = resolverFailureMessage(error) }
                    .getOrNull()
            } else {
                null
            }
            if (direct != null || deferred != null) {
                status.text = "Resolved ${(direct ?: deferred)?.path}"
            } else if (status.text.isNullOrBlank()) {
                status.text = "No deep link"
            }
        }
    }

    private fun resolverFailureMessage(error: Throwable): String = when (error) {
        is WtsSdkException.Server -> "Resolver unavailable (${error.statusCode}). Check the app key and network."
        is WtsSdkException.Timeout -> "Resolver timed out. The original web destination remains available."
        is WtsSdkException.Network -> "Resolver is offline. The original web destination remains available."
        else -> "No deep link"
    }

    private fun isTestSessionPairing(uri: Uri): Boolean =
        uri.scheme.equals("https", ignoreCase = true) && uri.path == "/_wts/test/pair"
}
