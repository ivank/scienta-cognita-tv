package com.neilturner.aerialviews.ui.core

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import coil3.ImageLoader
import coil3.asDrawable
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.common.SMBRuntimeException
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.enums.AspectRatio
import com.neilturner.aerialviews.models.enums.PhotoScale
import com.neilturner.aerialviews.models.enums.ProgressBarLocation
import com.neilturner.aerialviews.models.enums.ProgressBarType
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.services.InputStreamFetcher
import com.neilturner.aerialviews.ui.controls.ProgressBarEvent
import com.neilturner.aerialviews.ui.controls.ProgressState
import com.neilturner.aerialviews.ui.core.ImagePlayerHelper.buildGifDecoder
import com.neilturner.aerialviews.ui.core.ImagePlayerHelper.buildOkHttpClient
import com.neilturner.aerialviews.ui.helpers.BitmapHelper
import com.neilturner.aerialviews.ui.views.ArtLightingManager
import com.neilturner.aerialviews.ui.views.GalleryFrameView
import com.neilturner.aerialviews.ui.views.GallerySpotlightOverlay
import com.neilturner.aerialviews.ui.views.SculptureBacklightView
import com.neilturner.aerialviews.utils.FirebaseHelper
import com.neilturner.aerialviews.utils.filename
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.kosert.flowbus.GlobalBus
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import kotlin.time.Duration.Companion.milliseconds

class ImagePlayerView : FrameLayout {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    private var listener: OnImagePlayerEventListener? = null
    var isLoopingImage: Boolean = false

    fun setLooping(looping: Boolean) {
        isLoopingImage = looping
    }

    private val finishedRunnable: Runnable =
        Runnable {
            if (isLoopingImage) {
                val duration = (GeneralPrefs.slideshowSpeed.toIntOrNull() ?: 30) * 1000L
                val durationMinusFade = maxOf(1000L, duration - 1500L)
                startKenBurns(duration)
                postDelayed(finishedRunnable, durationMinusFade)
            } else {
                listener?.onImageFinished()
            }
        }
    private var errorRunnable = Runnable { listener?.onImageError() }

    private val ioJob = SupervisorJob()
    private val mainJob = SupervisorJob()
    private val ioScope = CoroutineScope(Dispatchers.IO + ioJob)
    private val mainScope = CoroutineScope(Dispatchers.Main + mainJob)

    private var pausedTimestamp: Long = 0
    private var totalDuration: Long = 0
    private var remainingDuration: Long = 0
    private var kenBurnsAnimator: ValueAnimator? = null
    private var naturalFocalY: Float = 0.5f
    private var currentImageWidth: Int = 0
    private var currentImageHeight: Int = 0
    private val baseMatrix = Matrix()
    private val animMatrix = Matrix()

    private enum class DisplayMode {
        STANDARD,
        GALLERY,
        SCULPTURE,
    }

    private var currentDisplayMode: DisplayMode = DisplayMode.STANDARD
    private var currentMedia: AerialMedia? = null
    private var currentDrawable: Drawable? = null
    private var isOverlayOnRight: Boolean = true

    fun setOverlayOnRight(onRight: Boolean) {
        if (isOverlayOnRight != onRight) {
            isOverlayOnRight = onRight
            if (currentDisplayMode == DisplayMode.SCULPTURE) {
                updateSculpturePosition()
            }
        }
    }

    private var backgroundReadyToken: Long = -1
    private var pendingSetupToken: Long = -1

    // Background blurred photo
    private val backgroundImageView =
        AppCompatImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = GONE
        }

    // Ambient dark scrim over blurred background for sculpture pop
    private val backgroundDimOverlay =
        View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#4D000000")) // 30% black dim
            visibility = GONE
        }

    // Ambient directional backlight & occlusion shadow behind sculpture
    private val sculptureBacklightView =
        SculptureBacklightView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            visibility = GONE
        }

    // Foreground image view for standard photos and sculptures
    private val foregroundImageView =
        AppCompatImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

    // Museum gallery background wall
    private val galleryBackgroundImageView =
        AppCompatImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = GONE
        }

    // Dynamic museum ceiling track spotlight on the gallery wall
    private val gallerySpotlightView =
        GallerySpotlightOverlay(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            visibility = GONE
        }

    // Museum picture frame for portrait paintings
    private val galleryFrameView =
        GalleryFrameView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            visibility = GONE
        }

    // Stationary foreground ceiling with museum lighting fixtures (sits in front of wall and artwork)
    private val galleryCeilingForegroundView =
        ImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP)
            scaleType = ImageView.ScaleType.FIT_XY
            adjustViewBounds = true
            visibility = GONE
        }

    private val progressBar =
        GeneralPrefs.progressBarLocation != ProgressBarLocation.DISABLED &&
            GeneralPrefs.progressBarType != ProgressBarType.VIDEOS

    private val blurHelper =
        BackgroundBlurHelper(
            backgroundImageView = backgroundImageView,
            ioScope = ioScope,
            mainScope = mainScope,
            resolveTargetSize = ::resolveTargetSize,
            onReady = ::markBackgroundReady,
        )

    init {
        addView(backgroundImageView)
        addView(backgroundDimOverlay)
        addView(sculptureBacklightView)
        addView(foregroundImageView)
        addView(galleryBackgroundImageView)
        addView(galleryFrameView)
        addView(gallerySpotlightView)
        addView(galleryCeilingForegroundView)
    }

    fun release() {
        stopKenBurns()
        ioJob.cancel()
        mainJob.cancel()
        removeCallbacks(finishedRunnable)
        removeCallbacks(errorRunnable)
        listener = null
    }

    private val imageLoader =
        ImageLoader
            .Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(ImagePlayerHelper.buildOkHttpClient(context = context)))
                add(InputStreamFetcher.Factory())
                add(buildGifDecoder())
            }.build()

    fun prefetch(media: AerialMedia) {
        ioScope.launch {
            try {
                val targetUri = stripCredentialsForCoil(media)
                val request =
                    ImageRequest
                        .Builder(context)
                        .data(targetUri)
                        .build()
                imageLoader.enqueue(request)
            } catch (e: Exception) {
                Timber.d(e, "Prefetch failed: ${e.message}")
            }
        }
    }

    fun setImage(media: AerialMedia) {
        currentMedia = media
        ioScope.launch {
            val baseStream = ImagePlayerHelper.streamFromMedia(context, media)
            if (baseStream == null) {
                loadImage(media, stripCredentialsForCoil(media))
                return@launch
            }

            if (
                (media.source == AerialMediaSource.IMMICH) ||
                (media.source == AerialMediaSource.NCMEMORIES)
            ) {
                loadImage(media, baseStream)
                return@launch
            }

            val totalStartTime = System.currentTimeMillis()
            val stream =
                PushbackInputStream(
                    BufferedInputStream(baseStream, STREAM_BUFFER_SIZE),
                    BitmapHelper.HEADER_BUFFER_SIZE,
                )

            try {
                val headerBytes = ByteArray(BitmapHelper.HEADER_BUFFER_SIZE)
                val headerLength = readUpTo(stream, headerBytes, headerBytes.size)
                if (headerLength <= 0) {
                    stream.close()
                    loadImage(media, stripCredentialsForCoil(media))
                    return@launch
                }
                stream.unread(headerBytes, 0, headerLength)

                val exifMetadata = BitmapHelper.extractExifMetadataFromHeader(headerBytes, headerLength)

                if (media.source != AerialMediaSource.IMMICH) {
                    media.metadata.exif.date = exifMetadata.date
                    media.metadata.exif.offset = exifMetadata.offset
                    media.metadata.exif.latitude = exifMetadata.latitude
                    media.metadata.exif.longitude = exifMetadata.longitude
                    media.metadata.exif.description = exifMetadata.description
                }

                Timber.d(
                    "ImagePlayerView: Extracted EXIF in ${System.currentTimeMillis() - totalStartTime}ms...",
                )

                loadImage(media, stream)
            } catch (e: TransportException) {
                Timber.e(e, "SMB transport dropped while reading image header")
                stream.close()
                onPlayerError()
            } catch (e: SMBRuntimeException) {
                Timber.e(e, "SMB runtime error while reading image header")
                stream.close()
                onPlayerError()
            } catch (e: Exception) {
                Timber.e(e, "Unexpected error reading image stream")
                stream.close()
                onPlayerError()
            }
        }
    }

    private fun loadImage(
        media: AerialMedia,
        data: Any?,
    ) {
        try {
            val (targetWidth, targetHeight) = resolveTargetSize()
            val maxDim = maxOf(targetWidth, targetHeight)
            val request =
                ImageRequest
                    .Builder(context)
                    .data(data)
                    .size(maxDim, maxDim)
                    .allowHardware(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || !GeneralPrefs.photoBackgroundBlurEnabled)
                    .target(
                        onStart = {},
                        onSuccess = { image ->
                            currentDrawable = image.asDrawable(resources)
                        },
                    ).listener(
                        onSuccess = { _, result ->
                            val drawable = currentDrawable ?: result.image.asDrawable(resources)
                            setupDisplay(drawable, result.image.width, result.image.height)
                            setupFinishedRunnable()
                        },
                        onError = { _, result ->
                            handleImageError(result.throwable)
                        },
                    ).build()
            imageLoader.enqueue(request)
        } catch (ex: Exception) {
            Timber.e(ex, "Exception while trying to load image: ${ex.message}")

            if (data is InputStream) {
                try {
                    data.close()
                } catch (_: Exception) {
                    // ignore
                }
            }

            listener?.onImageError()
        }
    }

    private fun setupDisplay(
        drawable: Drawable,
        width: Int,
        height: Int,
    ) {
        currentImageWidth = width
        currentImageHeight = height
        val isPortrait = height > width && (height.toFloat() / width.toFloat()) >= 1.05f

        if (isSculpture(currentMedia) || (isPortrait && isArchitecture(currentMedia))) {
            if (isPortrait) {
                currentDisplayMode = DisplayMode.SCULPTURE
                setupSculptureMode(drawable, width, height)
            } else {
                currentDisplayMode = DisplayMode.STANDARD
                setupStandardMode(drawable, width, height)
            }
        } else if (isPainting(currentMedia)) {
            currentDisplayMode = DisplayMode.GALLERY
            setupGalleryMode(drawable, width, height)
        } else {
            currentDisplayMode = DisplayMode.STANDARD
            setupStandardMode(drawable, width, height)
        }
    }

    private fun setupGalleryMode(
        drawable: Drawable,
        width: Int,
        height: Int,
    ) {
        foregroundImageView.visibility = GONE
        backgroundDimOverlay.visibility = GONE
        sculptureBacklightView.visibility = GONE
        blurHelper.cancel()

        val text = ((currentMedia?.metadata?.shortDescription ?: "") + " " + (currentMedia?.metadata?.longDescription ?: "")).lowercase()
        val isClassical =
            listOf(
                "mona lisa", "pearl earring", "las meninas", "creation of adam",
                "birth of venus", "night watch", "fresco", "frescoes", "botticelli",
                "velázquez", "vermeer", "da vinci", "michelangelo", "rembrandt",
                "qianlong", "court ladies", "immortal", "mughal", "persian",
            ).any { text.contains(it) }

        galleryBackgroundImageView.setImageResource(
            if (isClassical) R.drawable.bg_gallery_classical else R.drawable.bg_gallery_modern,
        )
        galleryBackgroundImageView.visibility = VISIBLE

        // Stationary foreground ceiling with museum track lights
        galleryCeilingForegroundView.setImageResource(
            if (isClassical) R.drawable.fg_gallery_ceiling_classical else R.drawable.fg_gallery_ceiling_modern,
        )
        galleryCeilingForegroundView.visibility = VISIBLE
        galleryCeilingForegroundView.translationY = 0f

        // Dynamic lighting profile
        val lightingProfile = ArtLightingManager.getProfile(currentMedia)
        gallerySpotlightView.setLightingProfile(lightingProfile)
        gallerySpotlightView.visibility = VISIBLE

        galleryFrameView.lightingProfile = lightingProfile
        galleryFrameView.focalY = naturalFocalY
        galleryFrameView.kenBurnsScale = 1.0f
        galleryFrameView.isClassicalStyle = isClassical
        galleryFrameView.paintingAspectRatio = width.toFloat() / height.toFloat()
        galleryFrameView.paintingDrawable = drawable
        galleryFrameView.visibility = VISIBLE

        val (_, vh) = resolveTargetSize()
        val wallCenterOffsetY = if (isClassical) -vh * 0.015f else -vh * 0.075f
        galleryFrameView.translationY = wallCenterOffsetY
        galleryFrameView.translationX = 0f
        galleryFrameView.scaleX = 1f
        galleryFrameView.scaleY = 1f
        galleryBackgroundImageView.scaleX = 1f
        galleryBackgroundImageView.scaleY = 1f
        gallerySpotlightView.scaleX = 1f
        gallerySpotlightView.scaleY = 1f

        markBackgroundReady(blurHelper.currentToken)
    }

    private fun setupSculptureMode(
        drawable: Drawable,
        width: Int,
        height: Int,
    ) {
        galleryBackgroundImageView.visibility = GONE
        gallerySpotlightView.visibility = GONE
        galleryCeilingForegroundView.visibility = GONE
        galleryFrameView.visibility = GONE
        galleryFrameView.paintingDrawable = null

        val lightingProfile = ArtLightingManager.getProfile(currentMedia)
        backgroundDimOverlay.alpha = lightingProfile.dimOverlayAlpha
        backgroundDimOverlay.visibility = VISIBLE

        sculptureBacklightView.setLightingProfile(lightingProfile)
        blurHelper.update(drawable)

        foregroundImageView.visibility = VISIBLE
        foregroundImageView.scaleType = ImageView.ScaleType.FIT_CENTER
        setForegroundDrawable(drawable)

        updateSculpturePosition()
    }

    private fun updateSculpturePosition() {
        val (vw, vh) = resolveTargetSize()
        if (currentImageWidth <= 0 || currentImageHeight <= 0) return

        val targetH = vh * 0.90f
        val targetW = targetH * (currentImageWidth.toFloat() / currentImageHeight.toFloat())

        // Position on the side NOT occupied by the info bubble
        val sideOffset = if (isOverlayOnRight) -(vw.toFloat() * 0.23f) else +(vw.toFloat() * 0.23f)

        foregroundImageView.translationX = sideOffset
        foregroundImageView.translationY = 0f
        foregroundImageView.scaleX = 1f
        foregroundImageView.scaleY = 1f

        val shadowW = (targetW * 1.55f).toInt()
        val shadowH = (targetH * 0.95f).toInt()
        sculptureBacklightView.layoutParams =
            LayoutParams(shadowW, shadowH, Gravity.CENTER).apply {
                sculptureBacklightView.translationX = sideOffset
            }
        sculptureBacklightView.visibility = VISIBLE
    }

    private fun setupStandardMode(
        drawable: Drawable,
        width: Int,
        height: Int,
    ) {
        galleryBackgroundImageView.visibility = GONE
        gallerySpotlightView.visibility = GONE
        galleryCeilingForegroundView.visibility = GONE
        galleryFrameView.visibility = GONE
        galleryFrameView.paintingDrawable = null
        sculptureBacklightView.visibility = GONE
        backgroundDimOverlay.visibility = GONE
        backgroundDimOverlay.alpha = 0.55f

        foregroundImageView.visibility = VISIBLE
        blurHelper.update(drawable.takeIf { currentMedia != null && shouldShowBlurBackground(currentMedia!!, it) })
        setForegroundDrawable(drawable)
        setScaleMode(width, height)
    }

    private fun isPainting(media: AerialMedia?): Boolean {
        if (media == null) return false
        if (isSculpture(media)) return false
        if (media.metadata.category.equals("painting", ignoreCase = true)) return true
        val text = ((media.metadata.shortDescription) + " " + (media.metadata.longDescription)).lowercase()
        return listOf(
            "mona lisa", "starry night", "birth of venus", "creation of adam",
            "the kiss", "pearl earring", "night watch", "the scream", "las meninas",
            "wanderer", "american gothic", "nighthawks", "great wave", "qingming",
            "fresco", "frescoes", "pine forest", "ninth wave", "barge haulers",
            "painting", "portrait", "canvas", "oil on",
        ).any { text.contains(it) }
    }

    private fun isSculpture(media: AerialMedia?): Boolean {
        if (media == null) return false
        if (media.metadata.category.equals("sculpture", ignoreCase = true)) return true
        val text = ((media.metadata.shortDescription) + " " + (media.metadata.longDescription)).lowercase()
        return listOf(
            "christ the redeemer", "david", "tutankhamun", "sphinx", "benin bronze",
            "ife head", "terracotta", "the thinker", "venus de milo", "winged victory",
            "moai", "sun stone", "madara rider", "bronze horseman", "shiva nataraja",
            "statue", "sculpture", "sculptures", "bust", "relief", "carving", "carved",
            "fountain", "installation", "readymade", "anyanwu", "pumpkin", "mirror room",
            "monolith", "stele", "moche portrait vessel", "canoe prow", "veranda post",
        ).any { text.contains(it) }
    }

    private fun isArchitecture(media: AerialMedia?): Boolean {
        if (media == null) return false
        if (media.metadata.category.equals("architecture", ignoreCase = true)) return true
        val text = ((media.metadata.shortDescription) + " " + (media.metadata.longDescription)).lowercase()
        return listOf("church", "temple", "cathedral", "monastery", "fortress", "pyramid", "castle").any { text.contains(it) }
    }

    private fun readUpTo(
        stream: InputStream,
        buffer: ByteArray,
        maxBytes: Int,
    ): Int {
        var total = 0
        val limit = minOf(maxBytes, buffer.size)
        while (total < limit) {
            val read = stream.read(buffer, total, limit - total)
            if (read <= 0) break
            total += read
        }
        return total
    }

    private fun setForegroundDrawable(drawable: Drawable?) {
        (foregroundImageView.drawable as? Animatable)?.stop()
        foregroundImageView.setImageDrawable(drawable)
        (drawable as? Animatable)?.start()
    }

    private fun shouldSkipBlurBackground(
        media: AerialMedia,
        drawable: Drawable,
    ): Boolean = media.uri.filename.endsWith(".gif", ignoreCase = true) || drawable is Animatable

    private fun shouldShowBlurBackground(
        media: AerialMedia,
        drawable: Drawable,
    ): Boolean {
        if (shouldSkipBlurBackground(media, drawable)) {
            return false
        }

        val imageWidth = drawable.intrinsicWidth
        val imageHeight = drawable.intrinsicHeight
        if (imageWidth <= 0 || imageHeight <= 0) {
            return false
        }

        val aspect = AspectRatio.fromDimensions(imageWidth, imageHeight)
        if (aspect == AspectRatio.PORTRAIT && GeneralPrefs.photoScalePortrait == PhotoScale.CENTER_CROP) {
            return false
        }

        val (containerWidth, containerHeight) = resolveTargetSize()
        if (containerWidth <= 0 || containerHeight <= 0) {
            return false
        }

        val scale =
            minOf(
                containerWidth.toFloat() / imageWidth.toFloat(),
                containerHeight.toFloat() / imageHeight.toFloat(),
            )
        val displayedWidth = imageWidth * scale
        val displayedHeight = imageHeight * scale
        val epsilon = 0.5f

        return displayedWidth < containerWidth - epsilon || displayedHeight < containerHeight - epsilon
    }

    private fun stripCredentialsForCoil(media: AerialMedia): Uri {
        if (media.source != AerialMediaSource.WEBDAV) {
            return media.uri
        }
        return ImagePlayerHelper.stripUserinfoFromUri(media.uri)
    }

    companion object {
        private const val STREAM_BUFFER_SIZE = 64 * 1024
    }

    private fun resolveTargetSize(): Pair<Int, Int> {
        val width = if (this.width > 0) this.width else resources.displayMetrics.widthPixels
        val height = if (this.height > 0) this.height else resources.displayMetrics.heightPixels
        return Pair(width, height)
    }

    private fun handleImageError(throwable: Throwable) {
        Timber.e(throwable, "Exception while loading image: ${throwable.message}")
        FirebaseHelper.crashlyticsException(throwable)
        onPlayerError()
    }

    fun stop() {
        stopKenBurns()
        removeCallbacks(finishedRunnable)
        setForegroundDrawable(null)
        currentDrawable = null
        currentMedia = null
        pausedTimestamp = 0
        remainingDuration = 0
        currentImageWidth = 0
        currentImageHeight = 0
        naturalFocalY = 0.5f

        foregroundImageView.translationX = 0f
        foregroundImageView.translationY = 0f
        baseMatrix.reset()
        animMatrix.reset()
        foregroundImageView.imageMatrix = baseMatrix

        galleryBackgroundImageView.visibility = GONE
        gallerySpotlightView.visibility = GONE
        galleryCeilingForegroundView.visibility = GONE
        galleryFrameView.visibility = GONE
        galleryFrameView.paintingDrawable = null
        galleryFrameView.scaleX = 1f
        galleryFrameView.scaleY = 1f
        galleryBackgroundImageView.scaleX = 1f
        galleryBackgroundImageView.scaleY = 1f
        gallerySpotlightView.scaleX = 1f
        gallerySpotlightView.scaleY = 1f
        galleryFrameView.kenBurnsScale = 1f

        sculptureBacklightView.visibility = GONE
        backgroundDimOverlay.visibility = GONE
        backgroundDimOverlay.alpha = 0.55f
        blurHelper.cancel()
    }

    fun pauseTimer() {
        pausedTimestamp = System.currentTimeMillis()
        removeCallbacks(finishedRunnable)
        kenBurnsAnimator?.pause()
    }

    fun resumeTimer(pauseDuration: Long) {
        if (pausedTimestamp > 0) {
            remainingDuration = maxOf(0, remainingDuration - pauseDuration)
            if (remainingDuration > 0) {
                kenBurnsAnimator?.resume()
                postDelayed(finishedRunnable, remainingDuration)
            } else {
                listener?.onImageFinished()
            }
            pausedTimestamp = 0
        }
    }

    private fun setScaleMode(
        width: Int,
        height: Int,
    ) {
        currentImageWidth = width
        currentImageHeight = height
        naturalFocalY = calculateFocalY(width, height)

        val (viewW, viewH) = resolveTargetSize()
        val vw = viewW.toFloat()
        val vh = viewH.toFloat()
        val iw = width.toFloat()
        val ih = height.toFloat()

        val isPortrait = ih > iw && (ih / iw) >= 1.15f
        if (isPortrait && iw > 0f && ih > 0f && vw > 0f && vh > 0f && GeneralPrefs.photoScalePortrait == PhotoScale.CENTER_CROP) {
            foregroundImageView.scaleType = ImageView.ScaleType.MATRIX
            val scale = maxOf(vw / iw, vh / ih)
            val scaledW = iw * scale
            val scaledH = ih * scale
            val transX = (vw - scaledW) / 2f

            val focalScaledY = scaledH * naturalFocalY
            val targetScreenY = vh * 0.38f
            val transY = (targetScreenY - focalScaledY).coerceIn(vh - scaledH, 0f)

            baseMatrix.reset()
            baseMatrix.postScale(scale, scale)
            baseMatrix.postTranslate(transX, transY)
            foregroundImageView.imageMatrix = baseMatrix
            foregroundImageView.translationX = 0f
            foregroundImageView.translationY = 0f
        } else {
            foregroundImageView.scaleType = resolveForegroundScaleType(width, height)
            foregroundImageView.translationX = 0f
            foregroundImageView.translationY = 0f
        }
    }

    private fun calculateFocalY(
        width: Int,
        height: Int,
    ): Float {
        if (height <= width) return 0.5f
        val aspect = height.toFloat() / width.toFloat()
        if (aspect < 1.15f) return 0.5f
        return if (aspect >= 1.35f) 0.22f else 0.32f
    }

    private fun resolveForegroundScaleType(
        width: Int,
        height: Int,
    ): ImageView.ScaleType {
        val aspect = AspectRatio.fromDimensions(width, height)
        Timber.i("Aspect ratio: $aspect")
        return when (aspect) {
            AspectRatio.SQUARE, AspectRatio.PORTRAIT -> getScaleType(GeneralPrefs.photoScalePortrait)
            AspectRatio.LANDSCAPE -> getScaleType(GeneralPrefs.photoScaleLandscape)
        }
    }

    private fun getScaleType(scale: PhotoScale?): ImageView.ScaleType =
        try {
            ImageView.ScaleType.valueOf(scale.toString())
        } catch (e: Exception) {
            Timber.e(e)
            ImageView.ScaleType.valueOf(PhotoScale.CENTER_CROP.toString())
        }

    private fun setupFinishedRunnable() {
        removeCallbacks(finishedRunnable)
        val token = blurHelper.currentToken
        if (currentDisplayMode == DisplayMode.GALLERY || backgroundReadyToken == token) {
            runSetupFinishedRunnable()
        } else {
            pendingSetupToken = token
        }
    }

    private fun startKenBurns(durationMs: Long) {
        stopKenBurns()

        val (viewW, viewH) = resolveTargetSize()
        val vw = viewW.toFloat()
        val vh = viewH.toFloat()

        when (currentDisplayMode) {
            DisplayMode.GALLERY -> {
                val startScale = 1.0f
                val endFrameScale = 1.20f // Refined, smooth 20% dolly in
                val endSpotScale = 1.08f // Intermediate rate for ceiling spotlight
                val endWallScale = 1.03f // Subtle background wall scale

                val focalYOnFrame = galleryFrameView.getFocalPointY()
                galleryFrameView.pivotX = galleryFrameView.width / 2f
                galleryFrameView.pivotY = focalYOnFrame
                galleryFrameView.focalY = naturalFocalY

                // Focal point in screen space for background wall & spotlight
                val screenFocalX = vw / 2f
                val screenFocalY = galleryFrameView.top + galleryFrameView.translationY + focalYOnFrame

                galleryBackgroundImageView.pivotX = screenFocalX
                galleryBackgroundImageView.pivotY = screenFocalY
                gallerySpotlightView.pivotX = screenFocalX
                gallerySpotlightView.pivotY = screenFocalY

                val startFrameTransY = galleryFrameView.translationY
                val endFrameTransY = startFrameTransY - 6f * resources.displayMetrics.density
                val density = resources.displayMetrics.density

                Timber.i("Gallery 3D Dolly Ken Burns: pivotY=$focalYOnFrame, screenFocalY=$screenFocalY, duration=$durationMs")
                kenBurnsAnimator =
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = durationMs
                        interpolator = LinearInterpolator()
                        addUpdateListener { animator ->
                            val f = animator.animatedFraction

                            // Plane 1: Foreground frame + painting dollies forward
                            val frameScale = startScale + (endFrameScale - startScale) * f
                            galleryFrameView.scaleX = frameScale
                            galleryFrameView.scaleY = frameScale
                            galleryFrameView.translationY = startFrameTransY + (endFrameTransY - startFrameTransY) * f

                            // Plane 2: Ceiling spotlight expands at intermediate rate
                            val spotScale = startScale + (endSpotScale - startScale) * f
                            gallerySpotlightView.scaleX = spotScale
                            gallerySpotlightView.scaleY = spotScale
                            gallerySpotlightView.translationY = 4f * density * f

                            // Plane 3: Background wall shifts with deep parallax drift
                            val wallScale = startScale + (endWallScale - startScale) * f
                            galleryBackgroundImageView.scaleX = wallScale
                            galleryBackgroundImageView.scaleY = wallScale
                            galleryBackgroundImageView.translationY = 8f * density * f
                        }
                        start()
                    }
            }
            DisplayMode.SCULPTURE -> {
                val sideOffset = if (isOverlayOnRight) -(vw * 0.23f) else +(vw * 0.23f)
                val zoomIn = kotlin.random.Random.nextBoolean()
                val startScale = if (zoomIn) 1.0f else 1.04f
                val endScale = if (zoomIn) 1.04f else 1.0f

                kenBurnsAnimator =
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = durationMs
                        interpolator = LinearInterpolator()
                        addUpdateListener { animator ->
                            val f = animator.animatedFraction
                            val s = startScale + (endScale - startScale) * f
                            foregroundImageView.scaleX = s
                            foregroundImageView.scaleY = s
                            foregroundImageView.translationX = sideOffset
                            sculptureBacklightView.scaleX = s
                            sculptureBacklightView.scaleY = s
                            sculptureBacklightView.translationX = sideOffset
                        }
                        start()
                    }
            }
            DisplayMode.STANDARD -> {
                val iw = currentImageWidth.toFloat()
                val ih = currentImageHeight.toFloat()
                val isPortrait = ih > iw && (ih / iw) >= 1.15f

                if (isPortrait && iw > 0 && ih > 0 && vw > 0 && vh > 0 && GeneralPrefs.photoScalePortrait == PhotoScale.CENTER_CROP) {
                    val zoomIn = kotlin.random.Random.nextBoolean()
                    val startScale = if (zoomIn) 1.0f else 1.06f
                    val endScale = if (zoomIn) 1.06f else 1.0f

                    val panDownDistance = vh * 0.05f
                    val startPanY = if (zoomIn) 0f else panDownDistance
                    val endPanY = if (zoomIn) panDownDistance else 0f

                    val pivotX = vw / 2f
                    val pivotY = vh * 0.38f

                    kenBurnsAnimator =
                        ValueAnimator.ofFloat(0f, 1f).apply {
                            duration = durationMs
                            interpolator = LinearInterpolator()
                            addUpdateListener { animator ->
                                val f = animator.animatedFraction
                                val curZoom = startScale + (endScale - startScale) * f
                                val curPanY = -(startPanY + (endPanY - startPanY) * f)

                                animMatrix.set(baseMatrix)
                                animMatrix.postScale(curZoom, curZoom, pivotX, pivotY)
                                animMatrix.postTranslate(0f, curPanY)
                                foregroundImageView.imageMatrix = animMatrix
                            }
                            start()
                        }
                } else {
                    val zoomIn = kotlin.random.Random.nextBoolean()
                    val startScale = if (zoomIn) 1.02f else 1.12f
                    val endScale = if (zoomIn) 1.12f else 1.02f

                    val startTransX = (kotlin.random.Random.nextFloat() * 2f - 1f) * 20f
                    val endTransX = (kotlin.random.Random.nextFloat() * 2f - 1f) * 40f
                    val startTransY = (kotlin.random.Random.nextFloat() * 2f - 1f) * 15f
                    val endTransY = (kotlin.random.Random.nextFloat() * 2f - 1f) * 30f

                    foregroundImageView.pivotX = vw / 2f
                    foregroundImageView.pivotY = vh / 2f

                    kenBurnsAnimator =
                        ValueAnimator.ofFloat(0f, 1f).apply {
                            duration = durationMs
                            interpolator = LinearInterpolator()
                            addUpdateListener { animator ->
                                val f = animator.animatedFraction
                                foregroundImageView.scaleX = startScale + (endScale - startScale) * f
                                foregroundImageView.scaleY = startScale + (endScale - startScale) * f
                                foregroundImageView.translationX = startTransX + (endTransX - startTransX) * f
                                foregroundImageView.translationY = startTransY + (endTransY - startTransY) * f
                            }
                            start()
                        }
                }
            }
        }
    }

    private fun stopKenBurns() {
        kenBurnsAnimator?.cancel()
        kenBurnsAnimator = null
        foregroundImageView.scaleX = 1f
        foregroundImageView.scaleY = 1f
        foregroundImageView.translationX = 0f
        foregroundImageView.translationY = 0f
        if (foregroundImageView.scaleType == ImageView.ScaleType.MATRIX) {
            foregroundImageView.imageMatrix = baseMatrix
        }
        val (_, vh) = resolveTargetSize()
        val wallCenterOffsetY = if (galleryFrameView.isClassicalStyle) -vh * 0.015f else -vh * 0.075f
        galleryFrameView.scaleX = 1f
        galleryFrameView.scaleY = 1f
        galleryFrameView.translationY = wallCenterOffsetY
        galleryBackgroundImageView.scaleX = 1f
        galleryBackgroundImageView.scaleY = 1f
        galleryBackgroundImageView.translationY = 0f
        gallerySpotlightView.scaleX = 1f
        gallerySpotlightView.scaleY = 1f
        gallerySpotlightView.translationY = 0f
        sculptureBacklightView.scaleX = 1f
        sculptureBacklightView.scaleY = 1f
    }

    private fun runSetupFinishedRunnable() {
        listener?.onImagePrepared()

        val speedSeconds = GeneralPrefs.slideshowSpeed.toLongOrNull() ?: 30L
        val duration = maxOf(20000L, speedSeconds * 1000L)
        val fadeDuration = GeneralPrefs.mediaFadeOutDuration.toLongOrNull() ?: 800L
        val durationMinusFade = maxOf(1000L, duration - fadeDuration)

        totalDuration = duration
        remainingDuration = durationMinusFade

        startKenBurns(duration)

        Timber.i("Image display duration: ${duration.milliseconds}, delay: ${durationMinusFade.milliseconds}")
        if (progressBar) GlobalBus.post(ProgressBarEvent(ProgressState.START, 0, duration))
        postDelayed(finishedRunnable, durationMinusFade)
    }

    private fun markBackgroundReady(token: Long) {
        backgroundReadyToken = token
        if (pendingSetupToken == token) {
            pendingSetupToken = -1
            runSetupFinishedRunnable()
        }
    }

    private fun onPlayerError() {
        post(errorRunnable)
    }

    fun setOnPlayerListener(listener: OnImagePlayerEventListener) {
        this.listener = listener
    }

    interface OnImagePlayerEventListener {
        fun onImageFinished()

        fun onImageError()

        fun onImagePrepared()
    }
}
