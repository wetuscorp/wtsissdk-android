package co.wetus.sdk

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import co.wetus.sdk.internal.EventRequest
import co.wetus.sdk.internal.EventStore
import co.wetus.sdk.internal.EventBatchResponse
import co.wetus.sdk.internal.IdentityMutationRequest
import co.wetus.sdk.internal.IdentityMutationStore
import co.wetus.sdk.internal.PreferencesEventStore
import co.wetus.sdk.internal.ReferrerSource
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
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
    fun corruptedQueueIsRemoved() {
        val preferences = context.getSharedPreferences("corrupt-test", Context.MODE_PRIVATE)
        preferences.edit().putString("event-queue-v1", "not-json").commit()
        val store = PreferencesEventStore(preferences, Json { ignoreUnknownKeys = true })

        assertTrue(store.load().isEmpty())
        assertFalse(preferences.contains("event-queue-v1"))
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

    private fun createSdk(
        store: EventStore = MemoryEventStore(),
        referrer: ReferrerSource = ReferrerSource { null },
    ) = WtsSdk(
        context = context,
        appKey = "public-app-key",
        options = WtsOptions(apiBaseUrl = server.url("/").toString()),
        client = OkHttpClient(),
        store = store,
        identityStore = MemoryIdentityMutationStore(),
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

    private fun fixture(name: String): String = requireNotNull(
        javaClass.classLoader?.getResource("mobile/v2/fixtures/$name"),
    ).readText()
}
