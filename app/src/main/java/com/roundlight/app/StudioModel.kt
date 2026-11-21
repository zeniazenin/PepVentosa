package com.roundlight.app

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roundlight.app.auth.*
import com.roundlight.app.camera.*
import com.roundlight.app.data.*
import com.roundlight.app.imaging.Renderer
import com.roundlight.app.model.*
import com.roundlight.app.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

class StudioModel(app: Application): AndroidViewModel(app) {
    val log=(app as RoundlightApp).log
    val store=ProjectStore(app,log); val renderer=Renderer(store); val gate=ForegroundGate()
    val auth=ChatGptAuth(CredentialStore(app)); val ai=AiDirector(auth,store,renderer,log)
    var projects by mutableStateOf<List<Project>>(emptyList()); private set
    var project by mutableStateOf<Project?>(null); private set
    var screen by mutableStateOf("home")
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null); private set
    var cameraStatus by mutableStateOf("Opening camera…")
    var capturing by mutableStateOf(false); private set
    var selected by mutableIntStateOf(0)
    var preview by mutableStateOf<Bitmap?>(null); private set
    var renderBusy by mutableStateOf(false); private set
    var previewError by mutableStateOf<String?>(null); private set
    var solo by mutableStateOf(false)
    var models by mutableStateOf<List<ModelChoice>>(emptyList()); private set
    var model by mutableStateOf("")
    var style by mutableStateOf("Natural · recognizable subject, soft surrounding texture")
    var lenses by mutableStateOf<List<Lens>>(emptyList()); private set
    var lensKey by mutableStateOf("")
    var cameraSettings by mutableStateOf(CameraSettings())
    private var operation: Job?=null; private var previewJob: Job?=null
    private val saves=Channel<Pair<Project?,CompletableDeferred<Unit>>>(Channel.UNLIMITED)
    private val history=ArrayDeque<Project>(); var undoCount by mutableIntStateOf(0); private set
    private val context=app
    val camera=CameraEngine(app,app.cacheDir,log,{ value -> viewModelScope.launch { cameraStatus=value } },{ bytes,raw,info ->
        viewModelScope.launch { val current=project
            try { if(current!=null){persist(null);val ready=current.copy(cameraKey=lensKey,captureWidth=cameraSettings.size.width,captureHeight=cameraSettings.size.height,captureRaw=cameraSettings.raw)
                val saved=withContext(Dispatchers.IO){store.addCapture(ready,bytes,raw,info)};project=saved;reload();cameraStatus="${saved.frames.size} frames saved · move a little around your subject"} else raw?.delete() }
            catch(e:Exception){raw?.delete();showError("capture",e)} finally{capturing=false}
        }
    },{ reason -> viewModelScope.launch { capturing=false;message=reason;cameraStatus=reason } })
    init {
        viewModelScope.launch { for((p,done) in saves) { try { if(p!=null)withContext(Dispatchers.IO){store.save(p)};done.complete(Unit) }catch(e:Exception){done.completeExceptionally(e);showError("project.save",e)} } }
        viewModelScope.launch { reload() }
        viewModelScope.launch { auth.state.map { it.activeClientId to it.active?.connected }.distinctUntilChanged().collect { models=emptyList();model="" } }
    }
    private suspend fun persist(p:Project?){val done=CompletableDeferred<Unit>();saves.send(p to done);done.await()}
    private suspend fun reload() { projects=withContext(Dispatchers.IO){store.list()} }
    fun run(label: String,block: suspend ()->Unit) {
        if(busy!=null)return
        operation=viewModelScope.launch {
            busy=label
            try { persist(null);block() } catch(e:CancellationException) { log.event("work.cancelled");throw e }
            catch(e:Exception){showError("work",e)} finally {busy=null}
        }
    }
    private fun showError(stage:String,error:Exception) {
        log.failure(stage,error)
        if(error is ServiceFailure)log.service(error.status,error.code,error.requestId)
        message=when(error) {
            is ServiceFailure -> error.message
            is TransportFailure -> error.message
            is IllegalArgumentException, is IllegalStateException -> error.message?.take(300) ?: "Unable to complete this edit. Your saved shoot is safe."
            is IOException -> "Could not finish reading, writing, or connecting. Check storage and connectivity; your saved shoot is safe."
            else -> "The operation could not finish. Export diagnostics from Account to troubleshoot. Your saved shoot is safe."
        }
    }
    fun cancel(){operation?.cancel();capturing=false}
    fun newShoot(name:String){run("Creating shoot…") { project=withContext(Dispatchers.IO){store.create(name)};history.clear();undoCount=0;selected=0;screen="shoot";reload() }}
    fun open(p:Project){project=p;selected=0;history.clear();undoCount=0;solo=false;screen="shoot";refreshPreview()}
    fun demo(){run("Drawing the illustrated study…") { val p=withContext(Dispatchers.IO){Demo.create(store)};open(p);screen="edit";reload() }}
    fun edit(next:Project,remember:Boolean=true){
        if(busy!=null)return
        if(remember)project?.let { history.addLast(it);while(history.size>30)history.removeFirst();undoCount=history.size }
        project=next; saves.trySend(next to CompletableDeferred());refreshPreview()
    }
    private suspend fun applyWork(next:Project){
        persist(next)
        project?.let {history.addLast(it);while(history.size>30)history.removeFirst()};undoCount=history.size;project=next;refreshPreview();reload()
    }
    fun undo(){if(history.isEmpty() || busy!=null)return;val old=history.removeLast();undoCount=history.size;edit(old,false)}
    fun frame(change:(Frame)->Frame){val p=project ?: return;if(selected !in p.frames.indices)return;edit(p.copy(frames=p.frames.mapIndexed { i,f ->if(i==selected)change(f)else f }))}
    fun refreshPreview(){
        previewJob?.cancel();previewError=null;val p=project ?: return;if(p.frames.isEmpty()){preview=null;return}
        val pick=if(solo)selected.coerceIn(p.frames.indices)else null
        previewJob=viewModelScope.launch { delay(130);renderBusy=true
            try { val image=withContext(Dispatchers.Default){renderer.render(p.copy(crop=0f),1200,pick)};preview=image }
            catch(e:CancellationException){throw e}catch(e:Exception){preview=null;previewError=e.message?.take(160) ?: "Preview unavailable";log.failure("preview",e)}finally{renderBusy=false}
        }
    }
    fun import(uris:List<Uri>){val start=project ?: return;run("Importing photos…"){
        var p=start
        for((i,uri) in uris.withIndex()){busy="Importing ${i+1} of ${uris.size}…";p=withContext(Dispatchers.IO){store.import(p,uri)};project=p}
        reload();refreshPreview()
    }}
    fun align(){val p=project ?: return;run("Aligning subject…") { val next=withContext(Dispatchers.Default){renderer.align(p){percent->viewModelScope.launch{busy="Aligning subject · $percent%"}}};applyWork(next);screen="edit";message=if(next.frames.any{it.confidence<.30f})"Some frames could not be aligned confidently. They were left in place; use the layer alignment controls." else "Subject alignment complete. Background variation has been preserved." }}
    fun aiCompose(){val p=project ?: return;if(model.isBlank()){message="Connect your account and choose an image-capable model in Account first.";return}
        val chosen=model;run("Preparing AI art direction…"){
            val aligned=withContext(Dispatchers.Default){renderer.align(p)}
            val next=withContext(Dispatchers.Default){ai.compose(aligned,chosen,style){label->viewModelScope.launch{busy=label}}}
            applyWork(next);screen="edit"
        }
    }
    fun export(uri:Uri,edge:Int,png:Boolean){val p=project ?: return;run("Rendering full-quality image…"){
        val bitmap=withContext(Dispatchers.Default){renderer.render(p,edge){percent->viewModelScope.launch{busy="Rendering export · $percent%"}}}
        try { withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use { check(bitmap.compress(if(png)Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,97,it)) } ?: throw IOException("Cannot write export")}
            message="Saved ${bitmap.width} × ${bitmap.height} ${if(png)"PNG" else "JPEG"}.";log.event("image.exported",mapOf("width" to bitmap.width,"height" to bitmap.height))
        } finally{bitmap.recycle()}
    }}
    fun archive(uri:Uri){val p=project ?: return;run("Exporting originals and editable recipe…"){withContext(Dispatchers.IO){store.archive(p,uri)};message="Shoot archive saved, including originals, RAW files and the edit recipe."}}
    fun diagnostics(uri:Uri){run("Exporting diagnostics…"){withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(log.report()) } ?: throw IOException()};message="Diagnostic log saved."}}
    fun loadModels(){run("Loading your available models…"){models=ai.models();model=models.firstOrNull()?.id.orEmpty();if(models.isEmpty())message="No available models were returned for this account."}}
    fun connect(existing:Account?,openBrowser:(String)->Unit){run("Continue in your browser, then return here…"){auth.signIn(existing,openBrowser,gate::awaitResumed);message="Account connected. Load your available models to begin."}}
    fun chooseAccount(id:String){run("Switching account…"){auth.select(id)}}
    fun signOut(){run("Disconnecting account…"){val revoked=auth.signOut();message=if(revoked)"Signed out." else "Local credentials removed. Remote revocation was not confirmed; disconnect Roundlight in ChatGPT settings."}}
    fun enumerate(){try { lenses=camera.lenses();val p=project
        if(!p?.cameraKey.isNullOrBlank())lensKey=p!!.cameraKey
        if(lensKey.isBlank())lensKey=lenses.firstOrNull()?.key.orEmpty();chooseLens(lensKey)
        if(p!=null && p.captureWidth>0 && p.captureHeight>0)cameraSettings=cameraSettings.copy(size=android.util.Size(p.captureWidth,p.captureHeight),raw=p.captureRaw)
    }catch(e:Exception){showError("camera.list",e)}}
    fun chooseLens(key:String){lensKey=key;val lens=lenses.find{it.key==key} ?:return
        val size=lens.sizes.filter{it.width.toLong()*it.height<=13_000_000}.firstOrNull() ?:lens.sizes.lastOrNull() ?:return
        cameraSettings=CameraSettings(size=size,aperture=lens.apertures.firstOrNull())
    }
    fun shutter(){if(capturing || busy!=null)return;try{store.requireSpace(if(cameraSettings.raw)250_000_000 else 60_000_000);capturing=true;camera.capture()}catch(e:Exception){showError("capture",e)}}
    fun lock(){camera.lock{config->viewModelScope.launch{cameraSettings=config}}}
    fun pauseCamera(){camera.close();if(capturing){capturing=false;cameraStatus="Capture was interrupted. Check the frame count and retake if needed."};cameraSettings=cameraSettings.copy(locked=false)}
    override fun onCleared(){camera.dispose();super.onCleared()}
}
