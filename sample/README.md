# wts.is Android sample

The sample follows the `0.5.0-alpha.1` integration: configure once, persist a unified consent decision, and continue sending existing events. Experiences are selected remotely and rendered automatically; the host does not add campaign keys.

For SDK Test Session V2, route a dashboard pairing URL through `joinTestSession` before normal `handle` processing:

```kotlin
if (uri.scheme == "https" && uri.path == "/_wts/test/pair") {
    WtsSdk.shared().joinTestSession(WtsTestSessionPairing.from(uri.toString()))
    WtsSdk.shared().runTestSessionProbes()
    return
}

val link = WtsSdk.shared().handle(uri)
```

Pairing and probes require granted consent. Test Experiences render automatically in an isolated queue. Do not put the short-lived pairing token in source control, analytics, or logs.
