package co.wetus.sdk.internal

import android.util.Base64
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Locale
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
        rootPublicKey: String,
        expectedSourceKey: String,
        json: Json,
        nowMillis: Long = System.currentTimeMillis(),
    ): ExperienceBootstrapResponse.Manifest? {
        if (rootPublicKey.isBlank()) return null
        return runCatching {
            val root = EdDSAPublicKey(X509EncodedKeySpec(decodeStandardBase64(rootPublicKey)))
            val keysetPayload = decodeUrlSafe(response.onlineKeyset.signedPayload)
            val rootVerifier = EdDSAEngine().apply {
                initVerify(root)
                update(keysetPayload)
            }
            if (!rootVerifier.verify(decodeUrlSafe(response.onlineKeyset.rootSignature))) return null
            val keyset = json.decodeFromString(
                ExperienceBootstrapResponse.OnlineKeysetPayload.serializer(),
                keysetPayload.decodeToString(),
            )
            if (keyset.version != response.onlineKeyset.version ||
                keyset.issuedAt != response.onlineKeyset.issuedAt ||
                keyset.expiresAt != response.onlineKeyset.expiresAt ||
                keyset.keys != response.onlineKeyset.keys ||
                parseTimestamp(keyset.issuedAt) > nowMillis ||
                parseTimestamp(keyset.expiresAt) <= nowMillis
            ) return null
            val onlineKey = keyset.keys.firstOrNull {
                it.keyId == response.keyId && it.algorithm == "Ed25519" &&
                    parseTimestamp(it.notBefore) <= nowMillis &&
                    parseTimestamp(it.expiresAt) > nowMillis
            } ?: return null
            val payload = decodeUrlSafe(response.signedPayload)
            val signature = decodeUrlSafe(response.signature)
            val publicKey = EdDSAPublicKey(
                X509EncodedKeySpec(decodeStandardBase64(onlineKey.publicKey)),
            )
            val verifier = EdDSAEngine().apply {
                initVerify(publicKey)
                update(payload)
            }
            if (!verifier.verify(signature)) return null
            json.decodeFromString(
                ExperienceBootstrapResponse.Manifest.serializer(),
                payload.decodeToString(),
            ).takeIf {
                it.sourceKey == expectedSourceKey &&
                    parseTimestamp(it.issuedAt) <= nowMillis &&
                    parseTimestamp(it.expiresAt) > nowMillis &&
                    it.expiresAt == response.expiresAt
            }
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

    private fun parseTimestamp(value: String): Long {
        require(value.endsWith("Z")) { "Expected an RFC 3339 UTC timestamp." }
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
        } ?: error("Invalid RFC 3339 UTC timestamp.")
    }
}
