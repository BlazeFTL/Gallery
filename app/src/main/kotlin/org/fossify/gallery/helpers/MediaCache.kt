package org.fossify.gallery.helpers

import org.fossify.gallery.models.ThumbnailItem

/**
 * Keeps the grouped media list of the last opened albums in memory, so reopening an album paints at once
 * while the fresh scan runs. The signature holds every setting that changes the list.
 */
object MediaCache {
    private const val MAX_ALBUMS = 8

    private class Entry(val signature: String, val media: ArrayList<ThumbnailItem>)

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > MAX_ALBUMS
    }

    @Synchronized
    fun get(key: String, signature: String): ArrayList<ThumbnailItem>? {
        val entry = entries[key] ?: return null
        if (entry.signature != signature || entry.media.isEmpty()) {
            return null
        }
        return ArrayList(entry.media)
    }

    @Synchronized
    fun put(key: String, signature: String, media: List<ThumbnailItem>) {
        if (media.isEmpty()) {
            entries.remove(key)
            return
        }
        entries[key] = Entry(signature, ArrayList(media))
    }

    @Synchronized
    fun remove(key: String) {
        entries.remove(key)
    }
}
