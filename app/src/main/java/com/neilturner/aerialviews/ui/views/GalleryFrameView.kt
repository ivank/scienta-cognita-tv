package com.neilturner.aerialviews.ui.views

import android.content.Context
import android.graphics.Bitmap
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
 * - Dignified architectural museum molding with smooth anti-aliased bevels and gold leaf fillet (zero pixel shimmer/crawling).
 * - Multi-layered physical wall shadows (tight contact AO, directional cast shadow, diffuse penumbra) anchoring the frame securely to the wall.
 * - Archival museum cotton matte (passe-partout) with angled inner core bevel & golden fillet.
 * - Material integration filters on the painting: linen weave micro-relief, molding overhang shadow, ambient room color wash, and satin varnish sheen.
 * - Hardware layer acceleration for silky-smooth sub-pixel Ken Burns scaling on 4K/1080p TVs.
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
        val shadowPadY = 36f * density
        val fTop = shadowPadY - 4f * density
        val fBottom = height.toFloat() - shadowPadY - 4f * density
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
    private val frameMoldingWidth = 22f * density
    private val matteWidth = 12f * density
    private val innerBevelWidth = 2.5f * density
    private val totalBorderPerSide = frameMoldingWidth + matteWidth

    // Drawing helpers
    private val frameRect = RectF()
    private val matteRect = RectF()
    private val canvasRect = RectF()
    private val shadowRect = RectF()
    private val tempRect = RectF()
    private val drawableBounds = Rect()

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val moldingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bevelHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bevelShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val goldFilletPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mattePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val canvasFilterPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linenTexturePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Reusable linen canvas texture bitmap
    private var linenBitmap: Bitmap? = null

    init {
        setWillNotDraw(false)
        // Enable hardware layer for silky-smooth sub-pixel bilinear interpolation during Ken Burns zoom
        setLayerType(LAYER_TYPE_HARDWARE, null)
        createLinenTexture()
    }

    private fun createLinenTexture() {
        val size = (16 * density).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Subtle weave pattern
        p.color = Color.argb(12, 0, 0, 0)
        p.strokeWidth = 1f * density
        for (i in 0 until size step (3 * density).toInt().coerceAtLeast(2)) {
            c.drawLine(0f, i.toFloat(), size.toFloat(), i.toFloat(), p)
            c.drawLine(i.toFloat(), 0f, i.toFloat(), size.toFloat(), p)
        }
        p.color = Color.argb(8, 255, 255, 255)
        for (i in 1 until size step (3 * density).toInt().coerceAtLeast(2)) {
            c.drawLine(0f, i.toFloat(), size.toFloat(), i.toFloat(), p)
            c.drawLine(i.toFloat(), 0f, i.toFloat(), size.toFloat(), p)
        }

        linenBitmap = bmp
        linenTexturePaint.shader = android.graphics.BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableW = MeasureSpec.getSize(widthMeasureSpec).toFloat()
        val availableH = MeasureSpec.getSize(heightMeasureSpec).toFloat()

        if (availableW <= 0f || availableH <= 0f) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        // On a 1080p screen, the vertical wall span is ~580px (between crown molding and baseboard).
        // Target an authentic frame height occupying ~52% of screen height (e.g. ~560px on 1080p)
        // so it hangs squarely on the wall with breathing room above and below.
        val maxAllowedH = availableH * 0.53f
        val maxAllowedW = availableW * 0.68f

        var targetCanvasH = maxAllowedH - 2 * totalBorderPerSide
        var targetCanvasW = targetCanvasH * paintingAspectRatio
        if (targetCanvasW + 2 * totalBorderPerSide > maxAllowedW) {
            targetCanvasW = maxAllowedW - 2 * totalBorderPerSide
            targetCanvasH = targetCanvasW / paintingAspectRatio
        }
        val targetFrameW = targetCanvasW + 2 * totalBorderPerSide
        val targetFrameH = targetCanvasH + 2 * totalBorderPerSide

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
        val fTop = shadowPadY - 4f * density
        val fRight = width.toFloat() - shadowPadX
        val fBottom = height.toFloat() - shadowPadY - 4f * density

        frameRect.set(fLeft, fTop, fRight, fBottom)

        // 1. Multi-tier physical wall shadows (grounds frame securely onto gallery wall)
        drawRealisticDropShadow(canvas, frameRect)

        // 2. Architectural Museum Molding (smooth, elegant, anti-aliased)
        drawArchitecturalFrameMolding(canvas, frameRect)

        // 3. Archival museum cotton matte (passe-partout)
        matteRect.set(
            frameRect.left + frameMoldingWidth,
            frameRect.top + frameMoldingWidth,
            frameRect.right - frameMoldingWidth,
            frameRect.bottom - frameMoldingWidth
        )
        drawMuseumMatte(canvas, matteRect)

        // 4. Painting canvas aperture
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
            // Clean clip inside inner archival bevel aperture
            canvas.clipRect(canvasRect)
            d.draw(canvas)

            // 5. Apply physical canvas integration filters (linen weave, room lighting bleed, overhang shadow, satin varnish)
            drawCanvasMaterialAndLightingFilters(canvas, canvasRect)
            canvas.restore()
        }
    }

    private fun drawRealisticDropShadow(canvas: Canvas, r: RectF) {
        val shadowOffsetX = 20f * density * lightingProfile.shadowOffsetXFactor
        val shadowAlpha = lightingProfile.shadowAlpha

        // 1. Soft diffuse ambient penumbra (wide soft shadow showing proximity to the wall)
        shadowRect.set(
            r.left - 10f * density + shadowOffsetX * 0.18f,
            r.top - 3f * density,
            r.right + 10f * density + shadowOffsetX * 0.18f,
            r.bottom + 16f * density
        )
        shadowPaint.color = Color.argb((shadowAlpha * 0.28f).toInt(), 10, 8, 6)
        canvas.drawRoundRect(shadowRect, 12f * density, 12f * density, shadowPaint)

        // 2. Tight contact ambient occlusion (AO) directly behind the physical frame perimeter
        shadowRect.set(
            r.left - 2f * density + shadowOffsetX * 0.10f,
            r.top + 1.5f * density,
            r.right + 2f * density + shadowOffsetX * 0.10f,
            r.bottom + 6f * density
        )
        shadowPaint.color = Color.argb((shadowAlpha * 0.82f).toInt(), 8, 6, 4)
        canvas.drawRoundRect(shadowRect, 5f * density, 5f * density, shadowPaint)

        // 3. Deep downward directional cast shadow onto the wall below the frame
        shadowRect.set(
            r.left + 4f * density + shadowOffsetX * 0.45f,
            r.bottom - 2f * density,
            r.right - 4f * density + shadowOffsetX * 0.45f,
            r.bottom + 24f * density
        )
        shadowPaint.shader = LinearGradient(
            shadowOffsetX * 0.5f, r.bottom - 2f * density,
            shadowOffsetX, r.bottom + 24f * density,
            Color.argb(shadowAlpha, 8, 6, 4),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(shadowRect, shadowPaint)

        // 4. Directional lateral cast shadow along the side opposite to the light
        if (lightingProfile.direction == LightDirection.LEFT) {
            // Light from left -> casts shadow to the right
            shadowRect.set(
                r.right - 4f * density,
                r.top + 6f * density,
                r.right + 18f * density,
                r.bottom + 14f * density
            )
            shadowPaint.shader = LinearGradient(
                r.right - 4f * density, 0f,
                r.right + 18f * density, 0f,
                Color.argb((shadowAlpha * 0.85f).toInt(), 8, 6, 4),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(shadowRect, shadowPaint)
        } else if (lightingProfile.direction == LightDirection.RIGHT) {
            // Light from right -> casts shadow to the left
            shadowRect.set(
                r.left - 18f * density,
                r.top + 6f * density,
                r.left + 4f * density,
                r.bottom + 14f * density
            )
            shadowPaint.shader = LinearGradient(
                r.left + 4f * density, 0f,
                r.left - 18f * density, 0f,
                Color.argb((shadowAlpha * 0.85f).toInt(), 8, 6, 4),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(shadowRect, shadowPaint)
        }

        shadowPaint.shader = null
    }

    private fun drawArchitecturalFrameMolding(canvas: Canvas, r: RectF) {
        val radius = 3f * density
        val m = frameMoldingWidth

        // 1. Tier 1: Outer Hardwood Cove Molding
        // Dark museum antique mahogany / walnut with subtle depth
        val outerWood = if (isClassicalStyle) Color.parseColor("#20140D") else Color.parseColor("#181512")
        moldingPaint.color = outerWood
        canvas.drawRoundRect(r, radius, radius, moldingPaint)

        // 2. Direct Overhead Light Catch on Top Rim (grounds frame against vertical wall)
        bevelHighlightPaint.shader = LinearGradient(
            0f, r.top,
            0f, r.top + 2.5f * density,
            Color.argb(0x85, 255, 235, 190), // Warm gold highlight catch on top edge
            Color.argb(0x10, 255, 235, 190),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(r.left, r.top, r.right, r.top + 2.5f * density, bevelHighlightPaint)
        bevelHighlightPaint.shader = null

        // 3. Directional Lateral Bevel Lighting (Light facing side catches light; opposite side recedes)
        when (lightingProfile.direction) {
            LightDirection.LEFT -> {
                bevelHighlightPaint.color = Color.parseColor("#382418")
                canvas.drawRect(r.left, r.top, r.left + 2f * density, r.bottom, bevelHighlightPaint)
                bevelShadowPaint.color = Color.parseColor("#0E0805")
                canvas.drawRect(r.right - 2f * density, r.top, r.right, r.bottom, bevelShadowPaint)
            }
            LightDirection.RIGHT -> {
                bevelHighlightPaint.color = Color.parseColor("#382418")
                canvas.drawRect(r.right - 2f * density, r.top, r.right, r.bottom, bevelHighlightPaint)
                bevelShadowPaint.color = Color.parseColor("#0E0805")
                canvas.drawRect(r.left, r.top, r.left + 2f * density, r.bottom, bevelShadowPaint)
            }
            LightDirection.CENTER -> {
                bevelShadowPaint.color = Color.parseColor("#0E0805")
                canvas.drawRect(r.left, r.bottom - 2f * density, r.right, r.bottom, bevelShadowPaint)
            }
        }

        // 4. Underside drop shadow on bottom molding rail
        bevelShadowPaint.color = Color.parseColor("#120A06")
        canvas.drawRect(r.left, r.bottom - 2.5f * density, r.right, r.bottom, bevelShadowPaint)

        // 5. Tier 2: Stepped Architectural Cove
        val coveInset = 5f * density
        tempRect.set(
            r.left + coveInset,
            r.top + coveInset,
            r.right - coveInset,
            r.bottom - coveInset
        )
        val coveColor = if (isClassicalStyle) Color.parseColor("#2C1B11") else Color.parseColor("#201C18")
        moldingPaint.color = coveColor
        canvas.drawRect(tempRect, moldingPaint)

        // Inner cove shading for authentic 3D architectural profile
        bevelShadowPaint.color = Color.parseColor("#26000000")
        canvas.drawRect(tempRect.left, tempRect.top, tempRect.right, tempRect.top + 2f * density, bevelShadowPaint)
        canvas.drawRect(tempRect.left, tempRect.top, tempRect.left + 2f * density, tempRect.bottom, bevelShadowPaint)

        // 6. Tier 3: Burnished Gold Leaf / Brushed Brass Inner Fillet
        val goldInset = m - 3f * density
        tempRect.set(
            r.left + goldInset,
            r.top + goldInset,
            r.right - goldInset,
            r.bottom - goldInset
        )
        goldFilletPaint.color = if (isClassicalStyle) Color.parseColor("#C49B38") else Color.parseColor("#9E8047")
        canvas.drawRect(tempRect, goldFilletPaint)

        // Delicate highlight on top edge of gold fillet
        bevelHighlightPaint.color = Color.argb(0x80, 255, 245, 210)
        canvas.drawRect(tempRect.left, tempRect.top, tempRect.right, tempRect.top + 1f * density, bevelHighlightPaint)
    }

    private fun drawMuseumMatte(canvas: Canvas, r: RectF) {
        // Natural warm archival cotton rag board (absorbs room light naturally, not stark white)
        mattePaint.color = if (isClassicalStyle) Color.parseColor("#EFE8DC") else Color.parseColor("#E8E2D6")
        canvas.drawRect(r, mattePaint)

        // Subtle ambient spotlight wash on matte board
        val tint = lightingProfile.spotlightWallColor
        val matteWash = Color.argb(0x10, Color.red(tint), Color.green(tint), Color.blue(tint))
        bevelHighlightPaint.color = matteWash
        canvas.drawRect(r, bevelHighlightPaint)

        // 45-degree angled inner core bevel around the aperture:
        // Top and light-side receive natural recess shadow
        bevelShadowPaint.color = Color.parseColor("#38000000") // 22% black
        bevelHighlightPaint.color = Color.parseColor("#60FFFFFF") // 37% white catch

        // Top bevel recess shadow
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

        // Bottom bevel receives delicate upward light reflection
        canvas.drawRect(
            r.left + matteWidth - innerBevelWidth, r.bottom - matteWidth,
            r.right - matteWidth + innerBevelWidth, r.bottom - matteWidth + innerBevelWidth, bevelHighlightPaint
        )

        // Fine gold filet liner directly hugging the painting aperture
        val innerGold = RectF(
            r.left + matteWidth - 1.2f * density,
            r.top + matteWidth - 1.2f * density,
            r.right - matteWidth + 1.2f * density,
            r.bottom - matteWidth + 1.2f * density
        )
        goldFilletPaint.color = Color.parseColor("#B8860B")
        canvas.drawRect(innerGold, goldFilletPaint)
    }

    private fun drawCanvasMaterialAndLightingFilters(canvas: Canvas, r: RectF) {
        // 1. Physical Linen Weave Micro-Relief Filter
        // Gives flat digital JPEG pixels the authentic tactile surface of stretched museum linen
        linenBitmap?.let {
            canvas.drawRect(r, linenTexturePaint)
        }

        // 2. Room Ambient Lighting & Color Bleed (Unified Atmosphere)
        // Binds the painting into the museum room's color temperature so it does not look like a cold digital sticker
        val tint = lightingProfile.spotlightWallColor
        val roomWashColor = Color.argb(0x18, Color.red(tint), Color.green(tint), Color.blue(tint))
        canvasFilterPaint.shader = LinearGradient(
            0f, r.top,
            0f, r.bottom,
            roomWashColor,
            Color.argb(0x08, Color.red(tint), Color.green(tint), Color.blue(tint)),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(r, canvasFilterPaint)

        // 3. Physical Frame Molding Overhang Cast Shadow
        // The carved frame molding projects 3-4 cm out from canvas; overhead spotlight casts a natural shadow downward
        val topOverhangDepth = 12f * density
        canvasFilterPaint.shader = LinearGradient(
            0f, r.top,
            0f, r.top + topOverhangDepth,
            Color.argb(0x65, 0, 0, 0),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(r.left, r.top, r.right, r.top + topOverhangDepth, canvasFilterPaint)

        // 4. Lateral molding overhang shadow from light-side frame edge
        val sideOverhangDepth = 8f * density
        if (lightingProfile.direction == LightDirection.LEFT) {
            canvasFilterPaint.shader = LinearGradient(
                r.left, 0f,
                r.left + sideOverhangDepth, 0f,
                Color.argb(0x30, 0, 0, 0),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(r.left, r.top, r.left + sideOverhangDepth, r.bottom, canvasFilterPaint)
        } else if (lightingProfile.direction == LightDirection.RIGHT) {
            canvasFilterPaint.shader = LinearGradient(
                r.right, 0f,
                r.right - sideOverhangDepth, 0f,
                Color.argb(0x30, 0, 0, 0),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(r.right - sideOverhangDepth, r.top, r.right, r.bottom, canvasFilterPaint)
        }

        // 5. Satin Museum Varnish Specular Sheen (Oil Painting Glaze)
        val cx = when (lightingProfile.direction) {
            LightDirection.LEFT -> r.left + r.width() * 0.32f
            LightDirection.CENTER -> r.centerX()
            LightDirection.RIGHT -> r.left + r.width() * 0.68f
        }
        val cy = r.top + r.height() * 0.28f
        val radius = maxOf(r.width(), r.height()) * 0.65f
        val sheenColor = Color.argb(0x20, Color.red(tint), Color.green(tint), Color.blue(tint))

        canvasFilterPaint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(sheenColor, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(r, canvasFilterPaint)
        canvasFilterPaint.shader = null
    }
}
