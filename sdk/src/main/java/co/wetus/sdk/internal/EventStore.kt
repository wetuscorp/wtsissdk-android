package co.wetus.sdk.internal

import android.content.SharedPreferences
import kotlinx.serialization.json.Json

internal interface EventStore {
    fun load(): List<EventRequest>
    fun save(events: List<EventRequest>): Boolean
}

internal interface IdentityMutationStore {
    fun load(): List<IdentityMutationRequest>
    fun save(mutations: List<IdentityMutationRequest>): Boolean
}

internal interface ExperienceInteractionStore {
    fun load(): List<ExperienceInteractionRequest>
    fun save(interactions: List<ExperienceInteractionRequest>): Boolean
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

    private companion object { const val KEY = "event-queue-v2" }
}

internal class PreferencesIdentityMutationStore(
    private val preferences: SharedPreferences,
    private val json: Json,
) : IdentityMutationStore {
    override fun load(): List<IdentityMutationRequest> {
        val raw = preferences.getString(KEY, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(IdentityMutationBatch.serializer(), raw).mutations
        }.getOrElse {
            preferences.edit().remove(KEY).commit()
            emptyList()
        }
    }

    override fun save(mutations: List<IdentityMutationRequest>): Boolean =
        if (mutations.isEmpty()) {
            preferences.edit().remove(KEY).commit()
        } else {
            preferences.edit()
                .putString(
                    KEY,
                    json.encodeToString(
                        IdentityMutationBatch.serializer(),
                        IdentityMutationBatch(mutations = mutations),
                    ),
                )
                .commit()
        }

    private companion object { const val KEY = "identity-queue-v2" }
}

internal class PreferencesExperienceInteractionStore(
    private val preferences: SharedPreferences,
    private val json: Json,
) : ExperienceInteractionStore {
    override fun load(): List<ExperienceInteractionRequest> {
        val raw = preferences.getString(KEY, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(
                ExperienceInteractionQueue.serializer(),
                raw,
            ).interactions
        }.getOrElse {
            preferences.edit().remove(KEY).commit()
            emptyList()
        }
    }

    override fun save(interactions: List<ExperienceInteractionRequest>): Boolean =
        if (interactions.isEmpty()) {
            preferences.edit().remove(KEY).commit()
        } else {
            preferences.edit()
                .putString(
                    KEY,
                    json.encodeToString(
                        ExperienceInteractionQueue.serializer(),
                        ExperienceInteractionQueue(
                            interactions = interactions,
                        ),
                    ),
                )
                .commit()
        }

    private companion object { const val KEY = "experience-interaction-queue-v2" }
}
