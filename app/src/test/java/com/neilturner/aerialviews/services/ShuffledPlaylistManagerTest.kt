package com.neilturner.aerialviews.services

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.neilturner.aerialviews.models.MediaPlaylist
import com.neilturner.aerialviews.models.videos.AerialMedia
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class ShuffledPlaylistManagerTest {
    private val memoryStorage = mutableMapOf<String, Any>()
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val sharedPrefs = mockk<SharedPreferences>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    private fun mockUri(path: String): Uri {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.toString() } returns path
        return uri
    }

    @BeforeEach
    fun setUp() {
        memoryStorage.clear()

        every { editor.putString(any(), any()) } answers {
            memoryStorage[firstArg()] = secondArg()
            editor
        }
        every { editor.putInt(any(), any()) } answers {
            memoryStorage[firstArg()] = secondArg()
            editor
        }
        every { editor.apply() } answers { }

        every { sharedPrefs.getString(any(), any()) } answers {
            (memoryStorage[firstArg()] as? String) ?: secondArg()
        }
        every { sharedPrefs.getInt(any(), any()) } answers {
            (memoryStorage[firstArg()] as? Int) ?: secondArg()
        }
        every { sharedPrefs.edit() } returns editor

        every { context.getSharedPreferences(any(), any()) } returns sharedPrefs
    }

    @Test
    fun `getOrGenerateShuffledList returns empty pair for empty list`() {
        val (list, pos) = ShuffledPlaylistManager.getOrGenerateShuffledList(context, emptyList())
        assertEquals(0, list.size)
        assertEquals(0, pos)
    }

    @Test
    fun `getOrGenerateShuffledList shuffles once and persists order`() {
        val items = (1..5).map { AerialMedia(uri = mockUri("https://test.com/item$it")) }

        val (firstRunList, firstRunPos) = ShuffledPlaylistManager.getOrGenerateShuffledList(context, items)
        assertEquals(5, firstRunList.size)
        assertEquals(0, firstRunPos)

        // Simulate watching items up to index 2
        ShuffledPlaylistManager.savePosition(context, 2)

        // Next run: should restore EXACT same shuffled order and advance to index 3
        val (secondRunList, secondRunPos) = ShuffledPlaylistManager.getOrGenerateShuffledList(context, items)
        assertEquals(firstRunList.map { it.uri.toString() }, secondRunList.map { it.uri.toString() })
        assertEquals(3, secondRunPos)
    }

    @Test
    fun `getOrGenerateShuffledList reshuffles when previous position reached the end`() {
        val items = (1..5).map { AerialMedia(uri = mockUri("https://test.com/item$it")) }

        val (firstRunList, _) = ShuffledPlaylistManager.getOrGenerateShuffledList(context, items)

        // Simulate reaching the last item (index 4 out of 5)
        ShuffledPlaylistManager.savePosition(context, 4)

        // Reshuffle occurs because all items were played through
        val (reshuffledList, nextPos) = ShuffledPlaylistManager.getOrGenerateShuffledList(context, items)
        assertEquals(5, reshuffledList.size)
        assertEquals(0, nextPos)
    }

    @Test
    fun `MediaPlaylist triggers onReshuffle when playing past last item`() {
        val items = (1..3).map { AerialMedia(uri = mockUri("https://test.com/item$it")) }
        var reshuffleCalled = false

        val playlist = MediaPlaylist(
            initialVideos = items,
            startPosition = 1, // Currently on item 1 (nextItem will play item 2 = index 2)
            onPositionChanged = {},
            onReshuffle = {
                reshuffleCalled = true
                items.reversed()
            },
        )

        // Advance to item 2 (index 2 - last item)
        val item2 = playlist.nextItem()
        assertEquals(items[2], item2)
        assertEquals(false, reshuffleCalled)

        // Advance past end -> should trigger reshuffle and return index 0 of reshuffled list
        val item0Reshuffled = playlist.nextItem()
        assertEquals(true, reshuffleCalled)
        assertEquals(items[2], item0Reshuffled) // Since reversed, items[2] is now at index 0
    }
}
