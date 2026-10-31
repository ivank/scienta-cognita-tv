package com.neilturner.aerialviews.ui.core

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
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

    /**
     * Two-token sync gate: ensures [runSetupFinishedRunnable] fires only after *both*
     * the foreground image load (Coil onSuccess) and the background blur (potentially
     * async on pre-S devices) have completed for the same image request.
     *
     * - [backgroundReadyToken] is set by [markBackgroundReady] when [BackgroundBlurHelper]
     *   reports its background job is done.
     * - [pendingSetupToken] is set by [setupFinishedRunnable] when the foreground load
     *   finishes but the background isn't ready yet.
     * - [runSetupFinishedRunnable] fires when both tokens agree on the same value.
     */
    private var backgroundReadyToken: Long = -1
    private var pendingSetupToken: Long = -1

    private val foregroundImageView =
        AppCompatImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
    private val backgroundImageView =
        AppCompatImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
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
        addView(foregroundImageView)
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
            .memoryCache(null)
            // .logger(logger)
            .components {
                add(OkHttpNetworkFetcherFactory(buildOkHttpClient()))
                add(InputStreamFetcher.Factory())
                add(buildGifDecoder())
            }.build()

    fun setImage(media: AerialMedia) {
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
        // Errors from the actual async network/disk load are delivered via onError, not here.
        try {
            val (targetWidth, targetHeight) = resolveTargetSize()
            val maxDim = maxOf(targetWidth, targetHeight)
            val request =
                ImageRequest
                    .Builder(context)
                    .data(data)
                    .size(maxDim, maxDim)
                    // The pre-S blurred background is a CPU bitmap operation. Coil hardware
                    // bitmaps cannot be drawn into the software canvas used by Drawable.toBitmap().
                    .allowHardware(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || !GeneralPrefs.photoBackgroundBlurEnabled)
                    .target(
                        onStart = {
                            // resetImageTransforms()
                        },
                        onSuccess = { image ->
                            val drawable = image.asDrawable(resources)
                            blurHelper.update(drawable.takeIf { shouldShowBlurBackground(media, it) })
                            setForegroundDrawable(drawable)
                        },
                    ).listener(
                        onSuccess = { _, result ->
                            setScaleMode(result.image.width, result.image.height)
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
        // If tall portrait art is scaled edge-to-edge with natural center matrix, it covers 100% of the display
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
        private const val STREAM_BUFFER_SIZE = 64 * 1024 // 64KB - helps reduce network round-trips
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
                // If time has expired, finish immediately
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

            // Natural center of the statue/portrait (head, face, upper sculpture)
            val focalScaledY = scaledH * naturalFocalY
            val targetScreenY = vh * 0.38f
            // Coerce so that image top is <= 0 (no top gap) and bottom is >= vh (no bottom gap)
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

        // For tall portrait art and statues (e.g. 2:3, 9:16), the natural center of interest
        // (head, face, upper sculpture) is in the upper ~22%-30%
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
        if (backgroundReadyToken == token) {
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
        removeCallbacks(finishedRunnable)
        // Notify immediately; the single error backoff is applied by
        // ScreenController.handleError(). Delaying here too would double it.
        post(errorRunnable)
    }

    fun setOnPlayerListener(listener: ScreenController) {
        this.listener = listener
    }

    interface OnImagePlayerEventListener {
        fun onImageFinished()

        fun onImageError()

        fun onImagePrepared()
    }
}
