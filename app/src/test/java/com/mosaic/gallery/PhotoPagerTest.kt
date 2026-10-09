package com.mosaic.gallery

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Touch/layout regression tests, not a device frame-rate benchmark. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w540dp-h960dp-xhdpi")
class PhotoPagerTest {
    private lateinit var pager: PhotoPager
    private var selected=0
    private var downTime=0L
    private val photos=(0..4).map{PhotoRecord(it.toLong(),Uri.parse("content://pager-test/$it"),"Photo $it",0,0,0)}
    @Before fun setUp(){
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        pager=PhotoPager(activity);activity.setContentView(pager)
        pager.onSelected={selected=photos.indexOf(it)}
        // Keep IO disabled: these tests isolate real RecyclerView touch and snap behavior.
        pager.submit(photos,photos[0].uri.toString())
        layout();shadowOf(Looper.getMainLooper()).idle()
    }
    private fun layout(){
        pager.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY))
        pager.layout(0,0,1080,1920)
    }
    private fun touch(action:Int,x:Float,advance:Long=20){
        if(advance>0)shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(advance))
        val time=SystemClock.uptimeMillis();if(action==MotionEvent.ACTION_DOWN)downTime=time
        val e=MotionEvent.obtain(downTime,time,action,x,960f,0)
        pager.dispatchTouchEvent(e);e.recycle()
    }
    private fun settle(){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))}
    @After fun tearDown(){pager.close();shadowOf(Looper.getMainLooper()).idle()}
    @Test fun dragFollowsFingerAndSnapsNext(){
        val image=pager.currentImage()!!;image.show(Bitmap.createBitmap(40,30,Bitmap.Config.ARGB_8888));val drawable=image.drawable
        touch(MotionEvent.ACTION_DOWN,900f)
        for(x in listOf(820f,680f,540f,400f,260f,180f))touch(MotionEvent.ACTION_MOVE,x)
        assertEquals(RecyclerView.SCROLL_STATE_DRAGGING,pager.scrollState)
        assertTrue("Page must move before release",(image.parent as android.view.View).left< -400)
        assertSame("Moving page must keep its drawable",drawable,image.drawable)
        touch(MotionEvent.ACTION_UP,180f);settle()
        assertEquals(1,selected);assertEquals(0,pager.currentImage()!!.left)
    }
    @Test fun reversingDragReturnsToOriginal(){
        touch(MotionEvent.ACTION_DOWN,900f)
        for(x in listOf(700f,500f,700f,850f,895f))touch(MotionEvent.ACTION_MOVE,x,80)
        touch(MotionEvent.ACTION_UP,895f,180);settle();assertEquals(0,selected)
    }
    @Test fun settlingCanBeInterruptedByANewDrag(){
        // Use a controlled native settling scroll: Robolectric collapses fling timing.
        // This is the same RecyclerView ViewFlinger used by PagerSnapHelper.
        pager.smoothScrollBy(900,0,null,500)
        assertEquals(RecyclerView.SCROLL_STATE_SETTLING,pager.scrollState)
        touch(MotionEvent.ACTION_DOWN,400f,0);touch(MotionEvent.ACTION_MOVE,550f,0)
        assertEquals("A new finger must take control immediately",RecyclerView.SCROLL_STATE_DRAGGING,pager.scrollState)
        touch(MotionEvent.ACTION_UP,550f);settle()
    }
    @Test fun zoomUpgradeKeepsTheVisiblePosition(){
        val image=pager.currentImage()!!;image.show(Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888))
        // Two taps use the real double-tap detector and animated matrix.
        touch(MotionEvent.ACTION_DOWN,540f);touch(MotionEvent.ACTION_UP,540f,20)
        touch(MotionEvent.ACTION_DOWN,540f,60);touch(MotionEvent.ACTION_UP,540f,20)
        settle()
        val before=FloatArray(9);image.imageMatrix.getValues(before)
        assertTrue("Double tap must zoom",before[0]>1080f/400f*1.5f)
        image.upgrade(Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888))
        val after=FloatArray(9);image.imageMatrix.getValues(after)
        assertEquals(before[2],after[2],0.1f);assertEquals(before[5],after[5],0.1f)
        assertEquals(before[0]/2,after[0],0.001f)
    }
    @Test fun cachedNeighbourDoesNotResetCurrentZoom(){
        val image=pager.currentImage()!!;image.show(Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888))
        touch(MotionEvent.ACTION_DOWN,540f);touch(MotionEvent.ACTION_UP,540f,20)
        touch(MotionEvent.ACTION_DOWN,540f,60);touch(MotionEvent.ACTION_UP,540f,20);settle()
        val before=FloatArray(9);image.imageMatrix.getValues(before);assertTrue(before[0]>1080f/400f*1.5f)
        val cache=PhotoPager::class.java.getDeclaredField("cache").apply{isAccessible=true}.get(pager) as android.util.LruCache<String,Bitmap>
        cache.put(photos[1].uri.toString(),Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888))
        val neighbour=pager.findViewHolderForAdapterPosition(1)!!
        PhotoPager::class.java.declaredMethods.first{it.name=="load"}.apply{isAccessible=true}.invoke(pager,neighbour,photos[1])
        shadowOf(Looper.getMainLooper()).idle()
        val after=FloatArray(9);image.imageMatrix.getValues(after);assertArrayEquals(before,after,.01f)
    }

}
