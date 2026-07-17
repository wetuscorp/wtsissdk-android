# wts.is Android SDK

Official SDK for verified Android App Links, deterministic Play Install Referrer deferred deep links, and offline custom event/revenue delivery. It returns validated route data; navigation remains under application control.

> `0.3.0-alpha.1` · Mobile Protocol V3 + Identity V1 + Experiences V1 · minSdk 23 · compileSdk 36 · Java 17

## Install

```kotlin
dependencies {
    implementation("co.wetus:wts-sdk:0.3.0-alpha.1")
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

Use `PERSONALIZED` only after profile consent. `PENDING` makes no Experience
request; `DENIED` clears local Experience state and unsent interactions.
Automatic mode uses native modal or bottom-sheet presentation. Manual mode
notifies `onExperienceAvailable` and waits for
`presentNextExperience()`. Interactions use a private persistent bounded queue,
UUID idempotency and retry. Impressions require one uninterrupted second of
native visibility.

For an unpublished device test, copy
`WtsSdk.shared().getExperienceDiagnostics().testDeviceToken` into the
dashboard test panel for the same Mobile App. The random token contains no
install, user, or profile identifier. Test traffic does not affect customer
analytics or impression usage.

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
