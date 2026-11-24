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
    var model by mutableStateOf(auth.state.value.active?.takeIf { it.connected }?.selectedModel.orEmpty())
    val selectedModelLabel: String
        get() = models.firstOrNull { it.id == model }?.label
            ?: auth.state.value.active?.selectedModelLabel?.takeIf { it.isNotBlank() }
            ?: model
    var style by mutableStateOf("Natural · recognizable subject, soft surrounding texture")
    var lenses by mutableStateOf<List<Lens>>(emptyList()); private set
    var lensKey by mutableStateOf("")
    var cameraSettings by mutableStateOf(CameraSettings())
    var liveEv100 by mutableStateOf<Float?>(null); private set
    var meteredLowLight by mutableStateOf(false); private set
    private var accountReturnScreen = "home"
    private var operation: Job?=null; private var previewJob: Job?=null
    private val saves=Channel<Pair<Project?,CompletableDeferred<Unit>>>(Channel.UNLIMITED)
    private val history=ArrayDeque<Project>(); var undoCount by mutableIntStateOf(0); private set
    private val context=app
    val camera=CameraEngine(app,app.cacheDir,log,{ value -> viewModelScope.launch { cameraStatus=value } },{ bytes,raw,info ->
        viewModelScope.launch {
            try {
                persist(null)
                val current=project
                if(current!=null){
                    val ready=current.copy(cameraKey=lensKey,captureWidth=cameraSettings.size.width,captureHeight=cameraSettings.size.height,captureRaw=cameraSettings.raw)
                    var saved=withContext(Dispatchers.IO){store.addCapture(ready,bytes,raw,info)}
                    if (saved.lowLightAuto && !saved.lowLight) {
                        val dark = meteredLowLight || withContext(Dispatchers.IO) {
                            ProjectStore.isLowLightPhoto(store.photo(saved, saved.frames.last()))
                        }
                        if (dark) {
                            saved = saved.copy(lowLight = true)
                            withContext(Dispatchers.IO) { store.save(saved) }
                            log.event("lowlight.auto_enabled")
                        }
                    }
                    project=saved;reload()
                    val modeTag = if (saved.lowLight) " · Low Light" else ""
                    cameraStatus="${saved.frames.size} frames saved$modeTag · move a little around your subject"
                } else raw?.delete()
            } catch(e:Exception){raw?.delete();showError("capture",e)} finally{capturing=false}
        }
    },{ reason -> viewModelScope.launch { capturing=false;message=reason;cameraStatus=reason } },{ ev, isLow ->
        viewModelScope.launch {
            liveEv100 = ev
            meteredLowLight = isLow
            val current = project ?: return@launch
            if (!current.lowLightAuto || busy != null) return@launch
            if (isLow && !current.lowLight) {
                val updated = current.copy(lowLight = true)
                project = updated
                projects = projects.map { if (it.id == updated.id) updated else it }
                saves.trySend(updated to CompletableDeferred())
                log.event("lowlight.auto_enabled")
            } else if (!isLow && current.lowLight && current.frames.isEmpty()) {
                val updated = current.copy(lowLight = false)
                project = updated
                projects = projects.map { if (it.id == updated.id) updated else it }
                saves.trySend(updated to CompletableDeferred())
            }
        }
    })
    init {
        viewModelScope.launch { for((p,done) in saves) { try { if(p!=null)withContext(Dispatchers.IO){store.save(p)};done.complete(Unit) }catch(e:Exception){done.completeExceptionally(e);showError("project.save",e)} } }
        viewModelScope.launch { reload() }
        viewModelScope.launch {
            var lastKey: Pair<String?, Boolean>? = null
            auth.state.map { it.activeClientId to (it.active?.connected == true) }.distinctUntilChanged().collect { key ->
                val active = auth.state.value.active
                if (lastKey != null && lastKey != key) models = emptyList()
                model = active?.takeIf { it.connected }?.selectedModel.orEmpty()
                lastKey = key
            }
        }
    }
    private suspend fun persist(p:Project?){val done=CompletableDeferred<Unit>();saves.send(p to done);done.await()}
    private suspend fun reload() { projects=withContext(Dispatchers.IO){store.list()} }
    fun run(label: String,block: suspend ((String)->Unit)->Unit) {
        if(busy!=null)return
        busy=label
        val job=viewModelScope.launch(start=CoroutineStart.LAZY) {
            val current=coroutineContext[Job]
            val setStatus:(String)->Unit={ text ->
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    if(operation===current && current?.isActive==true && busy!=null)busy=text
                }
            }
            try { persist(null);block(setStatus) } catch(e:CancellationException) { log.event("work.cancelled");throw e }
            catch(e:Exception){showError("work",e)} finally { if(operation===current){busy=null;operation=null} }
        }
        operation=job; job.start()
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
    fun openAccount(){if(screen!="account")accountReturnScreen=if(screen=="capture")"shoot" else screen;screen="account"}
    fun openShoot(){solo=false;screen="shoot";refreshPreview()}
    fun openEdit(){screen="edit";refreshPreview()}
    fun goBack(){
        when(screen){
            "capture","edit"->{solo=false;screen="shoot";refreshPreview()}
            "account"->{
                val target=if(accountReturnScreen in setOf("shoot","edit") && project!=null)accountReturnScreen else "home"
                screen=target;if(target=="shoot" || target=="edit")refreshPreview()
            }
            else->{screen="home"}
        }
    }
    fun newShoot(name:String){run("Creating shoot…") {
        previewJob?.cancel();preview=null;previewError=null;solo=false;lensKey=""
        project=withContext(Dispatchers.IO){store.create(name)};history.clear();undoCount=0;selected=0;screen="shoot";reload()
    }}
    fun renameShoot(target:Project,newName:String){run("Renaming shoot…") {
        val fresh=projects.find{it.id==target.id} ?: target
        val updated=withContext(Dispatchers.IO){store.rename(fresh,newName)}
        if(project?.id==updated.id)project=updated
        reload()
    }}
    fun deleteShoot(target:Project){run("Deleting shoot…") {
        withContext(Dispatchers.IO){store.delete(target.id)}
        if(project?.id==target.id){previewJob?.cancel();project=null;preview=null;previewError=null;history.clear();undoCount=0;selected=0;solo=false}
        reload()
    }}
    fun open(p:Project){val fresh=projects.find{it.id==p.id} ?: p;project=fresh;selected=0;history.clear();undoCount=0;solo=false;preview=null;previewError=null;screen="shoot";refreshPreview()}
    fun demo(){run("Drawing the illustrated study…") { val p=withContext(Dispatchers.IO){Demo.create(store)};open(p);screen="edit";reload() }}
    fun edit(next:Project,remember:Boolean=true){
        if(busy!=null)return
        if(remember)project?.let { history.addLast(it);while(history.size>30)history.removeFirst();undoCount=history.size }
        project=next;projects=projects.map{if(it.id==next.id)next else it};saves.trySend(next to CompletableDeferred());refreshPreview()
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
    fun setLowLightMode(lowLight: Boolean, auto: Boolean) {
        val p = project ?: return
        if (p.lowLight == lowLight && p.lowLightAuto == auto) return
        edit(p.copy(lowLight = lowLight, lowLightAuto = auto))
    }
    fun cycleCaptureLowLightMode() {
        val p = project ?: return
        when {
            p.lowLightAuto && p.lowLight -> setLowLightMode(lowLight = false, auto = false)
            p.lowLightAuto && !p.lowLight -> setLowLightMode(lowLight = true, auto = false)
            p.lowLight -> setLowLightMode(lowLight = false, auto = false)
            else -> setLowLightMode(lowLight = meteredLowLight, auto = true)
        }
    }
    fun import(uris:List<Uri>){val start=project ?: return;run("Importing photos…"){ setStatus ->
        var p=start
        var importedCount=0
        var lowLightCount=0
        try {
            for((i,uri) in uris.withIndex()){
                setStatus("Importing ${i+1} of ${uris.size}…")
                p=withContext(Dispatchers.IO){store.import(p,uri)}
                importedCount++
                if (p.lowLightAuto && !p.lowLight) {
                    val lastFrame = p.frames.lastOrNull()
                    if (lastFrame != null && withContext(Dispatchers.IO) { ProjectStore.isLowLightPhoto(store.photo(p, lastFrame)) }) {
                        lowLightCount++
                    }
                }
                project=p
            }
            if (p.lowLightAuto && !p.lowLight && importedCount > 0 && lowLightCount * 2 >= importedCount) {
                p = p.copy(lowLight = true)
                withContext(Dispatchers.IO) { store.save(p) }
                project = p
                log.event("lowlight.auto_enabled", mapOf("frames" to lowLightCount))
                message = "Low light detected in these photos — enabled Low Light mode to add exposures and accentuate detail."
            }
        } finally {
            if(p!==start){withContext(NonCancellable){reload()};refreshPreview()}
        }
    }}
    fun align(){val p=project ?: return;run("Aligning subject…") { setStatus ->
        val next=withContext(Dispatchers.Default){renderer.align(p){percent->setStatus("Aligning subject · $percent%")}}
        applyWork(next);screen="edit";message=if(next.frames.any{it.confidence<.30f})"Some frames could not be aligned confidently. They were left in place; use the layer alignment controls." else "Subject alignment complete. Background variation has been preserved."
    }}
    fun aiCompose(){
        val p=project ?: return
        val active=auth.state.value.active
        if(active==null || !active.connected){message="Your ChatGPT account is not connected. Open Account → Continue with ChatGPT to connect.";return}
        if(!active.canUsePlan){message="ChatGPT plan usage is not authorized yet. Open Account → Continue with ChatGPT to authorize it.";return}
        run("Preparing AI art direction…"){ setStatus ->
            var chosen=model.ifBlank { active.selectedModel }
            if(chosen.isBlank()){
                setStatus("Loading your available models…")
                val fetched=withContext(Dispatchers.IO){ai.models()}
                models=fetched
                val preferred=fetched.firstOrNull() ?: error("No available models were returned for this account. Open Account to check your plan.")
                chosen=preferred.id;model=preferred.id;auth.selectModel(preferred.id,preferred.label)
            }
            val aligned=withContext(Dispatchers.Default){renderer.align(p){percent->setStatus("Aligning subject · $percent%")}}
            val next=withContext(Dispatchers.Default){ai.compose(aligned,chosen,style){label->setStatus(label)}}
            applyWork(next);screen="edit"
        }
    }
    fun export(uri:Uri,edge:Int,png:Boolean){val p=project ?: return;run("Rendering full-quality image…"){ setStatus ->
        val bitmap=withContext(Dispatchers.Default){renderer.render(p,edge){percent->setStatus("Rendering export · $percent%")}}
        try { withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use { check(bitmap.compress(if(png)Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,97,it)) } ?: throw IOException("Cannot write export")}
            message="Saved ${bitmap.width} × ${bitmap.height} ${if(png)"PNG" else "JPEG"}.";log.event("image.exported",mapOf("width" to bitmap.width,"height" to bitmap.height))
        } finally{bitmap.recycle()}
    }}
    fun archive(uri:Uri){val p=project ?: return;run("Exporting originals and editable recipe…"){withContext(Dispatchers.IO){store.archive(p,uri)};message="Shoot archive saved, including originals, RAW files and the edit recipe."}}
    fun diagnostics(uri:Uri){run("Exporting diagnostics…"){withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(log.report()) } ?: throw IOException()};message="Diagnostic log saved."}}
    fun selectModel(choice:ModelChoice){
        model=choice.id
        viewModelScope.launch { try { auth.selectModel(choice.id,choice.label) } catch(e:Exception){showError("account.model",e)} }
    }
    fun loadModels(){run("Loading your available models…"){
        val fetched=withContext(Dispatchers.IO){ai.models()}
        models=fetched
        val preferred=fetched.firstOrNull{it.id==model} ?: fetched.firstOrNull{it.id==auth.state.value.active?.selectedModel} ?: fetched.firstOrNull()
        if(preferred!=null){model=preferred.id;auth.selectModel(preferred.id,preferred.label)}
        else {model="";message="No available models were returned for this account."}
    }}
    fun connect(existing:Account?,openBrowser:(String)->Unit){run("Continue in your browser, then return here…"){ setStatus ->
        auth.signIn(existing,openBrowser,gate::awaitResumed)
        val active=auth.state.value.active
        if(active?.canUsePlan==true){
            setStatus("Loading your available models…")
            try {
                val fetched=withContext(Dispatchers.IO){ai.models()}
                models=fetched
                val preferred=fetched.firstOrNull{it.id==active.selectedModel} ?: fetched.firstOrNull()
                if(preferred!=null){model=preferred.id;auth.selectModel(preferred.id,preferred.label);message="Account connected and ready with ${preferred.label}."}
                else message="Account connected, but no available models were returned for this account."
            } catch(e:CancellationException){throw e}
            catch(e:Exception){log.failure("models.autoload",e);message="Account connected. Tap Load available models to choose a model."}
        } else {
            message="Account connected, but plan usage permission was not granted. Tap Continue with ChatGPT to authorize it."
        }
    }}
    fun chooseAccount(id:String){run("Switching account…"){auth.select(id)}}
    fun signOut(){run("Disconnecting account…"){val revoked=auth.signOut();message=if(revoked)"Signed out." else "Local credentials removed. Remote revocation was not confirmed; disconnect LightPlay in ChatGPT settings."}}
    fun enumerate(): Boolean {
        return try {
            lenses=camera.lenses()
            if(lenses.isEmpty()){message="No compatible camera was found on this device. You can still import photos into a shoot.";return false}
            val p=project
            val preferred=p?.cameraKey?.takeIf{key->key.isNotBlank() && lenses.any{it.key==key}}
            val defaultRear=lenses.firstOrNull{!it.isFront && it.zoomLabel=="1×" && it.physicalId==null}
                ?: lenses.firstOrNull{!it.isFront && it.zoomLabel=="1×"}
                ?: lenses.firstOrNull{!it.isFront}
                ?: lenses.first()
            lensKey=preferred ?: lensKey.takeIf{key->lenses.any{it.key==key}} ?: defaultRear.key
            chooseLens(lensKey)
            if(p!=null && p.captureWidth>0 && p.captureHeight>0){
                val activeLens=lenses.find{it.key==lensKey}
                val matched=activeLens?.sizes?.firstOrNull{it.width==p.captureWidth && it.height==p.captureHeight} ?: android.util.Size(p.captureWidth,p.captureHeight)
                cameraSettings=cameraSettings.copy(size=matched,raw=p.captureRaw && activeLens?.rawSize!=null)
            }
            true
        }catch(e:Exception){showError("camera.list",e);false}
    }
    fun chooseLens(key:String){lensKey=key;val lens=lenses.find{it.key==key} ?:return
        val size=lens.sizes.filter{it.width.toLong()*it.height<=13_000_000}.firstOrNull() ?:lens.sizes.lastOrNull() ?:return
        cameraSettings=CameraSettings(size=size,aperture=lens.apertures.firstOrNull())
    }
    fun shutter(){if(capturing || busy!=null)return;try{store.requireSpace(if(cameraSettings.raw)250_000_000 else 60_000_000);capturing=true;camera.capture()}catch(e:Exception){showError("capture",e)}}
    fun lock(){camera.lock{config->viewModelScope.launch{cameraSettings=config}}}
    fun pauseCamera(){camera.close();if(capturing){capturing=false;cameraStatus="Capture was interrupted. Check the frame count and retake if needed."};cameraSettings=cameraSettings.copy(locked=false)}
    override fun onCleared(){camera.dispose();super.onCleared()}
}
