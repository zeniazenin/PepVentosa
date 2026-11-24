package com.roundlight.app

import com.roundlight.app.auth.*
import com.roundlight.app.data.*
import com.roundlight.app.imaging.*
import com.roundlight.app.model.*
import com.roundlight.app.network.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import kotlin.math.*

class CoreTests {
    @Test fun mixedAspectPhotosMapMasksIntoTheirLetterbox(){assertEquals(Point(0f,.25f),ImageGeometry.sourceToCanvas(Point(0f,0f),1.5f,.75f));assertEquals(Point(1f,.75f),ImageGeometry.sourceToCanvas(Point(1f,1f),1.5f,.75f))}
    @Test fun equalAlphaProgressionProducesArithmeticMean(){
        val values=listOf(.13f,.92f,.42f,.71f,.20f);var combined=0f
        values.forEachIndexed{i,v->combined=ColorMath.composite(combined,v,1f/(i+1))}
        assertEquals(values.average().toFloat(),combined,0.00001f)
    }
    @Test fun transformsRoundTripForPortraitAndLandscape(){for(aspect in listOf(.75f,1f,1.5f)){
        val t=Transform(.08f,-.04f,1.17f,12f);val p=Point(.22f,.71f);val roundTrip=t.inverse(t.forward(p,aspect),aspect)
        assertEquals(p.x,roundTrip.x,.00001f);assertEquals(p.y,roundTrip.y,.00001f)
    }}
    @Test fun identityGradeKeepsColor(){val c=ColorMath.grade(.2f,.4f,.8f,Grade(0f,1f,1f,0f));assertEquals(0xff3366cc.toInt(),c)}
    @Test fun exportBudgetCapsMemoryWithoutUpscaling(){val(w,h)=Renderer.dimensions(12000,9000,6000,256L*1024*1024);assertTrue(w*h*28L<256L*1024*1024*.43);assertEquals(4.0/3,w.toDouble()/h,.002)
        assertEquals(400 to 300,Renderer.dimensions(400,300,6000,256L*1024*1024))}
    @Test fun pathTraversalRejected(){assertThrows(IllegalArgumentException::class.java){Project(frames=listOf(Frame(file="../secret.jpg"))).validated()}}
    @Test fun nonFiniteOrUnboundedEditsRejected(){assertThrows(IllegalArgumentException::class.java){Project(frames=listOf(Frame(file="photo.jpg",opacity=Float.NaN))).validated()};assertFalse(Transform(scale=8f).valid());assertFalse(Stroke(listOf(Point(Float.NaN,0f))).valid())}
    @Test fun flatImagesNeverInventRegistration(){val r=Alignment.estimate(FloatArray(96*96),FloatArray(96*96),96,96,Point(.5f,.5f));assertEquals(Transform(),r.transform);assertEquals(0f,r.confidence,0f)}
    @Test fun registrationRecoversTranslation(){
        val n=96
        fun shape(x:Int,y:Int):Float {val a=exp(-((x-45.0).pow(2)/22+(y-55.0).pow(2)/90));val b=.8*exp(-((x-57.0).pow(2)/15+(y-33.0).pow(2)/20));val c=.6*exp(-((x-30.0).pow(2)/40+(y-43.0).pow(2)/13));return(a+b+c).toFloat()}
        val a=FloatArray(n*n){shape(it%n,it/n)};val b=FloatArray(n*n){shape(it%n-7,it/n+4)}
        val r=Alignment.estimate(a,b,n,n,Point(.5f,.5f));assertTrue("confidence ${r.confidence}",r.confidence>.90f)
        assertEquals(-7f/(n-1),r.transform.dx,.015f);assertEquals(4f/(n-1),r.transform.dy,.015f)
    }
    @Test fun pkceMatchesRfc7636Vector(){assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",OAuthRules.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))}
    @Test fun callbackRequiresStateAndIssuedClient(){assertEquals("code" to "oaiapp_example",OAuthRules.callback("state=state&code=code&client_id=oaiapp_example","state",null))
        assertThrows(IllegalArgumentException::class.java){OAuthRules.callback("state=wrong&code=code&client_id=oaiapp_example","state",null)}
        assertThrows(IllegalArgumentException::class.java){OAuthRules.callback("state=state&code=code","state",null)}
    }
    @Test fun callbackRejectsDuplicatesAndCrossAccountRegistration(){
        assertThrows(IllegalArgumentException::class.java){OAuthRules.callback("state=x&state=x&code=c&client_id=oaiapp_test","x",null)}
        assertThrows(IllegalArgumentException::class.java){OAuthRules.callback("state=x&code=c&client_id=oaiapp_other","x","oaiapp_test")}
    }
    @Test fun foregroundGateWaitsForBrowserReturn()=runBlocking {val gate=ForegroundGate();var finished=false;val job=launch{gate.awaitResumed();finished=true};yield();assertFalse(finished);gate.setResumed(true);job.join();assertTrue(finished)}
    @Test fun aiPlanRejectsMissingFramesAndLargeTransforms(){val good=LayerAdvice("frame",.5f,0f,0f,1f,0f,emptyList());val a=ArtAdvice("test",listOf(good),0f,1f,1f,0f)
        a.validated(setOf("frame"));assertThrows(IllegalArgumentException::class.java){a.validated(setOf("other"))};assertThrows(IllegalArgumentException::class.java){a.copy(layers=listOf(good.copy(dx=1f))).validated(setOf("frame"))}}
    @Test fun aiPlanRejectsInvalidPolygons(){val layer=LayerAdvice("frame",.5f,0f,0f,1f,0f,listOf(MaskRegion(listOf(Point(0f,0f),Point(2f,0f),Point(1f,1f)))))
        assertThrows(IllegalArgumentException::class.java){ArtAdvice("test",listOf(layer),0f,1f,1f,0f).validated(setOf("frame"))}}
    @Test fun inferenceUsesPlanCompatibleContract(){val body=ArtContract.body("chosen-model",JsonArray(emptyList()),"Natural");assertEquals(JsonPrimitive(false),body["store"]);assertEquals(JsonPrimitive(true),body["stream"])
        for(forbidden in listOf("max_output_tokens","temperature","previous_response_id","background","metadata"))assertFalse(body.containsKey(forbidden))
        assertEquals("json_schema",body["text"]!!.jsonObject["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }
    private fun stream(vararg events:String)=events.joinToString("\n\n"){"data: $it"}.plus("\n\n").reader().buffered()
    @Test fun completedStreamExtractsFinalOutput(){val s=stream("""{"type":"response.completed","response":{"status":"completed","output":[{"content":[{"type":"output_text","text":"{\"ok\":true}"}]}]}}""");assertEquals("{\"ok\":true}",ResponseStream.read(s))}
    @Test fun partialStreamNeverCountsAsSuccess(){assertThrows(IOException::class.java){ResponseStream.read(stream("""{"type":"response.output_text.delta","delta":"partial"}"""))}}
    @Test fun lateUsageFailureDoesNotApplyPartialPlan(){val s=stream("""{"type":"response.output_text.delta","delta":"partial"}""","""{"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}}""");val e=assertThrows(ServiceFailure::class.java){ResponseStream.read(s)};assertTrue(e.usageLimit)}
    @Test fun refusalDoesNotBecomeAnEdit(){assertThrows(IOException::class.java){ResponseStream.read(stream("""{"type":"response.refusal.done"}"""))}}
    @Test fun dnsRetriesExactlyOnceButTimeoutDoesNot()=runBlocking {var calls=0;assertEquals(2,retryDnsOnce(pause={}){calls++;if(calls==1)throw UnknownHostException();calls});calls=0
        try{retryDnsOnce(pause={}){calls++;throw java.net.SocketTimeoutException()}}catch(_:java.net.SocketTimeoutException){};assertEquals(1,calls)}
    @Test fun accountDeserializesLegacyVaultAndPreservesSelectedModel(){
        val legacy="""{"clientId":"oaiapp_123456","email":"a@b.com","accessToken":"tok","scopes":["chatgpt.tokens.use.direct"]}"""
        val decoded=appJson.decodeFromString<Account>(legacy)
        assertTrue(decoded.canUsePlan)
        assertEquals("",decoded.selectedModel)
        val withModel=decoded.copy(selectedModel="gpt-5",selectedModelLabel="GPT-5")
        val cleared=withModel.withoutTokens()
        assertFalse(cleared.connected)
        assertEquals("gpt-5",cleared.selectedModel)
        assertEquals("GPT-5",cleared.selectedModelLabel)
    }
    @Test fun tokenRefreshPreservesExistingFieldsWhenNullOrBlank(){
        val existing=Account(clientId="oaiapp_123456",accessToken="old",refreshToken="ref-1",idToken="id-1",scopes=setOf("openid","chatgpt.tokens.use.direct"),selectedModel="gpt-5")
        val refreshJson=Json.parseToJsonElement("""{"token_type":"Bearer","access_token":"new-tok","expires_in":3600,"refresh_token":null,"id_token":null,"scope":null}""").jsonObject
        val updated=OAuthRules.applyTokens(refreshJson,existing,nowMs=1000L)
        assertEquals("new-tok",updated.accessToken)
        assertEquals("ref-1",updated.refreshToken)
        assertEquals("id-1",updated.idToken)
        assertTrue(updated.canUsePlan)
        assertEquals("gpt-5",updated.selectedModel)
        val blankScope=Json.parseToJsonElement("""{"token_type":"Bearer","access_token":"new-tok-2","expires_in":3600,"scope":""}""").jsonObject
        assertTrue(OAuthRules.applyTokens(blankScope,updated,nowMs=2000L).canUsePlan)
    }
    @Test fun serviceFailureFallsBackToErrorTypeWhenCodeMissing(){
        val body=Json.parseToJsonElement("""{"error":{"code":null,"type":"server_error"}}""").jsonObject
        val failure=ServiceFailure.from(500,body,"req-1")
        assertEquals("server_error",failure.code)
        assertEquals("req-1",failure.requestId)
    }
    @Test fun responseStreamRejectsMalformedJsonAsIoException(){
        assertThrows(IOException::class.java){ResponseStream.read(stream("not-json"))}
    }
    @Test fun gradeRowMatchesPerPixelGrade(){
        val grade=Grade(exposure=0.65f,contrast=1.22f,saturation=1.35f,warmth=-0.4f)
        val pixels=intArrayOf(0xff000000.toInt(),0xffffffff.toInt(),0xff3366cc.toInt(),0xffc87a24.toInt(),0xff1a9e52.toInt())
        val expected=IntArray(pixels.size){i->val c=pixels[i];ColorMath.grade((c shr 16 and 255)/255f,(c shr 8 and 255)/255f,(c and 255)/255f,grade)}
        ColorMath.gradeRow(pixels,grade)
        assertArrayEquals(expected,pixels)
    }
}
