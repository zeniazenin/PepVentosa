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
    val cameraKey: String = "", val captureWidth: Int = 0, val captureHeight: Int = 0, val captureRaw: Boolean = false
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
}
