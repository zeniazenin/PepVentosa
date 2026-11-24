@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.roundlight.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.roundlight.app.StudioModel
import com.roundlight.app.data.ProjectStore
import com.roundlight.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

private val Ink=Color(0xFF101916); private val Panel=Color(0xFF1C2922); private val Sage=Color(0xFFD8E6A3)
private val Paper=Color(0xFFF3F1E7); private val Muted=Color(0xFFA6B3A5); private val Clay=Color(0xFFE9B39B)
private val palette=darkColorScheme(primary=Sage,onPrimary=Ink,background=Ink,onBackground=Paper,surface=Panel,onSurface=Paper,secondary=Clay,onSurfaceVariant=Muted,surfaceVariant=Color(0xFF29392E),outline=Color(0xFF61705F))

@Composable fun Studio(vm:StudioModel) {
    MaterialTheme(colorScheme=palette) {
        val context=LocalContext.current
        fun browse(url:String){
            try { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }
            catch(_:ActivityNotFoundException){ vm.message="No web browser was found to open this link." }
        }
        var newDialog by remember{mutableStateOf(false)};var aiDialog by remember{mutableStateOf(false)};var exportDialog by remember{mutableStateOf(false)}
        var exportEdge by remember{mutableIntStateOf(4096)};var exportPng by remember{mutableStateOf(false)}
        val photoExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")){it?.let{uri->vm.export(uri,exportEdge,false)}}
        val pngExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")){it?.let{uri->vm.export(uri,exportEdge,true)}}
        val zipExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){it?.let(vm::archive)}
        val logExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){it?.let(vm::diagnostics)}
        val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){if(it.isNotEmpty())vm.import(it)}
        val cameraPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){if(it){if(vm.enumerate())vm.screen="capture"}else vm.message="Camera permission is needed to capture. You can still import photos into a shoot."}
        fun capture(){if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED){if(vm.enumerate())vm.screen="capture"}else cameraPermission.launch(Manifest.permission.CAMERA)}
        BackHandler(vm.capturing){}
        BackHandler(vm.screen!="home" && vm.busy==null && !vm.capturing){vm.goBack()}
        Scaffold(containerColor=Ink,topBar={
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                if(vm.screen!="home")IconButton(onClick=vm::goBack,enabled=!vm.capturing){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back")}
                Column(Modifier.weight(1f)){Text("ROUNDLIGHT",fontSize=14.sp,letterSpacing=3.sp,fontWeight=FontWeight.SemiBold,color=Sage);Text("Photographs, in the round",fontSize=11.sp,color=Muted)}
                IconButton(onClick=vm::openAccount,enabled=!vm.capturing){Icon(Icons.Outlined.PersonOutline,"Account and diagnostics")}
            }
        }) { insets ->
            Box(Modifier.fillMaxSize().padding(insets)) {
                when(vm.screen){
                    "home"->Home(vm,{newDialog=true})
                    "shoot"->Shoot(vm,{capture()},{importer.launch(arrayOf("image/*"))},{aiDialog=true},{exportDialog=true},{zipExport.launch("Roundlight-${vm.project?.id}.zip")})
                    "capture"->Capture(vm)
                    "edit"->Editor(vm,{aiDialog=true},{exportDialog=true})
                    "account"->Account(vm,::browse,{logExport.launch("Roundlight-diagnostics.txt")})
                }
            }
        }
        vm.message?.let { text -> AlertDialog(onDismissRequest={vm.message=null},title={Text("Roundlight")},text={Text(text)},confirmButton={TextButton(onClick={vm.message=null}){Text("OK")}}) }
        vm.busy?.let { text -> AlertDialog(onDismissRequest={},title={Text("Working on your shoot")},text={Column(verticalArrangement=Arrangement.spacedBy(18.dp)){LinearProgressIndicator(Modifier.fillMaxWidth());Text(text);Text("Original photos are kept intact.",color=Muted,fontSize=12.sp)}},confirmButton={TextButton(onClick={vm.cancel()}){Text("Cancel")}}) }
        if(newDialog){var name by remember{mutableStateOf("")};AlertDialog(onDismissRequest={newDialog=false},title={Text("A new point of view")},text={OutlinedTextField(name,{name=it},label={Text("Shoot name")},placeholder={Text("Oak tree at golden hour")},singleLine=true)},confirmButton={Button(onClick={newDialog=false;vm.newShoot(name)}){Text("Create shoot")}},dismissButton={TextButton(onClick={newDialog=false}){Text("Cancel")}})}
        if(aiDialog){
            val connection by vm.auth.state.collectAsState()
            val active = connection.active
            if(active==null || !active.connected || !active.canUsePlan){
                AlertDialog(
                    onDismissRequest={aiDialog=false},
                    title={Text("Connect your ChatGPT account")},
                    text={Text(if(active?.connected==true) "Your ChatGPT account (${active.label}) is connected, but plan usage permission is not authorized yet. Open Account to authorize plan usage." else "Connect your ChatGPT account in Account before using AI composition. Local alignment and editing remain available without an account.")},
                    confirmButton={Button(onClick={aiDialog=false;vm.openAccount()}){Text("Open Account")}},
                    dismissButton={TextButton(onClick={aiDialog=false}){Text("Cancel")}}
                )
            } else {
                AlertDialog(onDismissRequest={aiDialog=false},title={Text("Compose with your ChatGPT account")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Text("Send resized previews of all ${vm.project?.frames?.size ?: 0} photos to OpenAI, in groups of six, followed by a composite review. Your authorized ChatGPT plan usage applies.")
                    Text("Account: ${active.label}\nModel: ${vm.selectedModelLabel.ifBlank { "Will load from your account" }}",color=Sage,fontSize=12.sp)
                    Text("AI can refine alignment, layer opacity, soft masks and color. You can undo the result and refine every edit. Originals and RAW files remain on your phone.",color=Muted)
                    Choice("Look",vm.style,listOf("Natural · recognizable subject, soft surrounding texture","Dreamlike · luminous, painterly and atmospheric","Graphic · defined subject, richer contrast")){vm.style=it}
                }},confirmButton={Button(onClick={aiDialog=false;vm.aiCompose()}){Text("Compose with AI")}},dismissButton={TextButton(onClick={aiDialog=false}){Text("Cancel")}})
            }
        }
        if(exportDialog)AlertDialog(onDismissRequest={exportDialog=false},title={Text("Save your finished photograph")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
            Choice("Longest edge",exportEdge.toString(),listOf("2048","3072","4096","6000")){exportEdge=it.toInt()}
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(exportPng,{exportPng=it});Text("Lossless PNG")}
            Text("JPEG uses quality 97. Resolution is limited to the source size and a safe memory budget; actual dimensions are shown after export.",color=Muted,fontSize=12.sp)
        }},confirmButton={Button(onClick={exportDialog=false;if(exportPng)pngExport.launch("Roundlight-${System.currentTimeMillis()}.png")else photoExport.launch("Roundlight-${System.currentTimeMillis()}.jpg")}){Text("Choose destination")}},dismissButton={TextButton(onClick={exportDialog=false}){Text("Cancel")}})
    }
}

@Composable private fun Home(vm:StudioModel,new:()->Unit){
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(22.dp)){
        Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(24.dp)).background(Brush.verticalGradient(listOf(Color(0xFF536C56),Color(0xFF253C2D))))){
            OrbitArt(Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.BottomStart).padding(22.dp)){Text("One subject.\nMany perspectives.",fontFamily=FontFamily.Serif,fontSize=32.sp,lineHeight=34.sp);Text("Make photographs that feel like paintings.",color=Paper.copy(alpha=.8f),fontSize=13.sp,modifier=Modifier.padding(top=10.dp))}
        }
        Button(onClick=new,modifier=Modifier.fillMaxWidth().height(54.dp),shape=RoundedCornerShape(14.dp)){Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(10.dp));Text("Start a new shoot",fontSize=16.sp)}
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
            listOf("01" to "Capture","02" to "Compose","03" to "Refine").forEach{(n,label)->Column(Modifier.weight(1f)){Text(n,color=Clay,fontSize=12.sp);Text(label,fontSize=15.sp,modifier=Modifier.padding(top=4.dp))}}
        }
        Section("Your shoots",if(vm.projects.isEmpty())"A little movement. A different kind of photograph." else "${vm.projects.size} saved locally on this device")
        vm.projects.forEach{p->Card(onClick={vm.open(p)},colors=CardDefaults.cardColors(containerColor=Panel)){
            Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
                Thumb(vm,p,p.frames.firstOrNull(),Modifier.size(68.dp).clip(RoundedCornerShape(10.dp)))
                Column(Modifier.weight(1f)){Text(p.name,fontWeight=FontWeight.Medium);Text("${p.frames.size} frames · local shoot",color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=6.dp))};Icon(Icons.Outlined.ChevronRight,null,tint=Muted)
            }
        }}
        OutlinedButton(onClick=vm::demo,modifier=Modifier.fillMaxWidth()){Text("Explore an illustrated demo")}
        Text("Inspired by Pep Ventosa’s in-the-round technique. An independent creative tool.",color=Muted,fontSize=12.sp,lineHeight=18.sp)
        Spacer(Modifier.height(10.dp))
    }
}
@Composable private fun OrbitArt(modifier:Modifier){Canvas(modifier){
    val center=Offset(size.width*.68f,size.height*.27f)
    for(i in 0..11){drawCircle(Color(0xFFDADE9B).copy(alpha=.025f),radius=size.width*(.12f+i*.017f),center=center+Offset(sin(i*1.2f)*24,cos(i*.8f)*18))}
    for(i in 0..90){val a=i*2.39996f;val r=sqrt(i/90f)*size.width*.25f;drawCircle(Color(if(i%3==0)0xFFE9B39B else 0xFFD8E6A3).copy(alpha=.12f),radius=8+i%7f,center=center+Offset(cos(a)*r,sin(a)*r*.7f))}
    for(i in 0..5)drawLine(Color(0xFFEBE8BB).copy(alpha=.12f),center+Offset(i*3f-8,5f),center+Offset(i*4f-8,size.height*.46f),strokeWidth=3f)
}}
@Composable private fun Section(title:String,subtitle:String=""){Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Text(title,fontFamily=FontFamily.Serif,fontSize=25.sp);if(subtitle.isNotBlank())Text(subtitle,color=Muted,fontSize=13.sp,lineHeight=19.sp)}}
@Composable private fun Thumb(vm:StudioModel,p:Project,f:Frame?,modifier:Modifier){val image by produceState<Bitmap?>(null,p.id,f?.file){value=if(f==null)null else withContext(Dispatchers.IO){runCatching{ProjectStore.decode(vm.store.photo(p,f),180)}.getOrNull()}}
    Box(modifier.background(Color(0xFF314735)),contentAlignment=Alignment.Center){image?.let{Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)}?:Icon(Icons.Outlined.FilterFrames,null,tint=Sage)}
}

@Composable private fun Shoot(vm:StudioModel,capture:()->Unit,import:()->Unit,ai:()->Unit,export:()->Unit,archive:()->Unit){val p=vm.project ?:return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
        Text("01 / CAPTURE  →  02 / COMPOSE  →  03 / REFINE",color=Sage,fontSize=10.sp,letterSpacing=1.sp)
        Section(p.name,"${p.frames.size} photographs · each original is saved in this shoot’s folder")
        if(p.frames.isEmpty()){
            Card(colors=CardDefaults.cardColors(containerColor=Panel)){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text("Find your anchor",fontFamily=FontFamily.Serif,fontSize=24.sp)
                Text("Start with a tree, statue or lamp post. Take 20–30 sharp photos while moving in a gentle arc. Keep the same lens, distance and subject position. A full circle is optional.",color=Muted,lineHeight=22.sp)
                Text("Lock exposure and white balance after composing. Keep checking focus as you move.",fontSize=13.sp,color=Sage)
            }}
        }else{
            Preview(vm,false)
            LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){itemsIndexed(p.frames){index,f->Column(horizontalAlignment=Alignment.CenterHorizontally){Thumb(vm,p,f,Modifier.size(62.dp).clip(RoundedCornerShape(9.dp)).clickable{vm.selected=index;vm.openEdit()});Text("${index+1}",fontSize=11.sp,color=Muted)}}}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Button(onClick=capture,modifier=Modifier.weight(1f)){Icon(Icons.Outlined.CameraAlt,null);Spacer(Modifier.width(8.dp));Text("Capture")};OutlinedButton(onClick=import,modifier=Modifier.weight(1f)){Text("Import photos")}}
        if(p.frames.isNotEmpty()){
            HorizontalDivider(color=Muted.copy(alpha=.15f));Section("Compose the orbit","Anchor the subject. Let the surroundings overlap.")
            Button(onClick=ai,enabled=p.frames.size>=2,modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.AutoAwesome,null);Spacer(Modifier.width(8.dp));Text("Compose with AI")}
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){OutlinedButton(onClick=vm::align,enabled=p.frames.size>=2,modifier=Modifier.weight(1f)){Text("Align locally")};OutlinedButton(onClick=vm::openEdit,modifier=Modifier.weight(1f)){Text("Edit layers")}}
            Text("Local alignment and editing need no account. AI composition sends previews only after you choose it.",fontSize=12.sp,color=Muted)
            OutlinedButton(onClick=export,modifier=Modifier.fillMaxWidth()){Text("Export finished image")}
            TextButton(onClick=archive,modifier=Modifier.fillMaxWidth()){Text("Back up shoot + originals")}
        }
    }
}

@Composable private fun Preview(vm:StudioModel,editable:Boolean,maskMode:String="View",brush:Float=.035f,strength:Float=.8f){val p=vm.project ?:return;val bitmap=vm.preview
    var points by remember{mutableStateOf<List<Point>>(emptyList())}
    LaunchedEffect(p.id,vm.selected,maskMode){points=emptyList()}
    val ratio=bitmap?.let{it.width.toFloat()/it.height} ?: .75f
    val currentFrame=p.frames.getOrNull(vm.selected)
    val touch=if(editable && maskMode!="View")Modifier.pointerInput(p.id,vm.selected,maskMode,brush,strength,currentFrame?.transform){
        fun mapped(x:Float,y:Float):Point=currentFrame?.transform?.inverse(Point(x/size.width,y/size.height),ratio) ?: Point(x/size.width,y/size.height)
        fun finish(){if(points.isNotEmpty()){val stroke=Stroke(points,brush/(currentFrame?.transform?.scale ?:1f),strength,maskMode=="Restore");if(stroke.valid())vm.frame{it.copy(masks=it.masks+stroke)};points=emptyList()}}
        detectDragGestures(onDragStart={points=listOf(mapped(it.x,it.y))},onDragEnd={finish()},onDragCancel={points=emptyList()}){change,_->change.consume();if(points.size<5000)points=points+mapped(change.position.x,change.position.y)}
    } else if(editable && maskMode=="View")Modifier.pointerInput(p.id){detectTapGestures(onLongPress={pos->vm.project?.let{current->vm.edit(current.copy(anchor=Point((pos.x/size.width).coerceIn(0f,1f),(pos.y/size.height).coerceIn(0f,1f))))}})}else Modifier
    val dab=if(editable && maskMode!="View")Modifier.pointerInput(p.id,vm.selected,maskMode,brush,strength,currentFrame?.transform){
        detectTapGestures { position ->
            val point=currentFrame?.transform?.inverse(Point(position.x/size.width,position.y/size.height),ratio) ?:Point(position.x/size.width,position.y/size.height)
            val stroke=Stroke(listOf(point),brush/(currentFrame?.transform?.scale ?:1f),strength,maskMode=="Restore")
            if(stroke.valid())vm.frame{it.copy(masks=it.masks+stroke)}
        }
    }else Modifier
    Box(Modifier.fillMaxWidth().heightIn(max=430.dp).aspectRatio(ratio,matchHeightConstraintsFirst=true).clip(RoundedCornerShape(14.dp)).background(Panel).then(touch).then(dab),contentAlignment=Alignment.Center){
        if(bitmap!=null)Image(bitmap.asImageBitmap(),"Composition preview",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)else if(vm.previewError!=null)Text(vm.previewError!!,Modifier.padding(20.dp),color=Muted)else CircularProgressIndicator()
        if(vm.renderBusy)CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(12.dp).size(20.dp),strokeWidth=2.dp)
        if(editable && maskMode=="View")Canvas(Modifier.fillMaxSize()){val a=Offset(p.anchor.x*size.width,p.anchor.y*size.height);drawLine(Sage.copy(alpha=.7f),a-Offset(8f,0f),a+Offset(8f,0f),2f);drawLine(Sage.copy(alpha=.7f),a-Offset(0f,8f),a+Offset(0f,8f),2f)}
        if(points.isNotEmpty())Text("${if(maskMode=="Hide")"Hiding" else "Restoring"} · release to preview",modifier=Modifier.align(Alignment.BottomCenter).padding(10.dp).background(Ink.copy(alpha=.8f),RoundedCornerShape(8.dp)).padding(8.dp),color=Sage,fontSize=12.sp)
        if(points.isNotEmpty())Canvas(Modifier.fillMaxSize()){
            val path=Path();points.forEachIndexed{i,pt->val mapped=currentFrame?.transform?.forward(pt,ratio) ?:pt;if(i==0)path.moveTo(mapped.x*size.width,mapped.y*size.height)else path.lineTo(mapped.x*size.width,mapped.y*size.height)}
            drawPath(path,if(maskMode=="Hide")Clay.copy(alpha=.55f)else Sage.copy(alpha=.55f),style=androidx.compose.ui.graphics.drawscope.Stroke(brush*min(size.width,size.height)*2,cap=StrokeCap.Round,join=StrokeJoin.Round))
        }
        if(editable && p.crop>0)Canvas(Modifier.fillMaxSize()){val x=size.width*p.crop;val y=size.height*p.crop;drawRect(Color.Black.copy(alpha=.4f),size=androidx.compose.ui.geometry.Size(size.width,y));drawRect(Color.Black.copy(alpha=.4f),Offset(0f,size.height-y),androidx.compose.ui.geometry.Size(size.width,y));drawRect(Color.Black.copy(alpha=.4f),Offset(0f,y),androidx.compose.ui.geometry.Size(x,size.height-2*y));drawRect(Color.Black.copy(alpha=.4f),Offset(size.width-x,y),androidx.compose.ui.geometry.Size(x,size.height-2*y))}
    }
}

@Composable private fun Editor(vm:StudioModel,ai:()->Unit,export:()->Unit){val p=vm.project ?:return;if(p.frames.isEmpty())return
    val selectedIndex=vm.selected.coerceIn(p.frames.indices)
    LaunchedEffect(selectedIndex){if(vm.selected!=selectedIndex)vm.selected=selectedIndex}
    val f=p.frames[selectedIndex]
    var maskMode by remember{mutableStateOf("View")};var brush by remember{mutableFloatStateOf(.035f)};var strength by remember{mutableFloatStateOf(.8f)}
    var advanced by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=18.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("The finishing room",fontFamily=FontFamily.Serif,fontSize=27.sp);Text(p.name,color=Muted,fontSize=12.sp)};IconButton(onClick=vm::undo,enabled=vm.undoCount>0){Icon(Icons.AutoMirrored.Outlined.Undo,"Undo last edit")};Button(onClick=export){Text("Export")}}
        Preview(vm,true,maskMode,brush,strength)
        Text(if(maskMode=="View")"Long-press the subject to set the alignment anchor." else "Paint with one finger on the image. Masks are soft and reversible.",fontSize=11.sp,color=Muted)
        Row(verticalAlignment=Alignment.CenterVertically){Text("Layers",fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f));TextButton(onClick={vm.solo=!vm.solo;vm.refreshPreview()}){Text(if(vm.solo)"Show blend" else "View selected layer")}}
        LazyRow(horizontalArrangement=Arrangement.spacedBy(9.dp)){itemsIndexed(p.frames){index,frame->Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.clickable{vm.selected=index;vm.refreshPreview()}){
            Thumb(vm,p,frame,Modifier.size(65.dp).border(if(index==selectedIndex)2.dp else 0.dp,if(index==selectedIndex)Sage else Color.Transparent,RoundedCornerShape(9.dp)).padding(3.dp).clip(RoundedCornerShape(7.dp)))
            Text("${index+1} · ${(frame.opacity*100).roundToInt()}%",color=if(index==selectedIndex)Sage else Muted,fontSize=10.sp)
        }}}
        Row(verticalAlignment=Alignment.CenterVertically){Text("Layer ${selectedIndex+1}",modifier=Modifier.weight(1f));Text(if(f.raw!=null)"RAW + JPEG" else "JPEG",color=Muted,fontSize=11.sp);Switch(f.visible,{value->vm.frame{it.copy(visible=value)}})}
        EditSlider("Opacity",f.opacity,0f..1f,{"${(it*100).roundToInt()}%"},p.id+f.id){value->vm.frame{it.copy(opacity=value)}}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("View","Hide","Restore").forEach{mode->FilterChip(selected=maskMode==mode,onClick={maskMode=mode},label={Text(mode)})}}
        if(maskMode!="View"){
            EditSlider("Brush size",brush,0.005f..0.15f,{"${(it*200).roundToInt()}%"},"brush"){brush=it}
            EditSlider("Brush strength",strength,.05f..1f,{"${(it*100).roundToInt()}%"},"strength"){strength=it}
            TextButton(onClick={vm.frame{it.copy(masks=emptyList(),regions=emptyList())}}){Text("Clear masks on this layer")}
        }
        TextButton(onClick={advanced=!advanced}){Text(if(advanced)"Hide alignment controls" else "Adjust layer alignment")}
        if(advanced){
            Text(if(f.confidence<.30f)"Low alignment confidence — check this frame manually." else "Alignment confidence ${(f.confidence*100).roundToInt()}%",color=if(f.confidence<.30f)Clay else Muted,fontSize=12.sp)
            EditSlider("Horizontal",f.transform.dx,-0.3f..0.3f,{"%.1f%%".format(it*100)},f.id+"x"){v->vm.frame{it.copy(transform=it.transform.copy(dx=v))}}
            EditSlider("Vertical",f.transform.dy,-0.3f..0.3f,{"%.1f%%".format(it*100)},f.id+"y"){v->vm.frame{it.copy(transform=it.transform.copy(dy=v))}}
            EditSlider("Scale",f.transform.scale,.7f..1.4f,{"%.2f×".format(it)},f.id+"s"){v->vm.frame{it.copy(transform=it.transform.copy(scale=v))}}
            EditSlider("Rotation",f.transform.degrees,-20f..20f,{"%.1f°".format(it)},f.id+"r"){v->vm.frame{it.copy(transform=it.transform.copy(degrees=v))}}
        }
        HorizontalDivider(color=Muted.copy(alpha=.15f));Section("Color & atmosphere")
        EditSlider("Exposure",p.grade.exposure,-2f..2f,{"%.2f EV".format(it)},p.id+"ev"){vm.edit(p.copy(grade=p.grade.copy(exposure=it)))}
        EditSlider("Contrast",p.grade.contrast,.5f..1.8f,{"%.2f".format(it)},p.id+"c"){vm.edit(p.copy(grade=p.grade.copy(contrast=it)))}
        EditSlider("Saturation",p.grade.saturation,0f..2f,{"%.2f".format(it)},p.id+"sat"){vm.edit(p.copy(grade=p.grade.copy(saturation=it)))}
        EditSlider("Warmth",p.grade.warmth,-1f..1f,{"%.2f".format(it)},p.id+"warm"){vm.edit(p.copy(grade=p.grade.copy(warmth=it)))}
        EditSlider("Trim edges",p.crop,0f..0.2f,{"${(it*100).roundToInt()}%"},p.id+"crop"){vm.edit(p.copy(crop=it))}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={vm.edit(p.averaged())},modifier=Modifier.weight(1f)){Text("Equal blend")};Button(onClick=ai,modifier=Modifier.weight(1f),enabled=p.frames.size>1){Text("AI compose")}}
        if(p.aiNote.isNotBlank())Card{Column(Modifier.padding(16.dp)){Text("Art director’s notes",fontWeight=FontWeight.SemiBold);Text(p.aiNote,color=Muted,fontSize=13.sp,modifier=Modifier.padding(top=8.dp))}}
        Text("RAW files are preserved as digital negatives. Composition uses their camera-rendered JPEG companions.",color=Muted,fontSize=11.sp)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable private fun EditSlider(label:String,value:Float,range:ClosedFloatingPointRange<Float>,format:(Float)->String,key:String,onCommit:(Float)->Unit){
    var local by remember(key,value){mutableFloatStateOf(value)}
    Column{Row{Text(label,fontSize=13.sp,modifier=Modifier.weight(1f));Text(format(local),fontSize=12.sp,color=Sage)}
        Slider(value=local.coerceIn(range),onValueChange={local=it},valueRange=range,onValueChangeFinished={if(local!=value)onCommit(local)},modifier=Modifier.height(34.dp))}
}
@Composable private fun <T> ChoiceItem(label:String,value:String,options:List<T>,itemLabel:(T)->String,enabled:Boolean=true,choose:(T)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label,color=Muted,fontSize=11.sp)
        Box {
            OutlinedButton(onClick={expanded=true},enabled=enabled && options.isNotEmpty(),modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(10.dp)) {
                Text(value,Modifier.weight(1f),fontSize=12.sp)
                Icon(Icons.Outlined.ExpandMore,null)
            }
            DropdownMenu(expanded,onDismissRequest={expanded=false}) {
                options.forEach { option ->
                    DropdownMenuItem(text={Text(itemLabel(option),fontSize=13.sp)},onClick={expanded=false;choose(option)})
                }
            }
        }
    }
}
@Composable private fun Choice(label:String,value:String,options:List<String>,enabled:Boolean=true,choose:(String)->Unit) =
    ChoiceItem(label,value,options,{it},enabled,choose)

private fun formatShutter(exposureNs:Long):String {
    val safe=exposureNs.coerceAtLeast(1L)
    return if(safe>=500_000_000L) "%.1f s".format(safe/1_000_000_000.0)
    else "1/${(1_000_000_000.0/safe).roundToInt().coerceAtLeast(1)} s"
}

@Composable private fun Capture(vm:StudioModel){val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current
    val lens=vm.lenses.find{it.key==vm.lensKey};val p=vm.project ?:return
    var settingsOpen by remember{mutableStateOf(false)};var ghost by remember{mutableStateOf(true)}
    val view=remember{TextureView(context)};val config by rememberUpdatedState(vm.cameraSettings)
    DisposableEffect(lens?.key,vm.cameraSettings.size,vm.cameraSettings.raw,lifecycle){
        fun open(){lens?.let{vm.cameraSettings=vm.cameraSettings.copy(locked=false);vm.camera.open(view,it,config)}}
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)open()else if(event==Lifecycle.Event.ON_PAUSE)vm.pauseCamera()}
        lifecycle.lifecycle.addObserver(observer)
        onDispose{lifecycle.lifecycle.removeObserver(observer);vm.pauseCamera()}
    }
    val guide by produceState<Bitmap?>(null,p.id,p.frames.firstOrNull()?.file){value=p.frames.firstOrNull()?.let{withContext(Dispatchers.IO){runCatching{ProjectStore.decode(vm.store.photo(p,it),640)}.getOrNull()}}}
    Column(Modifier.fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Text("${p.frames.size} frames",fontFamily=FontFamily.Serif,fontSize=24.sp,modifier=Modifier.weight(1f));IconButton(onClick={ghost=!ghost}){Icon(if(ghost)Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,"Toggle first-frame ghost")};IconButton(onClick={settingsOpen=true},enabled=!vm.capturing){Icon(Icons.Outlined.Tune,"Camera settings")}}
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(Color.Black),contentAlignment=Alignment.Center){
            val sensorOrientation=lens?.chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val rotated=sensorOrientation==90 || sensorOrientation==270
            val ratio=if(rotated)vm.cameraSettings.size.height.toFloat()/vm.cameraSettings.size.width else vm.cameraSettings.size.width.toFloat()/vm.cameraSettings.size.height
            val fitHeight=maxWidth/ratio > maxHeight
            Box(if(fitHeight)Modifier.fillMaxHeight().aspectRatio(ratio,matchHeightConstraintsFirst=true) else Modifier.fillMaxWidth().aspectRatio(ratio)){
                AndroidView(factory={view},modifier=Modifier.fillMaxSize())
                if(ghost)guide?.let{Image(it.asImageBitmap(),"First-frame alignment guide",Modifier.fillMaxSize(),contentScale=ContentScale.Fit,alpha=.20f)}
                Canvas(Modifier.fillMaxSize()){val anchor=Offset(p.anchor.x*size.width,p.anchor.y*size.height);drawLine(Color.White.copy(alpha=.22f),Offset(size.width/3,0f),Offset(size.width/3,size.height),1f);drawLine(Color.White.copy(alpha=.22f),Offset(size.width*2/3,0f),Offset(size.width*2/3,size.height),1f);drawLine(Sage,anchor-Offset(22f,0f),anchor+Offset(22f,0f),2f);drawLine(Sage,anchor-Offset(0f,22f),anchor+Offset(0f,22f),2f);drawCircle(Sage,36f,anchor,style=androidx.compose.ui.graphics.drawscope.Stroke(1.4f))}
            }
            Text(if(vm.cameraSettings.locked)"EXPOSURE / WB LOCKED" else "COMPOSE → LOCK → CAPTURE",color=Sage,fontSize=10.sp,letterSpacing=1.sp,modifier=Modifier.align(Alignment.TopCenter).padding(14.dp).background(Ink.copy(alpha=.7f),RoundedCornerShape(8.dp)).padding(8.dp))
        }
        Text(vm.cameraStatus,color=Muted,fontSize=12.sp,maxLines=3,modifier=Modifier.heightIn(min=32.dp))
        Row(Modifier.fillMaxWidth().padding(bottom=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
            OutlinedButton(onClick=vm::lock,enabled=!vm.capturing){Icon(Icons.Outlined.Lock,null,Modifier.size(16.dp));Spacer(Modifier.width(4.dp));Text("Lock")}
            Button(onClick=vm::shutter,enabled=!vm.capturing && vm.cameraSettings.locked,shape=CircleShape,contentPadding=PaddingValues(0.dp),modifier=Modifier.size(74.dp)){if(vm.capturing)CircularProgressIndicator(Modifier.size(28.dp),color=Ink)else Icon(Icons.Outlined.CameraAlt,"Take photo",Modifier.size(30.dp))}
            TextButton(onClick=vm::openShoot,enabled=!vm.capturing){Text("Review")}
        }
    }
    if(settingsOpen && lens!=null)AlertDialog(onDismissRequest={settingsOpen=false},title={Text("Capture settings")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        ChoiceItem("Camera",lens.label,vm.lenses,{it.label},p.frames.isEmpty()){chosen->vm.chooseLens(chosen.key)}
        if(p.frames.isNotEmpty())Text("Keep this lens throughout the shoot. Start a new shoot to switch lenses.",fontSize=11.sp,color=Muted)
        ChoiceItem("JPEG resolution",vm.cameraSettings.size.toString(),lens.sizes,{it.toString()},enabled=p.frames.isEmpty()){size->vm.cameraSettings=vm.cameraSettings.copy(size=size,locked=false)}
        Row(verticalAlignment=Alignment.CenterVertically){Switch(vm.cameraSettings.raw,{vm.cameraSettings=vm.cameraSettings.copy(raw=it,locked=false)},enabled=lens.rawSize!=null);Text(if(lens.rawSize!=null)"Save RAW + JPEG" else "RAW not exposed by this lens",fontSize=12.sp)}
        if(lens.apertures.size>1)ChoiceItem("Aperture",vm.cameraSettings.aperture?.let{"f/$it"} ?: "Auto",lens.apertures.toList(),{"f/$it"}){value->vm.cameraSettings=vm.cameraSettings.copy(aperture=value);vm.camera.update(vm.cameraSettings)}
        else Text("Aperture: f/${lens.apertures.firstOrNull() ?: "—"} · fixed by the lens",color=Muted,fontSize=12.sp)
        if(lens.manual){
            Row(verticalAlignment=Alignment.CenterVertically){Switch(vm.cameraSettings.manual,{vm.cameraSettings=vm.cameraSettings.copy(manual=it);vm.camera.update(vm.cameraSettings)});Text("Manual exposure",fontSize=13.sp)}
            if(vm.cameraSettings.manual){
                val isoRange=lens.chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val isoOptions=listOf(50,100,200,400,800,1600,3200,6400).filter{isoRange?.contains(it)==true}
                ChoiceItem("ISO",vm.cameraSettings.iso.toString(),isoOptions,{it.toString()}){vm.cameraSettings=vm.cameraSettings.copy(iso=it);vm.camera.update(vm.cameraSettings)}
                val times=listOf(30,60,125,250,500,1000,2000);val exposureRange=lens.chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val shutterOptions=times.map{1_000_000_000L/it}.filter{exposureRange?.contains(it)==true}
                ChoiceItem("Shutter",formatShutter(vm.cameraSettings.exposureNs),shutterOptions,::formatShutter){vm.cameraSettings=vm.cameraSettings.copy(exposureNs=it);vm.camera.update(vm.cameraSettings)}
            }
        } else Text(if(lens.aeLock)"Auto exposure lock available." else "This lens does not expose exposure lock.",fontSize=12.sp,color=Muted)
        val wbs=mapOf("Auto (lock after composing)" to CaptureRequest.CONTROL_AWB_MODE_AUTO,"Daylight" to CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,"Cloudy" to CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,"Shade" to CaptureRequest.CONTROL_AWB_MODE_SHADE,"Tungsten" to CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,"Fluorescent" to CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT).filter{it.value in lens.wbModes}
        Choice("White balance",wbs.entries.firstOrNull{it.value==vm.cameraSettings.wb}?.key ?: "Auto",wbs.keys.toList()){vm.cameraSettings=vm.cameraSettings.copy(wb=wbs.getValue(it),locked=false);vm.camera.update(vm.cameraSettings)}
        Text("Available controls come from Android’s camera interface. Samsung’s stock-camera modes may expose other resolutions. Lock again after changing settings or reopening capture.",fontSize=11.sp,color=Muted)
    }},confirmButton={TextButton(onClick={settingsOpen=false}){Text("Done")}})
}

@Composable private fun Account(vm:StudioModel,browse:(String)->Unit,logs:()->Unit){val connection by vm.auth.state.collectAsState();val active=connection.active
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
        Section("Your creative companion","Use your own ChatGPT account for AI art direction.")
        Card{Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            Text(if(active?.connected==true)active.label else "Bring your ChatGPT account",fontSize=18.sp)
            Text("Eligible accounts can authorize use of their ChatGPT plan. You sign in through OpenAI in your browser. Roundlight never receives your password.",color=Muted,fontSize=13.sp,lineHeight=20.sp)
            Button(onClick={vm.connect(active,browse)},modifier=Modifier.fillMaxWidth()){Text("Continue with ChatGPT")}
            if(active?.connected==true){
                Text(if(active.canUsePlan)"Plan usage is authorized" else "Plan usage permission is required. Continue with ChatGPT to authorize it.",color=if(active.canUsePlan)Sage else Clay,fontSize=12.sp)
                if(vm.models.isEmpty() && vm.model.isNotBlank()){
                    Text("Saved model: ${vm.selectedModelLabel}",color=Sage,fontSize=12.sp)
                }
                OutlinedButton(onClick=vm::loadModels,modifier=Modifier.fillMaxWidth(),enabled=active.canUsePlan){Text(if(vm.models.isEmpty() && vm.model.isBlank())"Load available models" else "Refresh available models")}
            }
        }}
        if(connection.accounts.isNotEmpty()){
            Text("Saved accounts",fontWeight=FontWeight.SemiBold)
            connection.accounts.forEach{account->OutlinedButton(onClick={vm.chooseAccount(account.clientId)},modifier=Modifier.fillMaxWidth()){Text((if(account.clientId==connection.activeClientId)"✓  " else "")+account.label)}}
            TextButton(onClick={vm.connect(null,browse)}){Text("Add another account or workspace")}
        }
        if(vm.models.isNotEmpty()){
            ChoiceItem("AI model",vm.selectedModelLabel.ifBlank { "Choose a model" },vm.models,{it.label}){chosen->vm.selectModel(chosen)}
            Text("Choose a model that supports images. Your choice is saved for this account.",fontSize=12.sp,color=Muted)
        }
        if(active?.connected==true){OutlinedButton(onClick={browse("https://chatgpt.com/settings/usage")},modifier=Modifier.fillMaxWidth()){Text("Manage ChatGPT access & usage")};TextButton(onClick=vm::signOut){Text("Sign out of this account")}}
        HorizontalDivider();Section("On your device")
        Text("Every shoot has a private folder containing its originals and editable recipe. Export a shoot archive to keep a backup before uninstalling. No photos are uploaded until you request AI composition.",color=Muted,fontSize=13.sp,lineHeight=21.sp)
        OutlinedButton(onClick=logs,modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.BugReport,null);Spacer(Modifier.width(8.dp));Text("Export diagnostic log")}
        Text("Logs contain event timings, counts and device details. They exclude photos, account identities, tokens and authorization URLs.",color=Muted,fontSize=12.sp)
        Text("Roundlight 0.1.0 · Independent, open-source software",color=Sage,fontSize=11.sp)
    }
}
