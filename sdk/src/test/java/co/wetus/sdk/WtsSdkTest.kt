package co.wetus.sdk

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import co.wetus.sdk.internal.EventRequest
import co.wetus.sdk.internal.EventStore
import co.wetus.sdk.internal.EventBatchResponse
import co.wetus.sdk.internal.IdentityMutationRequest
import co.wetus.sdk.internal.IdentityMutationStore
import co.wetus.sdk.internal.PreferencesExperienceInteractionStore
import co.wetus.sdk.internal.PreferencesEventStore
import co.wetus.sdk.internal.ReferrerSource
import co.wetus.sdk.internal.PersistedTestSession
import co.wetus.sdk.internal.TestSessionStore
import java.util.UUID
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
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
    }

    @AfterTest
    fun tearDown() { server.shutdown() }

    @Test
    fun revenueNormalizesCurrency() {
        assertEquals("TRY", WtsRevenue("12.50", "try").normalizedCurrency)
    }

    @Test
    fun resolveDecodesContractAndUsesMemoryCache() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture("resolve-success.json")))
        val sdk = createSdk()
        val uri = Uri.parse("https://demo.links.wts.is/summer")

        val first = sdk.handle(uri)
        val second = sdk.handle(uri)

        assertEquals("link_example", first.linkId)
        assertEquals("/products/123", first.path)
        assertEquals(first, second)
        assertEquals(1, server.requestCount)
        assertEquals("public-app-key", server.takeRequest().getHeader("X-WTS-App-Key"))
    }

    @Test
    fun noMatchPreservesFallbackUri() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val uri = Uri.parse("https://demo.links.wts.is/missing")

        val error = assertFailsWith<WtsSdkException.NoMatch> { createSdk().handle(uri) }

        assertEquals(uri, error.fallbackUri)
    }

    @Test
    fun invalidEventDoesNotReachPersistentQueue() = runTest {
        val store = MemoryEventStore()
        val sdk = createSdk(store = store)

        assertFailsWith<WtsSdkException.InvalidEvent> { sdk.track("Purchase Event") }

        assertTrue(store.load().isEmpty())
    }

    @Test
    fun screenUsesMobileProtocolV3AndPersistsTypedContext() = runTest {
        val store = MemoryEventStore()
        val sdk = createSdk(store = store)

        sdk.screen(
            " checkout ",
            mapOf(
                "cart_total" to WtsValue.NumberValue(749.90),
                "currency" to WtsValue.StringValue("TRY"),
                "member" to WtsValue.BooleanValue(true),
            ),
        )

        val event = store.load().single()
        assertEquals(3, event.schemaVersion)
        assertEquals("screen_view", event.type)
        assertEquals("checkout", event.screenName)
        assertEquals("TRY", event.properties["currency"]?.toString()?.trim('"'))
        assertTrue(event.sessionId?.isNotBlank() == true)
    }

    @Test
    fun experiencesRemainDisabledUntilTheHostExplicitlyOptsIn() = runTest {
        val sdk = createSdk()

        assertEquals(
            WtsExperienceResult.FEATURE_DISABLED,
            sdk.setExperienceConsent(WtsExperienceConsent.CONTEXTUAL),
        )
        assertFalse(sdk.getExperienceDiagnostics().enabled)
    }

    @Test
    fun contextualExperienceUsesBootstrapGrantWithoutDecisionRoundTrip() = runTest {
        val decideRequests = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/experiences/v1/bootstrap" -> MockResponse()
                    .setResponseCode(200)
                    .setBody(contextualExperienceBootstrapFixture)
                "/experiences/v1/interactions/batch",
                "/api/v1/sdk/v3/events/batch",
                -> MockResponse()
                    .setResponseCode(202)
                    .setBody("""{"accepted":[],"duplicates":[],"rejected":[]}""")
                "/experiences/v1/decide" -> {
                    decideRequests.incrementAndGet()
                    MockResponse().setResponseCode(500)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        var available: WtsExperience? = null
        val sdk = createSdk(
            options = WtsOptions(
                apiBaseUrl = server.url("/api/v1").toString(),
                collectorBaseUrl = server.url("/").toString(),
                experiences = WtsExperienceOptions(
                    enabled = true,
                    renderMode = WtsExperienceRenderMode.MANUAL,
                ),
            ),
        )
        sdk.onExperienceAvailable { available = it }

        assertEquals(
            WtsExperienceResult.ACCEPTED,
            sdk.setExperienceConsent(WtsExperienceConsent.CONTEXTUAL),
        )
        sdk.screen(
            "checkout",
            mapOf("cart_total" to WtsValue.NumberValue(749.90)),
        )

        assertEquals(0, decideRequests.get())
        assertEquals("campaign_checkout", available?.campaignId)
        assertEquals(1, sdk.getExperienceDiagnostics().queued)
    }

    @Test
    fun corruptedQueueIsRemoved() {
        val preferences = context.getSharedPreferences("corrupt-test", Context.MODE_PRIVATE)
        preferences.edit().putString("event-queue-v1", "not-json").commit()
        val store = PreferencesEventStore(preferences, Json { ignoreUnknownKeys = true })

        assertTrue(store.load().isEmpty())
        assertFalse(preferences.contains("event-queue-v1"))
    }

    @Test
    fun corruptedExperienceInteractionQueueIsRemoved() {
        val preferences = context.getSharedPreferences(
            "corrupt-experience-test",
            Context.MODE_PRIVATE,
        )
        preferences.edit().putString("experience-interaction-queue-v1", "not-json").commit()
        val store = PreferencesExperienceInteractionStore(
            preferences,
            Json { ignoreUnknownKeys = true },
        )

        assertTrue(store.load().isEmpty())
        assertFalse(preferences.contains("experience-interaction-queue-v1"))
    }

    @Test
    fun experienceDiagnosticsExposeASourceScopedTestDeviceToken() {
        val first = createSdk().getExperienceDiagnostics().testDeviceToken
        val second = createSdk().getExperienceDiagnostics().testDeviceToken

        assertEquals(first, UUID.fromString(first).toString())
        assertEquals(second, UUID.fromString(second).toString())
        assertFalse(first == second)
    }

    @Test
    fun canonicalBatchFixtureDecodesStableRejectionFields() {
        val response = Json.decodeFromString<EventBatchResponse>(fixture("event-batch-mixed.json"))

        assertEquals(1, response.accepted.size)
        assertEquals(1, response.duplicates.size)
        assertEquals("EVENT_NOT_REGISTERED", response.rejected.first().code)
        assertFalse(response.rejected.first().retryable)
    }

    @Test
    fun identityRequiresExplicitProfileConsent() = runTest {
        val sdk = createSdk()

        assertFailsWith<WtsSdkException.ProfileConsentRequired> {
            sdk.identify("customer_1842")
        }

        sdk.setProfileConsent(WtsProfileConsent.GRANTED)
        sdk.identify(
            "customer_1842",
            mapOf("plan" to WtsUserValue.StringValue("enterprise")),
        )
    }

    @Test
    fun opaqueExternalUserIdIsPreservedAndConsentDenialQueuesReset() = runTest {
        val identityStore = MemoryIdentityMutationStore()
        val sdk = createSdk(identityStore = identityStore)
        sdk.setProfileConsent(WtsProfileConsent.GRANTED)
        sdk.identify(" customer_1842 ")

        assertEquals(" customer_1842 ", identityStore.load().first().externalUserId)

        sdk.setProfileConsent(WtsProfileConsent.DENIED)
        assertEquals(1, identityStore.load().size)
        assertEquals("reset_identity", identityStore.load().first().type)
    }

    @Test
    fun oversizedIdentityMutationIsRejectedBeforePersistence() = runTest {
        val identityStore = MemoryIdentityMutationStore()
        val sdk = createSdk(identityStore = identityStore)
        sdk.setProfileConsent(WtsProfileConsent.GRANTED)
        val attributes = (0 until 50).associate {
            "attribute_$it" to WtsUserValue.of("x".repeat(2_048))
        }

        assertFailsWith<WtsSdkException.InvalidProfile> {
            sdk.identify("customer_1842", attributes)
        }
        assertTrue(identityStore.load().isEmpty())
    }

    @Test
    fun errorsExposeStableCodesAndFallbackUris() {
        val fallbackUri = Uri.parse("https://wts.is/fallback")

        assertEquals("TIMEOUT", WtsSdkException.Timeout(fallbackUri).code)
        assertEquals(fallbackUri, WtsSdkException.Timeout(fallbackUri).fallbackUri)
        assertEquals(
            "PROFILE_CONSENT_REQUIRED",
            WtsSdkException.ProfileConsentRequired.code,
        )
    }

    @Test
    fun sdkTestSessionPairsCanonicalLinkSanitizesSignalsAndLeaves() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                val body = request.body.readUtf8()
                requests += path to body
                return when (path) {
                    "/api/v1/sdk/test/v1/pair" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(testSessionPairResponse)
                    "/api/v1/sdk/test/v1/handshake" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(testSessionHandshakeResponse)
                    "/api/v1/sdk/test/v1/signals/batch" -> acceptedTestSignals(body)
                    "/api/v1/sdk/test/v1/experiences/decide" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(testSessionExperienceDecisionResponse)
                    "/api/v1/sdk/test/v1/resolve" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(testSessionResolveResponse)
                    "/api/v1/sdk/test/v1/leave" -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"accepted":true}""")
                    "/api/v1/sdk/v3/events/batch" -> acceptedEvents(body)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val testStore = MemoryTestSessionStore()
        val sdk = createSdk(
            testSessionStore = testStore,
            options = WtsOptions(
                apiBaseUrl = server.url("/api/v1").toString(),
                collectorBaseUrl = server.url("/").toString(),
                experiences = WtsExperienceOptions(
                    enabled = true,
                    renderMode = WtsExperienceRenderMode.MANUAL,
                ),
            ),
        )
        val pairing = WtsTestSessionPairing.from(
            "https://sample.wts.is/_wts/test/pair?pairing=${"p".repeat(32)}",
        )
        assertEquals("p".repeat(32), pairing.pairingToken)
        assertEquals("A2B3C4D5E6F7G8H9", WtsTestSessionPairing.from("A2B3C4D5E6F7G8H9").pairingCode)

        val joined = sdk.joinTestSession(pairing)
        assertTrue(joined.accepted)
        assertTrue(joined.compatible)
        assertEquals("test_profile_123", joined.testProfileExternalUserId)

        sdk.track(
            "checkout_started",
            properties = mapOf("cart_total" to WtsValue.NumberValue(749.9)),
            revenue = WtsRevenue("749.90", "try"),
        )
        val resolve = sdk.probeTestSessionUrl("https://sample.wts.is/offer?secret=value")
        assertTrue(resolve.match)
        val probes = sdk.runTestSessionProbes()
        assertTrue(probes.emitted.containsAll(listOf("identity", "event", "screen", "experiences")))
        assertEquals("ready", probes.experienceDecision?.outcome)
        assertTrue(
            sdk.reportTestSessionExperienceInteraction(
                WtsTestSessionExperienceInteraction.ACTION,
            ),
        )
        assertTrue(sdk.leaveTestSession())
        assertFalse(sdk.getTestSessionDiagnostics().joined)
        assertEquals(null, testStore.load())

        val signalPayload = requests
            .filter { it.first == "/api/v1/sdk/test/v1/signals/batch" }
            .joinToString(separator = "\n") { it.second }
        testSessionIdentityMethods.forEach { method ->
            assertTrue(signalPayload.contains("\"method\":\"$method\""))
        }
        assertTrue(signalPayload.contains("\"sdk_test_increment\""))
        assertTrue(signalPayload.contains("\"revenue\":{\"present\":true,\"currency\":\"TRY\"}"))
        assertTrue(signalPayload.contains("\"revenue\":{\"present\":true,\"currency\":\"USD\"}"))
        assertTrue(signalPayload.contains("\"experience_action\""))
        assertFalse(signalPayload.contains("experience_decision"))
        assertFalse(signalPayload.contains("749.9"))
        assertFalse(signalPayload.contains("secret=value"))
        assertFalse(signalPayload.contains("test_profile_123"))
        assertTrue(requests.any { it.first == "/api/v1/sdk/test/v1/pair" })
        assertTrue(requests.any { it.first == "/api/v1/sdk/test/v1/handshake" })
        assertTrue(requests.any { it.first == "/api/v1/sdk/test/v1/experiences/decide" })
        assertTrue(requests.any { it.first == "/api/v1/sdk/test/v1/leave" })
        assertFalse(requests.any { it.first == "/experiences/v1/interactions/batch" })
    }

    private fun createSdk(
        store: EventStore = MemoryEventStore(),
        identityStore: IdentityMutationStore = MemoryIdentityMutationStore(),
        testSessionStore: TestSessionStore = MemoryTestSessionStore(),
        referrer: ReferrerSource = ReferrerSource { null },
        options: WtsOptions = WtsOptions(apiBaseUrl = server.url("/").toString()),
    ) = WtsSdk(
        context = context,
        appKey = "public-app-key",
        options = options,
        client = OkHttpClient(),
        store = store,
        identityStore = identityStore,
        testSessionStore = testSessionStore,
        referrerSource = referrer,
    )

    private class MemoryEventStore : EventStore {
        private var events = emptyList<EventRequest>()
        override fun load() = events
        override fun save(events: List<EventRequest>): Boolean { this.events = events; return true }
    }

    private class MemoryIdentityMutationStore : IdentityMutationStore {
        private var mutations = emptyList<IdentityMutationRequest>()
        override fun load() = mutations
        override fun save(mutations: List<IdentityMutationRequest>): Boolean {
            this.mutations = mutations
            return true
        }
    }

    private class MemoryTestSessionStore : TestSessionStore {
        private var value: PersistedTestSession? = null
        override fun load(): PersistedTestSession? = value
        override fun save(value: PersistedTestSession): Boolean {
            this.value = value
            return true
        }
        override fun clear(): Boolean {
            value = null
            return true
        }
    }

    private fun acceptedTestSignals(body: String): MockResponse {
        val signalIds = Json.parseToJsonElement(body)
            .jsonObject["signals"]
            ?.jsonArray
            ?.map { it.jsonObject["clientSignalId"]?.jsonPrimitive?.content }
            ?.filterNotNull()
            .orEmpty()
        return MockResponse()
            .setResponseCode(202)
            .setBody(
                """{"accepted":[${signalIds.joinToString(",") { "\"$it\"" }}],"duplicates":[],"rejected":[]}""",
            )
    }

    private fun acceptedEvents(body: String): MockResponse {
        val eventIds = Json.parseToJsonElement(body)
            .jsonObject["events"]
            ?.jsonArray
            ?.map { it.jsonObject["clientEventId"]?.jsonPrimitive?.content }
            ?.filterNotNull()
            .orEmpty()
        return MockResponse()
            .setResponseCode(202)
            .setBody(
                """{"accepted":[${eventIds.joinToString(",") { "\"$it\"" }}],"duplicates":[],"rejected":[]}""",
            )
    }

    private fun fixture(name: String): String = requireNotNull(
        javaClass.classLoader?.getResource("mobile/v2/fixtures/$name"),
    ).readText()

    private val contextualExperienceBootstrapFixture = """
        {
          "manifest": {
            "sourceId": "source_mobile",
            "sourceManifestVersion": 7,
            "environment": "production",
            "expiresAt": "2099-01-01T00:00:00.000Z",
            "campaigns": [{
              "campaignId": "campaign_checkout",
              "campaignVersionId": "campaign_version_7",
              "priority": 100,
              "placement": "modal",
              "trigger": {
                "type": "screen_view",
                "screenName": "checkout"
              },
              "targeting": {
                "kind": "condition",
                "field": "platform",
                "operator": "equals",
                "value": "android"
              },
              "variants": [{
                "id": "variant_primary",
                "content": {
                  "translations": {
                    "tr": {
                      "title": "Siparişinizi tamamlayın",
                      "description": "Güvenli ödeme adımına devam edin.",
                      "primaryAction": null,
                      "secondaryAction": null
                    }
                  },
                  "closeable": true,
                  "themePreset": "brand",
                  "delaySeconds": 0,
                  "autoCloseSeconds": null
                },
                "asset": null
              }],
              "requiresPersonalization": false,
              "grant": "signed-contextual-grant",
              "assignment": {
                "assignmentId": "assignment_checkout",
                "kind": "variant",
                "variantId": "variant_primary"
              }
            }]
          },
          "signature": "signed-manifest",
          "keyId": "experience-key-v1",
          "expiresAt": "2099-01-01T00:00:00.000Z"
        }
    """.trimIndent()

    private companion object {
        val testSessionIdentityMethods = listOf(
            "identify",
            "update_user",
            "set_once",
            "increment",
            "reported_attribution",
            "reset_identity",
        )

        val testSessionPlanJson = """
            {
              "profile": {
                "selected": true,
                "available": true,
                "allowedMethods": ["identify", "update_user", "set_once", "increment", "reported_attribution", "reset_identity"]
              },
              "events": [{
                "eventKey": "checkout_started",
                "properties": [{ "key": "cart_total", "type": "number", "required": true }],
                "revenueEnabled": true
              }],
              "deepLink": { "selected": true, "available": true, "linkId": "link_123" },
              "experience": { "selected": true, "available": true, "campaignId": "campaign_123", "versionId": "version_123" },
              "screen": { "selected": true }
            }
        """.trimIndent()

        val testSessionPairResponse = """
            {
              "session": { "id": "session_123", "status": "running", "expiresAt": "2099-01-01T00:00:00.000Z" },
              "participant": { "id": "participant_123", "sourceId": "source_123", "sourceType": "mobile_app", "status": "paired" },
              "sessionToken": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "testProfile": { "externalUserId": "test_profile_123" },
              "requiredSdkVersion": "0.3.0-alpha.1",
              "testPlan": $testSessionPlanJson
            }
        """.trimIndent()

        val testSessionHandshakeResponse = """
            {
              "accepted": true,
              "compatible": true,
              "requiredSdkVersion": "0.3.0-alpha.1",
              "checks": [{ "key": "sdk_version", "status": "ready", "code": null, "message": "Ready" }],
              "testPlan": $testSessionPlanJson
            }
        """.trimIndent()

        val testSessionExperienceDecisionResponse = """
            {
              "outcome": "ready",
              "reason": null,
              "testGrant": { "fixtureId": "fixture_123", "expiresAt": "2099-01-01T00:00:00.000Z" },
              "decision": {
                "campaignId": "campaign_123",
                "campaignVersionId": "version_123",
                "placement": "modal",
                "defaultLocale": "en",
                "variant": { "id": "variant_123", "key": "control", "content": {}, "asset": null }
              }
            }
        """.trimIndent()

        val testSessionResolveResponse = """
            {
              "match": true,
              "status": "ready",
              "code": "RESOLVED",
              "originalUrl": "https://sample.wts.is/offer",
              "fallbackUrl": "https://example.com/offer",
              "link": { "id": "link_123", "path": "/offer", "parameters": {} }
            }
        """.trimIndent()
    }
}
