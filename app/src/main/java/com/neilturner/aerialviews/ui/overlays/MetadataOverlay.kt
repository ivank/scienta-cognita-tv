package com.neilturner.aerialviews.ui.overlays

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.models.enums.MetadataType
import com.neilturner.aerialviews.models.enums.OverlayType
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import com.neilturner.aerialviews.ui.core.VideoPlayerView
import com.neilturner.aerialviews.ui.helpers.ColourHelper
import com.neilturner.aerialviews.ui.helpers.FontHelper
import com.neilturner.aerialviews.ui.helpers.OverlayOpacityHelper
import com.neilturner.aerialviews.ui.overlays.state.MetadataOverlayState
import com.neilturner.aerialviews.ui.views.MiniMapView
import com.neilturner.aerialviews.utils.WorldCoordinates
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class MetadataOverlay
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : LinearLayout(context, attrs, defStyleAttr) {
        private var poiJob: Job? = null
        private val visibleAlpha = OverlayOpacityHelper.visibleAlpha(context)
        var isFadingOutMedia = false
        var type: OverlayType = OverlayType.METADATA1

        private val miniMapView: MiniMapView
        private val textLayout: LinearLayout
        private val titleView: AppCompatTextView
        private val subtitleView: AppCompatTextView
        private val descriptionView: AppCompatTextView

        private var currentLocationText: String = ""
        private var currentDetailedText: String = ""
        var isRightSide: Boolean = true
            private set

        private var detailsAnimator: ValueAnimator? = null
        var isDetailsMode: Boolean = false
            private set

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_minimap_card)

            miniMapView = MiniMapView(context)

            textLayout =
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                }

            titleView =
                AppCompatTextView(context).apply {
                    TextViewCompat.setTextAppearance(this, R.style.OverlayText)
                    includeFontPadding = false
                    maxLines = 2
                    setLineSpacing(0f, 1.15f)
                }

            subtitleView =
                AppCompatTextView(context).apply {
                    TextViewCompat.setTextAppearance(this, R.style.OverlayText)
                    includeFontPadding = false
                    setTextColor(Color.parseColor("#B3FFFFFF")) // Soft 70% white
                    visibility = GONE
                    maxLines = 1
                    setLineSpacing(0f, 1.15f)
                }

            descriptionView =
                AppCompatTextView(context).apply {
                    TextViewCompat.setTextAppearance(this, R.style.OverlayText)
                    includeFontPadding = false
                    setTextColor(Color.parseColor("#E6FFFFFF")) // Crisp 90% white
                    visibility = GONE
                    alpha = 0f
                    maxLines = 5
                    ellipsize = TextUtils.TruncateAt.END
                    setLineSpacing(0f, 1.25f)
                }

            textLayout.addView(titleView)
            textLayout.addView(subtitleView)
            textLayout.addView(descriptionView)

            applyLayoutForSide(isRightSide)
        }

        fun setAlignment(isRight: Boolean) {
            if (isRightSide == isRight && childCount > 0) return
            isRightSide = isRight
            applyLayoutForSide(isRight)
        }

        private fun applyLayoutForSide(isRight: Boolean) {
            val density = context.resources.displayMetrics.density
            val padMapSide = (6 * density).toInt()
            val padTextSide = (16 * density).toInt()
            val padV = (6 * density).toInt()

            val mapW = (104 * density).toInt()
            val mapH = if (isDetailsMode) (148 * density).toInt() else (52 * density).toInt()
            val gap = (14 * density).toInt()

            if (isRight) {
                // Card in right corner: text on left (right-aligned), map on outer right edge
                setPadding(padTextSide, padV, padMapSide, padV)

                titleView.gravity = Gravity.END
                titleView.textAlignment = View.TEXT_ALIGNMENT_TEXT_END
                subtitleView.gravity = Gravity.END
                subtitleView.textAlignment = View.TEXT_ALIGNMENT_TEXT_END
                descriptionView.gravity = Gravity.END
                descriptionView.textAlignment = View.TEXT_ALIGNMENT_TEXT_END
                textLayout.gravity = Gravity.CENTER_VERTICAL or Gravity.END

                miniMapView.layoutParams =
                    LayoutParams(mapW, mapH).apply {
                        marginStart = gap
                        marginEnd = 0
                    }

                removeAllViews()
                addView(textLayout)
                addView(miniMapView)
            } else {
                // Card in left corner: map on outer left edge, text on right (left-aligned)
                setPadding(padMapSide, padV, padTextSide, padV)

                titleView.gravity = Gravity.START
                titleView.textAlignment = View.TEXT_ALIGNMENT_TEXT_START
                subtitleView.gravity = Gravity.START
                subtitleView.textAlignment = View.TEXT_ALIGNMENT_TEXT_START
                descriptionView.gravity = Gravity.START
                descriptionView.textAlignment = View.TEXT_ALIGNMENT_TEXT_START
                textLayout.gravity = Gravity.CENTER_VERTICAL or Gravity.START

                miniMapView.layoutParams =
                    LayoutParams(mapW, mapH).apply {
                        marginStart = 0
                        marginEnd = gap
                    }

                removeAllViews()
                addView(miniMapView)
                addView(textLayout)
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            poiJob?.cancel()
            poiJob = null
            detailsAnimator?.cancel()
        }

        fun style(
            font: String,
            sizeSp: Float,
            weight: String,
        ) {
            // Main title size: compact, refined HUD text to fit more description in the bubble
            val titleSize = if (sizeSp > 0) (sizeSp * 0.60f).coerceIn(10.5f, 13f) else 12f
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSize)
            titleView.typeface = FontHelper.getTypeface(context, font, weight)
            titleView.setTextColor(ColourHelper.overlayColour(GeneralPrefs.overlayColour))

            // Subtitle size: 2sp smaller than title
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, (titleSize - 2f).coerceAtLeast(9.5f))
            subtitleView.typeface = FontHelper.getTypeface(context, font, "400")

            // Description size: compact readable font
            descriptionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, (titleSize - 1.5f).coerceAtLeast(10f))
            descriptionView.typeface = FontHelper.getTypeface(context, font, "400")

            val density = context.resources.displayMetrics.density
            val screenWidth = context.resources.displayMetrics.widthPixels
            val mapW = (104 * density).toInt()
            val gap = (14 * density).toInt()
            val padTotal = (22 * density).toInt()
            val maxTextWidth = ((screenWidth * 0.45f) - mapW - gap - padTotal).toInt()
            titleView.maxWidth = maxTextWidth.coerceIn((200 * density).toInt(), (450 * density).toInt())
            subtitleView.maxWidth = maxTextWidth.coerceIn((200 * density).toInt(), (450 * density).toInt())
            descriptionView.maxWidth = maxTextWidth.coerceIn((200 * density).toInt(), (450 * density).toInt())
            (descriptionView.layoutParams as? LayoutParams)?.topMargin = (5 * density).toInt()
        }

        fun setDetailsMode(
            enable: Boolean,
            animated: Boolean = true,
        ) {
            if (isDetailsMode == enable) return
            isDetailsMode = enable

            detailsAnimator?.cancel()

            val density = context.resources.displayMetrics.density
            val normalCardH = (64 * density).toInt()
            val detailsCardH = (160 * density).toInt()

            val normalMapH = (52 * density).toInt()
            val detailsMapH = (148 * density).toInt()

            val targetFraction = if (enable) 1f else 0f
            val currentFraction = miniMapView.zoomFraction

            if (enable) {
                descriptionView.text = currentDetailedText.ifBlank { currentLocationText }
                descriptionView.visibility = VISIBLE
            }

            if (!animated) {
                miniMapView.setZoomFraction(targetFraction)
                miniMapView.layoutParams =
                    miniMapView.layoutParams.apply {
                        height = if (enable) detailsMapH else normalMapH
                    }
                layoutParams =
                    layoutParams.apply {
                        height = if (enable) detailsCardH else normalCardH
                    }
                descriptionView.alpha = targetFraction
                if (!enable) descriptionView.visibility = GONE
                requestLayout()
                return
            }

            detailsAnimator =
                ValueAnimator.ofFloat(currentFraction, targetFraction).apply {
                    duration = 450L
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { animator ->
                        val fraction = animator.animatedValue as Float
                        miniMapView.setZoomFraction(fraction)

                        val curCardH = (normalCardH + (detailsCardH - normalCardH) * fraction).toInt()
                        val curMapH = (normalMapH + (detailsMapH - normalMapH) * fraction).toInt()

                        miniMapView.layoutParams =
                            miniMapView.layoutParams.apply {
                                height = curMapH
                            }
                        layoutParams =
                            layoutParams.apply {
                                height = curCardH
                            }
                        descriptionView.alpha = fraction
                        requestLayout()
                    }
                    addListener(
                        object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                if (!enable) {
                                    descriptionView.visibility = GONE
                                }
                            }
                        },
                    )
                    start()
                }
        }

        fun updateLocationData(
            location: String,
            poi: Map<Int, String>,
            metadataType: MetadataType,
            player: VideoPlayerView,
            detailedText: String = "",
        ) {
            isFadingOutMedia = false
            this.visibility = VISIBLE
            currentLocationText = location
            currentDetailedText = detailedText

            val activeText =
                if (metadataType == MetadataType.DYNAMIC) {
                    poi[0]?.replace("\n", " ") ?: location
                } else {
                    location
                }

            applyDisplayText(activeText, location)

            if (isDetailsMode) {
                descriptionView.text = detailedText.ifBlank { location }
                descriptionView.visibility = VISIBLE
                descriptionView.alpha = 1f
            }

            // Hide if completely blank
            if (titleView.text.isNullOrBlank()) {
                this.visibility = GONE
            }

            // Dynamic points of interest timer
            if (metadataType == MetadataType.DYNAMIC && poi.size > 1) {
                updatePointsOfInterest(poi, player, location)
            } else {
                poiJob?.cancel()
                poiJob = null
            }
        }

        private fun applyDisplayText(
            primary: String,
            fallbackLocation: String,
        ) {
            val cleanPrimary = primary.trim()
            val coords =
                WorldCoordinates.find(cleanPrimary)
                    ?: WorldCoordinates.find(fallbackLocation)

            miniMapView.setCoordinates(coords?.latitude, coords?.longitude)

            // If primary is a specific landmark/POI different from the general location,
            // show landmark as title and city/region as subtitle!
            if (cleanPrimary != fallbackLocation && fallbackLocation.isNotBlank()) {
                titleView.text = cleanPrimary
                subtitleView.text = fallbackLocation
                subtitleView.visibility = VISIBLE
            } else {
                // Check if primary contains comma (e.g. "Cathedral Canyon, Lake Powell, Utah")
                if (cleanPrimary.contains(",")) {
                    val parts = cleanPrimary.split(",", limit = 2)
                    titleView.text = parts[0].trim()
                    subtitleView.text = parts[1].trim()
                    subtitleView.visibility = VISIBLE
                } else {
                    titleView.text = cleanPrimary
                    subtitleView.visibility = GONE
                }
            }
        }

        fun render(
            state: MetadataOverlayState,
            player: VideoPlayerView,
        ) {
            updateLocationData(state.text, state.poi, state.metadataType, player, state.detailedText)
        }

        private fun updatePointsOfInterest(
            poi: Map<Int, String>,
            player: VideoPlayerView,
            baseLocation: String,
        ) {
            val poiTimes = poi.keys.sorted()
            var lastPoi = 0

            poiJob?.cancel()
            poiJob =
                findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                    delay(1000.milliseconds)
                    while (isActive) {
                        val time = player.currentPosition / 1000
                        val newPoi = poiTimes.findLast { it <= time } ?: 0
                        val shouldUpdate = newPoi != lastPoi

                        if (shouldUpdate && !isFadingOutMedia) {
                            val nextText = poi[newPoi]?.replace("\n", " ") ?: ""
                            lastPoi = newPoi

                            if (!OverlayOpacityHelper.isVisible(this@MetadataOverlay.alpha, visibleAlpha)) {
                                applyDisplayText(nextText, baseLocation)
                                delay(1000.milliseconds)
                                continue
                            }

                            // Smooth fade out, change text & pin, fade in
                            textLayout
                                .animate()
                                .alpha(0f)
                                .setDuration(600)
                                .withEndAction {
                                    applyDisplayText(nextText, baseLocation)
                                    textLayout
                                        .animate()
                                        .alpha(1f)
                                        .setDuration(600)
                                        .start()
                                }.start()
                        }

                        val interval = if (shouldUpdate) 3000L else 1000L
                        delay(interval.milliseconds)
                    }
                }
        }
    }
