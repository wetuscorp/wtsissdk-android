package co.wetus.sdk.sample

/**
 * Replace [appKey] before using this sample against a real wts.is application.
 * The checked-in value keeps the sample installable without generating network
 * traffic or treating a placeholder credential as a production configuration.
 */
object SampleConfiguration {
    const val appKey = "replace-with-public-app-key"

    val isConfigured: Boolean
        get() = appKey != "replace-with-public-app-key"
}
