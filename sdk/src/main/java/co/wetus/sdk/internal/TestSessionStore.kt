package co.wetus.sdk.internal

import android.content.SharedPreferences
import kotlinx.serialization.json.Json

internal interface TestSessionStore {
    fun load(): PersistedTestSession?
    fun save(value: PersistedTestSession): Boolean
    fun clear(): Boolean
}

internal class PreferencesTestSessionStore(
    private val preferences: SharedPreferences,
    private val json: Json,
) : TestSessionStore {
    override fun load(): PersistedTestSession? = preferences.getString(KEY, null)?.let { raw ->
        runCatching { json.decodeFromString(PersistedTestSession.serializer(), raw) }.getOrNull()
    }

    override fun save(value: PersistedTestSession): Boolean = preferences.edit()
        .putString(KEY, json.encodeToString(PersistedTestSession.serializer(), value))
        .commit()

    override fun clear(): Boolean = preferences.edit().remove(KEY).commit()

    private companion object { const val KEY = "sdk-test-session-v1" }
}
