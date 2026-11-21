package com.roundlight.app.imaging

import com.roundlight.app.model.*
import kotlin.math.*

/** Subject-weighted similarity registration. Background parallax is deliberately preserved. */
object Alignment {
    data class Result(val transform: Transform, val confidence: Float)
    fun edges(pixels: IntArray, width: Int, height: Int): FloatArray {
        val gray = FloatArray(pixels.size) { i -> val c=pixels[i]; ((c shr 16 and 255)*.2126f+(c shr 8 and 255)*.7152f+(c and 255)*.0722f)/255 }
        return FloatArray(gray.size) { i ->
            val x=i%width; val y=i/width
            if (x==0 || x==width-1 || y==0 || y==height-1) 0f
            else abs(gray[i+1]-gray[i-1])+abs(gray[i+width]-gray[i-width])
        }
    }
    fun estimate(reference: FloatArray, moving: FloatArray, width: Int, height: Int, anchor: Point, aspect: Float = width.toFloat()/height): Result {
        require(reference.size == width*height && moving.size == reference.size)
        if (reference.sum()<.01f || moving.sum()<.01f) return Result(Transform(),0f)
        fun score(t: Transform): Float {
            var sw=0.0; var sa=0.0; var sb=0.0; var saa=0.0; var sbb=0.0; var sab=0.0
            val radians = Math.toRadians(t.degrees.toDouble()); val cs=cos(radians).toFloat(); val sn=sin(radians).toFloat()
            for (y in 3 until height-3 step 2) for (x in 3 until width-3 step 2) {
                val nx=x.toFloat()/(width-1); val ny=y.toFloat()/(height-1)
                val dist=((nx-anchor.x)/.23f).pow(2)+((ny-anchor.y)/.29f).pow(2)
                if (dist > 4f) continue
                val xx=(nx-.5f-t.dx)*aspect/t.scale; val yy=(ny-.5f-t.dy)/t.scale
                val u=((cs*xx+sn*yy)/aspect+.5f)*(width-1); val v=(-sn*xx+cs*yy+.5f)*(height-1)
                if (u<1 || v<1 || u>=width-2 || v>=height-2) continue
                val ix=u.toInt(); val iy=v.toInt(); val fx=u-ix; val fy=v-iy
                val b=(moving[iy*width+ix]*(1-fx)+moving[iy*width+ix+1]*fx)*(1-fy)+(moving[(iy+1)*width+ix]*(1-fx)+moving[(iy+1)*width+ix+1]*fx)*fy
                val a=reference[y*width+x]; val w=exp(-dist.toDouble())
                sw+=w; sa+=w*a; sb+=w*b; saa+=w*a*a; sbb+=w*b*b; sab+=w*a*b
            }
            if (sw<5) return -1f
            val denominator=sqrt(max(0.0,saa-sa*sa/sw)*max(0.0,sbb-sb*sb/sw))
            return if (denominator<1e-8) -1f else ((sab-sa*sb/sw)/denominator).toFloat()
        }
        var best=Transform(); var quality=score(best)
        fun consider(t: Transform) { if (t.valid()) { val q=score(t); if (q>quality) {best=t;quality=q} } }
        for (y in -4..4) for (x in -4..4) consider(Transform(x*.04f,y*.04f))
        for (step in listOf(.04f,.02f,.01f,.004f)) {
            repeat(2) {
                val center=best
                for (s in -1..1) for (r in -1..1) for (y in -1..1) for (x in -1..1)
                    consider(center.copy(dx=center.dx+x*step,dy=center.dy+y*step,scale=center.scale+s*step,degrees=center.degrees+r*step*80))
            }
        }
        return if(quality<.30f) Result(Transform(),quality.coerceIn(0f,1f)) else Result(best,quality.coerceIn(0f,1f))
    }
}
