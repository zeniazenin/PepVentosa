package com.roundlight.app.imaging

import android.graphics.*
import com.roundlight.app.data.ProjectStore
import com.roundlight.app.model.*
import com.roundlight.app.model.Point
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.*

class Renderer(private val store: ProjectStore) {
    companion object {
        fun matrix(t: Transform,w: Int,h: Int) = Matrix().apply {
            setScale(t.scale,t.scale,w/2f,h/2f); postRotate(t.degrees,w/2f,h/2f); postTranslate(t.dx*w,t.dy*h)
        }
        fun dimensions(width: Int,height: Int,edge: Int,maxMemory: Long): Pair<Int,Int> {
            val availablePixels=(maxMemory*.42/28).toLong().coerceAtLeast(250_000)
            val scale=minOf(1.0,edge.toDouble()/max(width,height),sqrt(availablePixels.toDouble()/(width.toLong()*height)))
            return max(1,(width*scale).toInt()) to max(1,(height*scale).toInt())
        }
    }
    suspend fun render(p: Project,edge: Int=1200,solo: Int?=null,onProgress: (Int)->Unit={}): Bitmap {
        require(p.frames.isNotEmpty()) { "Take or import photos first." }
        val original=ProjectStore.dimensions(store.photo(p,p.frames.first()))
        val (w,h)=dimensions(original.first,original.second,edge,Runtime.getRuntime().maxMemory())
        val result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888); val canvas=Canvas(result)
        val useLowLight = p.lowLight && solo == null
        var luminous: Bitmap? = if (useLowLight) Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) } else null
        val luminousCanvas = luminous?.let(::Canvas)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val screenPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
        try {
            val frames=if(solo==null)p.frames else listOf(p.frames[solo.coerceIn(p.frames.indices)].copy(opacity=1f,visible=true))
            require(frames.any { it.visible && it.opacity>0 }) { "At least one layer must be visible." }
            val weights = if (useLowLight) {
                LowLightMath.layerWeights(
                    FloatArray(frames.size) { frames[it].opacity },
                    BooleanArray(frames.size) { frames[it].visible }
                )
            } else FloatArray(0)
            val lowLightBoost = if (useLowLight) LowLightMath.additiveBoost(frames.count { it.visible && it.opacity > 0f }) else 1f
            frames.forEachIndexed { index,f ->
                currentCoroutineContext().ensureActive(); onProgress(index*100/frames.size)
                if(f.visible && f.opacity>0) {
                    val source=ProjectStore.decode(store.photo(p,f),max(w,h))
                    try {
                        val transform=matrix(f.transform,w,h)
                        val sw=source.width.toFloat(); val sh=source.height.toFloat(); val fit=min(w/sw,h/sh)
                        val rect=RectF((w-sw*fit)/2,(h-sh*fit)/2,(w+sw*fit)/2,(h+sh*fit)/2)
                        val gain=ColorMatrix(floatArrayOf(f.exposureGain,0f,0f,0f,0f,0f,f.exposureGain,0f,0f,0f,0f,0f,f.exposureGain,0f,0f,0f,0f,0f,1f,0f))
                        val filter = ColorMatrixColorFilter(gain)
                        paint.colorFilter=filter
                        val screenAlpha = if (useLowLight) ((weights[index] * lowLightBoost).coerceIn(0f, 1f) * 255f).roundToInt() else 0
                        if(f.masks.isEmpty() && f.regions.isEmpty()) {
                            canvas.save(); canvas.concat(transform); paint.alpha=(255*f.opacity).roundToInt(); canvas.drawBitmap(source,null,rect,paint); canvas.restore()
                            if (luminousCanvas != null && screenAlpha > 0) {
                                screenPaint.colorFilter = filter; screenPaint.alpha = screenAlpha
                                luminousCanvas.save(); luminousCanvas.concat(transform); luminousCanvas.drawBitmap(source, null, rect, screenPaint); luminousCanvas.restore()
                            }
                        } else {
                            val layer=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                            val mask=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                            try {
                                val lc=Canvas(layer); paint.alpha=255; lc.concat(transform); lc.drawBitmap(source,null,rect,paint)
                                val mc=Canvas(mask); mc.drawColor(Color.WHITE); mc.concat(transform)
                                f.regions.forEach { region ->
                                    val path=Path(); region.points.forEachIndexed { i,pt -> if(i==0)path.moveTo(pt.x*w,pt.y*h) else path.lineTo(pt.x*w,pt.y*h) }; path.close()
                                    mc.drawPath(path,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; alpha=(region.strength*255).roundToInt();
                                        maskFilter=BlurMaskFilter(min(w,h)*.012f,BlurMaskFilter.Blur.NORMAL); xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
                                }
                                f.masks.forEach { stroke ->
                                    currentCoroutineContext().ensureActive()
                                    val radius=stroke.radius*min(w,h); val brush=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        color=Color.WHITE; alpha=(stroke.strength*255).roundToInt(); strokeWidth=radius*2
                                        strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND; style=Paint.Style.STROKE
                                        maskFilter=BlurMaskFilter(max(1f,radius*.35f),BlurMaskFilter.Blur.NORMAL)
                                        xfermode=PorterDuffXfermode(if(stroke.restore)PorterDuff.Mode.SRC_OVER else PorterDuff.Mode.DST_OUT)
                                    }
                                    val path=Path(); stroke.points.forEachIndexed { i,pt -> if(i==0)path.moveTo(pt.x*w,pt.y*h) else path.lineTo(pt.x*w,pt.y*h) }
                                    if(stroke.points.size==1) { brush.style=Paint.Style.FILL; mc.drawCircle(stroke.points[0].x*w,stroke.points[0].y*h,radius,brush) } else mc.drawPath(path,brush)
                                }
                                val maskPaint=Paint().apply { xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
                                // Mask and layer are already in canvas coordinates.
                                Canvas(layer).drawBitmap(mask,0f,0f,maskPaint)
                                canvas.drawBitmap(layer,0f,0f,Paint().apply { alpha=(255*f.opacity).roundToInt() })
                                if (luminousCanvas != null && screenAlpha > 0) {
                                    screenPaint.colorFilter = null; screenPaint.alpha = screenAlpha
                                    luminousCanvas.drawBitmap(layer, 0f, 0f, screenPaint)
                                }
                            } finally { layer.recycle(); mask.recycle() }
                        }
                    } finally { source.recycle() }
                }
            }
            // Flatten transparency onto deep night tone in low light, or warm paper in daylight.
            canvas.drawColor(if (useLowLight) Color.rgb(10, 13, 15) else Color.rgb(245, 243, 236), PorterDuff.Mode.DST_OVER)
            if (useLowLight && luminous != null) {
                val meanRow = IntArray(w)
                val lumRow = IntArray(w)
                for (y in 0 until h) {
                    if (y % 32 == 0) currentCoroutineContext().ensureActive()
                    result.getPixels(meanRow, 0, w, 0, y, w, 1)
                    luminous.getPixels(lumRow, 0, w, 0, y, w, 1)
                    LowLightMath.combineLowLightRow(meanRow, lumRow)
                    result.setPixels(meanRow, 0, w, 0, y, w, 1)
                }
                luminous.recycle(); luminous = null
                currentCoroutineContext().ensureActive()
                LowLightMath.accentuateDetailRows(
                    width = w,
                    height = h,
                    readRow = { y, buf -> result.getPixels(buf, 0, w, 0, y, w, 1) },
                    writeRow = { y, buf -> result.setPixels(buf, 0, w, 0, y, w, 1) }
                )
            }
            if(solo==null) {
                val row=IntArray(w)
                for(y in 0 until h) { if(y%32==0)currentCoroutineContext().ensureActive(); result.getPixels(row,0,w,0,y,w,1)
                    ColorMath.gradeRow(row,p.grade)
                    result.setPixels(row,0,w,0,y,w,1)
                }
            }
            onProgress(100)
            if(p.crop>0 && solo==null) { val dx=(w*p.crop).toInt(); val dy=(h*p.crop).toInt(); val cw=w-2*dx; val ch=h-2*dy; if(cw>0 && ch>0) { val cropped=Bitmap.createBitmap(result,dx,dy,cw,ch); if(cropped!==result)result.recycle(); return cropped } }
            return result
        } catch(e:Throwable) { luminous?.recycle(); result.recycle(); throw e }
    }
    suspend fun align(p: Project,onProgress: (Int)->Unit={}): Project {
        require(p.frames.isNotEmpty()); val n=96
        val first=ProjectStore.decode(store.photo(p,p.frames.first()),192); val aspect=first.width.toFloat()/first.height; first.recycle()
        fun features(f: Frame): FloatArray {
            val source=ProjectStore.decode(store.photo(p,f),192);val small=Bitmap.createBitmap(n,n,Bitmap.Config.ARGB_8888)
            try {
                val sourceAspect=source.width.toFloat()/source.height
                val a=ImageGeometry.sourceToCanvas(Point(0f,0f),sourceAspect,aspect);val b=ImageGeometry.sourceToCanvas(Point(1f,1f),sourceAspect,aspect)
                Canvas(small).drawBitmap(source,null,RectF(a.x*n,a.y*n,b.x*n,b.y*n),Paint(Paint.FILTER_BITMAP_FLAG))
                val pixels=IntArray(n*n);small.getPixels(pixels,0,n,0,0,n,n);return Alignment.edges(pixels,n,n)
            } finally {small.recycle();source.recycle()}
        }
        val ref=features(p.frames.first())
        return p.copy(frames=p.frames.mapIndexed { i,f ->
            currentCoroutineContext().ensureActive(); onProgress(i*100/p.frames.size)
            if(i==0)f.copy(transform=Transform(),confidence=1f) else {
                val result=Alignment.estimate(ref,features(f),n,n,p.anchor,aspect)
                f.copy(transform=result.transform,confidence=result.confidence)
            }
        })
    }
}
