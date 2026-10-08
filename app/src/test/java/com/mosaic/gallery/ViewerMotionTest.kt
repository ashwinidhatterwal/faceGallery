package com.mosaic.gallery

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w540dp-h960dp-xhdpi")
class ViewerMotionTest{
    private val photos=(0..7).map{PhotoRecord(it.toLong(),Uri.parse("content://motion/$it"),"Photo $it",0,0,0)}
    private fun layout(view:View,w:Int=1080,h:Int=1920){
        view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h)
    }
    private fun touch(view:View,action:Int,x:Float,y:Float){
        val time=SystemClock.uptimeMillis();val event=MotionEvent.obtain(time,time,action,x,y,0)
        view.dispatchTouchEvent(event);event.recycle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
    }
    private fun pager():PhotoPager{
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        return PhotoPager(activity).apply{activity.setContentView(this);submit(photos,photos[0].uri.toString());layout(this);currentImage()!!.show(Bitmap.createBitmap(40,30,Bitmap.Config.ARGB_8888))}
    }
    @Test fun upwardSwipeOpensPeopleWithoutChangingPhoto(){
        val pager=pager();var opened=0;var selected=0;pager.onSwipeUp={opened++};pager.onSelected={selected++}
        touch(pager,MotionEvent.ACTION_DOWN,540f,1100f);touch(pager,MotionEvent.ACTION_MOVE,540f,850f)
        touch(pager,MotionEvent.ACTION_UP,540f,800f)
        assertEquals(1,opened);assertEquals(0,selected);assertEquals(0f,pager.currentImage()!!.translationY,0.01f);pager.close()
    }
    @Test fun cancelledUpwardSwipeDoesNotOpenPeople(){
        val pager=pager();var opened=0;pager.onSwipeUp={opened++}
        touch(pager,MotionEvent.ACTION_DOWN,540f,1100f);touch(pager,MotionEvent.ACTION_MOVE,540f,850f)
        touch(pager,MotionEvent.ACTION_CANCEL,540f,850f);assertEquals(0,opened);pager.close()
    }
    @Test fun downwardDragFollowsFingerShrinksAndReleases(){
        val pager=pager();var closed=0;pager.onDismissReleased={closed++}
        touch(pager,MotionEvent.ACTION_DOWN,540f,700f)
        touch(pager,MotionEvent.ACTION_MOVE,550f,950f)
        val image=pager.currentImage()!!
        assertEquals(250f,image.translationY,0.01f);assertTrue(image.scaleX<1f)
        assertEquals(RecyclerView.SCROLL_STATE_IDLE,pager.scrollState)
        touch(pager,MotionEvent.ACTION_UP,550f,950f);assertEquals(1,closed);pager.close()
    }
    @Test fun cancelledDismissRestoresImage(){
        val pager=pager();var closed=false;pager.onDismissReleased={closed=true}
        touch(pager,MotionEvent.ACTION_DOWN,540f,700f);touch(pager,MotionEvent.ACTION_MOVE,540f,1000f)
        touch(pager,MotionEvent.ACTION_CANCEL,540f,1000f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(closed);assertEquals(0f,pager.currentImage()!!.translationY,0.01f);assertEquals(1f,pager.currentImage()!!.scaleX,0.01f);pager.close()
    }
    @Test fun zoomedPhotoPansInsteadOfDismissing(){
        val pager=pager();var closed=false;pager.onDismissReleased={closed=true}
        touch(pager,MotionEvent.ACTION_DOWN,540f,700f);touch(pager,MotionEvent.ACTION_UP,540f,700f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        touch(pager,MotionEvent.ACTION_DOWN,540f,700f);touch(pager,MotionEvent.ACTION_UP,540f,700f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(pager.currentImage()!!.canDismiss)
        touch(pager,MotionEvent.ACTION_DOWN,540f,700f);touch(pager,MotionEvent.ACTION_MOVE,540f,1000f);touch(pager,MotionEvent.ACTION_UP,540f,1000f)
        assertFalse(closed);assertEquals(0f,pager.currentImage()!!.translationY,0.01f);pager.close()
    }
    @Test fun railCentresBothEndpointsAndAvoidsProgrammaticFeedback(){
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val adapter=FilmstripAdapter(activity){};adapter.submit(photos)
        val rail=PhotoRail(activity);rail.adapter=adapter;activity.setContentView(rail);layout(rail,1080,100)
        var callbacks=0;rail.onCentered={callbacks++}
        for(index in listOf(0,7)){
            rail.focus(index,false);layout(rail,1080,100);shadowOf(Looper.getMainLooper()).idle()
            val image=rail.findViewHolderForAdapterPosition(index)!!.itemView
            assertEquals(540f,(image.left+image.right)/2f,1f)
        }
        assertEquals(0,callbacks);adapter.close()
    }
    @Test fun browsingRailChangesCentredPhoto(){
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val adapter=FilmstripAdapter(activity){};adapter.submit(photos)
        val rail=PhotoRail(activity);rail.adapter=adapter;activity.setContentView(rail);layout(rail,1080,100)
        rail.focus(0,false);layout(rail,1080,100);var selected=0;rail.onCentered={selected=it}
        touch(rail,MotionEvent.ACTION_DOWN,700f,50f)
        for(x in listOf(600f,500f,400f,300f))touch(rail,MotionEvent.ACTION_MOVE,x,50f)
        assertTrue("Rail must open the photo under its centre during a drag",selected>0)
        touch(rail,MotionEvent.ACTION_UP,300f,50f);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        val view=rail.findViewHolderForAdapterPosition(selected)!!.itemView
        assertEquals(540f,(view.left+view.right)/2f,1f);adapter.close()
    }
}
