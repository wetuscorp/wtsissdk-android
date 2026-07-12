package co.wetus.sdk.internal

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

internal fun interface ReferrerSource { suspend fun read(): String? }

internal class PlayReferrerSource(private val context: Context) : ReferrerSource {
    override suspend fun read(): String? = suspendCancellableCoroutine { continuation ->
        val client = InstallReferrerClient.newBuilder(context).build()
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(code: Int) {
                val value = if (code == InstallReferrerResponse.OK) {
                    runCatching { client.installReferrer.installReferrer }.getOrNull()
                } else null
                client.endConnection()
                if (continuation.isActive) continuation.resume(value)
            }

            override fun onInstallReferrerServiceDisconnected() {
                if (continuation.isActive) continuation.resume(null)
            }
        })
        continuation.invokeOnCancellation { client.endConnection() }
    }
}
