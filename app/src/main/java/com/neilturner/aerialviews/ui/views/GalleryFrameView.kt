package com.neilturner.aerialviews.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import com.neilturner.aerialviews.R

/**
 * An authentic museum picture frame with dynamic aspect ratio adaptation.
 *
 * Features:
 * - 9-slice sliced high-resolution ornamental carved gold Baroque molding.
 * - Archival museum cotton matte (passe-partout) with angled inner core bevel & gold fillet.
 * - Realistic multi-layered directional drop shadows (contact AO, directional cast, diffuse penumbra).
 * - Physical lighting layers on the painting itself (overhang molding cast shadow, spotlight wash, varnish sheen).
 * - Displays paintings of all aspect ratios (portrait, square, landscape, panoramic) in full uncropped view.
 */
class GalleryFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var paintingDrawable: Drawable? = null
        set(value) {
            field = value
            invalidate()
        }

    var paintingAspectRatio: Float = 0.75f // width / height
        set(value) {
            if (value > 0f && field != value) {
                field = value
                requestLayout()
                invalidate()
            }
        }

    var isClassicalStyle: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var kenBurnsScale: Float = 1.0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    fun getFocalPointY(): Float {
        val shadowPadY = 44f * density
        val fTop = shadowPadY - 6f * density
        val fBottom = height.toFloat() - shadowPadY - 6f * density
        val cTop = fTop + totalBorderPerSide
        val cBottom = fBottom - totalBorderPerSide
        val cHeight = maxOf(1f, cBottom - cTop)
        return cTop + cHeight * focalY
    }

    var focalY: Float = 0.35f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var lightingProfile: ArtLightingProfile = ArtLightingManager.PROFILES[0]
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density

    // Scaled dimensions
    private val frameMoldingWidth = 30f * density
    private val matteWidth = 14f * density
    private val innerBevelWidth = 2f * density
    private val totalBorderPerSide = frameMoldingWidth + matteWidth

    // Drawing helpers
    private val frameRect = RectF()
    private val matteRect = RectF()
    private val canvasRect = RectF()
    private val shadowRect = RectF()
    private val drawableBounds = Rect()

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outerMoldingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bevelHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bevelShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val goldFilletPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mattePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val canvasLightingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // Cached source rects for 1024x1024 9-slice frame
    private val sliceC = 230
    private val srcTL = Rect(0, 0, sliceC, sliceC)
    private val srcTR = Rect(1024 - sliceC, 0, 1024, sliceC)
    private val srcBL = Rect(0, 1024 - sliceC, sliceC, 1024)
    private val srcBR = Rect(1024 - sliceC, 1024 - sliceC, 1024, 1024)

    private val srcT = Rect(sliceC, 0, 1024 - sliceC, sliceC)
    private val srcB = Rect(sliceC, 1024 - sliceC, 1024 - sliceC, 1024)
    private val srcL = Rect(0, sliceC, sliceC, 1024 - sliceC)
    private val srcR = Rect(1024 - sliceC, sliceC, 1024, 1024 - sliceC)

    // Reusable destination rects
    private val dstTL = RectF()
    private val dstTR = RectF()
    private val dstBL = RectF()
    private val dstBR = RectF()
    private val dstT = RectF()
    private val dstB = RectF()
    private val dstL = RectF()
    private val dstR = RectF()

    companion object {
        @Volatile
        private var ornateFrameBitmap: Bitmap? = null

        private fun getOrnateFrameBitmap(resources: android.content.res.Resources): Bitmap? {
            val current = ornateFrameBitmap
            if (current != null && !current.isRecycled) {
                return current
            }
            synchronized(this) {
                if (ornateFrameBitmap == null || ornateFrameBitmap?.isRecycled == true) {
                    try {
                        val opts = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        ornateFrameBitmap = BitmapFactory.decodeResource(resources, R.drawable.frame_baroque_ornate, opts)
                    } catch (e: Exception) {
                        // ignore and fallback to procedural draw
                    }
                }
                return ornateFrameBitmap
            }
        }
    }

    init {
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableW = MeasureSpec.getSize(widthMeasureSpec).toFloat()
        val availableH = MeasureSpec.getSize(heightMeasureSpec).toFloat()

        if (availableW <= 0f || availableH <= 0f) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        // On a 1080p screen, the gallery wall extends from y=0 to ~860px (where wainscoting/floor starts).
        // Target an elegant frame fitting comfortably on the wall for portrait, square, and landscape.
        val maxAllowedW = availableW * 0.82f
        val maxAllowedH = availableH * 0.76f

        var targetCanvasH = maxAllowedH - 2 * totalBorderPerSide
        var targetCanvasW = targetCanvasH * paintingAspectRatio
        if (targetCanvasW + 2 * totalBorderPerSide > maxAllowedW) {
            targetCanvasW = maxAllowedW - 2 * totalBorderPerSide
            targetCanvasH = targetCanvasW / paintingAspectRatio
        }
        val targetFrameW = targetCanvasW + 2 * totalBorderPerSide
        val targetFrameH = targetCanvasH + 2 * totalBorderPerSide

        // Extra padding for the drop shadow
        val shadowPadX = 40f * density
        val shadowPadY = 44f * density

        val measuredW = (targetFrameW + shadowPadX * 2).toInt()
        val measuredH = (targetFrameH + shadowPadY * 2).toInt()

        setMeasuredDimension(measuredW, measuredH)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val shadowPadX = 40f * density
        val shadowPadY = 44f * density

        val fLeft = shadowPadX
        val fTop = shadowPadY - 6f * density
        val fRight = width.toFloat() - shadowPadX
        val fBottom = height.toFloat() - shadowPadY - 6f * density

        frameRect.set(fLeft, fTop, fRight, fBottom)

        // 1. Draw realistic multi-layer drop shadows under the frame onto the wall
        drawRealisticDropShadow(canvas, frameRect)

        // 2. Draw outer picture frame molding (9-slice ornate carved gold frame or rich museum molding)
        drawFrameMolding(canvas, frameRect)

        // 3. Draw archival museum matte (passe-partout)
        matteRect.set(
            frameRect.left + frameMoldingWidth,
            frameRect.top + frameMoldingWidth,
            frameRect.right - frameMoldingWidth,
            frameRect.bottom - frameMoldingWidth
        )
        drawMuseumMatte(canvas, matteRect)

        // 4. Draw painting canvas aperture
        canvasRect.set(
            matteRect.left + matteWidth,
            matteRect.top + matteWidth,
            matteRect.right - matteWidth,
            matteRect.bottom - matteWidth
        )

        val d = paintingDrawable
        if (d != null && canvasRect.width() > 0 && canvasRect.height() > 0) {
            drawableBounds.set(
                canvasRect.left.toInt(),
                canvasRect.top.toInt(),
                canvasRect.right.toInt(),
                canvasRect.bottom.toInt()
            )
            d.bounds = drawableBounds

            canvas.save()
            // Clip cleanly within the inner archival matte aperture
            canvas.clipRect(canvasRect)
            d.draw(canvas)

            // 5. Draw physical lighting and shadow layers on top of the painting itself
            drawCanvasLightingFilters(canvas, canvasRect)
            canvas.restore()
        }
    }

    private fun drawRealisticDropShadow(canvas: Canvas, r: RectF) {
        val shadowOffsetX = 24f * density * lightingProfile.shadowOffsetXFactor
        val shadowAlpha = lightingProfile.shadowAlpha

        // 1. Soft diffuse ambient penumbra behind entire frame
        shadowRect.set(
            r.left - 10f * density + shadowOffsetX * 0.2f,
            r.top - 4f * density,
            r.right + 10f * density + shadowOffsetX * 0.2f,
            r.bottom + 16f * density
        )
        shadowPaint.color = Color.argb((shadowAlpha * 0.35f).toInt(), 0, 0, 0)
        canvas.drawRoundRect(shadowRect, 12f * density, 12f * density, shadowPaint)

        // 2. Tight contact ambient occlusion right behind the frame outer molding
        shadowRect.set(
            r.left - 2f * density + shadowOffsetX * 0.15f,
            r.top + 2f * density,
            r.right + 2f * density + shadowOffsetX * 0.15f,
            r.bottom + 8f * density
        )
        shadowPaint.color = Color.argb((shadowAlpha * 0.75f).toInt(), 0, 0, 0)
        canvas.drawRoundRect(shadowRect, 6f * density, 6f * density, shadowPaint)

        // 3. Deep directional cast shadow falling downward onto the wall
        shadowRect.set(
            r.left + 4f * density + shadowOffsetX * 0.5f,
            r.bottom - 2f * density,
            r.right - 4f * density + shadowOffsetX * 0.5f,
            r.bottom + 28f * density
        )
        shadowPaint.shader = LinearGradient(
            shadowOffsetX * 0.5f, r.bottom - 2f * density,
            shadowOffsetX, r.bottom + 28f * density,
            Color.argb(shadowAlpha, 0, 0, 0),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(shadowRect, shadowPaint)

        // 4. Directional lateral cast shadow along the side opposite to the light
        if (lightingProfile.direction == LightDirection.LEFT) {
            // Light from left -> casts shadow to the right
            shadowRect.set(
                r.right - 4f * density,
                r.top + 10f * density,
                r.right + 22f * density,
                r.bottom + 18f * density
            )
            shadowPaint.shader = LinearGradient(
                r.right - 4f * density, 0f,
                r.right + 22f * density, 0f,
                Color.argb((shadowAlpha * 0.85f).toInt(), 0, 0, 0),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(shadowRect, shadowPaint)
        } else if (lightingProfile.direction == LightDirection.RIGHT) {
            // Light from right -> casts shadow to the left
            shadowRect.set(
                r.left - 22f * density,
                r.top + 10f * density,
                r.left + 4f * density,
                r.bottom + 18f * density
            )
            shadowPaint.shader = LinearGradient(
                r.left + 4f * density, 0f,
                r.left - 22f * density, 0f,
                Color.argb((shadowAlpha * 0.85f).toInt(), 0, 0, 0),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(shadowRect, shadowPaint)
        }

        shadowPaint.shader = null
    }

    private fun drawFrameMolding(canvas: Canvas, r: RectF) {
        val bitmap = getOrnateFrameBitmap(resources)
        if (bitmap != null) {
            val m = frameMoldingWidth

            dstTL.set(r.left, r.top, r.left + m, r.top + m)
            dstTR.set(r.right - m, r.top, r.right, r.top + m)
            dstBL.set(r.left, r.bottom - m, r.left + m, r.bottom)
            dstBR.set(r.right - m, r.bottom - m, r.right, r.bottom)

            dstT.set(r.left + m, r.top, r.right - m, r.top + m)
            dstB.set(r.left + m, r.bottom - m, r.right - m, r.bottom)
            dstL.set(r.left, r.top + m, r.left + m, r.bottom - m)
            dstR.set(r.right - m, r.top + m, r.right, r.bottom - m)

            // Draw 4 corners
            canvas.drawBitmap(bitmap, srcTL, dstTL, bitmapPaint)
            canvas.drawBitmap(bitmap, srcTR, dstTR, bitmapPaint)
            canvas.drawBitmap(bitmap, srcBL, dstBL, bitmapPaint)
            canvas.drawBitmap(bitmap, srcBR, dstBR, bitmapPaint)

            // Draw 4 rails
            canvas.drawBitmap(bitmap, srcT, dstT, bitmapPaint)
            canvas.drawBitmap(bitmap, srcB, dstB, bitmapPaint)
            canvas.drawBitmap(bitmap, srcL, dstL, bitmapPaint)
            canvas.drawBitmap(bitmap, srcR, dstR, bitmapPaint)

            // Inner gold fillet lining between molding and matte
            val innerRim = RectF(
                r.left + frameMoldingWidth - 2.5f * density,
                r.top + frameMoldingWidth - 2.5f * density,
                r.right - frameMoldingWidth + 2.5f * density,
                r.bottom - frameMoldingWidth + 2.5f * density
            )
            goldFilletPaint.color = Color.parseColor("#B8860B") // Antique gold fillet
            canvas.drawRect(innerRim, goldFilletPaint)
            return
        }

        // Procedural fallback if bitmap fails to load
        val radius = 4f * density
        outerMoldingPaint.color = Color.parseColor("#261710") // Rich mahogany
        canvas.drawRoundRect(r, radius, radius, outerMoldingPaint)

        bevelHighlightPaint.color = Color.parseColor("#5A3C2A")
        canvas.drawRect(r.left, r.top, r.right, r.top + 3f * density, bevelHighlightPaint)

        goldFilletPaint.color = Color.parseColor("#D4AF37")
        val innerRim = RectF(
            r.left + frameMoldingWidth - 3f * density,
            r.top + frameMoldingWidth - 3f * density,
            r.right - frameMoldingWidth + 3f * density,
            r.bottom - frameMoldingWidth + 3f * density
        )
        canvas.drawRect(innerRim, goldFilletPaint)
    }

    private fun drawMuseumMatte(canvas: Canvas, r: RectF) {
        // Archival museum cotton matte board
        mattePaint.color = if (isClassicalStyle) Color.parseColor("#F5EFE6") else Color.parseColor("#EFEBE4")
        canvas.drawRect(r, mattePaint)

        // 45-degree core bevel around the inner aperture:
        bevelShadowPaint.color = Color.parseColor("#26000000") // 15% black
        bevelHighlightPaint.color = Color.parseColor("#4DFFFFFF") // 30% white

        canvas.drawRect(
            r.left + matteWidth - innerBevelWidth, r.top + matteWidth - innerBevelWidth,
            r.right - matteWidth + innerBevelWidth, r.top + matteWidth, bevelShadowPaint
        )

        if (lightingProfile.direction == LightDirection.LEFT) {
            canvas.drawRect(
                r.left + matteWidth - innerBevelWidth, r.top + matteWidth,
                r.left + matteWidth, r.bottom - matteWidth + innerBevelWidth, bevelShadowPaint
            )
            canvas.drawRect(
                r.right - matteWidth, r.top + matteWidth,
                r.right - matteWidth + innerBevelWidth, r.bottom - matteWidth, bevelHighlightPaint
            )
        } else if (lightingProfile.direction == LightDirection.RIGHT) {
            canvas.drawRect(
                r.right - matteWidth, r.top + matteWidth,
                r.right - matteWidth + innerBevelWidth, r.bottom - matteWidth, bevelShadowPaint
            )
            canvas.drawRect(
                r.left + matteWidth - innerBevelWidth, r.top + matteWidth,
                r.left + matteWidth, r.bottom - matteWidth + innerBevelWidth, bevelHighlightPaint
            )
        } else {
            canvas.drawRect(
                r.left + matteWidth - innerBevelWidth, r.top + matteWidth,
                r.left + matteWidth, r.bottom - matteWidth + innerBevelWidth, bevelShadowPaint
            )
            canvas.drawRect(
                r.right - matteWidth, r.top + matteWidth,
                r.right - matteWidth + innerBevelWidth, r.bottom - matteWidth, bevelHighlightPaint
            )
        }

        // Bottom receives delicate light reflection
        canvas.drawRect(
            r.left + matteWidth - innerBevelWidth, r.bottom - matteWidth,
            r.right - matteWidth + innerBevelWidth, r.bottom - matteWidth + innerBevelWidth, bevelHighlightPaint
        )
    }

    private fun drawCanvasLightingFilters(canvas: Canvas, r: RectF) {
        // 1. Top frame molding overhang cast shadow
        // The carved frame protrudes 3-4 cm out from canvas; overhead spotlight casts a downward shadow
        val topOverhangDepth = 14f * density
        canvasLightingPaint.shader = LinearGradient(
            0f, r.top,
            0f, r.top + topOverhangDepth,
            Color.argb(0x80, 0, 0, 0),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(r.left, r.top, r.right, r.top + topOverhangDepth, canvasLightingPaint)

        // 2. Lateral molding overhang shadow from light-side frame edge
        val sideOverhangDepth = 10f * density
        if (lightingProfile.direction == LightDirection.LEFT) {
            // Light from left casts shadow from left molding onto left side of canvas
            canvasLightingPaint.shader = LinearGradient(
                r.left, 0f,
                r.left + sideOverhangDepth, 0f,
                Color.argb(0x38, 0, 0, 0),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(r.left, r.top, r.left + sideOverhangDepth, r.bottom, canvasLightingPaint)
        } else if (lightingProfile.direction == LightDirection.RIGHT) {
            // Light from right casts shadow from right molding onto right side of canvas
            canvasLightingPaint.shader = LinearGradient(
                r.right, 0f,
                r.right - sideOverhangDepth, 0f,
                Color.argb(0x38, 0, 0, 0),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(r.right - sideOverhangDepth, r.top, r.right, r.bottom, canvasLightingPaint)
        }

        // 3. Directional overhead illumination wash (subtle warm light washing down canvas)
        val tint = lightingProfile.spotlightWallColor
        val washColor = Color.argb(0x18, Color.red(tint), Color.green(tint), Color.blue(tint))
        canvasLightingPaint.shader = LinearGradient(
            0f, r.top,
            0f, r.bottom,
            washColor,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(r, canvasLightingPaint)

        // 4. Satin museum varnish specular sheen
        val cx = when (lightingProfile.direction) {
            LightDirection.LEFT -> r.left + r.width() * 0.32f
            LightDirection.CENTER -> r.centerX()
            LightDirection.RIGHT -> r.left + r.width() * 0.68f
        }
        val cy = r.top + r.height() * 0.28f
        val radius = maxOf(r.width(), r.height()) * 0.65f
        val sheenColor = Color.argb(0x1F, Color.red(tint), Color.green(tint), Color.blue(tint))

        canvasLightingPaint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(sheenColor, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(r, canvasLightingPaint)
        canvasLightingPaint.shader = null
    }
}
