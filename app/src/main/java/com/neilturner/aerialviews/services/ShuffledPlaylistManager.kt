package com.neilturner.aerialviews.services

import android.content.Context
import com.neilturner.aerialviews.models.videos.AerialMedia
import timber.log.Timber

/**
 * Manages persistent shuffled playlist order and resume position.
 *
 * Rules:
 * 1. Shuffles the playlist once and persists the exact sequence to disk.
 * 2. Remembers the current position across app launches, sleep, and reboots so playback continues.
 * 3. Once all items in the playlist have been played through, reshuffles into a fresh random order
 *    and starts over from index 0.
 */
object ShuffledPlaylistManager {
    private const val PREFS_NAME = "shuffled_playlist_store"
    private const val KEY_ORDER = "shuffled_order_uris"
    private const val KEY_POSITION = "shuffled_current_position"

    /**
     * Retrieves the persisted shuffled playlist order and resume position.
     * If no valid shuffle order exists, or if the media set has changed, generates
     * a brand new random shuffle ONCE and persists it.
     */
    fun getOrGenerateShuffledList(
        context: Context,
        items: List<AerialMedia>,
        shuffleFn: (List<AerialMedia>) -> List<AerialMedia> = { it.shuffled() },
    ): Pair<List<AerialMedia>, Int> {
        if (items.isEmpty()) return Pair(emptyList(), 0)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedOrderStr = prefs.getString(KEY_ORDER, null)
        val savedPosition = prefs.getInt(KEY_POSITION, -1)

        if (!savedOrderStr.isNullOrEmpty()) {
            try {
                val savedUris = savedOrderStr.lines().filter { it.isNotBlank() }

                if (savedUris.size == items.size) {
                    val itemsByUri = mutableMapOf<String, MutableList<AerialMedia>>()
                    for (item in items) {
                        itemsByUri.getOrPut(item.uri.toString()) { mutableListOf() }.add(item)
                    }

                    val orderedList = mutableListOf<AerialMedia>()
                    for (uri in savedUris) {
                        val matchingList = itemsByUri[uri]
                        if (!matchingList.isNullOrEmpty()) {
                            orderedList.add(matchingList.removeAt(0))
                        }
                    }

                    if (orderedList.size == items.size) {
                        // Advance to the next item so we continue where we left off
                        val nextPosition = if (savedPosition in 0 until items.size - 1) {
                            savedPosition + 1
                        } else if (savedPosition >= items.size - 1) {
                            // All items played through previously -> reshuffle before starting over!
                            Timber.i("ShuffledPlaylistManager: Saved position at end ($savedPosition). Reshuffling before start.")
                            val reshuffled = shuffleFn(items)
                            saveShuffledOrder(context, reshuffled, 0)
                            return Pair(reshuffled, 0)
                        } else {
                            0
                        }

                        Timber.i(
                            "ShuffledPlaylistManager: Restored persisted shuffled order (${orderedList.size} items), " +
                                "resuming at position $nextPosition (last saved: $savedPosition)",
                        )
                        return Pair(orderedList, nextPosition)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "ShuffledPlaylistManager: Failed to parse saved playlist order")
            }
        }

        // Generate brand new shuffle ONCE
        Timber.i("ShuffledPlaylistManager: Generating fresh initial shuffle for ${items.size} items")
        val shuffled = shuffleFn(items)
        saveShuffledOrder(context, shuffled, 0)
        return Pair(shuffled, 0)
    }

    fun savePosition(context: Context, position: Int) {
        if (position < 0) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_POSITION, position).apply()
        Timber.d("ShuffledPlaylistManager: Saved position $position")
    }

    /**
     * Called when all items in the playlist have been played through.
     * Reshuffles the entire list into a fresh random order, resets position to 0, and saves to disk.
     */
    fun reshuffleAndRestart(
        context: Context,
        items: List<AerialMedia>,
        shuffleFn: (List<AerialMedia>) -> List<AerialMedia> = { it.shuffled() },
    ): List<AerialMedia> {
        Timber.i("ShuffledPlaylistManager: All ${items.size} items played through! Reshuffling before starting over.")
        val shuffled = shuffleFn(items)
        saveShuffledOrder(context, shuffled, 0)
        return shuffled
    }

    private fun saveShuffledOrder(
        context: Context,
        items: List<AerialMedia>,
        position: Int,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val serialized = items.joinToString("\n") { it.uri.toString() }
        prefs.edit()
            .putString(KEY_ORDER, serialized)
            .putInt(KEY_POSITION, position)
            .apply()
    }
}
