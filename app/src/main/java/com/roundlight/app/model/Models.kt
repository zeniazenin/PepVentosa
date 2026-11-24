package com.roundlight.app.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.*
import java.util.UUID

val appJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable data class Point(val x: Float, val y: Float)
@Serializable data class Transform(val dx: Float = 0f, val dy: Float = 0f, val scale: Float = 1f, val degrees: Float = 0f) {
    fun valid() = dx.isFinite() && dy.isFinite() && scale.isFinite() && degrees.isFinite() &&
        abs(dx) <= .4f && abs(dy) <= .4f && scale in .65f..1.5f && abs(degrees) <= 25f
    fun inverse(p: Point, aspect: Float): Point {
        val r = Math.toRadians(degrees.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
        val x = (p.x - .5f - dx) * aspect / scale; val y = (p.y - .5f - dy) / scale
        return Point((c*x+s*y)/aspect+.5f, -s*x+c*y+.5f)
    }
    fun forward(p: Point, aspect: Float): Point {
        val r=Math.toRadians(degrees.toDouble());val c=cos(r).toFloat();val s=sin(r).toFloat()
        val x=(p.x-.5f)*aspect*scale;val y=(p.y-.5f)*scale
        return Point((c*x-s*y)/aspect+.5f+dx,s*x+c*y+.5f+dy)
    }
}
@Serializable data class Stroke(val points: List<Point>, val radius: Float = .035f, val strength: Float = .8f, val restore: Boolean = false) {
    fun valid() = points.isNotEmpty() && points.size <= 10000 && radius.isFinite() && radius in .002f..0.25f &&
        strength.isFinite() && strength in 0f..1f && points.all { it.x.isFinite() && it.y.isFinite() && it.x in -1f..2f && it.y in -1f..2f }
}
@Serializable data class MaskRegion(val points: List<Point>, val strength: Float = .7f) {
    fun valid() = points.size in 3..40 && strength in 0f..1f && points.all { it.x in 0f..1f && it.y in 0f..1f }
}
@Serializable data class Frame(
    val id: String = UUID.randomUUID().toString(), val file: String, val raw: String? = null,
    val opacity: Float = 1f, val visible: Boolean = true, val transform: Transform = Transform(),
    val masks: List<Stroke> = emptyList(), val confidence: Float = 1f, val capturedAt: Long = System.currentTimeMillis(),
    val captureInfo: String = "Imported", val exposureGain: Float = 1f, val regions: List<MaskRegion> = emptyList()
)
@Serializable data class Grade(val exposure: Float = 0f, val contrast: Float = 1.06f, val saturation: Float = 1.06f, val warmth: Float = 0f)
@Serializable data class Project(
    val id: String = UUID.randomUUID().toString(), val name: String = "Untitled orbit", val createdAt: Long = System.currentTimeMillis(),
    val frames: List<Frame> = emptyList(), val anchor: Point = Point(.5f, .55f), val grade: Grade = Grade(),
    val aiNote: String = "", val crop: Float = 0f, val schemaVersion: Int = 1,
    val cameraKey: String = "", val captureWidth: Int = 0, val captureHeight: Int = 0, val captureRaw: Boolean = false,
    val lowLight: Boolean = false, val lowLightAuto: Boolean = true
) {
    fun validated(): Project {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Invalid project identifier." }
        require(schemaVersion == 1) { "This project needs a newer app version." }
        require(name.length <= 200 && frames.map { it.id }.distinct().size == frames.size)
        require(anchor.x in 0f..1f && anchor.y in 0f..1f && crop in 0f..0.2f)
        require(grade.exposure in -2f..2f && grade.contrast in .5f..1.8f && grade.saturation in 0f..2f && grade.warmth in -1f..1f)
        frames.forEach {
            require(it.file.matches(Regex("[a-zA-Z0-9_-]+\\.(jpg|jpeg|png)"))) { "Invalid photo path." }
            require(it.raw == null || it.raw.matches(Regex("[a-zA-Z0-9_-]+\\.dng")))
            require(it.opacity in 0f..1f && it.transform.valid() && it.masks.all(Stroke::valid) && it.regions.all(MaskRegion::valid) && it.exposureGain in .5f..2f)
        }
        return this
    }
    fun averaged() = copy(frames = frames.mapIndexed { i, f -> f.copy(opacity = 1f/(i+1), visible = true) })
}
data class ModelChoice(val id: String, val label: String)

object ImageGeometry {
    fun sourceToCanvas(p:Point,sourceAspect:Float,canvasAspect:Float):Point {
        val w=min(1f,sourceAspect/canvasAspect);val h=min(1f,canvasAspect/sourceAspect)
        return Point((1-w)/2+p.x*w,(1-h)/2+p.y*h)
    }
}

object ColorMath {
    fun composite(bottom: Float, top: Float, opacity: Float) = bottom*(1-opacity)+top*opacity
    fun grade(r: Float, g: Float, b: Float, v: Grade): Int {
        val ev = 2f.pow(v.exposure)
        val rr = r*ev*(1+v.warmth*.12f); val gg = g*ev; val bb = b*ev*(1-v.warmth*.12f)
        val luma = .2126f*rr+.7152f*gg+.0722f*bb
        fun channel(c: Float) = (((luma+(c-luma)*v.saturation-.5f)*v.contrast+.5f).coerceIn(0f,1f)*255+.5f).toInt()
        return (255 shl 24) or (channel(rr) shl 16) or (channel(gg) shl 8) or channel(bb)
    }
    fun gradeRow(row: IntArray, v: Grade) {
        val ev = 2f.pow(v.exposure)
        val rScale = ev * (1f + v.warmth * .12f) / 255f
        val gScale = ev / 255f
        val bScale = ev * (1f - v.warmth * .12f) / 255f
        val sat = v.saturation
        val contrast = v.contrast
        for (x in row.indices) {
            val c = row[x]
            val rr = (c shr 16 and 255) * rScale
            val gg = (c shr 8 and 255) * gScale
            val bb = (c and 255) * bScale
            val luma = .2126f * rr + .7152f * gg + .0722f * bb
            val nr = (((luma + (rr - luma) * sat - .5f) * contrast + .5f).coerceIn(0f, 1f) * 255f + .5f).toInt()
            val ng = (((luma + (gg - luma) * sat - .5f) * contrast + .5f).coerceIn(0f, 1f) * 255f + .5f).toInt()
            val nb = (((luma + (bb - luma) * sat - .5f) * contrast + .5f).coerceIn(0f, 1f) * 255f + .5f).toInt()
            row[x] = (255 shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
    }
}

/** Photographic light-metering, low-light detection, and additive exposure math. */
object LowLightMath {
    const val ENTER_EV100 = 4.8f
    const val EXIT_EV100 = 5.8f

    /** Standard photographic Exposure Value normalized to ISO 100: EV100 = log2(N^2 / t) - log2(ISO / 100). */
    fun ev100(aperture: Float?, exposureSec: Double?, iso: Int?): Float? {
        if (aperture == null || exposureSec == null || iso == null) return null
        if (!aperture.isFinite() || aperture <= 0f || !exposureSec.isFinite() || exposureSec <= 0.0 || iso <= 0) return null
        val ev = ln((aperture.toDouble() * aperture.toDouble()) / exposureSec) / ln(2.0) - ln(iso / 100.0) / ln(2.0)
        return if (ev.isFinite()) ev.toFloat() else null
    }

    /** Hysteresis decision to prevent flickering near dusk/indoor boundaries. */
    fun isLowLightEv(ev100: Float, currentlyLowLight: Boolean = false): Boolean =
        if (currentlyLowLight) ev100 < EXIT_EV100 else ev100 <= ENTER_EV100

    /** Fast histogram-based luminance detector for imported or fallback frames. */
    fun isLowLightPixels(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return false
        val hist = IntArray(256)
        var sum = 0.0
        for (c in pixels) {
            val luma = (((c shr 16 and 255) * 54 + (c shr 8 and 255) * 183 + (c and 255) * 19) shr 8).coerceIn(0, 255)
            hist[luma]++
            sum += luma
        }
        val mean = (sum / (pixels.size * 255.0)).toFloat()
        val p90Target = (pixels.size * 0.90).toInt()
        var acc = 0
        var p90Bin = 255
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= p90Target) { p90Bin = i; break }
        }
        val p90 = p90Bin / 255f
        return mean < 0.19f || (mean < 0.26f && p90 < 0.56f)
    }

    /** Exact effective weight of each layer in a normal source-over stack (harmonic 1, 1/2, ..., 1/N -> uniform 1/N). */
    fun layerWeights(opacities: FloatArray, visible: BooleanArray): FloatArray {
        val weights = FloatArray(opacities.size)
        var remaining = 1f
        for (i in opacities.lastIndex downTo 0) {
            val a = if (visible[i]) opacities[i].coerceIn(0f, 1f) else 0f
            weights[i] = remaining * a
            remaining *= (1f - a)
        }
        return weights
    }

    /** Cumulative exposure gain for additive low-light stacking, gently bounded for large frame counts. */
    fun additiveBoost(visibleCount: Int): Float =
        if (visibleCount <= 1) 1.25f else (1.25f + 0.58f * ln(visibleCount.toFloat())).coerceAtMost(2.90f)

    /** Blend noise-reduced mean pixel with additive-screen luminous pixel. */
    fun combineLowLightRow(meanRow: IntArray, luminousRow: IntArray) {
        for (x in meanRow.indices) {
            val m = meanRow[x]
            val l = luminousRow[x]
            val mr = (m shr 16) and 255; val mg = (m shr 8) and 255; val mb = m and 255
            val lr = (l shr 16) and 255; val lg = (l shr 8) and 255; val lb = l and 255
            // 32% noise-reduced base + 68% additive photon accumulation
            val rr = (mr * 0.32f + lr * 0.68f + 0.5f).toInt().coerceIn(0, 255)
            val gg = (mg * 0.32f + lg * 0.68f + 0.5f).toInt().coerceIn(0, 255)
            val bb = (mb * 0.32f + lb * 0.68f + 0.5f).toInt().coerceIn(0, 255)
            meanRow[x] = (255 shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
    }

    /**
     * Memory-light luminance unsharp mask (3-row sliding buffer) with shadow noise gate:
     * accentuates fine subject edges and low-light texture without amplifying flat-shadow grain.
     */
    fun accentuateDetailRows(
        width: Int,
        height: Int,
        amount: Float = 0.62f,
        threshold: Float = 4.0f,
        readRow: (Int, IntArray) -> Unit,
        writeRow: (Int, IntArray) -> Unit
    ) {
        if (width < 3 || height < 3) return
        val rgbPrev = IntArray(width)
        val rgbCurr = IntArray(width)
        val rgbNext = IntArray(width)
        val lumaPrev = FloatArray(width)
        val lumaCurr = FloatArray(width)
        val lumaNext = FloatArray(width)
        val outRow = IntArray(width)

        fun toLuma(rgb: IntArray, dst: FloatArray) {
            for (x in 0 until width) {
                val c = rgb[x]
                dst[x] = ((c shr 16 and 255) * 0.2126f + (c shr 8 and 255) * 0.7152f + (c and 255) * 0.0722f)
            }
        }
        readRow(0, rgbPrev); toLuma(rgbPrev, lumaPrev)
        readRow(1, rgbCurr); toLuma(rgbCurr, lumaCurr)

        for (y in 1 until height - 1) {
            readRow(y + 1, rgbNext); toLuma(rgbNext, lumaNext)
            outRow[0] = rgbCurr[0]
            outRow[width - 1] = rgbCurr[width - 1]
            for (x in 1 until width - 1) {
                val blur = (
                    lumaPrev[x - 1] + lumaPrev[x] + lumaPrev[x + 1] +
                    lumaCurr[x - 1] + lumaCurr[x] + lumaCurr[x + 1] +
                    lumaNext[x - 1] + lumaNext[x] + lumaNext[x + 1]
                ) / 9f
                val detail = lumaCurr[x] - blur
                val absDetail = abs(detail)
                if (absDetail <= threshold) {
                    outRow[x] = rgbCurr[x]
                } else {
                    // Smooth noise-gated micro-contrast delta applied equally across RGB to prevent chroma noise
                    val gated = (absDetail - threshold) * sign(detail)
                    val delta = (gated * amount).coerceIn(-38f, 38f)
                    val c = rgbCurr[x]
                    val r = ((c shr 16 and 255) + delta + 0.5f).toInt().coerceIn(0, 255)
                    val g = ((c shr 8 and 255) + delta + 0.5f).toInt().coerceIn(0, 255)
                    val b = ((c and 255) + delta + 0.5f).toInt().coerceIn(0, 255)
                    outRow[x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            writeRow(y, outRow)
            // Shift sliding window without allocations
            System.arraycopy(rgbCurr, 0, rgbPrev, 0, width)
            System.arraycopy(lumaCurr, 0, lumaPrev, 0, width)
            System.arraycopy(rgbNext, 0, rgbCurr, 0, width)
            System.arraycopy(lumaNext, 0, lumaCurr, 0, width)
        }
    }
}
