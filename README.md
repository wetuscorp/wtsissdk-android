# wts.is Android SDK

Official SDK for verified Android App Links, deterministic Play Install Referrer deferred deep links, and offline custom event/revenue delivery. It returns validated route data; navigation remains under application control.

> `0.4.0-alpha.1` source line · Mobile Protocol V3 + Identity V1 + Experiences V1 + SDK Test Session V1 · minSdk 23 · compileSdk 36 · Java 17

> **Release note:** SDK Test & Validate APIs below are source-line APIs. Use
> them only after the matching Android package release has been published. This
> document does not claim that `0.4.0-alpha.1` is already available from Maven
> Central.

## Install

```kotlin
dependencies {
    implementation("co.wetus:wts-sdk:<matching-published-version>")
}
```

## Configure and handle links

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        WtsSdk.configure(this, "YOUR_PUBLIC_APP_KEY")
    }
}

lifecycleScope.launch {
    runCatching { WtsSdk.shared().handle(intent.data!!) }
        .onSuccess { link ->
            if (link.path in allowedRoutes) router.navigate(link.path, link.parameters)
        }
        .onFailure { error ->
            val fallback = (error as? WtsSdkException)?.fallbackUri
            if (fallback != null) openBrowser(fallback)
        }
}
```

Add an `android:autoVerify="true"` HTTPS intent filter for the exact host shown in the dashboard. Verify the package name and every release signing SHA-256 fingerprint before production.

## Deferred deep links

```kotlin
lifecycleScope.launch {
    WtsSdk.shared().getDeferredDeepLink()?.let { link ->
        if (link.path in allowedRoutes) router.navigate(link.path, link.parameters)
    }
}
```

The official Play Install Referrer API is consumed once per install after a terminal match/no-match result. Transient network failures remain retryable. No GAID, advertising identifier, or probabilistic matching is used.

## Events and revenue

```kotlin
WtsSdk.shared().track(
    eventKey = "purchase_completed",
    properties = mapOf("plan" to WtsValue.of("pro"), "trial" to WtsValue.of(false)),
    revenue = WtsRevenue("49.90", "TRY"),
)
WtsSdk.shared().flush() // optional
```

The private SharedPreferences queue is atomic, FIFO and bounded to 100 events/1 MiB. Batches are capped at 50 events/64 KiB and use exponential retry with jitter. Install identity remains in private SharedPreferences.

## Screens and Experiences

Screen views are built-in Mobile Protocol V3 events and do not require a
custom-event definition:

```kotlin
WtsSdk.shared().screen(
    name = "checkout",
    properties = mapOf(
        "cart_total" to WtsValue.of(749.90),
        "currency" to WtsValue.of("TRY"),
        "item_count" to WtsValue.of(3.0),
    ),
)
```

Experiences remains disabled until the host opts in and supplies a separate
consent decision:

```kotlin
WtsSdk.configure(
    this,
    "YOUR_PUBLIC_APP_KEY",
    WtsOptions(
        experiences = WtsExperienceOptions(
            enabled = true,
            renderMode = WtsExperienceRenderMode.AUTOMATIC,
            // Public Ed25519 verification keys: kid -> base64 SPKI DER.
            manifestVerificationKeys = mapOf(
                "current-experience-kid" to "BASE64_SPKI_DER_ED25519_PUBLIC_KEY",
            ),
            allowedInternalRoutes = setOf("/checkout", "/account"),
            allowedCallbackKeys = setOf("apply_offer"),
            allowedDeepLinkHosts = setOf("go.example.com"),
            allowedDeepLinkSchemes = setOf("example"),
            allowedWebOrigins = setOf("https://www.example.com"),
        ),
    ),
)

WtsSdk.shared().setExperienceConsent(WtsExperienceConsent.CONTEXTUAL)
```

The key map contains public verification material, not a secret. Get the
current key id and base64 SPKI DER public key from the Experience setup for the
same source; never embed a manifest signing private key in an application.
The SDK accepts a manifest only after it verifies the collector's signed
payload with the matching key id. The unsigned compatibility `manifest` field
in the response is never used for a decision or render.

Use `PERSONALIZED` only after profile consent. `PENDING` makes no Experience
request; `DENIED` clears local Experience state and unsent interactions.
Automatic mode uses native modal or bottom-sheet presentation.

Manual mode never invokes the native renderer. It supplies one opaque,
process-local presentation handle per queued Experience; do not persist it,
log it, or treat it as an authorization token. A host renderer must report the
real lifecycle with that handle:

```kotlin
WtsSdk.shared().onExperienceAvailable { presentation ->
    renderExperience(presentation.experience) { renderSucceeded, visibleForOneSecond, actionId, dismiss ->
        lifecycleScope.launch {
            if (renderSucceeded) {
                WtsSdk.shared().acknowledgeExperienceRender(presentation.handle)
            }
            if (visibleForOneSecond) {
                WtsSdk.shared().acknowledgeExperienceImpression(presentation.handle)
            }
            actionId?.let {
                WtsSdk.shared().reportExperienceAction(presentation.handle, it)
            }
            if (dismiss) {
                WtsSdk.shared().dismissExperience(
                    presentation.handle,
                    WtsExperienceDismissReason.DISMISSED,
                )
            }
        }
    }
}
```

Each lifecycle call is validated against the live manual presentation; duplicate
acknowledgements are idempotent and stale or forged handles are rejected.
Interactions use a private persistent bounded queue, UUID idempotency and
retry. Impressions require one uninterrupted second of native visibility.
HTTPS deep-link actions require an exact host in `allowedDeepLinkHosts`; adding
`https` to `allowedDeepLinkSchemes` never bypasses that host allowlist.

For an unpublished device test, copy
`WtsSdk.shared().getExperienceDiagnostics().testDeviceToken` into the
dashboard test panel for the same Mobile App. The random token contains no
install, user, or profile identifier. Test traffic does not affect customer
analytics or impression usage.

## SDK Test & Validate

SDK Test & Validate is a short-lived, dashboard-issued validation session. It
uses a separate bounded retry queue and never creates production events,
identities, Experience interactions, or attribution records. Do not hardcode,
persist outside the SDK, or log its pairing URL or token.

The dashboard QR code uses this canonical form:

```text
https://<mobile-app-host>/_wts/test/pair?pairing=<dashboard-issued-token>
```

When the application receives an incoming URL, recognize that pairing route
and join it **before** normal deep-link handling. A pairing URL is not an
application route and must not be passed to `handle`.

```kotlin
private fun Uri.isWtsTestPairing() =
    scheme == "https" && path == "/_wts/test/pair"

suspend fun onIncomingUrl(uri: Uri) {
    if (uri.isWtsTestPairing()) {
        val join = WtsSdk.shared().joinTestSession(
            WtsTestSessionPairing.from(uri.toString()),
        )
        showSdkTestChecks(join.checks)
        return
    }

    // Normal production behavior stays unchanged.
    val link = WtsSdk.shared().handle(uri)
    if (link.path in allowedRoutes) router.navigate(link.path, link.parameters)
}
```

Inspect the isolated session without creating analytics, then run only the
dashboard-selected probe plan:

```kotlin
val diagnostics = WtsSdk.shared().getTestSessionDiagnostics()
val probes = WtsSdk.shared().runTestSessionProbes()

// A ready decision is test-only. Render its typed content in your test UI;
// do not send it into the normal Experiences renderer.
if (probes.experienceDecision?.outcome == "ready") {
    showTestExperiencePreview(probes.experienceDecision!!)
    WtsSdk.shared().reportTestSessionExperienceInteraction(
        WtsTestSessionExperienceInteraction.IMPRESSION,
    )
}
```

Call `reportTestSessionExperienceInteraction(ACTION)` only after a real action
in that manual test preview. It is accepted only after the isolated Experience
decision is ready. Production Experience lifecycle signals are never copied
into this test transport. Use `probeTestSessionUrl(url)` for an event-free
resolver check, and call `leaveTestSession()` when the operator finishes. The
session is also cleared on expiry.

## User identity and reported attribution

Profile operations require an explicit consent decision from the host application. Use your own stable, opaque customer ID rather than an email address as `externalUserId`; the value is case-sensitive and is not trimmed or normalized.

```kotlin
WtsSdk.shared().setProfileConsent(WtsProfileConsent.GRANTED)

lifecycleScope.launch {
    WtsSdk.shared().identify(
        externalUserId = "customer_1842",
        attributes = mapOf(
            "email" to WtsUserValue.of("user@example.com"),
            "plan" to WtsUserValue.of("enterprise"),
            "subscribed" to WtsUserValue.of(true),
        ),
    )

    WtsSdk.shared().updateUser(
        WtsUserUpdate(
            set = mapOf("plan" to WtsUserValue.of("business")),
            setOnce = mapOf("signup_channel" to WtsUserValue.of("partner")),
            increment = mapOf("lifetime_orders" to 1.0),
        ),
    )

    WtsSdk.shared().setReportedAttribution(
        WtsReportedAttribution(
            source = "newsletter",
            medium = "email",
            campaign = "summer_2026",
            externalRef = "mailing-482",
        ),
    )
}
```

Call `resetIdentity()` on logout. It removes the current profile binding, rotates the anonymous/session context and preserves the installation identity used by Install Referrer. Setting profile consent to `DENIED` also queues a binding reset while anonymous analytics remains available. Identity mutations are durable, idempotent and flushed before queued events.

See the installable `sample` app, [security policy](SECURITY.md), and [support policy](SUPPORT.md). Full integration documentation: https://wts.is/en/resources/docs/sdk-android
