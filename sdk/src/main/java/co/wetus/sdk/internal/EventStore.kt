package co.wetus.sdk.internal

import android.content.SharedPreferences
import kotlinx.serialization.json.Json

internal interface EventStore {
    fun load(): List<EventRequest>
    fun save(events: List<EventRequest>): Boolean
}

internal class PreferencesEventStore(
    private val preferences: SharedPreferences,
    private val json: Json,
) : EventStore {
    override fun load(): List<EventRequest> {
        val raw = preferences.getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString(EventBatch.serializer(), raw).events }
            .getOrElse {
                preferences.edit().remove(KEY).commit()
                emptyList()
            }
    }

    override fun save(events: List<EventRequest>): Boolean = if (events.isEmpty()) {
        preferences.edit().remove(KEY).commit()
    } else {
        preferences.edit()
            .putString(KEY, json.encodeToString(EventBatch.serializer(), EventBatch(events = events)))
            .commit()
    }

    private companion object { const val KEY = "event-queue-v1" }
}
