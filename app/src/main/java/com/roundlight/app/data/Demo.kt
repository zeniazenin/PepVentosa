package com.roundlight.app.data

import android.graphics.*
import com.roundlight.app.model.*
import kotlin.math.*
import kotlin.random.Random

/** Original synthetic study, explicitly labeled in the UI. No inference or borrowed artwork. */
object Demo {
    fun create(store: ProjectStore): Project {
        var project=store.create("Autumn study · illustrated demo")
        repeat(9) { frame ->
            val bitmap=Bitmap.createBitmap(900,1200,Bitmap.Config.ARGB_8888); val c=Canvas(bitmap); val p=Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader=LinearGradient(0f,0f,0f,1200f,intArrayOf(Color.rgb(154,180,170),Color.rgb(219,214,173),Color.rgb(109,129,86)),null,Shader.TileMode.CLAMP); c.drawRect(0f,0f,900f,1200f,p); p.shader=null
            val random=Random(120+frame)
            repeat(30) { val x=random.nextFloat()*1100-100; p.color=Color.argb(75,60,84,65); c.drawRect(x,random.nextFloat()*400,x+random.nextFloat()*12+2,1030f,p) }
            p.color=Color.rgb(93,80,57); p.strokeWidth=24f; p.strokeCap=Paint.Cap.ROUND
            c.drawLine(450f+frame,1020f,450f,380f,p)
            repeat(28) { val angle=random.nextFloat()*PI.toFloat(); val x=450+cos(angle)*random.nextFloat()*300; val y=420+sin(angle)*230
                p.strokeWidth=random.nextFloat()*7+2; c.drawLine(450f,800-random.nextFloat()*350,x,y,p) }
            repeat(2400) { val angle=random.nextFloat()*2*PI.toFloat(); val radius=sqrt(random.nextFloat()); val x=450+cos(angle)*350*radius+(frame-4)*3; val y=455+sin(angle)*310*radius
                p.color=when(random.nextInt(5)) {0->Color.argb(170,168,94,42);1->Color.argb(160,211,160,60);2->Color.argb(180,129,120,58);3->Color.argb(190,227,184,91);else->Color.argb(180,100,116,63)}
                c.drawOval(x-7,y-4,x+7,y+4,p)
            }
            val bytes=java.io.ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG,95,out); out.toByteArray() }; bitmap.recycle()
            project=store.addCapture(project,bytes,null,"Illustrated demo — synthetic frame ${frame+1}")
        }
        return project
    }
}
