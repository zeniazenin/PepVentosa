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
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

@Composable fun Studio(vm:StudioModel) {
    MaterialTheme(colorScheme=AnalogColorScheme) {
        val context=LocalContext.current
        val connection by vm.auth.state.collectAsState()
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

        Box(Modifier.fillMaxSize().leatherSurface()) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    CameraTopPlate(
                        showBack = vm.screen != "home",
                        enabled = !vm.capturing,
                        aiConnected = connection.active?.canUsePlan == true,
                        onBack = vm::goBack,
                        onAccount = vm::openAccount
                    )
                }
            ) { insets ->
                Box(Modifier.fillMaxSize().padding(insets)) {
                    when(vm.screen){
                        "home"->Home(vm,{newDialog=true})
                        "shoot"->Shoot(vm,{capture()},{importer.launch(arrayOf("image/*"))},{aiDialog=true},{exportDialog=true},{zipExport.launch("LightPlay-${vm.project?.id}.zip")})
                        "capture"->Capture(vm)
                        "edit"->Editor(vm,{aiDialog=true},{exportDialog=true})
                        "account"->Account(vm,::browse,{logExport.launch("LightPlay-diagnostics.txt")})
                    }
                }
            }
        }

        vm.message?.let { text ->
            AnalogDialog(
                title = "LightPlay",
                onDismissRequest = { vm.message = null },
                confirmButton = { MetalButton("OK", style = MetalStyle.Brass, height = 40.dp, onClick = { vm.message = null }) }
            ) { Text(text, color = AnalogColors.EnamelCream, fontSize = 13.5.sp, lineHeight = 20.sp) }
        }
        vm.busy?.let { text ->
            AnalogDialog(
                title = "Working on your shoot",
                onDismissRequest = {},
                confirmButton = { MetalButton("Cancel", style = MetalStyle.Steel, height = 40.dp, onClick = { vm.cancel() }) }
            ) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                    color = AnalogColors.BrassLight,
                    trackColor = AnalogColors.RecessedBg
                )
                Text(text, color = AnalogColors.EnamelCream, fontSize = 13.5.sp)
                Text("Original photos are kept intact.", color = AnalogColors.MutedMetal, fontSize = 12.sp)
            }
        }
        if(newDialog){
            var name by remember{mutableStateOf("")}
            AnalogDialog(
                title = "A new point of view",
                onDismissRequest = { newDialog = false },
                confirmButton = { MetalButton("Create shoot", style = MetalStyle.Brass, height = 40.dp, onClick = { newDialog = false; vm.newShoot(name) }) },
                dismissButton = { MetalButton("Cancel", style = MetalStyle.Recessed, height = 40.dp, onClick = { newDialog = false }) }
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Shoot name") },
                    placeholder = { Text("Oak tree at golden hour") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if(aiDialog){
            val active = connection.active
            if(active==null || !active.connected || !active.canUsePlan){
                AnalogDialog(
                    title = "Connect your ChatGPT account",
                    onDismissRequest = { aiDialog = false },
                    confirmButton = { MetalButton("Open Account", style = MetalStyle.Brass, height = 40.dp, onClick = { aiDialog = false; vm.openAccount() }) },
                    dismissButton = { MetalButton("Cancel", style = MetalStyle.Recessed, height = 40.dp, onClick = { aiDialog = false }) }
                ) {
                    Text(
                        text = if(active?.connected==true) "Your ChatGPT account (${active.label}) is connected, but plan usage permission is not authorized yet. Open Account to authorize plan usage."
                        else "Connect your ChatGPT account in Account before using AI composition. Local alignment and editing remain available without an account.",
                        color = AnalogColors.EnamelCream,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
            } else {
                AnalogDialog(
                    title = "Compose with your ChatGPT account",
                    onDismissRequest = { aiDialog = false },
                    confirmButton = { MetalButton("Compose with AI", style = MetalStyle.Brass, height = 40.dp, onClick = { aiDialog = false; vm.aiCompose() }) },
                    dismissButton = { MetalButton("Cancel", style = MetalStyle.Recessed, height = 40.dp, onClick = { aiDialog = false }) }
                ) {
                    Text("Send resized previews of all ${vm.project?.frames?.size ?: 0} photos to OpenAI, in groups of six, followed by a composite review. Your authorized ChatGPT plan usage applies.", color = AnalogColors.EnamelCream, fontSize = 13.sp, lineHeight = 19.sp)
                    Box(
                        Modifier.fillMaxWidth().machinedMetal(MetalStyle.Recessed, 9.dp).padding(10.dp)
                    ) {
                        Text(
                            "ACCOUNT · ${active.label}\nMODEL   · ${vm.selectedModelLabel.ifBlank { "Auto-load from account" }}",
                            color = AnalogColors.WarmAmber,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            lineHeight = 16.sp
                        )
                    }
                    Text("AI can refine alignment, layer opacity, soft masks and color. You can undo the result and refine every edit. Originals and RAW files remain on your phone.", color = AnalogColors.MutedMetal, fontSize = 12.sp, lineHeight = 17.sp)
                    Choice("Look",vm.style,listOf(
                        "Natural · recognizable subject, soft surrounding texture",
                        "Portrait · natural center person, expressive messy surroundings",
                        "Dreamlike · luminous, painterly and atmospheric",
                        "Graphic · defined subject, richer contrast"
                    )){vm.style=it}
                }
            }
        }
        if(exportDialog){
            AnalogDialog(
                title = "Save your finished photograph",
                onDismissRequest = { exportDialog = false },
                confirmButton = {
                    MetalButton("Choose destination", style = MetalStyle.Brass, height = 40.dp, onClick = {
                        exportDialog = false
                        if(exportPng) pngExport.launch("LightPlay-${System.currentTimeMillis()}.png")
                        else photoExport.launch("LightPlay-${System.currentTimeMillis()}.jpg")
                    })
                },
                dismissButton = { MetalButton("Cancel", style = MetalStyle.Recessed, height = 40.dp, onClick = { exportDialog = false }) }
            ) {
                Choice("Longest edge",exportEdge.toString(),listOf("2048","3072","4096","6000")){exportEdge=it.toInt()}
                Row(verticalAlignment=Alignment.CenterVertically){
                    Checkbox(exportPng,{exportPng=it},colors=CheckboxDefaults.colors(checkedColor=AnalogColors.BrassLight,checkmarkColor=AnalogColors.EngravedDark))
                    Text("Lossless PNG", color = AnalogColors.EnamelCream, fontSize = 13.5.sp)
                }
                Text("JPEG uses quality 97. Resolution is limited to the source size and a safe memory budget; actual dimensions are shown after export.",color=AnalogColors.MutedMetal,fontSize=12.sp,lineHeight=17.sp)
            }
        }
    }
}

@Composable private fun Home(vm:StudioModel,new:()->Unit){
    var actionTarget by remember{mutableStateOf<Project?>(null)}
    var renameTarget by remember{mutableStateOf<Project?>(null)}
    var deleteTarget by remember{mutableStateOf<Project?>(null)}

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=18.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
        // Analog rangefinder hero plate
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(248.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF070A09))
                .padding(2.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(1.2.dp, Brush.linearGradient(listOf(AnalogColors.BrassLight.copy(alpha = 0.65f), Color(0x44000000), AnalogColors.BrassDeep.copy(alpha = 0.65f))), RoundedCornerShape(18.dp))
                .background(Brush.radialGradient(listOf(Color(0xFF23332B), Color(0xFF131B17), Color(0xFF0A0E0C))))
        ) {
            OrbitArt(Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.TopStart).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(AnalogColors.IndexRed))
                    Text("35MM · MULTIPLE EXPOSURE", fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.6.sp, color = AnalogColors.BrassLight)
                }
            }
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)){
                Text("One subject.\nMany perspectives.",fontFamily=FontFamily.Serif,fontSize=31.sp,lineHeight=34.sp,color=AnalogColors.EnamelCream)
                Text("Make photographs that feel like paintings.",color=AnalogColors.EnamelCream.copy(alpha=.8f),fontSize=13.sp,modifier=Modifier.padding(top=8.dp))
            }
        }

        MetalButton(
            text = "Start a new shoot",
            icon = Icons.Outlined.Add,
            style = MetalStyle.Brass,
            height = 54.dp,
            modifier = Modifier.fillMaxWidth(),
            onClick = new
        )

        // Machined 3-step film workflow strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .machinedMetal(MetalStyle.Gunmetal, 13.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            listOf("01" to "Capture","02" to "Compose","03" to "Refine").forEach{(n,label)->
                Column(Modifier.weight(1f)){
                    Text(n,color=AnalogColors.WarmAmber,fontFamily=FontFamily.Monospace,fontWeight=FontWeight.Bold,fontSize=11.sp)
                    Text(label,fontSize=14.sp,fontWeight=FontWeight.Medium,color=AnalogColors.EnamelCream,modifier=Modifier.padding(top=2.dp))
                }
            }
        }

        Section("Your shoots",if(vm.projects.isEmpty())"A little movement. A different kind of photograph." else "${vm.projects.size} saved locally · long-press to rename or delete")

        vm.projects.forEach{p->
            LeatherCard(
                onClick = { vm.open(p) },
                onLongClick = { actionTarget = p },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
                    Box(
                        Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.2.dp, AnalogColors.BrassLine, RoundedCornerShape(10.dp))
                    ) {
                        Thumb(vm,p,p.frames.firstOrNull(),Modifier.fillMaxSize())
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)){
                        Text(p.name,fontWeight=FontWeight.SemiBold,fontSize=15.sp,color=AnalogColors.EnamelCream,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilmCounterWindow(p.frames.size, label = "FRM")
                            if (p.lowLight) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(Color(0x33C29E69))
                                        .border(0.7.dp, AnalogColors.BrassLine, RoundedCornerShape(5.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("LOW LIGHT", color = AnalogColors.WarmAmber, fontFamily = FontFamily.Monospace, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Text("local shoot",color=AnalogColors.MutedMetal,fontSize=11.5.sp)
                            }
                        }
                    }
                    Icon(Icons.Outlined.ChevronRight,null,tint=AnalogColors.BrassLight)
                }
            }
        }

        MetalButton(
            text = "Explore an illustrated demo",
            icon = Icons.Outlined.AutoAwesome,
            style = MetalStyle.Steel,
            modifier = Modifier.fillMaxWidth(),
            onClick = vm::demo
        )
        Text("Inspired by Pep Ventosa’s in-the-round technique. An independent creative tool.",color=AnalogColors.MutedMetal,fontSize=11.5.sp,lineHeight=18.sp)
        Spacer(Modifier.height(10.dp))
    }

    actionTarget?.let { target ->
        AnalogDialog(
            title = target.name,
            onDismissRequest = { actionTarget = null },
            confirmButton = { MetalButton("Close", style = MetalStyle.Recessed, height = 40.dp, onClick = { actionTarget = null }) }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilmCounterWindow(target.frames.size, label = "EXP")
                Text("${target.frames.size} saved photograph${if (target.frames.size == 1) "" else "s"}", color = AnalogColors.MutedMetal, fontSize = 12.5.sp)
            }
            MetalButton(
                text = "Rename shoot",
                icon = Icons.Outlined.Edit,
                style = MetalStyle.Steel,
                modifier = Modifier.fillMaxWidth(),
                onClick = { actionTarget = null; renameTarget = target }
            )
            MetalButton(
                text = "Delete shoot",
                icon = Icons.Outlined.DeleteOutline,
                style = MetalStyle.Recessed,
                modifier = Modifier.fillMaxWidth(),
                onClick = { actionTarget = null; deleteTarget = target }
            )
        }
    }

    renameTarget?.let { target ->
        var editedName by remember(target.id) { mutableStateOf(target.name) }
        AnalogDialog(
            title = "Rename shoot",
            onDismissRequest = { renameTarget = null },
            confirmButton = {
                MetalButton("Save name", style = MetalStyle.Brass, height = 40.dp, onClick = {
                    val chosen = editedName
                    renameTarget = null
                    vm.renameShoot(target, chosen)
                })
            },
            dismissButton = { MetalButton("Cancel", style = MetalStyle.Recessed, height = 40.dp, onClick = { renameTarget = null }) }
        ) {
            OutlinedTextField(
                value = editedName,
                onValueChange = { editedName = it },
                label = { Text("Shoot name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    deleteTarget?.let { target ->
        AnalogDialog(
            title = "Delete shoot?",
            onDismissRequest = { deleteTarget = null },
            confirmButton = {
                MetalButton("Delete", icon = Icons.Outlined.DeleteOutline, style = MetalStyle.Brass, height = 40.dp, onClick = {
                    deleteTarget = null
                    vm.deleteShoot(target)
                })
            },
            dismissButton = { MetalButton("Cancel", style = MetalStyle.Recessed, height = 40.dp, onClick = { deleteTarget = null }) }
        ) {
            Text(
                "Permanently delete “${target.name}” along with all ${target.frames.size} photo${if (target.frames.size == 1) "" else "s"}, RAW files, and edits inside it? This cannot be undone.",
                color = AnalogColors.EnamelCream,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
        }
    }
}

@Composable private fun OrbitArt(modifier:Modifier){
    Canvas(modifier){
        val center=Offset(size.width*.72f,size.height*.32f)
        // Warm analog 35mm light leak glow upper-right
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0x52E68A5C), Color(0x00E68A5C)), center = Offset(size.width * 0.92f, size.height * 0.08f), radius = size.width * 0.55f),
            radius = size.width * 0.55f,
            center = Offset(size.width * 0.92f, size.height * 0.08f)
        )
        // Concentric engraved brass lens rings
        for(i in 0..4){
            drawCircle(
                color = AnalogColors.BrassLight.copy(alpha = 0.08f + i * 0.025f),
                radius = size.width * (.12f + i * .065f),
                center = center,
                style = DrawStroke(width = if (i == 2) 2.2f else 1.2f)
            )
        }
        // Subtle f-stop calibration ticks along the outer ring
        val tickR = size.width * 0.38f
        for (deg in 0 until 360 step 15) {
            val rad = Math.toRadians(deg.toDouble())
            val p1 = center + Offset(cos(rad).toFloat() * (tickR - 6f), sin(rad).toFloat() * (tickR - 6f))
            val p2 = center + Offset(cos(rad).toFloat() * (tickR + 6f), sin(rad).toFloat() * (tickR + 6f))
            drawLine(AnalogColors.BrassLight.copy(alpha = 0.16f), p1, p2, strokeWidth = 1.4f)
        }
        // Painterly multi-exposure light discs
        for(i in 0..64){
            val a=i*2.39996f; val r=sqrt(i/64f)*size.width*.24f
            drawCircle(
                color = when (i % 3) { 0 -> AnalogColors.WarmAmber; 1 -> AnalogColors.SageGlow; else -> Color(0xFFE99B7F) }.copy(alpha=.12f),
                radius = 7 + i % 6f,
                center = center + Offset(cos(a)*r, sin(a)*r*.75f)
            )
        }
        // Subtle vertical tree trunk impression
        for(i in 0..4) drawLine(AnalogColors.BrassLight.copy(alpha=.13f), center+Offset(i*3f-6, 4f), center+Offset(i*4f-6, size.height*.52f), strokeWidth=2.5f)
    }
}

@Composable private fun Section(title:String,subtitle:String=""){
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text(title,fontFamily=FontFamily.Serif,fontSize=24.sp,color=AnalogColors.EnamelCream)
        if(subtitle.isNotBlank())Text(subtitle,color=AnalogColors.MutedMetal,fontSize=12.5.sp,lineHeight=18.sp)
    }
}

@Composable private fun Thumb(vm:StudioModel,p:Project,f:Frame?,modifier:Modifier){
    val image by produceState<Bitmap?>(null,p.id,f?.file){value=if(f==null)null else withContext(Dispatchers.IO){runCatching{ProjectStore.decode(vm.store.photo(p,f),180)}.getOrNull()}}
    Box(modifier.background(Color(0xFF1A241E)),contentAlignment=Alignment.Center){
        image?.let{Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)}
            ?: Icon(Icons.Outlined.FilterFrames,null,tint=AnalogColors.BrassLight)
    }
}

@Composable private fun FilmstripRow(
    vm: StudioModel,
    p: Project,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0A0D0B))
            .border(1.dp, Color(0x44C29E69), RoundedCornerShape(12.dp))
            .padding(vertical = 8.dp)
    ) {
        // Subtle 35mm film sprocket strip accent
        Canvas(Modifier.fillMaxWidth().height(5.dp).padding(horizontal = 10.dp)) {
            val count = max(8, (size.width / 18.dp.toPx()).toInt())
            val spacing = size.width / count
            for (i in 0 until count) {
                drawRoundRect(
                    color = Color(0x40D4B27D),
                    topLeft = Offset(i * spacing + 4f, 0f),
                    size = androidx.compose.ui.geometry.Size(8.dp.toPx(), size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(p.frames) { index, frame ->
                val isSelected = selectedIndex == index
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onSelect(index) }
                ) {
                    Thumb(
                        vm, p, frame,
                        Modifier
                            .size(64.dp)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) AnalogColors.BrassLight else Color(0x33F3EFE4),
                                shape = RoundedCornerShape(7.dp)
                            )
                            .padding(2.dp)
                            .clip(RoundedCornerShape(5.dp))
                    )
                    Text(
                        text = if (selectedIndex != null) "%02d · %d%%".format(index + 1, (frame.opacity * 100).roundToInt()) else "%02d".format(index + 1),
                        color = if (isSelected) AnalogColors.WarmAmber else AnalogColors.MutedMetal,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.5.sp,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable private fun Shoot(vm:StudioModel,capture:()->Unit,import:()->Unit,ai:()->Unit,export:()->Unit,archive:()->Unit){
    val p=vm.project ?:return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=18.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("01 / CAPTURE  →  02 / COMPOSE  →  03 / REFINE",color=AnalogColors.BrassLight,fontFamily=FontFamily.Monospace,fontSize=9.5.sp,letterSpacing=0.8.sp)
                Spacer(Modifier.height(4.dp))
                Section(p.name,"${p.frames.size} photographs · originals kept in shoot folder")
            }
            FilmCounterWindow(p.frames.size, label = "EXP")
        }

        if(p.frames.isEmpty()){
            LeatherCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(AnalogColors.IndexRed))
                        Text("Find your anchor",fontFamily=FontFamily.Serif,fontSize=24.sp,color=AnalogColors.EnamelCream)
                    }
                    Text("Start with a tree, statue or lamp post. Take 20–30 sharp photos while moving in a gentle arc. Keep the same lens, distance and subject position. A full circle is optional.",color=AnalogColors.MutedMetal,fontSize=13.5.sp,lineHeight=21.sp)
                    Text("Lock exposure and white balance after composing. Keep checking focus as you move.",fontSize=12.5.sp,color=AnalogColors.WarmAmber)
                }
            }
        }else{
            Preview(vm,false)
            FilmstripRow(vm, p, selectedIndex = null) { index -> vm.selected = index; vm.openEdit() }
        }

        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
            MetalButton("Capture", icon = Icons.Outlined.CameraAlt, style = MetalStyle.Brass, modifier = Modifier.weight(1f), onClick = capture)
            MetalButton("Import photos", icon = Icons.Outlined.PhotoLibrary, style = MetalStyle.Steel, modifier = Modifier.weight(1f), onClick = import)
        }

        if(p.frames.isNotEmpty()){
            MetalPlateCard(showScrews = true) {
                Section("Compose the orbit","Anchor the subject. Let the surroundings overlap.")
                Spacer(Modifier.height(12.dp))
                LowLightModeDeck(p, vm::setLowLightMode)
                Spacer(Modifier.height(14.dp))
                MetalButton("Compose with AI", icon = Icons.Outlined.AutoAwesome, style = MetalStyle.Brass, enabled = p.frames.size >= 2, modifier = Modifier.fillMaxWidth(), onClick = ai)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    MetalButton("Align locally", style = MetalStyle.Steel, enabled = p.frames.size >= 2, modifier = Modifier.weight(1f), onClick = vm::align)
                    MetalButton("Edit layers", style = MetalStyle.Steel, modifier = Modifier.weight(1f), onClick = vm::openEdit)
                }
                Spacer(Modifier.height(10.dp))
                Text("Local alignment and editing need no account. AI composition sends previews only after you choose it.",fontSize=11.5.sp,color=AnalogColors.MutedMetal,lineHeight=17.sp)
            }
            MetalButton("Export finished image", icon = Icons.Outlined.Download, style = MetalStyle.Steel, modifier = Modifier.fillMaxWidth(), onClick = export)
            MetalButton("Back up shoot + originals", icon = Icons.Outlined.Inventory2, style = MetalStyle.Recessed, modifier = Modifier.fillMaxWidth(), onClick = archive)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable private fun Preview(vm:StudioModel,editable:Boolean,maskMode:String="View",brush:Float=.035f,strength:Float=.8f){
    val p=vm.project ?:return; val bitmap=vm.preview
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

    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max=430.dp)
            .aspectRatio(ratio,matchHeightConstraintsFirst=true)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF070A09))
            .border(1.5.dp, Brush.verticalGradient(listOf(AnalogColors.BrassLine, Color(0x66000000))), RoundedCornerShape(14.dp))
            .padding(2.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(touch)
            .then(dab),
        contentAlignment=Alignment.Center
    ){
        if(bitmap!=null)Image(bitmap.asImageBitmap(),"Composition preview",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
        else if(vm.previewError!=null)Text(vm.previewError!!,Modifier.padding(20.dp),color=AnalogColors.MutedMetal)
        else CircularProgressIndicator(color=AnalogColors.BrassLight)
        if(vm.renderBusy)CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(12.dp).size(20.dp),strokeWidth=2.dp,color=AnalogColors.BrassLight)
        if(editable && maskMode=="View")Canvas(Modifier.fillMaxSize()){
            val a=Offset(p.anchor.x*size.width,p.anchor.y*size.height)
            drawCircle(AnalogColors.WarmAmber.copy(alpha=.8f), 14f, a, style = DrawStroke(1.5f))
            drawLine(AnalogColors.WarmAmber.copy(alpha=.9f),a-Offset(10f,0f),a+Offset(10f,0f),2f)
            drawLine(AnalogColors.WarmAmber.copy(alpha=.9f),a-Offset(0f,10f),a+Offset(0f,10f),2f)
        }
        if(points.isNotEmpty())Text("${if(maskMode=="Hide")"Hiding" else "Restoring"} · release to preview",modifier=Modifier.align(Alignment.BottomCenter).padding(10.dp).background(AnalogColors.LeatherBase.copy(alpha=.88f),RoundedCornerShape(8.dp)).padding(8.dp),color=AnalogColors.WarmAmber,fontFamily=FontFamily.Monospace,fontSize=11.sp)
        if(points.isNotEmpty())Canvas(Modifier.fillMaxSize()){
            val path=Path();points.forEachIndexed{i,pt->val mapped=currentFrame?.transform?.forward(pt,ratio) ?:pt;if(i==0)path.moveTo(mapped.x*size.width,mapped.y*size.height)else path.lineTo(mapped.x*size.width,mapped.y*size.height)}
            drawPath(path,if(maskMode=="Hide")AnalogColors.IndexRed.copy(alpha=.55f)else AnalogColors.SageGlow.copy(alpha=.55f),style=DrawStroke(brush*min(size.width,size.height)*2,cap=StrokeCap.Round,join=StrokeJoin.Round))
        }
        if(editable && p.crop>0)Canvas(Modifier.fillMaxSize()){val x=size.width*p.crop;val y=size.height*p.crop;drawRect(Color.Black.copy(alpha=.45f),size=androidx.compose.ui.geometry.Size(size.width,y));drawRect(Color.Black.copy(alpha=.45f),Offset(0f,size.height-y),androidx.compose.ui.geometry.Size(size.width,y));drawRect(Color.Black.copy(alpha=.45f),Offset(0f,y),androidx.compose.ui.geometry.Size(x,size.height-2*y));drawRect(Color.Black.copy(alpha=.45f),Offset(size.width-x,y),androidx.compose.ui.geometry.Size(x,size.height-2*y))}
    }
}

@Composable private fun Editor(vm:StudioModel,ai:()->Unit,export:()->Unit){
    val p=vm.project ?:return; if(p.frames.isEmpty())return
    val selectedIndex=vm.selected.coerceIn(p.frames.indices)
    LaunchedEffect(selectedIndex){if(vm.selected!=selectedIndex)vm.selected=selectedIndex}
    val f=p.frames[selectedIndex]
    var maskMode by remember{mutableStateOf("View")}; var brush by remember{mutableFloatStateOf(.035f)}; var strength by remember{mutableFloatStateOf(.8f)}
    var advanced by remember{mutableStateOf(false)}

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(10.dp)){
            Column(Modifier.weight(1f)){
                Text("The finishing room",fontFamily=FontFamily.Serif,fontSize=26.sp,color=AnalogColors.EnamelCream)
                Text(p.name,color=AnalogColors.MutedMetal,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            MetalIconButton(
                icon = Icons.AutoMirrored.Outlined.Undo,
                contentDescription = "Undo last edit",
                enabled = vm.undoCount > 0,
                onClick = vm::undo
            )
            MetalButton("Export", style = MetalStyle.Brass, height = 40.dp, onClick = export)
        }

        Preview(vm,true,maskMode,brush,strength)
        Text(
            if(maskMode=="View")"Long-press the subject to set the alignment anchor." else "Paint with one finger on the image. Masks are soft and reversible.",
            fontSize=11.sp,
            color=AnalogColors.MutedMetal
        )

        Row(verticalAlignment=Alignment.CenterVertically){
            Text("FILMSTRIP LAYERS",fontFamily=FontFamily.Monospace,fontSize=11.sp,letterSpacing=1.2.sp,fontWeight=FontWeight.SemiBold,color=AnalogColors.BrassLight,modifier=Modifier.weight(1f))
            MetalButton(
                text = if(vm.solo)"Show blend" else "View selected layer",
                style = if(vm.solo) MetalStyle.Brass else MetalStyle.Recessed,
                height = 34.dp,
                onClick = { vm.solo = !vm.solo; vm.refreshPreview() }
            )
        }
        FilmstripRow(vm, p, selectedIndex = selectedIndex) { index -> vm.selected = index; vm.refreshPreview() }

        // Layer control deck
        MetalPlateCard(showScrews = true) {
            Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)){
                Text("Layer ${selectedIndex+1}",fontWeight=FontWeight.SemiBold,fontSize=15.sp,color=AnalogColors.EnamelCream,modifier=Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(5.dp)).background(AnalogColors.RecessedBg).border(0.7.dp, AnalogColors.BrassLine, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(if(f.raw!=null)"RAW + JPEG" else "JPEG",color=AnalogColors.WarmAmber,fontFamily=FontFamily.Monospace,fontSize=10.sp)
                }
                Switch(
                    checked = f.visible,
                    onCheckedChange = { value -> vm.frame { it.copy(visible = value) } },
                    colors = SwitchDefaults.colors(checkedThumbColor = AnalogColors.EngravedDark, checkedTrackColor = AnalogColors.BrassLight)
                )
            }
            Spacer(Modifier.height(10.dp))
            AnalogDialSlider("Opacity",f.opacity,0f..1f,{"${(it*100).roundToInt()}%"},p.id+f.id){value->vm.frame{it.copy(opacity=value)}}
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                listOf("View","Hide","Restore").forEach{mode->
                    MetalChip(label = mode, selected = maskMode == mode, onClick = { maskMode = mode })
                }
            }
            if(maskMode!="View"){
                Spacer(Modifier.height(12.dp))
                AnalogDialSlider("Brush size",brush,0.005f..0.15f,{"${(it*200).roundToInt()}%"},"brush"){brush=it}
                Spacer(Modifier.height(6.dp))
                AnalogDialSlider("Brush strength",strength,.05f..1f,{"${(it*100).roundToInt()}%"},"strength"){strength=it}
                Spacer(Modifier.height(10.dp))
                MetalButton("Clear masks on this layer", style = MetalStyle.Recessed, height = 38.dp, modifier = Modifier.fillMaxWidth(), onClick = { vm.frame { it.copy(masks = emptyList(), regions = emptyList()) } })
            }
            Spacer(Modifier.height(10.dp))
            MetalButton(
                text = if(advanced)"Hide alignment controls" else "Adjust layer alignment",
                style = MetalStyle.Recessed,
                height = 38.dp,
                modifier = Modifier.fillMaxWidth(),
                onClick = { advanced = !advanced }
            )
            if(advanced){
                Spacer(Modifier.height(10.dp))
                Text(
                    if(f.confidence<.30f)"Low alignment confidence — check this frame manually." else "Alignment confidence ${(f.confidence*100).roundToInt()}%",
                    color = if(f.confidence<.30f) AnalogColors.IndexRed else AnalogColors.MutedMetal,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(6.dp))
                AnalogDialSlider("Horizontal",f.transform.dx,-0.3f..0.3f,{"%.1f%%".format(it*100)},f.id+"x"){v->vm.frame{it.copy(transform=it.transform.copy(dx=v))}}
                AnalogDialSlider("Vertical",f.transform.dy,-0.3f..0.3f,{"%.1f%%".format(it*100)},f.id+"y"){v->vm.frame{it.copy(transform=it.transform.copy(dy=v))}}
                AnalogDialSlider("Scale",f.transform.scale,.7f..1.4f,{"%.2f×".format(it)},f.id+"s"){v->vm.frame{it.copy(transform=it.transform.copy(scale=v))}}
                AnalogDialSlider("Rotation",f.transform.degrees,-20f..20f,{"%.1f°".format(it)},f.id+"r"){v->vm.frame{it.copy(transform=it.transform.copy(degrees=v))}}
            }
        }

        // Color & atmosphere darkroom deck
        MetalPlateCard(showScrews = true) {
            Section("Color & atmosphere")
            Spacer(Modifier.height(10.dp))
            LowLightModeDeck(p, vm::setLowLightMode)
            Spacer(Modifier.height(12.dp))
            AnalogDialSlider("Exposure",p.grade.exposure,-2f..2f,{"%.2f EV".format(it)},p.id+"ev"){vm.edit(p.copy(grade=p.grade.copy(exposure=it)))}
            Spacer(Modifier.height(6.dp))
            AnalogDialSlider("Contrast",p.grade.contrast,.5f..1.8f,{"%.2f".format(it)},p.id+"c"){vm.edit(p.copy(grade=p.grade.copy(contrast=it)))}
            Spacer(Modifier.height(6.dp))
            AnalogDialSlider("Saturation",p.grade.saturation,0f..2f,{"%.2f".format(it)},p.id+"sat"){vm.edit(p.copy(grade=p.grade.copy(saturation=it)))}
            Spacer(Modifier.height(6.dp))
            AnalogDialSlider("Warmth",p.grade.warmth,-1f..1f,{"%.2f".format(it)},p.id+"warm"){vm.edit(p.copy(grade=p.grade.copy(warmth=it)))}
            Spacer(Modifier.height(6.dp))
            AnalogDialSlider("Trim edges",p.crop,0f..0.2f,{"${(it*100).roundToInt()}%"},p.id+"crop"){vm.edit(p.copy(crop=it))}
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                MetalButton("Equal blend", style = MetalStyle.Steel, modifier = Modifier.weight(1f), onClick = { vm.edit(p.averaged()) })
                MetalButton("AI compose", icon = Icons.Outlined.AutoAwesome, style = MetalStyle.Brass, enabled = p.frames.size > 1, modifier = Modifier.weight(1f), onClick = ai)
            }
        }

        if(p.aiNote.isNotBlank()){
            LeatherCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)){
                    Text("Art director’s notes",fontWeight=FontWeight.SemiBold,color=AnalogColors.BrassLight,fontSize=14.sp)
                    Text(p.aiNote,color=AnalogColors.EnamelCream.copy(alpha=0.88f),fontSize=13.sp,lineHeight=19.sp,modifier=Modifier.padding(top=8.dp))
                }
            }
        }
        Text("RAW files are preserved as digital negatives. Composition uses their camera-rendered JPEG companions.",color=AnalogColors.MutedMetal,fontSize=11.sp)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable private fun <T> ChoiceItem(label:String,value:String,options:List<T>,itemLabel:(T)->String,enabled:Boolean=true,choose:(T)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.uppercase(),color=AnalogColors.MutedMetal,fontFamily=FontFamily.Monospace,fontSize=10.sp,letterSpacing=0.8.sp)
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .machinedMetal(if (enabled && options.isNotEmpty()) MetalStyle.Steel else MetalStyle.Recessed, 10.dp)
                    .clickable(enabled = enabled && options.isNotEmpty()) { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(value,Modifier.weight(1f),fontSize=12.5.sp,color=if(enabled)AnalogColors.EnamelCream else AnalogColors.MutedMetal,maxLines=1,overflow=TextOverflow.Ellipsis)
                Icon(Icons.Outlined.ExpandMore,null,tint=AnalogColors.BrassLight,modifier=Modifier.size(18.dp))
            }
            DropdownMenu(expanded,onDismissRequest={expanded=false},containerColor=AnalogColors.GunmetalMid) {
                options.forEach { option ->
                    DropdownMenuItem(text={Text(itemLabel(option),fontSize=13.sp,color=AnalogColors.EnamelCream)},onClick={expanded=false;choose(option)})
                }
            }
        }
    }
}
@Composable private fun Choice(label:String,value:String,options:List<String>,enabled:Boolean=true,choose:(String)->Unit) =
    ChoiceItem(label,value,options,{it},enabled,choose)

@Composable private fun LowLightModeDeck(p: Project, onSet: (Boolean, Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "EXPOSURE MODE",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                color = AnalogColors.MutedMetal,
                modifier = Modifier.weight(1f)
            )
            if (p.lowLight && p.lowLightAuto) {
                Text(
                    "AUTO-DETECTED",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = AnalogColors.WarmAmber
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetalChip(
                label = "Daylight",
                selected = !p.lowLight,
                onClick = { onSet(false, false) },
                modifier = Modifier.weight(1f)
            )
            MetalChip(
                label = "Low Light",
                selected = p.lowLight,
                onClick = { onSet(true, false) },
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = if (p.lowLight) "Additive exposure stacking + micro-contrast detail accentuation for dim scenes."
            else "Standard balanced averaging across frames for daylight scenes.",
            fontSize = 11.sp,
            color = AnalogColors.MutedMetal,
            lineHeight = 15.sp
        )
    }
}

private fun formatShutter(exposureNs:Long):String {
    val safe=exposureNs.coerceAtLeast(1L)
    return if(safe>=500_000_000L) "%.1f s".format(safe/1_000_000_000.0)
    else "1/${(1_000_000_000.0/safe).roundToInt().coerceAtLeast(1)} s"
}

@Composable private fun Capture(vm:StudioModel){
    val context=LocalContext.current; val lifecycle=LocalLifecycleOwner.current
    val lens=vm.lenses.find{it.key==vm.lensKey}; val p=vm.project ?:return
    var settingsOpen by remember{mutableStateOf(false)}
    var lensPickerOpen by remember{mutableStateOf(false)}
    var ghost by remember{mutableStateOf(true)}
    val view=remember{TextureView(context)}; val config by rememberUpdatedState(vm.cameraSettings)
    DisposableEffect(lens?.key,vm.cameraSettings.size,vm.cameraSettings.raw,lifecycle){
        fun open(){lens?.let{vm.cameraSettings=vm.cameraSettings.copy(locked=false);vm.camera.open(view,it,config)}}
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)open()else if(event==Lifecycle.Event.ON_PAUSE)vm.pauseCamera()}
        lifecycle.lifecycle.addObserver(observer)
        onDispose{lifecycle.lifecycle.removeObserver(observer);vm.pauseCamera()}
    }
    val guide by produceState<Bitmap?>(null,p.id,p.frames.firstOrNull()?.file){value=p.frames.firstOrNull()?.let{withContext(Dispatchers.IO){runCatching{ProjectStore.decode(vm.store.photo(p,it),640)}.getOrNull()}}}

    Column(Modifier.fillMaxSize().padding(horizontal=14.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        // Top camera instrument bar
        Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilmCounterWindow(p.frames.size, label = "EXP")
            Text("${p.frames.size} frames",fontFamily=FontFamily.Monospace,fontSize=11.sp,color=AnalogColors.MutedMetal,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
            // Tactile Low Light mode indicator & quick-cycle switch (Auto / Low Light / Daylight)
            val modeText = when {
                p.lowLightAuto && p.lowLight -> "LOW·AUTO"
                p.lowLightAuto -> "AUTO"
                p.lowLight -> "LOW ON"
                else -> "DAY"
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .machinedMetal(if (p.lowLight) MetalStyle.Brass else MetalStyle.Steel, 10.dp)
                    .clickable(enabled = !vm.capturing) { vm.cycleCaptureLowLightMode() }
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (p.lowLight) Icons.Outlined.NightsStay else Icons.Outlined.WbSunny,
                    contentDescription = "Low light shooting mode",
                    tint = if (p.lowLight) AnalogColors.EngravedDark else AnalogColors.WarmAmber,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = modeText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (p.lowLight) AnalogColors.EngravedDark else AnalogColors.EnamelCream
                )
            }
            MetalIconButton(
                icon = if(ghost)Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                contentDescription = "Toggle first-frame ghost",
                size = 38.dp,
                active = ghost,
                onClick = { ghost = !ghost }
            )
            MetalIconButton(
                icon = Icons.Outlined.Tune,
                contentDescription = "Camera settings",
                size = 38.dp,
                enabled = !vm.capturing,
                onClick = { settingsOpen = true }
            )
        }

        // Rangefinder optical viewfinder in brushed-metal housing
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF060807))
                .border(1.5.dp, Brush.verticalGradient(listOf(AnalogColors.BrassLine, Color(0x88000000))), RoundedCornerShape(16.dp))
                .padding(3.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Color.Black),
            contentAlignment=Alignment.Center
        ){
            val sensorOrientation=lens?.chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val rotated=sensorOrientation==90 || sensorOrientation==270
            val ratio=if(rotated)vm.cameraSettings.size.height.toFloat()/vm.cameraSettings.size.width else vm.cameraSettings.size.width.toFloat()/vm.cameraSettings.size.height
            val fitHeight=maxWidth/ratio > maxHeight
            Box(if(fitHeight)Modifier.fillMaxHeight().aspectRatio(ratio,matchHeightConstraintsFirst=true) else Modifier.fillMaxWidth().aspectRatio(ratio)){
                AndroidView(factory={view},modifier=Modifier.fillMaxSize())
                if(ghost)guide?.let{Image(it.asImageBitmap(),"First-frame alignment guide",Modifier.fillMaxSize(),contentScale=ContentScale.Fit,alpha=.20f)}
                // Etched rangefinder reticle + corner frame lines
                Canvas(Modifier.fillMaxSize()){
                    val anchor=Offset(p.anchor.x*size.width,p.anchor.y*size.height)
                    drawLine(Color.White.copy(alpha=.18f),Offset(size.width/3,0f),Offset(size.width/3,size.height),1f)
                    drawLine(Color.White.copy(alpha=.18f),Offset(size.width*2/3,0f),Offset(size.width*2/3,size.height),1f)
                    val m = 16f; val cLen = 22f
                    val bracketColor = AnalogColors.BrassLight.copy(alpha = 0.55f)
                    // 4 rangefinder corner framelines
                    drawLine(bracketColor, Offset(m, m), Offset(m + cLen, m), 2f)
                    drawLine(bracketColor, Offset(m, m), Offset(m, m + cLen), 2f)
                    drawLine(bracketColor, Offset(size.width - m, m), Offset(size.width - m - cLen, m), 2f)
                    drawLine(bracketColor, Offset(size.width - m, m), Offset(size.width - m, m + cLen), 2f)
                    drawLine(bracketColor, Offset(m, size.height - m), Offset(m + cLen, size.height - m), 2f)
                    drawLine(bracketColor, Offset(m, size.height - m), Offset(m, size.height - m - cLen), 2f)
                    drawLine(bracketColor, Offset(size.width - m, size.height - m), Offset(size.width - m - cLen, size.height - m), 2f)
                    drawLine(bracketColor, Offset(size.width - m, size.height - m), Offset(size.width - m, size.height - m - cLen), 2f)
                    // Rangefinder split-prism subject crosshair
                    drawLine(AnalogColors.WarmAmber,anchor-Offset(22f,0f),anchor+Offset(22f,0f),2f)
                    drawLine(AnalogColors.WarmAmber,anchor-Offset(0f,22f),anchor+Offset(0f,22f),2f)
                    drawCircle(AnalogColors.WarmAmber,36f,anchor,style=DrawStroke(1.5f))
                }
            }
            // Viewfinder top LED status indicator
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xC80B100E))
                    .border(0.8.dp, if (vm.cameraSettings.locked) AnalogColors.IndexRed.copy(alpha = 0.7f) else AnalogColors.BrassLine, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (vm.cameraSettings.locked) AnalogColors.IndexRed else AnalogColors.SageGlow)
                )
                Text(
                    if(vm.cameraSettings.locked)"EXPOSURE / WB LOCKED" else "COMPOSE → LOCK → CAPTURE",
                    color = if(vm.cameraSettings.locked) AnalogColors.WarmAmber else AnalogColors.EnamelCream,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.5.sp,
                    letterSpacing = 1.sp
                )
            }
            // Viewfinder analog light meter & low-light HUD
            if (p.lowLight || vm.liveEv100 != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Color(0xC0090D0B))
                        .border(0.7.dp, if (p.lowLight) AnalogColors.BrassLine else Color(0x33A2AEA4), RoundedCornerShape(7.dp))
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    if (p.lowLight) {
                        Icon(Icons.Outlined.NightsStay, null, tint = AnalogColors.WarmAmber, modifier = Modifier.size(12.dp))
                        Text(
                            if (p.lowLightAuto) "LOW LIGHT · AUTO" else "LOW LIGHT · ADDITIVE",
                            color = AnalogColors.WarmAmber,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    vm.liveEv100?.let { ev ->
                        Text(
                            "EV %.1f".format(ev),
                            color = if (p.lowLight) AnalogColors.EnamelCream else AnalogColors.MutedMetal,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.5.sp
                        )
                    }
                }
            }
        }

        // Tactile optical multi-camera turret + engraved active lens specs
        if (vm.lenses.isNotEmpty()) {
            LensTurretBar(
                lenses = vm.lenses,
                selectedKey = vm.lensKey,
                lockedForShoot = p.frames.isNotEmpty() || vm.capturing,
                onSelect = { chosen -> vm.chooseLens(chosen.key) },
                onOpenInspector = { if (!vm.capturing) lensPickerOpen = true }
            )
        }

        Text(vm.cameraStatus,color=AnalogColors.MutedMetal,fontSize=11.5.sp,maxLines=2,overflow=TextOverflow.Ellipsis)

        // Machined metal bottom control deck with mechanical shutter button
        Row(
            Modifier
                .fillMaxWidth()
                .machinedMetal(MetalStyle.Gunmetal, 16.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.SpaceBetween
        ){
            MetalButton(
                text = "Lock",
                icon = Icons.Outlined.Lock,
                style = if (vm.cameraSettings.locked) MetalStyle.Brass else MetalStyle.Steel,
                enabled = !vm.capturing,
                height = 44.dp,
                onClick = vm::lock
            )
            ShutterReleaseButton(
                enabled = !vm.capturing && vm.cameraSettings.locked,
                locked = vm.cameraSettings.locked,
                capturing = vm.capturing,
                onClick = vm::shutter
            )
            MetalButton(
                text = "Review",
                style = MetalStyle.Steel,
                enabled = !vm.capturing,
                height = 44.dp,
                onClick = vm::openShoot
            )
        }
    }

    if (lensPickerOpen && vm.lenses.isNotEmpty()) {
        LensPickerDialog(
            lenses = vm.lenses,
            selectedKey = vm.lensKey,
            lockedForShoot = p.frames.isNotEmpty(),
            onSelect = { chosen -> vm.chooseLens(chosen.key) },
            onDismiss = { lensPickerOpen = false }
        )
    }

    if(settingsOpen && lens!=null){
        AnalogDialog(
            title = "Capture settings",
            onDismissRequest = { settingsOpen = false },
            confirmButton = { MetalButton("Done", style = MetalStyle.Brass, height = 40.dp, onClick = { settingsOpen = false }) }
        ) {
            Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
                // Interactive lens summary card button opening the full multi-camera visual picker
                Text("CAMERA LENS", color = AnalogColors.MutedMetal, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 0.8.sp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .machinedMetal(MetalStyle.Steel, 10.dp)
                        .clickable { settingsOpen = false; lensPickerOpen = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(lens.zoomLabel, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = AnalogColors.WarmAmber, fontSize = 14.sp)
                    Column(Modifier.weight(1f)) {
                        Text("${lens.role}${lens.equivMm?.let { " · ${it}mm eq" } ?: ""}", color = AnalogColors.EnamelCream, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(lens.specSummary, color = AnalogColors.MutedMetal, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = AnalogColors.BrassLight)
                }
                if(p.frames.isNotEmpty())Text("Keep this lens throughout the shoot. Start a new shoot to switch lenses.",fontSize=11.sp,color=AnalogColors.MutedMetal)

                ChoiceItem("JPEG resolution",vm.cameraSettings.size.toString(),lens.sizes,{it.toString()},enabled=p.frames.isEmpty()){size->vm.cameraSettings=vm.cameraSettings.copy(size=size,locked=false)}
                Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    Switch(
                        checked = vm.cameraSettings.raw,
                        onCheckedChange = { vm.cameraSettings = vm.cameraSettings.copy(raw = it, locked = false) },
                        enabled = lens.rawSize != null,
                        colors = SwitchDefaults.colors(checkedThumbColor = AnalogColors.EngravedDark, checkedTrackColor = AnalogColors.BrassLight)
                    )
                    Text(if(lens.rawSize!=null)"Save RAW + JPEG" else "RAW not exposed by this lens",fontSize=12.5.sp,color=AnalogColors.EnamelCream)
                }
                if(lens.apertures.size>1)ChoiceItem("Aperture",vm.cameraSettings.aperture?.let{"f/$it"} ?: "Auto",lens.apertures.toList(),{"f/$it"}){value->vm.cameraSettings=vm.cameraSettings.copy(aperture=value);vm.camera.update(vm.cameraSettings)}
                else Text("Aperture: f/${lens.apertures.firstOrNull() ?: "—"} · fixed by the lens",color=AnalogColors.MutedMetal,fontSize=12.sp)
                if(lens.manual){
                    Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(10.dp)){
                        Switch(
                            checked = vm.cameraSettings.manual,
                            onCheckedChange = { vm.cameraSettings = vm.cameraSettings.copy(manual = it); vm.camera.update(vm.cameraSettings) },
                            colors = SwitchDefaults.colors(checkedThumbColor = AnalogColors.EngravedDark, checkedTrackColor = AnalogColors.BrassLight)
                        )
                        Text("Manual exposure",fontSize=13.sp,color=AnalogColors.EnamelCream)
                    }
                    if(vm.cameraSettings.manual){
                        val isoRange=lens.chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                        val isoOptions=listOf(50,100,200,400,800,1600,3200,6400).filter{isoRange?.contains(it)==true}
                        ChoiceItem("ISO",vm.cameraSettings.iso.toString(),isoOptions,{it.toString()}){vm.cameraSettings=vm.cameraSettings.copy(iso=it);vm.camera.update(vm.cameraSettings)}
                        val times=listOf(30,60,125,250,500,1000,2000);val exposureRange=lens.chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                        val shutterOptions=times.map{1_000_000_000L/it}.filter{exposureRange?.contains(it)==true}
                        ChoiceItem("Shutter",formatShutter(vm.cameraSettings.exposureNs),shutterOptions,::formatShutter){vm.cameraSettings=vm.cameraSettings.copy(exposureNs=it);vm.camera.update(vm.cameraSettings)}
                    }
                } else Text(if(lens.aeLock)"Auto exposure lock available." else "This lens does not expose exposure lock.",fontSize=12.sp,color=AnalogColors.MutedMetal)
                val wbs=mapOf("Auto (lock after composing)" to CaptureRequest.CONTROL_AWB_MODE_AUTO,"Daylight" to CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,"Cloudy" to CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,"Shade" to CaptureRequest.CONTROL_AWB_MODE_SHADE,"Tungsten" to CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,"Fluorescent" to CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT).filter{it.value in lens.wbModes}
                Choice("White balance",wbs.entries.firstOrNull{it.value==vm.cameraSettings.wb}?.key ?: "Auto",wbs.keys.toList()){vm.cameraSettings=vm.cameraSettings.copy(wb=wbs.getValue(it),locked=false);vm.camera.update(vm.cameraSettings)}
                val shootModes = listOf(
                    "Auto (detect low light)",
                    "Low Light (additive + detail)",
                    "Daylight (standard blend)"
                )
                val currentShootMode = when {
                    p.lowLightAuto -> shootModes[0]
                    p.lowLight -> shootModes[1]
                    else -> shootModes[2]
                }
                Choice("Shooting mode", currentShootMode, shootModes) { chosen ->
                    when (chosen) {
                        shootModes[0] -> vm.setLowLightMode(lowLight = vm.meteredLowLight, auto = true)
                        shootModes[1] -> vm.setLowLightMode(lowLight = true, auto = false)
                        else -> vm.setLowLightMode(lowLight = false, auto = false)
                    }
                }
                Text("In Low Light mode, exposures accumulate light with filmic highlight roll-off and accentuate fine detail.",fontSize=11.sp,color=AnalogColors.WarmAmber.copy(alpha=0.88f),lineHeight=16.sp)
                Text("Available controls come from Android’s camera interface. Samsung’s stock-camera modes may expose other resolutions. Lock again after changing settings or reopening capture.",fontSize=11.sp,color=AnalogColors.MutedMetal,lineHeight=16.sp)
            }
        }
    }
}

@Composable private fun Account(vm:StudioModel,browse:(String)->Unit,logs:()->Unit){
    val connection by vm.auth.state.collectAsState(); val active=connection.active
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=18.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
        Section("Your creative companion","Use your own ChatGPT account for AI art direction.")

        MetalPlateCard(showScrews = true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (active?.canUsePlan == true) AnalogColors.SageGlow else if (active?.connected == true) AnalogColors.WarmAmber else AnalogColors.IndexRed)
                )
                Text(
                    if(active?.connected==true) active.label else "Bring your ChatGPT account",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AnalogColors.EnamelCream
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("Eligible accounts can authorize use of their ChatGPT plan. You sign in through OpenAI in your browser. LightPlay never receives your password.",color=AnalogColors.MutedMetal,fontSize=13.sp,lineHeight=19.sp)
            Spacer(Modifier.height(14.dp))
            MetalButton("Continue with ChatGPT", style = MetalStyle.Brass, modifier = Modifier.fillMaxWidth(), onClick = { vm.connect(active, browse) })
            if(active?.connected==true){
                Spacer(Modifier.height(12.dp))
                Text(
                    if(active.canUsePlan)"Plan usage is authorized" else "Plan usage permission is required. Continue with ChatGPT to authorize it.",
                    color = if(active.canUsePlan) AnalogColors.SageGlow else AnalogColors.IndexRed,
                    fontSize = 12.sp
                )
                if(vm.models.isEmpty() && vm.model.isNotBlank()){
                    Spacer(Modifier.height(4.dp))
                    Text("Saved model: ${vm.selectedModelLabel}",color=AnalogColors.WarmAmber,fontFamily=FontFamily.Monospace,fontSize=11.5.sp)
                }
                Spacer(Modifier.height(10.dp))
                MetalButton(
                    text = if(vm.models.isEmpty() && vm.model.isBlank())"Load available models" else "Refresh available models",
                    style = MetalStyle.Steel,
                    enabled = active.canUsePlan,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = vm::loadModels
                )
            }
        }

        if(connection.accounts.isNotEmpty()){
            MetalPlateCard(showScrews = false) {
                Text("SAVED ACCOUNTS",fontFamily=FontFamily.Monospace,fontSize=10.5.sp,letterSpacing=1.2.sp,color=AnalogColors.BrassLight)
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    connection.accounts.forEach{account->
                        val isCurrent = account.clientId == connection.activeClientId
                        MetalButton(
                            text = (if(isCurrent)"✓  " else "") + account.label,
                            style = if(isCurrent) MetalStyle.Steel else MetalStyle.Recessed,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { vm.chooseAccount(account.clientId) }
                        )
                    }
                    MetalButton("Add another account or workspace", style = MetalStyle.Recessed, height = 40.dp, modifier = Modifier.fillMaxWidth(), onClick = { vm.connect(null, browse) })
                }
            }
        }

        if(vm.models.isNotEmpty()){
            MetalPlateCard(showScrews = false) {
                ChoiceItem("AI model",vm.selectedModelLabel.ifBlank { "Choose a model" },vm.models,{it.label}){chosen->vm.selectModel(chosen)}
                Spacer(Modifier.height(8.dp))
                Text("Choose a model that supports images. Your choice is saved for this account.",fontSize=12.sp,color=AnalogColors.MutedMetal)
            }
        }

        if(active?.connected==true){
            MetalButton("Manage ChatGPT access & usage", style = MetalStyle.Steel, modifier = Modifier.fillMaxWidth(), onClick = { browse("https://chatgpt.com/settings/usage") })
            MetalButton("Sign out of this account", style = MetalStyle.Recessed, modifier = Modifier.fillMaxWidth(), onClick = vm::signOut)
        }

        LeatherCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Section("On your device")
                Text("Every shoot has a private folder containing its originals and editable recipe. Export a shoot archive to keep a backup before uninstalling. No photos are uploaded until you request AI composition.",color=AnalogColors.MutedMetal,fontSize=12.5.sp,lineHeight=19.sp)
                MetalButton("Export diagnostic log", icon = Icons.Outlined.BugReport, style = MetalStyle.Steel, modifier = Modifier.fillMaxWidth(), onClick = logs)
                Text("Logs contain event timings, counts and device details. They exclude photos, account identities, tokens and authorization URLs.",color=AnalogColors.MutedMetal,fontSize=11.5.sp,lineHeight=17.sp)
            }
        }
        Text("LightPlay 0.1.0 · Independent, open-source software",color=AnalogColors.BrassLight,fontFamily=FontFamily.Monospace,fontSize=10.5.sp)
        Spacer(Modifier.height(8.dp))
    }
}
