package co.wetus.sdk.internal

import android.util.Base64
import java.security.spec.X509EncodedKeySpec
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey

/**
 * Verifies collector-issued Experience manifests before they can influence a
 * local decision or renderer. The outer `manifest` field is intentionally not
 * used: only the verified, signed payload is decoded.
 */
internal object ExperienceManifestVerifier {
    fun verify(
        response: ExperienceBootstrapResponse,
        verificationKeys: Map<String, String>,
        expectedSourceKey: String,
        json: Json,
    ): ExperienceBootstrapResponse.Manifest? {
        val encodedPublicKey = verificationKeys[response.keyId] ?: return null
        return runCatching {
            val payload = decodeUrlSafe(response.signedPayload)
            val signature = decodeUrlSafe(response.signature)
            val publicKey = EdDSAPublicKey(X509EncodedKeySpec(decodeStandardBase64(encodedPublicKey)))
            val verifier = EdDSAEngine().apply {
                initVerify(publicKey)
                update(payload)
            }
            if (!verifier.verify(signature)) return null
            json.decodeFromString(ExperienceBootstrapResponse.Manifest.serializer(), payload.decodeToString())
                .takeIf { it.sourceKey == expectedSourceKey }
        }.getOrNull()
    }

    private fun decodeUrlSafe(value: String): ByteArray {
        require(value.isNotBlank() && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "Expected an unpadded base64url value."
        }
        return Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private fun decodeStandardBase64(value: String): ByteArray {
        require(value.isNotBlank()) { "Expected a base64 public key." }
        return Base64.decode(value, Base64.DEFAULT)
    }
}
