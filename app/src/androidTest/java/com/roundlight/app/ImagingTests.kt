package com.roundlight.app

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.roundlight.app.data.*
import com.roundlight.app.imaging.Renderer
import com.roundlight.app.model.*
import com.roundlight.app.model.Point
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class) class ImagingTests {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val store=ProjectStore(context,Diagnostics(context));private val created=mutableListOf<String>()
    private fun project():Project=store.create("Test fixture").also{created+=it.id}.copy(grade=Grade(0f,1f,1f,0f))
    private fun add(p:Project,color:Int):Project {val bitmap=Bitmap.createBitmap(300,400,Bitmap.Config.ARGB_8888);bitmap.eraseColor(color);val data=ByteArrayOutputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,100,it);it.toByteArray()};bitmap.recycle();return store.addCapture(p,data,null,"Synthetic test")}
    @After fun cleanup(){created.forEach{store.folder(it).deleteRecursively()}}
    @Test fun jpegBlendMatchesKnownColors()=runBlocking {val p=add(add(project(),Color.RED),Color.BLUE);val result=Renderer(store).render(p,400);val c=result.getPixel(150,200);assertEquals(128f,Color.red(c).toFloat(),3f);assertEquals(128f,Color.blue(c).toFloat(),3f);result.recycle()}
    @Test fun softHideAndRestoreWorkInLayerCoordinates()=runBlocking {
        val p=add(add(project(),Color.RED),Color.BLUE);val hide=Stroke(listOf(Point(.5f,.5f)),.2f,1f)
        val masked=p.copy(frames=p.frames.mapIndexed{i,f->if(i==1)f.copy(masks=listOf(hide))else f});val renderer=Renderer(store)
        val hidden=renderer.render(masked,400);assertTrue(Color.red(hidden.getPixel(150,200))>240);assertTrue(Color.blue(hidden.getPixel(150,200))<15);hidden.recycle()
        val restored=masked.copy(frames=masked.frames.mapIndexed{i,f->if(i==1)f.copy(masks=f.masks+hide.copy(restore=true))else f})
        val result=renderer.render(restored,400);assertEquals(128f,Color.blue(result.getPixel(150,200)).toFloat(),5f);result.recycle()
    }
    @Test fun recipePersistsOriginalAndMasks(){val p=add(project(),Color.GREEN).let{it.copy(frames=it.frames.map{f->f.copy(masks=listOf(Stroke(listOf(Point(.4f,.6f)))) )})};store.save(p);assertEquals(p,store.read(p.id));assertTrue(store.photo(p,p.frames.first()).length()>0)}
    @Test fun cropDimensionsMatchRecipe()=runBlocking {val p=add(project(),Color.YELLOW).copy(crop=.1f);val result=Renderer(store).render(p,400);assertEquals(240,result.width);assertEquals(320,result.height);result.recycle()}
    @Test fun encryptedVaultNeverWritesPlainTokens(){val vault=CredentialStore(context);val before=vault.read();try{val value=Connections(accounts=listOf(Account("oaiapp_fixture",accessToken="synthetic-secret-for-encryption-test")),activeClientId="oaiapp_fixture");vault.write(value);assertEquals(value,vault.read());assertFalse(File(context.noBackupFilesDir,"chatgpt.vault").readBytes().toString(Charsets.ISO_8859_1).contains("synthetic-secret"))}finally{vault.write(before)}}
    @Test fun manyLayerRenderKeepsDimensionsAndCompletes()=runBlocking {var p=project();repeat(24){p=add(p,Color.rgb(it*9,120,180))};val start=System.currentTimeMillis();val output=Renderer(store).render(p,400);assertEquals(300,output.width);assertTrue(System.currentTimeMillis()-start<15000);output.recycle()}
    @Test fun highResolutionMaskedExportRespectsHeapBudget()=runBlocking {
        val original=Bitmap.createBitmap(4000,3000,Bitmap.Config.ARGB_8888);original.eraseColor(Color.rgb(100,150,200))
        val data=ByteArrayOutputStream().use{original.compress(Bitmap.CompressFormat.JPEG,95,it);it.toByteArray()};original.recycle()
        val first=store.addCapture(project(),data,null,"Synthetic 12MP test")
        val layers=(0..5).map{i->first.frames[0].copy(id="test-$i",opacity=1f/(i+1),masks=listOf(Stroke(listOf(Point(.4f,.5f),Point(.6f,.5f)),.04f,.7f)))}
        val p=first.copy(frames=layers);val output=Renderer(store).render(p,6000)
        assertTrue(output.width<=4000);assertTrue(output.width.toLong()*output.height*28<Runtime.getRuntime().maxMemory()*.43)
        assertTrue(Color.blue(output.getPixel(output.width/2,output.height/2))>150);output.recycle()
    }
}
