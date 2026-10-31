package com.neilturner.aerialviews.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.neilturner.aerialviews.models.videos.AerialMedia
import kotlin.math.abs

enum class LightDirection {
    LEFT,
    CENTER,
    RIGHT,
}

data class ArtLightingProfile(
    val name: String,
    val direction: LightDirection,
    val spotlightWallColor: Int,      // Color of spotlight cone on gallery wall
    val ambientGlowColor: Int,        // Backlight color for sculptures
    val shadowOffsetXFactor: Float,   // -1.0f to +1.0f: shifts drop shadow
    val shadowAlpha: Int,             // Wall drop shadow alpha
    val dimOverlayAlpha: Float,       // Background dim level for sculpture mode
)

object ArtLightingManager {
    val PROFILES = listOf(
        // 1. Classic Halogen Museum Warm (2800K - Left Light)
        ArtLightingProfile(
            name = "Halogen Museum Warm",
            direction = LightDirection.LEFT,
            spotlightWallColor = Color.parseColor("#32FFE082"), // Warm halogen amber beam
            ambientGlowColor = Color.parseColor("#4DFFB300"),   // Golden bronze rim glow
            shadowOffsetXFactor = 1.0f,                         // Light from left -> shadow right
            shadowAlpha = 0x58,
            dimOverlayAlpha = 0.58f,
        ),
        // 2. Cool Exhibition Daylight (5500K - Right Light)
        ArtLightingProfile(
            name = "Cool Exhibition Daylight",
            direction = LightDirection.RIGHT,
            spotlightWallColor = Color.parseColor("#28E0F7FA"), // Crisp gallery daylight beam
            ambientGlowColor = Color.parseColor("#4580DEEA"),   // Luminous marble cyan halo
            shadowOffsetXFactor = -1.0f,                        // Light from right -> shadow left
            shadowAlpha = 0x50,
            dimOverlayAlpha = 0.54f,
        ),
        // 3. Archival Overhead Museum Focus (Center Light)
        ArtLightingProfile(
            name = "Archival Overhead Focus",
            direction = LightDirection.CENTER,
            spotlightWallColor = Color.parseColor("#2CFFF8E7"), // Pure archival ivory spotlight
            ambientGlowColor = Color.parseColor("#42FFFFFF"),   // Neutral archival white halo
            shadowOffsetXFactor = 0.0f,                         // Overhead symmetrical shadow
            shadowAlpha = 0x60,
            dimOverlayAlpha = 0.62f,
        ),
        // 4. Golden Hour Radiance (Warm Saffron - Left Light)
        ArtLightingProfile(
            name = "Golden Hour Radiance",
            direction = LightDirection.LEFT,
            spotlightWallColor = Color.parseColor("#35FFB74D"), // Rich honey gold beam
            ambientGlowColor = Color.parseColor("#55FFA000"),   // Deep amber backlight
            shadowOffsetXFactor = 1.25f,                        // Wide directional shadow
            shadowAlpha = 0x52,
            dimOverlayAlpha = 0.60f,
        ),
        // 5. Dramatic Chiaroscuro (Caravaggio / Nocturne - Right Light)
        ArtLightingProfile(
            name = "Dramatic Chiaroscuro",
            direction = LightDirection.RIGHT,
            spotlightWallColor = Color.parseColor("#35EDB879"), // Intense warm candlelit spotlight
            ambientGlowColor = Color.parseColor("#48FF7043"),   // Rich terracotta ember glow
            shadowOffsetXFactor = -1.25f,                       // Dramatic angled shadow
            shadowAlpha = 0x75,                                 // Deep chiaroscuro contrast
            dimOverlayAlpha = 0.70f,
        ),
        // 6. Ethereal Moonlight / Lapis Lazuli (Center Light)
        ArtLightingProfile(
            name = "Ethereal Moonlight",
            direction = LightDirection.CENTER,
            spotlightWallColor = Color.parseColor("#2490CAF9"), // Silvery blue moonlight beam
            ambientGlowColor = Color.parseColor("#4564B5F6"),   // Lapis lazuli ethereal halo
            shadowOffsetXFactor = 0.0f,
            shadowAlpha = 0x55,
            dimOverlayAlpha = 0.65f,
        ),
    )

    fun getProfile(media: AerialMedia?): ArtLightingProfile {
        if (media == null) return PROFILES[0]
        val key = "${media.metadata.title}_${media.uri}"
        val seed = abs(key.hashCode())
        return PROFILES[seed % PROFILES.size]
    }
}

/**
 * Renders an authentic ceiling track-light spotlight cone cast onto the gallery wall
 * behind the framed painting.
 */
class GallerySpotlightOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val beamPath = Path()
    private val floorRect = RectF()
    private var currentProfile: ArtLightingProfile = ArtLightingManager.PROFILES[0]

    init {
        setWillNotDraw(false)
    }

    fun setLightingProfile(profile: ArtLightingProfile) {
        currentProfile = profile
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val lampX = when (currentProfile.direction) {
            LightDirection.LEFT -> w * 0.42f
            LightDirection.CENTER -> w * 0.50f
            LightDirection.RIGHT -> w * 0.58f
        }
        val lampY = h * 0.08f

        val c = currentProfile.spotlightWallColor
        val cr = Color.red(c)
        val cg = Color.green(c)
        val cb = Color.blue(c)
        val ca = Color.alpha(c)

        // 1. Soft ambient radial wash across wall and painting
        paint.shader = RadialGradient(
            lampX, h * 0.35f, w * 0.52f,
            intArrayOf(
                c,
                Color.argb((ca * 0.60f).toInt(), cr, cg, cb),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        // 2. Volumetric directional spotlight cone (scattering from ceiling fixture onto painting)
        beamPath.reset()
        val topHalfW = 28f * resources.displayMetrics.density
        val bottomHalfW = w * 0.36f
        val floorY = h * 0.86f

        beamPath.moveTo(lampX - topHalfW, lampY)
        beamPath.lineTo(lampX + topHalfW, lampY)
        beamPath.lineTo(lampX + bottomHalfW, floorY)
        beamPath.lineTo(lampX - bottomHalfW, floorY)
        beamPath.close()

        paint.shader = LinearGradient(
            lampX, lampY,
            lampX, floorY,
            intArrayOf(
                Color.argb((ca * 0.85f).toInt().coerceAtMost(255), cr, cg, cb),
                Color.argb((ca * 0.45f).toInt(), cr, cg, cb),
                Color.argb((ca * 0.15f).toInt(), cr, cg, cb),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.35f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(beamPath, paint)

        // 3. Ceiling lamp aperture bloom (bright point of origin)
        paint.shader = RadialGradient(
            lampX, lampY, 40f * resources.displayMetrics.density,
            intArrayOf(
                Color.argb((ca * 1.25f).toInt().coerceAtMost(255), cr, cg, cb),
                Color.argb((ca * 0.40f).toInt(), cr, cg, cb),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(lampX, lampY, 40f * resources.displayMetrics.density, paint)

        // 4. Subtle floor reflection pool on the polished parquet
        floorRect.set(lampX - w * 0.28f, floorY - 14f * resources.displayMetrics.density, lampX + w * 0.28f, floorY + 32f * resources.displayMetrics.density)
        paint.shader = RadialGradient(
            lampX, floorY, w * 0.26f,
            intArrayOf(
                Color.argb((ca * 0.30f).toInt(), cr, cg, cb),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawOval(floorRect, paint)

        paint.shader = null
    }
}

/**
 * Dynamic directional rim backlight and occlusion shadow placed behind sculptures.
 */
class SculptureBacklightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var currentProfile: ArtLightingProfile = ArtLightingManager.PROFILES[0]

    init {
        setWillNotDraw(false)
    }

    fun setLightingProfile(profile: ArtLightingProfile) {
        currentProfile = profile
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val offsetX = when (currentProfile.direction) {
            LightDirection.LEFT -> -w * 0.10f
            LightDirection.CENTER -> 0f
            LightDirection.RIGHT -> +w * 0.10f
        }
        val offsetY = -h * 0.06f

        val cx = w / 2f + offsetX
        val cy = h / 2f + offsetY
        val radius = maxOf(w, h) * 0.55f

        val glowColor = currentProfile.ambientGlowColor

        // 1. Radiant directional colored rim glow
        paint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(
                glowColor,
                Color.argb((Color.alpha(glowColor) * 0.45f).toInt(), Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor)),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawOval(0f, 0f, w, h, paint)

        // 2. Soft grounding occlusion shadow behind torso/pedestal
        paint.shader = RadialGradient(
            w / 2f, h / 2f + h * 0.08f, radius * 0.65f,
            intArrayOf(Color.parseColor("#8C000000"), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawOval(w * 0.12f, h * 0.12f, w * 0.88f, h * 0.88f, paint)
        paint.shader = null
    }
}
