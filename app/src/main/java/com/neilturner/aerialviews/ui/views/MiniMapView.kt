package com.neilturner.aerialviews.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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

        var zoomFraction: Float = 0f
            private set

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

        private val mapBgPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.parseColor("#26000000") // 15% black backdrop
            }

        private val mapBorderPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = context.resources.displayMetrics.density * 0.75f
                color = Color.parseColor("#33FFFFFF") // 20% white border
            }

        private val clipPath = Path()
        private val clipRect = RectF()

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

        fun setZoomFraction(fraction: Float) {
            val clamped = fraction.coerceIn(0f, 1f)
            if (zoomFraction != clamped) {
                zoomFraction = clamped
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            val density = resources.displayMetrics.density
            val cornerRadius = 6f * density

            clipRect.set(0f, 0f, w, h)
            clipPath.reset()
            clipPath.addRoundRect(clipRect, cornerRadius, cornerRadius, Path.Direction.CW)

            // 1. Draw subtle background
            canvas.drawRoundRect(clipRect, cornerRadius, cornerRadius, mapBgPaint)

            val lat = latitude
            val lon = longitude

            // 2. Draw map with canvas transform
            canvas.save()
            canvas.clipPath(clipPath)

            val baseH = w / 2f
            if (lat != null && lon != null) {
                // Equirectangular projection coordinates on 2:1 world map
                val px0 = ((lon + 180.0) / 360.0 * w).toFloat()
                val py0 = ((90.0 - lat) / 180.0 * baseH).toFloat()

                // Cur zoom: 1.0x to 7.0x (~size of India region)
                val curZoom = 1.0f + (6.0f * zoomFraction)

                // Pin glides from natural world position to center of view
                val targetPinX = px0 + (w / 2f - px0) * zoomFraction
                val targetPinY = py0 + (h / 2f - py0) * zoomFraction

                canvas.translate(targetPinX, targetPinY)
                canvas.scale(curZoom, curZoom)
                canvas.translate(-px0, -py0)

                mapDrawable?.let {
                    it.setBounds(0, 0, w.toInt(), baseH.toInt())
                    it.draw(canvas)
                }
            } else {
                mapDrawable?.let {
                    val top = ((h - baseH) / 2f).toInt()
                    it.setBounds(0, top, w.toInt(), top + baseH.toInt())
                    it.draw(canvas)
                }
            }
            canvas.restore()

            // 3. Draw border
            canvas.drawRoundRect(clipRect, cornerRadius, cornerRadius, mapBorderPaint)

            // 4. Draw location pin
            if (lat != null && lon != null) {
                val px0 = ((lon + 180.0) / 360.0 * w).toFloat()
                val py0 = ((90.0 - lat) / 180.0 * baseH).toFloat()
                val targetPinX = px0 + (w / 2f - px0) * zoomFraction
                val targetPinY = py0 + (h / 2f - py0) * zoomFraction

                val baseRadius = 3.5f * density
                val pulseRadius = baseRadius + (4f * density * pulseFraction)
                val pulseAlpha = ((1f - pulseFraction) * 160).toInt().coerceIn(0, 255)

                // Outer pulsing halo
                pinHaloPaint.alpha = pulseAlpha
                canvas.drawCircle(targetPinX, targetPinY, pulseRadius, pinHaloPaint)

                // Main glowing red dot
                pinCorePaint.alpha = 230
                canvas.drawCircle(targetPinX, targetPinY, baseRadius, pinCorePaint)

                // Crisp white center dot
                canvas.drawCircle(targetPinX, targetPinY, baseRadius * 0.45f, pinCenterPaint)
            }
        }
    }
