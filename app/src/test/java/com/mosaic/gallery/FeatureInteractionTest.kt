package com.mosaic.gallery

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="xhdpi")
class FeatureInteractionTest{
    private fun layout(view:View,w:Int=1080,h:Int=1920){view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h)}
    private fun rotate(view:View, degrees:Float=90f){
        val time=SystemClock.uptimeMillis()
        fun event(action:Int,points:List<Pair<Float,Float>>){
            val properties=points.indices.map{MotionEvent.PointerProperties().apply{id=it;toolType=MotionEvent.TOOL_TYPE_FINGER}}.toTypedArray()
            val coords=points.map{MotionEvent.PointerCoords().apply{x=it.first;y=it.second;pressure=1f;size=1f}}.toTypedArray()
            val event=MotionEvent.obtain(time,time+20,action,points.size,properties,coords,0,0,1f,1f,0,0,0,0);view.dispatchTouchEvent(event);event.recycle()
        }
        event(MotionEvent.ACTION_DOWN,listOf(400f to 600f))
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),listOf(400f to 600f,600f to 600f))
        val radians=Math.toRadians(degrees.toDouble());val dx=(100*kotlin.math.cos(radians)).toFloat();val dy=(100*kotlin.math.sin(radians)).toFloat()
        event(MotionEvent.ACTION_MOVE,listOf(500f-dx to 600f-dy,500f+dx to 600f+dy))
        event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),listOf(500f to 500f,500f to 700f))
        event(MotionEvent.ACTION_UP,listOf(500f to 500f))
    }
    @Test fun railFocusBeforeLayoutCentresRandomOpenedPhoto(){
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val photos=(0..15).map{PhotoRecord(it.toLong(),Uri.parse("content://focus/$it"),"$it",0,0,0)}
        val adapter=FilmstripAdapter(activity){};adapter.submit(photos)
        val rail=PhotoRail(activity);rail.adapter=adapter;activity.setContentView(rail)
        rail.focus(11,false);layout(rail,1080,100);layout(rail,1080,100)
        val item=rail.findViewHolderForAdapterPosition(11)!!.itemView
        assertEquals(540f,(item.left+item.right)/2f,1f);adapter.close()
    }
    @Test fun viewerRecognisesDeliberateTwistAndStillOffersQuarterTurn(){
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val view=ZoomPhotoView(activity);activity.setContentView(view);layout(view)
        view.show(Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888));rotate(view,60f)
        assertEquals(48f,view.rotationDegrees,0.1f);view.rotateQuarterTurn();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertEquals(180f,view.rotationDegrees,0.01f)
        val values=FloatArray(9);view.imageMatrix.getValues(values);assertTrue(values[0]<-.5f)
    }
    @Test fun editorTwistCommitsRotationWithoutCropping(){
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val view=CropPhotoView(activity);activity.setContentView(view);layout(view)
        view.bitmap=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(Bitmap.createBitmap(1080,1920,Bitmap.Config.ARGB_8888)))
        var angle=0f;view.onRotationFinished={angle=it};rotate(view,60f)
        assertEquals(0f,angle,0.01f);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertEquals(90f,angle,0.01f);assertEquals(CropBounds(),view.crop)
    }
    @Test fun foldersIncludingAllStayInAlbumsAndReturnToAlbumGrid(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().visible();val activity=controller.get()
        fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}
        fun call(name:String,vararg args:Any){val method=MainActivity::class.java.declaredMethods.first{it.name==name};method.isAccessible=true;method.invoke(activity,*args)}
        field("allPhotos").set(activity,(0..3).map{PhotoRecord(it.toLong(),Uri.parse("content://folder/$it"),"$it",0,0,0,"Camera")})
        val root=field("galleryRoot").get(activity) as View;root.visibility=View.VISIBLE;val grid=field("grid").get(activity) as RecyclerView
        val adapter=field("adapter").get(activity) as PhotoGridAdapter
        call("switchPage","Albums");layout(root)
        for(chosen in listOf("Camera","")){
            val cover=(field("adapter").get(activity) as PhotoGridAdapter).albumView(chosen)!!
            call("openAlbum",chosen,cover);layout(root)
            val folder=field("grid").get(activity) as RecyclerView
            assertEquals(folder.scaleX,folder.scaleY,0.001f);assertNotNull(folder.clipBounds)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertEquals("Albums",field("page").get(activity));assertTrue(field("folderOpen").getBoolean(activity));assertNotSame(grid,field("grid").get(activity));assertEquals(4,((field("grid").get(activity) as RecyclerView).layoutManager as GridLayoutManager).spanCount)
            call("handleBack");layout(root);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));layout(root)
            assertEquals("Albums",field("page").get(activity));assertFalse(field("folderOpen").getBoolean(activity));assertSame(grid,field("grid").get(activity));assertEquals(3,(grid.layoutManager as GridLayoutManager).spanCount)
        };controller.destroy()
    }
    @Test fun systemBarsAndBottomActionsShareCanvas(){
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get();val root=android.widget.LinearLayout(activity)
        Ui.insets(activity,root)
        assertFalse(activity.window.isNavigationBarContrastEnforced);assertFalse(activity.window.isStatusBarContrastEnforced)
        val bar=GalleryStyle.bar(activity)
        assertEquals(GalleryStyle.canvas(activity),(bar.background as android.graphics.drawable.ColorDrawable).color)
    }
}
