# Changelog

## 0.2.0-alpha.1

- Upgraded event delivery to Mobile Protocol V2 while preserving App Link and Install Referrer behavior.
- Added consent-gated Identity V1 operations: `identify`, `updateUser`, `setReportedAttribution`, and `resetIdentity`.
- Added persistent, idempotent identity mutations that flush before queued events.
- Preserved the installation UUID across logout while rotating profile and session identity.
- Reset the server-side profile binding when profile consent is denied.
- Preserved opaque external user IDs and retried retryable batch rejections.
- Added stable public error codes for native and cross-platform callers.

## 0.1.0-alpha.1

- Initial public alpha with App Link resolution, Play Install Referrer deferred deep links, typed errors, persistent event/revenue queue, bounded cache and explicit flush.
