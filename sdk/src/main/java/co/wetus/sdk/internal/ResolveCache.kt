package co.wetus.sdk.internal

import co.wetus.sdk.WtsDeepLink

internal class ResolveCache(private val capacity: Int = 100) {
    private data class Entry(val value: WtsDeepLink, val expiresAt: Long)
    private val values = object : LinkedHashMap<String, Entry>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > capacity
    }

    @Synchronized fun get(key: String, now: Long): WtsDeepLink? {
        val entry = values[key] ?: return null
        if (entry.expiresAt <= now) { values.remove(key); return null }
        return entry.value
    }

    @Synchronized fun put(key: String, value: WtsDeepLink, expiresAt: Long) {
        values[key] = Entry(value, expiresAt)
    }

    @Synchronized fun clear() = values.clear()
}
