# wts.is Android sample

The sample demonstrates normal App Link handling. When adding SDK Test &
Validate, keep the production path unchanged and route a dashboard-issued QR
pairing URL through `joinTestSession` **before** `handle`:

```kotlin
if (uri.scheme == "https" && uri.path == "/_wts/test/pair") {
    val result = WtsSdk.shared().joinTestSession(
        WtsTestSessionPairing.from(uri.toString()),
    )
    showSdkTestChecks(result.checks)
    return
}

val link = WtsSdk.shared().handle(uri)
```

The canonical pairing QR has the form
`https://<mobile-app-host>/_wts/test/pair?pairing=<dashboard-issued-token>`.
Use the dashboard-issued token only for the short-lived session; do not place it
in source control, analytics, or logs.

After joining, inspect `getTestSessionDiagnostics()`, run
`runTestSessionProbes()`, and use `probeTestSessionUrl()` for a resolver-only
check. If the probe result contains a ready test Experience decision, show it
only in a test preview and then call
`reportTestSessionExperienceInteraction(IMPRESSION)` or `ACTION` after the
corresponding real test interaction. Call `leaveTestSession()` when done.

These APIs are part of the `0.4.0-alpha.1` source line; use them only after the
matching Android package release has been published.
