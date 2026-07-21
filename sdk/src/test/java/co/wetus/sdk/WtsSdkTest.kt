package co.wetus.sdk

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import co.wetus.sdk.internal.EventRequest
import co.wetus.sdk.internal.EventStore
import co.wetus.sdk.internal.ExperienceBootstrapResponse
import co.wetus.sdk.internal.ExperienceManifestVerifier
import co.wetus.sdk.internal.IdentityMutationRequest
import co.wetus.sdk.internal.IdentityMutationStore
import co.wetus.sdk.internal.PersistedTestSession
import co.wetus.sdk.internal.ReferrerSource
import co.wetus.sdk.internal.TestSessionStore
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSASecurityProvider
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WtsSdkTest {
    private lateinit var server: MockWebServer
    private lateinit var context: Context

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        context = ApplicationProvider.getApplicationContext()
        listOf("co.wetus.wts-sdk", "co.wetus.wts-sdk.v0.5").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @AfterTest
    fun tearDown() {
        listOf("co.wetus.wts-sdk", "co.wetus.wts-sdk.v0.5").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        server.shutdown()
    }

    @Test
    fun pendingUsesFunctionalResolveWithoutIdentityOrEventStorage() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"matched":true,"destination":"https://example.com/fallback","path":"/offers","parameters":{"campaign":"summer"}}""",
        ))
        val events = MemoryEventStore()
        val sdk = createSdk(store = events)

        sdk.track("checkout_started")
        val result = sdk.handle(Uri.parse("https://go.example/summer"))

        assertEquals(WtsConsentState.PENDING, sdk.getConsentState())
        assertEquals("/offers", result.path)
        assertNull(result.attributionId)
        assertTrue(events.load().isEmpty())
        val request = server.takeRequest()
        assertEquals("/api/v1/sdk/v4/functional-resolve", request.path)
        assertFalse(request.body.readUtf8().contains("installId"))
        assertFalse(preferences().contains("install-id"))
    }

    @Test
    fun publicConfigureClearsLegacyStateWithoutMigratingIt() {
        val legacy = context.getSharedPreferences("co.wetus.wts-sdk", Context.MODE_PRIVATE)
        assertTrue(legacy.edit().putString("event-queue", "legacy").commit())

        val sdk = WtsSdk.configure(context, "legacy-cleanup-app-key")

        assertEquals(WtsConsentState.PENDING, sdk.getConsentState())
        assertFalse(legacy.contains("event-queue"))
    }

    @Test
    fun grantPersistsAndUsesMobileV4AndExperiencesV2() = runTest {
        val fixture = SignedFixture.make()
        server.dispatcher = experienceDispatcher(fixture)
        val sdk = createSdk(rootPublicKey = fixture.rootPublicKey)

        sdk.setConsent(WtsConsentState.GRANTED)
        sdk.screen("checkout")
        sdk.flush()
        eventually {
            server.requestCount >= 3
        }

        val paths = (1..server.requestCount).map { server.takeRequest().path }
        assertEquals(WtsConsentState.GRANTED, sdk.getConsentState())
        assertTrue(paths.contains("/experiences/v2/bootstrap"))
        assertTrue(paths.contains("/experiences/v2/decide"))
        assertTrue(paths.contains("/api/v1/sdk/v4/events/batch"))

        val restored = createSdk(rootPublicKey = fixture.rootPublicKey)
        assertEquals(WtsConsentState.GRANTED, restored.getConsentState())
    }

    @Test
    fun denialClearsLocalStateAndStopsDataNetwork() = runTest {
        val fixture = SignedFixture.make()
        server.dispatcher = experienceDispatcher(fixture)
        val events = MemoryEventStore()
        val identity = MemoryIdentityStore()
        val sdk = createSdk(
            store = events,
            identityStore = identity,
            rootPublicKey = fixture.rootPublicKey,
        )
        sdk.setConsent(WtsConsentState.GRANTED)
        sdk.track("checkout_started")
        sdk.identify("customer-1")
        sdk.setConsent(WtsConsentState.DENIED)
        val requestsAfterDenial = server.requestCount
        sdk.track("checkout_started")
        sdk.flush()
        delay(50)

        assertEquals(WtsConsentState.DENIED, sdk.getConsentState())
        assertTrue(events.load().isEmpty())
        assertTrue(identity.load().isEmpty())
        assertFalse(preferences().contains("install-id"))
        assertEquals(requestsAfterDenial, server.requestCount)
    }

    @Test
    fun rootTrustAllowsLeafRotationAndRejectsReplayTamperUnknownAndExpiry() {
        val first = SignedFixture.make(keyId = "leaf-1")
        val second = SignedFixture.make(keyId = "leaf-2", root = first.root)
        val json = Json { ignoreUnknownKeys = true }
        fun verify(fixture: SignedFixture, sourceKey: String = "public-app-key", now: Long = 1_800_000_000_000) =
            ExperienceManifestVerifier.verify(
                response = json.decodeFromString(
                    ExperienceBootstrapResponse.serializer(),
                    fixture.response,
                ),
                rootPublicKey = first.rootPublicKey,
                expectedSourceKey = sourceKey,
                json = json,
                nowMillis = now,
            )

        assertNotNull(verify(first))
        assertNotNull(verify(second))
        assertNull(verify(first, sourceKey = "another-source"))
        assertNull(verify(first.copy(response = first.response.replace(
            "\"keyId\":\"leaf-1\"",
            "\"keyId\":\"unknown\"",
        ))))
        assertNull(verify(first.copy(response = first.response.replace(
            "\"signature\":\"",
            "\"signature\":\"AAAA",
        ))))
        assertNull(verify(first, now = 4_100_000_000_000))
    }

    private fun createSdk(
        store: EventStore = MemoryEventStore(),
        identityStore: IdentityMutationStore = MemoryIdentityStore(),
        rootPublicKey: String = "",
    ) = WtsSdk(
        context = context,
        appKey = "public-app-key",
        options = WtsOptions(
            apiBaseUrl = server.url("/api/v1").toString(),
            collectorBaseUrl = server.url("/").toString(),
        ),
        client = OkHttpClient(),
        store = store,
        identityStore = identityStore,
        testSessionStore = MemoryTestSessionStore(),
        referrerSource = ReferrerSource { null },
        experienceRootPublicKey = rootPublicKey,
    )

    private fun experienceDispatcher(fixture: SignedFixture): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
            "/experiences/v2/bootstrap" -> MockResponse()
                .setHeader("ETag", "\"manifest-7\"")
                .setResponseCode(200)
                .setBody(fixture.response)
            "/experiences/v2/decide" -> MockResponse().setResponseCode(200).setBody(
                """{"mode":"contextual","decisions":[],"serverTime":"2027-01-01T00:00:00.000Z"}""",
            )
            "/experiences/v2/interactions/batch" -> accepted("interactions", "clientInteractionId", request)
            "/api/v1/sdk/v4/events/batch" -> accepted("events", "clientEventId", request)
            "/api/v1/sdk/v2/identity/mutations" -> accepted("mutations", "clientMutationId", request)
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun accepted(arrayKey: String, idKey: String, request: RecordedRequest): MockResponse {
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val ids = body[arrayKey]?.jsonArray.orEmpty().mapNotNull {
            it.jsonObject[idKey]?.jsonPrimitive?.content
        }
        return MockResponse().setResponseCode(202).setBody(
            """{"accepted":[${ids.joinToString(",") { "\"$it\"" }}],"duplicates":[],"rejected":[]}""",
        )
    }

    private suspend fun eventually(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            delay(10)
        }
        assertTrue(condition())
    }

    private fun preferences() =
        context.getSharedPreferences("co.wetus.wts-sdk.v0.5", Context.MODE_PRIVATE)
}

private data class SignedFixture(
    val response: String,
    val rootPublicKey: String,
    val root: KeyPair,
) {
    companion object {
        fun make(
            keyId: String = "leaf-1",
            root: KeyPair = signingKeyPair(),
        ): SignedFixture {
            val leaf = signingKeyPair()
            val keysetPayload = """{"expiresAt":"2099-01-01T00:00:00.000Z","issuedAt":"2026-01-01T00:00:00.000Z","keys":[{"algorithm":"Ed25519","expiresAt":"2099-01-01T00:00:00.000Z","keyId":"$keyId","notBefore":"2026-01-01T00:00:00.000Z","publicKey":"${leaf.public.encoded.standardBase64()}"}],"version":1}"""
            val manifestPayload = """{"campaigns":[{"assignment":null,"campaignId":"campaign-checkout","campaignVersionId":"version-7","grant":null,"placement":"modal","priority":100,"requiresPersonalization":false,"targeting":{"field":"platform","kind":"condition","operator":"equals","value":"android"},"trigger":{"screenName":"checkout","type":"screen_view"},"variants":[]}],"environment":"production","expiresAt":"2099-01-01T00:00:00.000Z","generatedAt":"2026-01-01T00:00:00.000Z","issuedAt":"2026-01-01T00:00:00.000Z","manifestVersion":7,"schemaVersion":2,"sourceId":"source-mobile","sourceKey":"public-app-key"}"""
            val keysetSignature = sign(root, keysetPayload.toByteArray()).base64Url()
            val manifestSignature = sign(leaf, manifestPayload.toByteArray()).base64Url()
            val keysetObject = Json.parseToJsonElement(keysetPayload).jsonObject
            val manifestObject = Json.parseToJsonElement(manifestPayload).jsonObject
            val response = Json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                kotlinx.serialization.json.buildJsonObject {
                    put("onlineKeyset", kotlinx.serialization.json.buildJsonObject {
                        keysetObject.forEach { (key, value) -> put(key, value) }
                        put("signedPayload", keysetPayload.toByteArray().base64Url())
                        put("rootSignature", keysetSignature)
                    })
                    put("manifest", manifestObject)
                    put("signedPayload", manifestPayload.toByteArray().base64Url())
                    put("signature", manifestSignature)
                    put("keyId", keyId)
                    put("expiresAt", "2099-01-01T00:00:00.000Z")
                },
            )
            return SignedFixture(
                response = response,
                rootPublicKey = root.public.encoded.standardBase64(),
                root = root,
            )
        }

        private fun signingKeyPair(): KeyPair = KeyPairGenerator
            .getInstance("EdDSA", EdDSASecurityProvider())
            .apply {
                initialize(EdDSANamedCurveTable.getByName("Ed25519"), SecureRandom())
            }
            .generateKeyPair()

        private fun sign(key: KeyPair, value: ByteArray): ByteArray = EdDSAEngine().run {
            initSign(key.private)
            update(value)
            sign()
        }

        private fun ByteArray.base64Url(): String = Base64.encodeToString(
            this,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

        private fun ByteArray.standardBase64(): String =
            Base64.encodeToString(this, Base64.NO_WRAP)
    }
}

private class MemoryEventStore : EventStore {
    private var values = emptyList<EventRequest>()
    override fun load() = values
    override fun save(events: List<EventRequest>): Boolean { values = events; return true }
}

private class MemoryIdentityStore : IdentityMutationStore {
    private var values = emptyList<IdentityMutationRequest>()
    override fun load() = values
    override fun save(mutations: List<IdentityMutationRequest>): Boolean { values = mutations; return true }
}

private class MemoryTestSessionStore : TestSessionStore {
    override fun load(): PersistedTestSession? = null
    override fun save(value: PersistedTestSession) = true
    override fun clear() = true
}
