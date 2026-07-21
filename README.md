# wts.is Android SDK

Official Android SDK for verified App Links, deterministic Play Install Referrer attribution, analytics, identity, and deployless Experiences.

> `0.5.0-alpha.1` · Mobile Protocol V4 · Experiences Protocol V2 · SDK Test Session V2 · minSdk 23 · compileSdk 36 · Java 17

## Install

Pin the alpha exactly:

```kotlin
dependencies {
    implementation("co.wetus:wts-sdk:0.5.0-alpha.1")
}
```

## One-time integration

Configure once with the public app/source key. Consent UI belongs to the host application; pass the user decision once and reuse the persisted state on later launches.

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        WtsSdk.configure(this, "YOUR_PUBLIC_APP_KEY")
    }
}

val sdk = WtsSdk.shared()
when (sdk.getConsentState()) {
    WtsConsentState.PENDING -> showConsentUi()
    WtsConsentState.GRANTED -> Unit
    WtsConsentState.DENIED -> Unit
}

lifecycleScope.launch {
    sdk.setConsent(WtsConsentState.GRANTED) // or DENIED
}
```

`PENDING` and `DENIED` disable event, identity, attribution, Experience, and test-session storage/network. The only exception is a data-minimized direct-link resolve. `DENIED` also clears local SDK data and closes an active Experience. Grant restores the queues, identity binding, signed config refresh, and deferred attribution automatically.

The `0.5` namespace starts with `PENDING`; `0.4` state is cleared and is not migrated.

## Existing events drive Experiences

Keep using the event APIs already integrated in the application. Campaigns select these panel-defined events and need no campaign key or client deploy.

```kotlin
lifecycleScope.launch {
    sdk.track(
        eventKey = "purchase_completed",
        properties = mapOf("plan" to WtsValue.of("pro")),
        revenue = WtsRevenue("49.90", "TRY"),
    )

    sdk.screen(
        name = "checkout",
        properties = mapOf("item_count" to WtsValue.of(3)),
    )
}
```

After grant, the SDK refreshes a source-bound signed manifest in the foreground every 60 seconds and on stale events. Refresh is single-flight and conditional. A cached contextual manifest is accepted offline only until its signed expiry. Matching modal and bottom-sheet campaigns are priority-queued and rendered automatically.

No Experience verification key, allowlist, renderer mode, manual presentation handle, or acknowledgement API is configured by the host.

Advanced internal-route and custom-callback actions may use one optional handler:

```kotlin
sdk.onExperienceAction { experience, action ->
    when (action.type) {
        WtsExperienceActionType.OPEN_INTERNAL_ROUTE -> {
            router.open(action.target)
            true
        }
        WtsExperienceActionType.CUSTOM_CALLBACK -> callbacks.run(action.target)
        else -> false
    }
}
```

Return `true` only when handled. A missing, failing, or `false` handler records `unhandled` and keeps the Experience open. Web actions accept HTTPS only; deep links accept HTTPS or a safe custom scheme. Unsafe schemes such as `http`, `javascript`, `data`, `file`, and `about` are rejected.

Use `getExperienceDiagnostics()` for support and operational diagnostics. Delivery can be disabled server-side without disabling analytics or functional direct-link resolution.

## App Links and deferred links

```kotlin
lifecycleScope.launch {
    val link = sdk.handle(intent.data!!)
    if (link.path in allowedRoutes) router.navigate(link.path, link.parameters)

    sdk.getDeferredDeepLink()?.let { deferred ->
        if (deferred.path in allowedRoutes) {
            router.navigate(deferred.path, deferred.parameters)
        }
    }
}
```

Before grant, `handle` performs functional resolution without creating an install identity or attribution event. Normal attribution and Play Install Referrer deferred resolution begin only after grant. Add an `android:autoVerify="true"` HTTPS intent filter for the exact dashboard host and register every release signing SHA-256 fingerprint.

## Identity

Unified consent also controls identity and profile operations:

```kotlin
lifecycleScope.launch {
    sdk.identify(
        externalUserId = "customer_1842",
        attributes = mapOf("plan" to WtsUserValue.of("enterprise")),
    )
}
```

After the identity binding is accepted, Experience decisions automatically move from contextual to personalized mode. Call `resetIdentity()` on logout.

## SDK Test Session V2

Join a dashboard-issued pairing URL before normal link handling and only after grant:

```kotlin
if (uri.scheme == "https" && uri.path == "/_wts/test/pair") {
    sdk.joinTestSession(WtsTestSessionPairing.from(uri.toString()))
    sdk.runTestSessionProbes()
    return
}
```

Test Experiences use the automatic renderer in an isolated test queue and never enter the production campaign queue. Pairing credentials are short-lived and must not be logged or committed.

See the installable `sample`, [security policy](SECURITY.md), and [support policy](SUPPORT.md). Full documentation: https://wts.is/en/resources/docs/sdk-android
