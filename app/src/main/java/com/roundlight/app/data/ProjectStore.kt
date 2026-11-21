package com.roundlight.app.data

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.util.AtomicFile
import androidx.exifinterface.media.ExifInterface
import com.roundlight.app.model.*
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

class ProjectStore(private val context: Context, private val log: Diagnostics) {
    val root=File(context.filesDir,"projects").apply { mkdirs() }
    fun folder(id: String): File { require(id.matches(Regex("[a-zA-Z0-9-]{1,80}"))); return File(root,id) }
    fun photo(p: Project,f: Frame) = File(folder(p.id),f.file)
    fun list(): List<Project> = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull {
        try { read(it.name) } catch (e: Exception) { log.failure("project.read",e); null }
    }.sortedByDescending { it.createdAt }
    fun read(id: String): Project = appJson.decodeFromString<Project>(AtomicFile(File(folder(id),"project.json")).openRead().bufferedReader().use { it.readText() }).validated()
    @Synchronized fun save(p: Project) {
        p.validated(); val dir=folder(p.id); check(dir.exists() || dir.mkdirs()) { "Could not create the shoot folder." }
        val file=AtomicFile(File(dir,"project.json")); val stream=file.startWrite()
        try { stream.write(appJson.encodeToString(p).toByteArray()); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun create(name: String) = Project(name=name.trim().ifBlank { "Untitled orbit" }.take(200)).also(::save)
    fun freeBytes() = root.usableSpace
    fun requireSpace(bytes: Long=80_000_000) { require(freeBytes()>bytes+30_000_000) { "Storage is nearly full. Export your shoots and free space before continuing." } }
    fun addCapture(p: Project, jpeg: ByteArray, raw: File?, metadata: String): Project {
        requireSpace(jpeg.size.toLong()); val name=UUID.randomUUID().toString(); val dest=File(folder(p.id),"$name.jpg")
        val rawDest=raw?.let { File(folder(p.id),"$name.dng") }
        try {
            dest.outputStream().use { it.write(jpeg) }
            if(raw != null) check(raw.renameTo(rawDest!!)) { "Could not save the RAW file." }
            val next=p.copy(frames=p.frames+Frame(file=dest.name,raw=rawDest?.name,opacity=1f/(p.frames.size+1),captureInfo=metadata))
            save(next); log.event("capture.saved",mapOf("frames" to next.frames.size,"bytes" to jpeg.size)); return next
        } catch(e: Exception) { dest.delete(); rawDest?.delete(); throw e }
    }
    fun import(p: Project,uri: Uri): Project {
        requireSpace(); val name="${UUID.randomUUID()}.jpg"; val temp=File(folder(p.id),"import.tmp")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { out ->
                val buffer=ByteArray(65536); var total=0L
                while(true) { val n=input.read(buffer); if(n<0)break; total+=n; require(total<256_000_000) { "This photo is too large to import." }; out.write(buffer,0,n) }
            } } ?: throw IOException("Unable to open the selected photo.")
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeFile(temp.path,bounds)
            require(bounds.outWidth>0 && bounds.outHeight>0) { "Choose a JPEG, PNG or supported photo. Import RAW with its JPEG companion." }
            // Preserve JPEG originals byte-for-byte; other formats are normalized to a bounded working JPEG.
            val actualName=if(bounds.outMimeType=="image/jpeg") name else name
            val dest=File(folder(p.id),actualName)
            if(bounds.outMimeType=="image/jpeg") check(temp.renameTo(dest)) else {
                val image=decode(temp,6000); try { dest.outputStream().use { check(image.compress(Bitmap.CompressFormat.JPEG,98,it)) } } finally { image.recycle() }
            }
            val next=p.copy(frames=p.frames+Frame(file=dest.name,opacity=1f/(p.frames.size+1)))
            try { save(next) } catch(e:Exception) { dest.delete(); throw e }; return next
        } finally { temp.delete() }
    }
    fun archive(p: Project,uri: Uri) {
        val output=context.contentResolver.openOutputStream(uri) ?: throw IOException("Unable to open the export destination.")
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("project.json")); zip.write(appJson.encodeToString(p).toByteArray()); zip.closeEntry()
            p.frames.flatMap { listOfNotNull(it.file,it.raw) }.distinct().forEach { name ->
                zip.putNextEntry(ZipEntry("photos/$name")); File(folder(p.id),name).inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        log.event("project.archived",mapOf("frames" to p.frames.size))
    }
    companion object {
        fun dimensions(file:File):Pair<Int,Int> {
            val opts=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,opts)
            require(opts.outWidth>0 && opts.outHeight>0){"A source photo is missing or unreadable."}
            val rotation=ExifInterface(file).rotationDegrees
            return if(rotation==90 || rotation==270)opts.outHeight to opts.outWidth else opts.outWidth to opts.outHeight
        }
        fun decode(file: File,maxEdge: Int): Bitmap {
            val opts=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeFile(file.path,opts)
            require(opts.outWidth>0 && opts.outHeight>0) { "A source photo is missing or unreadable." }
            // Budget for decode, EXIF rotation and resampling coexisting briefly.
            val safePixels=(Runtime.getRuntime().maxMemory()*.30/12).toLong()
            var sample=1
            while(max(opts.outWidth,opts.outHeight)/sample>maxEdge*1.5 || opts.outWidth.toLong()*opts.outHeight/(sample.toLong()*sample)>safePixels)sample*=2
            opts.inJustDecodeBounds=false; opts.inSampleSize=sample; opts.inPreferredConfig=Bitmap.Config.ARGB_8888
            var bitmap=BitmapFactory.decodeFile(file.path,opts) ?: throw IOException("Unable to decode photo.")
            val exif=ExifInterface(file); val matrix=Matrix()
            if(exif.isFlipped) matrix.postScale(-1f,1f)
            if(exif.rotationDegrees!=0) matrix.postRotate(exif.rotationDegrees.toFloat())
            if(!matrix.isIdentity) { val rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true); if(rotated!==bitmap) bitmap.recycle(); bitmap=rotated }
            val ratio=min(1f,maxEdge.toFloat()/max(bitmap.width,bitmap.height))
            if(ratio<1f) { val scaled=Bitmap.createScaledBitmap(bitmap,max(1,(bitmap.width*ratio).roundToInt()),max(1,(bitmap.height*ratio).roundToInt()),true); if(scaled!==bitmap) bitmap.recycle(); bitmap=scaled }
            return bitmap
        }
    }
}
