package com.roundlight.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.*
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.roundlight.app.data.Diagnostics
import java.io.File
import java.util.UUID
import kotlin.math.*

data class Lens(val cameraId: String,val physicalId: String?,val label: String,val chars: CameraCharacteristics,val physicalRequestKeys: Set<CaptureRequest.Key<*>> = emptySet()) {
    val key get() = cameraId+":"+physicalId.orEmpty()
    val sizes get() = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG).orEmpty().sortedByDescending { it.width.toLong()*it.height }
    val rawSize get() = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong()*it.height }
    val apertures get() = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf()
    val manual get() = (chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()).contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) &&
        chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) != null &&
        chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) != null
    val aeLock get() = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE)==true
    val awbLock get() = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE)==true
    val wbModes get() = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
}
data class CameraSettings(val size: Size=Size(4000,3000),val raw: Boolean=false,val locked: Boolean=false,
    val manual: Boolean=false,val iso: Int=100,val exposureNs: Long=4_000_000,val aperture: Float?=null,val wb: Int=CaptureRequest.CONTROL_AWB_MODE_AUTO)

/** All camera/session/image ownership lives on a single handler. Exactly one capture may be in flight. */
class CameraEngine(private val context: Context,private val tempDir: File,private val log: Diagnostics,
    private val status: (String)->Unit,private val captured: (ByteArray,File?,String)->Unit,private val failed: (String)->Unit) {
    private val manager=context.getSystemService(CameraManager::class.java)
    private val thread=HandlerThread("roundlight-camera").apply { start() }; private val handler=Handler(thread.looper)
    private var device: CameraDevice?=null; private var session: CameraCaptureSession?=null
    private var jpegReader: ImageReader?=null; private var rawReader: ImageReader?=null; private var surface: Surface?=null
    private var preview: TextureView?=null; private var lens: Lens?=null; private var settings=CameraSettings()
    private var last: TotalCaptureResult?=null; private var lockedGains: RggbChannelVector?=null; private var lockedTransform: ColorSpaceTransform?=null
    private var pending: Pending?=null; private var generation=0; @Volatile private var closed=false
    private data class Pending(val id: String=UUID.randomUUID().toString(),var jpeg: ByteArray?=null,var jpegTime: Long=0,var raw: Image?=null,var result: TotalCaptureResult?=null)
    fun lenses(): List<Lens> {
        val ids=manager.cameraIdList.toSet(); val result=mutableListOf<Lens>()
        for(id in ids) {
            val c=manager.getCameraCharacteristics(id); val facing=if(c.get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_FRONT)"Front" else "Rear"
            fun label(ch: CameraCharacteristics,suffix: String): String { val focal=ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull(); return "$facing · ${focal?.let { "%.1f mm".format(it) } ?: "Camera"} $suffix" }
            if(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG)?.isNotEmpty()==true)
                result+=Lens(id,null,label(c,"($id)"),c)
            val physicalKeys = c.availablePhysicalCameraRequestKeys?.toSet().orEmpty()
            c.physicalCameraIds.filter { it !in ids }.forEach { physical ->
                try { val pc=manager.getCameraCharacteristics(physical); if(pc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG)?.isNotEmpty()==true)
                    result+=Lens(id,physical,label(pc,"· lens $physical"),pc,physicalKeys)
                } catch(e:Exception) { log.failure("camera.physical",e) }
            }
        }
        return result
    }
    fun open(view: TextureView,choice: Lens,config: CameraSettings) {
        closed=false
        view.surfaceTextureListener=object: TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(t: SurfaceTexture,w: Int,h: Int) { handler.post { openInternal() } }
            override fun onSurfaceTextureSizeChanged(t: SurfaceTexture,w: Int,h: Int) { orientPreview() }
            override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean { close(); return true }
            override fun onSurfaceTextureUpdated(t: SurfaceTexture) {}
        }
        val available = view.isAvailable
        handler.post {
            preview=view; lens=choice; settings=config.copy(locked=false)
            if (available) openInternal()
        }
    }
    @SuppressLint("MissingPermission") private fun openInternal() {
        if(closed)return
        releaseCamera(); val epoch=++generation; val choice=lens ?: return
        try {
            manager.openCamera(choice.cameraId,object: CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { if(closed || epoch!=generation)camera.close() else { device=camera; configure(epoch) } }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); if(epoch==generation) { releaseCamera(); failed("Camera disconnected. Return to the shoot and reopen capture.") } }
                override fun onError(camera: CameraDevice,error: Int) { camera.close(); if(epoch==generation) { releaseCamera(); log.event("camera.device_error",mapOf("code" to error)); failed("Camera unavailable ($error). Close other camera apps and retry.") } }
            },handler)
        } catch(e:Exception) { log.failure("camera.open",e); failed("Camera could not open. Check camera permission and retry.") }
    }
    private fun configure(epoch: Int) {
        val cam=device ?: return; val choice=lens ?: return; val texture=preview?.surfaceTexture ?: return
        try {
            val map=choice.chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val ratio=settings.size.width.toFloat()/settings.size.height
            val previewSize=map.getOutputSizes(SurfaceTexture::class.java).filter { max(it.width,it.height)<=1920 }
                .minByOrNull { abs(it.width.toFloat()/it.height-ratio)*10000+abs(it.width-1280) } ?: Size(1280,720)
            texture.setDefaultBufferSize(previewSize.width,previewSize.height); surface=Surface(texture)
            jpegReader=ImageReader.newInstance(settings.size.width,settings.size.height,ImageFormat.JPEG,2).also { reader ->
                reader.setOnImageAvailableListener({ r ->
                    val image=r.acquireNextImage() ?: return@setOnImageAvailableListener
                    image.use { val task=pending ?: return@use; val buffer=it.planes[0].buffer; task.jpeg=ByteArray(buffer.remaining()).also(buffer::get); task.jpegTime=it.timestamp; finishIfReady() }
                },handler)
            }
            if(settings.raw) {
                val size=choice.rawSize ?: error("RAW unavailable")
                rawReader=ImageReader.newInstance(size.width,size.height,ImageFormat.RAW_SENSOR,2).also { reader ->
                    reader.setOnImageAvailableListener({ r -> val image=r.acquireNextImage() ?: return@setOnImageAvailableListener
                        val task=pending; if(task==null)image.close() else { task.raw?.close(); task.raw=image; finishIfReady() }
                    },handler)
                }
            }
            val outputs=listOfNotNull(surface,jpegReader?.surface,rawReader?.surface).map { s -> OutputConfiguration(s).apply { choice.physicalId?.let(::setPhysicalCameraId) } }
            cam.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,outputs,{ handler.post(it) },object: CameraCaptureSession.StateCallback() {
                override fun onConfigured(value: CameraCaptureSession) {
                    if(closed || epoch!=generation){value.close();return}; session=value; repeatPreview(); orientPreview()
                    status("Metering the scene…")
                }
                override fun onConfigureFailed(value: CameraCaptureSession) { value.close(); log.event("camera.session_failed"); failed(if(settings.raw)"This lens cannot combine RAW + JPEG at this size. Choose JPEG or a lower resolution and retry." else "This lens/size combination is unavailable. Choose the main rear camera or a smaller resolution.") }
            }))
        } catch(e:Exception) { log.failure("camera.configure",e); failed("Capture setup failed. Try JPEG and a smaller resolution.") }
    }
    private fun orientPreview() { val view=preview ?: return
        // Camera2's SurfaceTexture producer already supplies sensor orientation for the
        // fixed portrait window. Applying sensor rotation again turns the viewfinder sideways.
        view.post { view.setTransform(Matrix()) }
    }
    private fun physicalResult(result: TotalCaptureResult, physicalId: String?): CaptureResult {
        if (physicalId == null) return result
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            result.physicalCameraTotalResults[physicalId] ?: result
        } else {
            @Suppress("DEPRECATION")
            result.physicalCameraResults[physicalId] ?: result
        }
    }
    private fun validGains(gains: RggbChannelVector?): Boolean {
        if (gains == null) return false
        return listOf(gains.red, gains.greenEven, gains.greenOdd, gains.blue).all { it.isFinite() && it > 0f }
    }
    private fun apply(builder: CaptureRequest.Builder) {
        val choice=lens ?: return; val ch=choice.chars
        fun <T> set(key: CaptureRequest.Key<T>,value: T) {
            builder.set(key,value)
            val physical=choice.physicalId
            if(physical!=null && key in choice.physicalRequestKeys) builder.setPhysicalCameraKey(key,value,physical)
        }
        set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO)
        val af=ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        if(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in af)set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_OFF)
        if(settings.manual && choice.manual) {
            set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF)
            val iso=ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)!!.clamp(settings.iso)
            val exposure=ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)!!.clamp(settings.exposureNs)
            set(CaptureRequest.SENSOR_SENSITIVITY,iso); set(CaptureRequest.SENSOR_EXPOSURE_TIME,exposure)
            ch.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)?.let { set(CaptureRequest.SENSOR_FRAME_DURATION,max(exposure,33_333_333L).coerceAtMost(it)) }
        } else { set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON); if(choice.aeLock)set(CaptureRequest.CONTROL_AE_LOCK,settings.locked) }
        settings.aperture?.takeIf { aperture -> choice.apertures.any { it==aperture } }?.let { set(CaptureRequest.LENS_APERTURE,it) }
        val manualPost = (ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()).contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
        val gains = lockedGains
        if (settings.wb != CaptureRequest.CONTROL_AWB_MODE_AUTO) {
            set(CaptureRequest.CONTROL_AWB_MODE, settings.wb)
            if (choice.awbLock) set(CaptureRequest.CONTROL_AWB_LOCK, false)
        } else if (choice.awbLock) {
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            set(CaptureRequest.CONTROL_AWB_LOCK, settings.locked)
        } else if (settings.locked && gains != null && manualPost) {
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            set(CaptureRequest.COLOR_CORRECTION_GAINS, gains); lockedTransform?.let { set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, it) }
        } else {
            set(CaptureRequest.CONTROL_AWB_MODE, settings.wb)
        }
    }
    private fun repeatPreview() {
        try { val request=device?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW) ?: return; surface?.let(request::addTarget); apply(request)
            session?.setRepeatingRequest(request.build(),object: CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s:CameraCaptureSession,r:CaptureRequest,result:TotalCaptureResult) {
                    val first=last==null;last=result
                    if(first)status("Ready · compose, then lock exposure and white balance")
                }
            },handler)
        } catch(e:Exception) { log.failure("camera.preview",e); failed("Camera settings were rejected. Reopen capture with automatic exposure.") }
    }
    fun update(config: CameraSettings) { handler.post { settings=config; if(!config.locked){lockedGains=null;lockedTransform=null}; repeatPreview() } }
    fun lock(onLocked: (CameraSettings)->Unit) { handler.post {
        val choice=lens ?: return@post; val result=last
        if(result==null){failed("Wait for the preview to settle before locking.");return@post}
        val measured=physicalResult(result,choice.physicalId)
        val measuredGains=measured.get(CaptureResult.COLOR_CORRECTION_GAINS)
        lockedGains=measuredGains.takeIf(::validGains); lockedTransform=measured.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        val keepManual = settings.manual && choice.manual
        settings=settings.copy(
            locked=true,
            manual=choice.manual,
            iso=if(keepManual) settings.iso else measured.get(CaptureResult.SENSOR_SENSITIVITY) ?: settings.iso,
            exposureNs=if(keepManual) settings.exposureNs else measured.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: settings.exposureNs,
            aperture=measured.get(CaptureResult.LENS_APERTURE) ?: settings.aperture
        )
        log.event("camera.locked",mapOf("iso" to settings.iso,"exposureNs" to settings.exposureNs,"manual" to if(choice.manual)1 else 0,"wbLock" to if(choice.awbLock)1 else 0))
        repeatPreview(); onLocked(settings); status(if(settings.wb!=CaptureRequest.CONTROL_AWB_MODE_AUTO || choice.awbLock || lockedGains!=null)"Exposure and white balance locked" else "Exposure fixed · use a fixed white-balance preset")
    } }
    fun capture() { handler.post {
        if(pending!=null)return@post
        val cam=device; val ses=session
        if(cam==null || ses==null){failed("Camera is still opening. Try again in a moment.");return@post}
        try {
            val task=Pending(); pending=task
            val request=cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE); jpegReader?.surface?.let(request::addTarget); rawReader?.surface?.let(request::addTarget)
            apply(request); request.set(CaptureRequest.JPEG_QUALITY,98.toByte()); request.set(CaptureRequest.JPEG_ORIENTATION,lens!!.chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90)
            ses.capture(request.build(),object: CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s:CameraCaptureSession,r:CaptureRequest,result:TotalCaptureResult) { if(pending===task){task.result=result;finishIfReady()} }
                override fun onCaptureFailed(s:CameraCaptureSession,r:CaptureRequest,failure:CaptureFailure) { if(pending===task){discard();failed("The camera did not finish this frame. Please retake it.")} }
            },handler)
            handler.postDelayed({if(pending===task){discard();failed("Capture timed out. No incomplete frame was added; reopen the camera and retry.")}},15000)
        } catch(e:Exception){discard();log.failure("camera.capture",e);failed("Unable to capture this frame. Your previous photos are safe.")}
    } }
    private fun finishIfReady() {
        val task=pending ?: return; val bytes=task.jpeg ?: return; val result=task.result ?: return
        if(settings.raw && task.raw==null)return
        var rawFile: File?=null
        try {
            val timestamp=result.get(CaptureResult.SENSOR_TIMESTAMP)
            require(timestamp==null || (task.jpegTime==timestamp && (task.raw==null || task.raw!!.timestamp==timestamp))) { "Frame timestamp mismatch" }
            val choice=lens!!; val metadata=physicalResult(result,choice.physicalId)
            if(task.raw!=null) {
                rawFile=File(tempDir,"${task.id}.dng")
                DngCreator(choice.chars,metadata).use { dng ->
                    dng.setOrientation(when(choice.chars.get(CameraCharacteristics.SENSOR_ORIENTATION)){90->6;180->3;270->8;else->1})
                    rawFile.outputStream().use { dng.writeImage(it,task.raw!!) }
                }
            }
            val isoText = metadata.get(CaptureResult.SENSOR_SENSITIVITY)?.toString() ?: "auto"
            val expText = metadata.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { "%.2f ms".format(it / 1_000_000f) } ?: "auto"
            val apText = metadata.get(CaptureResult.LENS_APERTURE)?.let { "f/$it" } ?: "f/—"
            val info="${choice.label} · ISO $isoText · $expText · $apText · WB ${settings.wb} · locked ${settings.locked}"
            task.raw?.close(); task.raw=null; pending=null; captured(bytes,rawFile,info)
        } catch(e:Exception) { rawFile?.delete();discard();log.failure("camera.save",e);failed("Could not finish saving this frame. Try JPEG if RAW repeatedly fails.") }
    }
    private fun discard(){pending?.raw?.close();pending=null}
    private fun releaseCamera(){discard();session?.close();session=null;device?.close();device=null;jpegReader?.close();jpegReader=null;rawReader?.close();rawReader=null;surface?.release();surface=null;last=null;lockedGains=null;lockedTransform=null}
    fun close(){closed=true;handler.post { generation++;releaseCamera() }}
    fun dispose(){closed=true;handler.post { generation++;releaseCamera();thread.quitSafely() }}
}
