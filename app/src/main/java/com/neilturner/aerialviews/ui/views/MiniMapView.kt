package com.neilturner.aerialviews.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.neilturner.aerialviews.R

class MiniMapView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        private val mapDrawable: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_world_map)
        private var latitude: Double? = null
        private var longitude: Double? = null

        private val pinHaloPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.parseColor("#99FF453A") // Translucent red halo
            }

        private val pinCorePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.parseColor("#FFFF453A") // Bright red pin center
            }

        private val pinCenterPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.WHITE
            }

        private var pulseFraction = 0f
        private val pulseAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1500L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                addUpdateListener {
                    pulseFraction = it.animatedValue as Float
                    if (latitude != null && longitude != null) {
                        invalidate()
                    }
                }
            }

        init {
            pulseAnimator.start()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (!pulseAnimator.isRunning) pulseAnimator.start()
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            pulseAnimator.cancel()
        }

        fun setCoordinates(
            lat: Double?,
            lon: Double?,
        ) {
            if (latitude != lat || longitude != lon) {
                latitude = lat
                longitude = lon
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            // 1. Draw subtle world map silhouette
            mapDrawable?.let {
                it.setBounds(0, 0, width, height)
                it.draw(canvas)
            }

            // 2. Draw location pin if coordinates are known
            val lat = latitude
            val lon = longitude
            if (lat != null && lon != null) {
                // Equirectangular projection
                val px = ((lon + 180.0) / 360.0 * w).toFloat().coerceIn(4f, w - 4f)
                val py = ((90.0 - lat) / 180.0 * h).toFloat().coerceIn(4f, h - 4f)

                val density = resources.displayMetrics.density
                val baseRadius = 3.5f * density
                val pulseRadius = baseRadius + (4f * density * pulseFraction)
                val pulseAlpha = ((1f - pulseFraction) * 160).toInt().coerceIn(0, 255)

                // Outer pulsing halo
                pinHaloPaint.alpha = pulseAlpha
                canvas.drawCircle(px, py, pulseRadius, pinHaloPaint)

                // Main glowing red dot
                pinCorePaint.alpha = 230
                canvas.drawCircle(px, py, baseRadius, pinCorePaint)

                // Crisp white center dot
                canvas.drawCircle(px, py, baseRadius * 0.45f, pinCenterPaint)
            }
        }
    }
