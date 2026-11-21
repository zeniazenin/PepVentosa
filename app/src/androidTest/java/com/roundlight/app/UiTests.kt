package com.roundlight.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.geometry.Offset
import org.junit.Rule
import org.junit.Test

class UiTests {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun shot(name:String){val bitmap=compose.onRoot().captureToImage().asAndroidBitmap();val folder=InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("qa")!!;File(folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
    @Test fun newShootAndAccountNavigation(){
        shot("home")
        compose.onNodeWithText("Start a new shoot").performClick()
        compose.onNodeWithText("Shoot name").performTextInput("Emulator field test")
        compose.onNodeWithText("Create shoot").performClick()
        compose.waitUntil(10000){compose.onAllNodesWithText("Find your anchor").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Find your anchor").assertIsDisplayed()
        compose.onNodeWithContentDescription("Account and diagnostics").performClick()
        compose.onNodeWithText("Continue with ChatGPT").assertIsDisplayed()
        shot("account")
        compose.onNodeWithText("Export diagnostic log").performScrollTo().assertIsDisplayed()
    }
    @Test fun illustratedDemoOpensRealLayerEditor(){
        compose.onNodeWithText("Explore an illustrated demo").performScrollTo().performClick()
        compose.waitUntil(30000){compose.onAllNodesWithText("The finishing room").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("The finishing room").assertIsDisplayed()
        compose.waitUntil(20000){ViewModelProvider(compose.activity)[StudioModel::class.java].preview!=null}
        shot("editor")
        compose.onNodeWithText("Opacity").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Hide",useUnmergedTree=true).performScrollTo().performClick()
        compose.onNodeWithText("Brush size").performScrollTo().assertIsDisplayed()
        val vm=ViewModelProvider(compose.activity)[StudioModel::class.java]
        compose.onNodeWithContentDescription("Composition preview").performScrollTo().performTouchInput{swipe(center-Offset(80f,40f),center+Offset(80f,40f),400)}
        compose.waitUntil(10000){vm.project!!.frames[0].masks.isNotEmpty()}
        compose.onNodeWithContentDescription("Composition preview").performTouchInput{click(center)}
        compose.waitUntil(10000){vm.project!!.frames[0].masks.size==2}
    }
    @Test fun cameraCapturesAndPersistsAnActualEmulatorFrame(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission("com.roundlight.app",android.Manifest.permission.CAMERA)
        val vm=ViewModelProvider(compose.activity)[StudioModel::class.java]
        compose.runOnUiThread{vm.newShoot("Emulator camera verification")}
        compose.waitUntil(10000){vm.project!=null && vm.busy==null}
        compose.runOnUiThread{vm.enumerate();vm.screen="capture"}
        compose.waitUntil(20000){vm.cameraStatus.startsWith("Ready")}
        // Let the emulated camera deliver metering results; Lock reports if it is not ready.
        compose.waitUntil(15000){vm.message==null && vm.cameraStatus.startsWith("Ready")}
        compose.onNodeWithText("Lock",useUnmergedTree=true).performClick()
        compose.waitUntil(10000){vm.cameraSettings.locked}
        shot("capture")
        compose.onNodeWithContentDescription("Take photo").performClick()
        compose.waitUntil(20000){vm.project!!.frames.isNotEmpty() || vm.message!=null}
        org.junit.Assert.assertNull(vm.message)
        org.junit.Assert.assertEquals(1,vm.project!!.frames.size)
        org.junit.Assert.assertEquals(1,vm.store.read(vm.project!!.id).frames.size)
        compose.onNodeWithText("Review").performClick()
        compose.waitUntil(10000){vm.preview!=null}
        shot("captured-frame")
    }
}
