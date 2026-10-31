package com.neilturner.aerialviews.providers

import android.content.Context
import androidx.core.net.toUri
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.data.network.JsonHelper.parseJson
import com.neilturner.aerialviews.data.network.JsonHelper.parseJsonMap
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.enums.AerialMediaType
import com.neilturner.aerialviews.models.enums.ProviderSourceType
import com.neilturner.aerialviews.models.enums.SceneType
import com.neilturner.aerialviews.models.enums.TimeOfDay
import com.neilturner.aerialviews.models.prefs.ProviderPreferences
import com.neilturner.aerialviews.models.videos.AerialExifMetadata
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.models.videos.AerialMediaMetadata
import com.neilturner.aerialviews.models.videos.Comm1Videos
import com.neilturner.aerialviews.utils.filenameWithoutExtension
import timber.log.Timber

class Comm1MediaProvider(
    context: Context,
    private val prefs: ProviderPreferences,
) : MediaProvider(context) {
    override val type = ProviderSourceType.REMOTE
    val metadata = mutableMapOf<String, Pair<String, Map<Int, String>>>()
    val videos = mutableListOf<AerialMedia>()

    override val enabled: Boolean
        get() = prefs.enabled

    override fun settingsHash(): String = prefs.settingsHash()

    override suspend fun fetch(): ProviderFetchResult {
        if (metadata.isEmpty()) buildVideoAndMetadata()
        return ProviderFetchResult.Success(media = videos, summary = "")
    }

    override suspend fun fetchMetadata(media: List<AerialMedia>): List<AerialMedia> {
        if (metadata.isEmpty()) buildVideoAndMetadata()
        val metadataMap = metadata
        return media.map { item ->
            val data = metadataMap[item.uri.filenameWithoutExtension.lowercase()]
            if (data != null) {
                item.metadata.shortDescription = data.first
                item.metadata.pointsOfInterest = data.second
            }
            item
        }
    }

    private suspend fun buildVideoAndMetadata() {
        val quality = prefs.quality
        val strings = parseJsonMap(context, R.raw.comm1_strings)
        val wrapper = parseJson<Comm1Videos>(context, R.raw.comm1)

        wrapper.photos?.forEach { photo ->
            if (prefs.enabled) {
                val detailed = photo.details.ifBlank { photo.description }
                val hasVideo = !photo.video1080.isNullOrBlank()
                val mediaUri = if (hasVideo) photo.video1080.orEmpty().toUri() else photo.uriAtQuality(quality)
                videos.add(
                    AerialMedia(
                        mediaUri,
                        type = if (hasVideo) AerialMediaType.VIDEO else AerialMediaType.IMAGE,
                        source = AerialMediaSource.COMM1,
                        metadata =
                            AerialMediaMetadata(
                                shortDescription = photo.title,
                                longDescription = detailed,
                                category = photo.category,
                                exif = AerialExifMetadata(description = photo.title),
                            ),
                    ),
                )
            }
        }

        wrapper.assets?.forEach { asset ->
            val timeOfDay = TimeOfDay.fromString(asset.timeOfDay)
            val scene = SceneType.fromString(asset.scene)

            val timeOfDayMatches = prefs.timeOfDay.contains(timeOfDay.toString())
            val sceneMatches = prefs.scene.contains(scene.toString())

            if (timeOfDayMatches && sceneMatches && prefs.enabled) {
                videos.add(
                    AerialMedia(
                        asset.uriAtQuality(quality),
                        type = AerialMediaType.VIDEO,
                        source = AerialMediaSource.COMM1,
                        metadata =
                            AerialMediaMetadata(
                                timeOfDay = timeOfDay,
                                scene = scene,
                                shortDescription = asset.description,
                                longDescription = asset.details,
                            ),
                    ),
                )
            }

            val data =
                Pair(
                    asset.description,
                    asset.pointsOfInterest.mapValues { poi ->
                        strings[poi.value] ?: asset.description
                    },
                )
            asset.allUrls().forEachIndexed { index, url ->
                metadata[url] = data
            }
        }

        Timber.i("${metadata.count()} metadata items found")
        Timber.i("${videos.count()} $quality videos found")
    }
}
