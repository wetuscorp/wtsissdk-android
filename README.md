# wts.is Android SDK

Official SDK for verified Android App Links, deterministic Play Install Referrer deferred deep links, and offline custom event/revenue delivery. It returns validated route data; navigation remains under application control.

> `0.1.0-alpha.1` · protocol V1 · minSdk 23 · compileSdk 36 · Java 17

## Install

```kotlin
dependencies {
    implementation("co.wetus:wts-sdk-android:0.1.0-alpha.1")
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

See the installable `sample` app, [security policy](SECURITY.md), and [support policy](SUPPORT.md). Full integration documentation: https://wts.is/docs/sdk/android
