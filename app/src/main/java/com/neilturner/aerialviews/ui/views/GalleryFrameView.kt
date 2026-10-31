package com.neilturner.aerialviews.ui.views

import android.content.Context
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

/**
 * An authentic museum picture frame with dynamic aspect ratio adaptation.
 *
 * Features:
 * - Multi-tiered beveled wood and gold leaf molding.
 * - Archival museum cotton matte (passe-partout) with angled inner core bevel.
 * - Realistic multi-layered directional drop shadows cast onto the gallery wall.
 * - Displays the painting in 100% full height without cropping.
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

    var isClassicalStyle: Boolean = false
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
        val shadowPadY = 40f * density
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
    private val frameMoldingWidth = 20f * density
    private val matteWidth = 16f * density
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
    private val innerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spotlightSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)

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

        // On a 1080p screen, the gallery wall extends from y=0 to ~860px (where floor starts).
        // Target an elegant frame height occupying ~73% of screen height (e.g. ~790px on 1080p).
        var targetFrameH = availableH * 0.73f
        var targetCanvasH = maxOf(50f, targetFrameH - 2 * totalBorderPerSide)
        var targetCanvasW = targetCanvasH * paintingAspectRatio
        var targetFrameW = targetCanvasW + 2 * totalBorderPerSide

        // Ensure frame does not exceed 75% of screen width
        val maxFrameW = availableW * 0.75f
        if (targetFrameW > maxFrameW) {
            targetFrameW = maxFrameW
            targetCanvasW = targetFrameW - 2 * totalBorderPerSide
            targetCanvasH = targetCanvasW / paintingAspectRatio
            targetFrameH = targetCanvasH + 2 * totalBorderPerSide
        }

        // Extra padding for the drop shadow
        val shadowPadX = 36f * density
        val shadowPadY = 40f * density

        val measuredW = (targetFrameW + shadowPadX * 2).toInt()
        val measuredH = (targetFrameH + shadowPadY * 2).toInt()

        setMeasuredDimension(measuredW, measuredH)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val shadowPadX = 36f * density
        val shadowPadY = 40f * density

        val fLeft = shadowPadX
        val fTop = shadowPadY - 6f * density
        val fRight = width.toFloat() - shadowPadX
        val fBottom = height.toFloat() - shadowPadY - 6f * density

        frameRect.set(fLeft, fTop, fRight, fBottom)

        // 1. Draw realistic drop shadow under the frame onto the wall
        drawRealisticDropShadow(canvas, frameRect)

        // 2. Draw outer picture frame molding (wood and gold)
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
            canvas.restore()

            // 5. Draw inner shadow around canvas edges for physical recess
            drawCanvasRecessShadow(canvas, canvasRect)

            // 6. Subtle varnish spotlight reflection positioned towards light source
            drawSpotlightSheen(canvas, canvasRect)
        }
    }

    private fun drawRealisticDropShadow(canvas: Canvas, r: RectF) {
        val shadowOffsetX = 20f * density * lightingProfile.shadowOffsetXFactor
        val shadowAlpha = lightingProfile.shadowAlpha

        // Ambient soft shadow cast onto the wall
        shadowRect.set(
            r.left - 4f * density + shadowOffsetX * 0.35f,
            r.top - 2f * density,
            r.right + 4f * density + shadowOffsetX * 0.35f,
            r.bottom + 8f * density
        )
        shadowPaint.color = Color.argb(shadowAlpha / 2, 0, 0, 0)
        canvas.drawRoundRect(shadowRect, 6f * density, 6f * density, shadowPaint)

        // Deep directional shadow cast downward & sideways according to track light angle
        shadowRect.set(
            r.left + 6f * density + shadowOffsetX,
            r.bottom - 4f * density,
            r.right - 6f * density + shadowOffsetX,
            r.bottom + 22f * density
        )
        shadowPaint.shader = LinearGradient(
            shadowOffsetX * 0.5f, r.bottom - 4f * density,
            shadowOffsetX, r.bottom + 22f * density,
            Color.argb(shadowAlpha, 0, 0, 0),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(shadowRect, shadowPaint)
        shadowPaint.shader = null
    }

    private fun drawFrameMolding(canvas: Canvas, r: RectF) {
        val radius = 4f * density

        if (isClassicalStyle) {
            // Classical European Museum Frame: rich mahogany wood + ornate gold leaf bevels
            outerMoldingPaint.color = Color.parseColor("#261710") // Rich antique mahogany
            canvas.drawRoundRect(r, radius, radius, outerMoldingPaint)

            // Top highlight
            bevelHighlightPaint.color = Color.parseColor("#5A3C2A")
            canvas.drawRect(r.left, r.top, r.right, r.top + 3f * density, bevelHighlightPaint)

            // Directional lateral highlight / shadow on molding
            when (lightingProfile.direction) {
                LightDirection.LEFT -> {
                    bevelHighlightPaint.color = Color.parseColor("#4A2C1A")
                    canvas.drawRect(r.left, r.top, r.left + 2.5f * density, r.bottom, bevelHighlightPaint)
                    bevelShadowPaint.color = Color.parseColor("#150A05")
                    canvas.drawRect(r.right - 2.5f * density, r.top, r.right, r.bottom, bevelShadowPaint)
                }
                LightDirection.RIGHT -> {
                    bevelHighlightPaint.color = Color.parseColor("#4A2C1A")
                    canvas.drawRect(r.right - 2.5f * density, r.top, r.right, r.bottom, bevelHighlightPaint)
                    bevelShadowPaint.color = Color.parseColor("#150A05")
                    canvas.drawRect(r.left, r.top, r.left + 2.5f * density, r.bottom, bevelShadowPaint)
                }
                LightDirection.CENTER -> {
                    bevelShadowPaint.color = Color.parseColor("#150A05")
                    canvas.drawRect(r.left, r.bottom - 2.5f * density, r.right, r.bottom, bevelShadowPaint)
                }
            }

            // Gold leaf outer fillet
            goldFilletPaint.color = Color.parseColor("#B8860B") // Dark goldenrod
            canvas.drawRect(
                r.left + 4f * density, r.top + 4f * density,
                r.right - 4f * density, r.bottom - 4f * density,
                goldFilletPaint
            )

            // Inner gold leaf rim
            val innerRim = RectF(
                r.left + frameMoldingWidth - 3f * density,
                r.top + frameMoldingWidth - 3f * density,
                r.right - frameMoldingWidth + 3f * density,
                r.bottom - frameMoldingWidth + 3f * density
            )
            goldFilletPaint.color = Color.parseColor("#D4AF37") // Metallic gold
            canvas.drawRect(innerRim, goldFilletPaint)
        } else {
            // Modern Museum Gallery Frame: sleek deep espresso/dark oak with refined brass inner fillet
            outerMoldingPaint.color = Color.parseColor("#1C1A18") // Dark slate oak
            canvas.drawRoundRect(r, radius, radius, outerMoldingPaint)

            // Top edge delicate light catch
            bevelHighlightPaint.color = Color.parseColor("#3A3734")
            canvas.drawRect(r.left, r.top, r.right, r.top + 2f * density, bevelHighlightPaint)

            // Directional lateral bevel lighting
            when (lightingProfile.direction) {
                LightDirection.LEFT -> {
                    bevelHighlightPaint.color = Color.parseColor("#32302D")
                    canvas.drawRect(r.left, r.top, r.left + 2f * density, r.bottom, bevelHighlightPaint)
                    bevelShadowPaint.color = Color.parseColor("#080706")
                    canvas.drawRect(r.right - 2f * density, r.top, r.right, r.bottom, bevelShadowPaint)
                }
                LightDirection.RIGHT -> {
                    bevelHighlightPaint.color = Color.parseColor("#32302D")
                    canvas.drawRect(r.right - 2f * density, r.top, r.right, r.bottom, bevelHighlightPaint)
                    bevelShadowPaint.color = Color.parseColor("#080706")
                    canvas.drawRect(r.left, r.top, r.left + 2f * density, r.bottom, bevelShadowPaint)
                }
                LightDirection.CENTER -> {
                    bevelShadowPaint.color = Color.parseColor("#080706")
                    canvas.drawRect(r.left, r.bottom - 2f * density, r.right, r.bottom, bevelShadowPaint)
                }
            }

            // Bottom edge shadow
            bevelShadowPaint.color = Color.parseColor("#0C0A09")
            canvas.drawRect(r.left, r.bottom - 2f * density, r.right, r.bottom, bevelShadowPaint)

            // Refined warm brass inner fillet
            val innerFillet = RectF(
                r.left + frameMoldingWidth - 2.5f * density,
                r.top + frameMoldingWidth - 2.5f * density,
                r.right - frameMoldingWidth + 2.5f * density,
                r.bottom - frameMoldingWidth + 2.5f * density
            )
            goldFilletPaint.color = Color.parseColor("#9E8047") // Brushed brass
            canvas.drawRect(innerFillet, goldFilletPaint)
        }
    }

    private fun drawMuseumMatte(canvas: Canvas, r: RectF) {
        // Archival museum cotton matte board
        mattePaint.color = if (isClassicalStyle) Color.parseColor("#F4EFE6") else Color.parseColor("#EFEBE4")
        canvas.drawRect(r, mattePaint)

        // 45-degree core bevel around the inner aperture:
        // Top and light-side receive subtle shadow / reflection based on direction
        bevelShadowPaint.color = Color.parseColor("#26000000") // 15% black
        bevelHighlightPaint.color = Color.parseColor("#4DFFFFFF") // 30% white

        canvas.drawRect(r.left + matteWidth - innerBevelWidth, r.top + matteWidth - innerBevelWidth,
            r.right - matteWidth + innerBevelWidth, r.top + matteWidth, bevelShadowPaint)

        if (lightingProfile.direction == LightDirection.LEFT) {
            canvas.drawRect(r.left + matteWidth - innerBevelWidth, r.top + matteWidth,
                r.left + matteWidth, r.bottom - matteWidth + innerBevelWidth, bevelShadowPaint)
            canvas.drawRect(r.right - matteWidth, r.top + matteWidth,
                r.right - matteWidth + innerBevelWidth, r.bottom - matteWidth, bevelHighlightPaint)
        } else if (lightingProfile.direction == LightDirection.RIGHT) {
            canvas.drawRect(r.right - matteWidth, r.top + matteWidth,
                r.right - matteWidth + innerBevelWidth, r.bottom - matteWidth, bevelShadowPaint)
            canvas.drawRect(r.left + matteWidth - innerBevelWidth, r.top + matteWidth,
                r.left + matteWidth, r.bottom - matteWidth + innerBevelWidth, bevelHighlightPaint)
        } else {
            canvas.drawRect(r.left + matteWidth - innerBevelWidth, r.top + matteWidth,
                r.left + matteWidth, r.bottom - matteWidth + innerBevelWidth, bevelShadowPaint)
            canvas.drawRect(r.right - matteWidth, r.top + matteWidth,
                r.right - matteWidth + innerBevelWidth, r.bottom - matteWidth, bevelHighlightPaint)
        }

        // Bottom receives subtle light reflection
        canvas.drawRect(r.left + matteWidth - innerBevelWidth, r.bottom - matteWidth,
            r.right - matteWidth + innerBevelWidth, r.bottom - matteWidth + innerBevelWidth, bevelHighlightPaint)
    }

    private fun drawCanvasRecessShadow(canvas: Canvas, r: RectF) {
        // Top inner vignette
        innerShadowPaint.shader = LinearGradient(
            0f, r.top, 0f, r.top + 6f * density,
            Color.parseColor("#33000000"), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(r.left, r.top, r.right, r.top + 6f * density, innerShadowPaint)

        // Lateral inner recess shadow from the light-facing side
        val (startX, endX) = when (lightingProfile.direction) {
            LightDirection.RIGHT -> Pair(r.right, r.right - 6f * density)
            else -> Pair(r.left, r.left + 6f * density)
        }
        innerShadowPaint.shader = LinearGradient(
            startX, 0f, endX, 0f,
            Color.parseColor("#26000000"), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        val shadowLeft = minOf(startX, endX)
        val shadowRight = maxOf(startX, endX)
        canvas.drawRect(shadowLeft, r.top, shadowRight, r.bottom, innerShadowPaint)
        innerShadowPaint.shader = null
    }

    private fun drawSpotlightSheen(canvas: Canvas, r: RectF) {
        val cx = when (lightingProfile.direction) {
            LightDirection.LEFT -> r.left + r.width() * 0.32f
            LightDirection.CENTER -> r.centerX()
            LightDirection.RIGHT -> r.left + r.width() * 0.68f
        }
        val cy = r.top + r.height() * 0.32f
        val radius = maxOf(r.width(), r.height()) * 0.7f

        val tint = lightingProfile.spotlightWallColor
        val sheenColor = Color.argb(0x18, Color.red(tint), Color.green(tint), Color.blue(tint))

        spotlightSheenPaint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(sheenColor, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(r, spotlightSheenPaint)
        spotlightSheenPaint.shader = null
    }
}
