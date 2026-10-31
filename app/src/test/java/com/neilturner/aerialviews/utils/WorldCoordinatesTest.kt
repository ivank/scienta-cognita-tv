package com.neilturner.aerialviews.utils

import android.net.Uri
import com.neilturner.aerialviews.models.videos.AerialExifMetadata
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.models.videos.AerialMediaMetadata
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WorldCoordinatesTest {
    @Test
    fun `resolves exact Apple location names`() {
        val sf = WorldCoordinates.find("San Francisco")
        assertNotNull(sf)
        assertEquals(37.7749, sf!!.latitude)
        assertEquals(-122.4194, sf.longitude)

        val london = WorldCoordinates.find("London")
        assertNotNull(london)
        assertEquals(51.5074, london!!.latitude)
        assertEquals(-0.1278, london.longitude)

        val dubai = WorldCoordinates.find("Dubai")
        assertNotNull(dubai)
        assertEquals(25.2048, dubai!!.latitude)
        assertEquals(55.2708, dubai.longitude)
    }

    @Test
    fun `resolves case-insensitively with whitespace`() {
        val ny = WorldCoordinates.find("  new york night  ")
        assertNotNull(ny)
        assertEquals(40.7128, ny!!.latitude)
        assertEquals(-74.0060, ny.longitude)
    }

    @Test
    fun `resolves via keyword fallback`() {
        val gg = WorldCoordinates.find("Flying over the Golden Gate Bridge in foggy afternoon")
        assertNotNull(gg)
        assertEquals(37.8199, gg!!.latitude)
        assertEquals(-122.4783, gg.longitude)
    }

    @Test
    fun `returns null for blank or unknown name`() {
        assertNull(WorldCoordinates.find(null))
        assertNull(WorldCoordinates.find(""))
        assertNull(WorldCoordinates.find("   "))
        assertNull(WorldCoordinates.find("random unknown video xyz"))
    }

    @Test
    fun `prioritizes media EXIF GPS if present`() {
        val media =
            AerialMedia(
                uri = mockk<Uri>(relaxed = true),
                metadata =
                    AerialMediaMetadata(
                        shortDescription = "London",
                        exif = AerialExifMetadata(latitude = 12.34, longitude = 56.78),
                    ),
            )
        val coords = WorldCoordinates.fromMedia(media)
        assertNotNull(coords)
        assertEquals(12.34, coords!!.latitude)
        assertEquals(56.78, coords.longitude)
    }

    @Test
    fun `falls back to description when EXIF GPS is absent`() {
        val media =
            AerialMedia(
                uri = mockk<Uri>(relaxed = true),
                metadata =
                    AerialMediaMetadata(
                        shortDescription = "Hawaii",
                    ),
            )
        val coords = WorldCoordinates.fromMedia(media)
        assertNotNull(coords)
        assertEquals(19.8968, coords!!.latitude)
        assertEquals(-155.5828, coords.longitude)
    }
}
