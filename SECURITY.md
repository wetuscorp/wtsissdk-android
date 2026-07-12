# Security

Supported security fixes are released for the latest alpha and latest stable minor. Report vulnerabilities privately to `security@wetus.co`; do not open a public issue. Include the affected version, impact, and a minimal reproduction. We acknowledge reports within three business days.

The public app key is an identifier, not a secret. Never put server credentials in an application. This SDK does not access IDFA, GAID, or probabilistic fingerprints. Install identity remains in private SharedPreferences and sensitive values are redacted from logs.
