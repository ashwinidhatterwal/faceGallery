package com.mosaic.gallery

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController
import java.time.Duration
import kotlin.math.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="xhdpi")
class FingerTransformTest {
    private lateinit var view:ZoomPhotoView
    private lateinit var controller:ActivityController<Activity>
    private var tick=0L;private var taps=0
    private data class Finger(val id:Int,val x:Float,val y:Float)
    @Before fun setup(){
        controller=Robolectric.buildActivity(Activity::class.java).setup()
        view=ZoomPhotoView(controller.get());view.onTap={taps++};controller.get().setContentView(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY));view.layout(0,0,800,600)
        view.show(Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888))
    }
    @After fun close(){controller.pause().stop().destroy()}
    private fun send(action:Int,vararg fingers:Finger){
        tick+=32;val now=SystemClock.uptimeMillis()
        val p=fingers.map{MotionEvent.PointerProperties().apply{id=it.id;toolType=MotionEvent.TOOL_TYPE_FINGER}}.toTypedArray()
        val c=fingers.map{MotionEvent.PointerCoords().apply{x=it.x;y=it.y;pressure=1f;size=1f}}.toTypedArray()
        val e=MotionEvent.obtain(now,now+tick,action,p.size,p,c,0,0,1f,1f,0,0,0,0);view.dispatchTouchEvent(e);e.recycle()
    }
    private fun pair(cx:Float=300f,cy:Float=220f,r:Float=100f,angle:Float=0f):Array<Finger>{
        val a=angle*PI/180;val dx=(r*cos(a)).toFloat();val dy=(r*sin(a)).toFloat()
        return arrayOf(Finger(7,cx-dx,cy-dy),Finger(11,cx+dx,cy+dy))
    }
    private fun begin(points:Array<Finger> =pair()){send(MotionEvent.ACTION_DOWN,points[0]);send(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),*points)}
    private fun source(x:Float,y:Float):FloatArray{val m=Matrix();assertTrue(view.imageMatrix.invert(m));return floatArrayOf(x,y).also{m.mapPoints(it)}}
    private fun screen(x:Float,y:Float)=floatArrayOf(x,y).also{view.imageMatrix.mapPoints(it)}
    @Test fun movingPinchKeepsImagePointUnderMovingMidpoint(){
        val anchor=source(300f,220f);begin()
        send(MotionEvent.ACTION_MOVE,*pair(350f,260f,180f));assertArrayEquals(anchor,source(350f,260f),.01f)
        send(MotionEvent.ACTION_MOVE,*pair(420f,300f,220f));assertArrayEquals(anchor,source(420f,300f),.01f);assertEquals(0f,view.rotationDegrees,0f)
    }
    @Test fun twoFingerPanAtFitFollowsWithoutClampingUntilRelease(){
        val anchor=source(300f,220f);begin();send(MotionEvent.ACTION_MOVE,*pair(420f,320f))
        assertArrayEquals(anchor,source(420f,320f),.01f)
        val p=pair(420f,320f);send(MotionEvent.ACTION_POINTER_UP or (1 shl 8),*p);send(MotionEvent.ACTION_UP,p[0])
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));assertArrayEquals(floatArrayOf(view.width/2f,view.height/2f),screen(200f,150f),.01f)
    }
    @Test fun deliberateTwistRotatesAroundFingersAndStillScales(){
        val anchor=source(300f,220f);begin();send(MotionEvent.ACTION_MOVE,*pair(angle=10f));assertEquals(0f,view.rotationDegrees,.01f)
        send(MotionEvent.ACTION_MOVE,*pair(340f,250f,100f,25f));assertEquals(13f,view.rotationDegrees,.02f);assertArrayEquals(anchor,source(340f,250f),.01f)
        send(MotionEvent.ACTION_MOVE,*pair(380f,280f,160f,50f));assertEquals(38f,view.rotationDegrees,.02f);assertArrayEquals(anchor,source(380f,280f),.01f)
    }
    @Test fun strongPinchWithAngularDriftDoesNotBecomeRotation(){
        begin();send(MotionEvent.ACTION_MOVE,*pair(r=155f,angle=20f));send(MotionEvent.ACTION_MOVE,*pair(r=220f,angle=25f))
        assertEquals(0f,view.rotationDegrees,0f)
    }
    @Test fun deliberateTwistCanFollowAnEarlierPinch(){
        begin();send(MotionEvent.ACTION_MOVE,*pair(r=160f,angle=4f));send(MotionEvent.ACTION_MOVE,*pair(r=162f,angle=12f));assertEquals(0f,view.rotationDegrees,0f)
        send(MotionEvent.ACTION_MOVE,*pair(r=162f,angle=30f));assertEquals(11f,view.rotationDegrees,.03f)
    }
    @Test fun pointerReorderingAndReplacementDoNotJump(){
        begin();send(MotionEvent.ACTION_MOVE,*pair(r=160f));val before=screen(200f,150f)
        send(MotionEvent.ACTION_MOVE,*pair(r=160f).reversedArray());assertArrayEquals(before,screen(200f,150f),.01f)
        val p=pair(r=160f);val third=Finger(21,450f,400f)
        send(MotionEvent.ACTION_POINTER_DOWN or (2 shl 8),p[0],p[1],third)
        send(MotionEvent.ACTION_POINTER_UP,p[0],p[1],third)
        send(MotionEvent.ACTION_MOVE,p[1],third);assertArrayEquals(before,screen(200f,150f),.01f)
    }
    @Test fun angleCrossing180IsSmallContinuousTurn(){
        begin(pair(angle=175f));send(MotionEvent.ACTION_MOVE,*pair(angle=-179f));assertEquals(0f,view.rotationDegrees,.01f)
        send(MotionEvent.ACTION_MOVE,*pair(angle=-165f));assertEquals(8f,view.rotationDegrees,.03f)
    }
    @Test fun remainingFingerPansAndNeverTogglesChrome(){
        begin();val p=pair(r=160f);send(MotionEvent.ACTION_MOVE,*p);send(MotionEvent.ACTION_POINTER_UP or (1 shl 8),*p)
        val anchor=source(p[0].x,p[0].y);val next=p[0].copy(x=p[0].x+30,y=p[0].y+20)
        send(MotionEvent.ACTION_MOVE,next);assertArrayEquals(anchor,source(next.x,next.y),.01f);send(MotionEvent.ACTION_UP,next)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));assertEquals(0,taps)
    }
    @Test fun previewUpgradePreservesTwistedPinchPosition(){
        begin();send(MotionEvent.ACTION_MOVE,*pair(350f,240f,120f,30f));send(MotionEvent.ACTION_MOVE,*pair(360f,260f,180f,40f))
        val before=screen(100f,80f);view.upgrade(Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888));assertArrayEquals(before,screen(200f,160f),.01f)
    }
    @Test fun nearCoincidentPointersCannotExplodeZoom(){
        begin(pair(r=10f));send(MotionEvent.ACTION_MOVE,*pair(r=200f));val m=FloatArray(9);view.imageMatrix.getValues(m);assertEquals(2f,m[0],.01f)
        send(MotionEvent.ACTION_MOVE,*pair(r=220f));view.imageMatrix.getValues(m);assertEquals(2.2f,m[0],.01f)
    }
    @Test fun rotatedImageCanZoomOutUntilItFits(){
        begin();send(MotionEvent.ACTION_MOVE,*pair(angle=90f));assertFalse(view.canDismiss)
        send(MotionEvent.ACTION_MOVE,*pair(r=65f,angle=90f))
        val corners=floatArrayOf(0f,0f,400f,0f,400f,300f,0f,300f);view.imageMatrix.mapPoints(corners)
        val xs=listOf(corners[0],corners[2],corners[4],corners[6]);val ys=listOf(corners[1],corners[3],corners[5],corners[7])
        assertTrue(xs.max()-xs.min()<=view.width+.1f);assertTrue(ys.max()-ys.min()<=view.height+.1f)
    }

}
