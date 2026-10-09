package com.mosaic.gallery

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w360dp-h780dp-xxhdpi")
class PlaybackAndFastScrollTest {
    private fun layout(v:View,w:Int=1080,h:Int=1920){v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h)}
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun send(v:View,action:Int,x:Float,y:Float){MotionEvent.obtain(0,20,action,x,y,0).also{v.dispatchTouchEvent(it);it.recycle()}}
    @Test fun draggingTimelineDoesNotPageToAdjacentPhoto(){
        val c=Robolectric.buildActivity(Activity::class.java).setup();val pager=PhotoPager(c.get());c.get().setContentView(pager)
        val video=PhotoRecord(1,Uri.parse("content://test/video/1"),"a.mp4",0,400,300,mimeType="video/mp4",durationMillis=120000)
        val photo=video.copy(id=2,uri=Uri.parse("content://test/photos/2"),mimeType="image/jpeg")
        pager.submit(listOf(video,photo),video.uri.toString());layout(pager);Shadows.shadowOf(Looper.getMainLooper()).idle()
        val player=all(pager).filterIsInstance<GalleryVideoView>().first();player.visibility=View.VISIBLE;layout(pager)
        val seek=all(player).filterIsInstance<VideoSeekBar>().single();seek.isEnabled=true
        val point=IntArray(2);seek.getLocationInWindow(point);val origin=IntArray(2);pager.getLocationInWindow(origin)
        val y=(point[1]-origin[1])+seek.height/2f
        send(pager,MotionEvent.ACTION_DOWN,850f,y)
        for(x in listOf(720f,580f,400f,200f))send(pager,MotionEvent.ACTION_MOVE,x,y)
        send(pager,MotionEvent.ACTION_UP,200f,y)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertEquals(RecyclerView.SCROLL_STATE_IDLE,pager.scrollState)
        assertEquals(0,pager.findViewHolderForAdapterPosition(0)!!.itemView.left)
        assertTrue(seek.progress in 1..4999)
        pager.close();c.pause().stop().destroy()
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) fun transparentControlsUseCombinedTimeLabel(){
        val c=Robolectric.buildActivity(Activity::class.java).setup();val view=GalleryVideoView(c.get())
        val video=PhotoRecord(1,Uri.parse("content://test/video/1"),"a.mp4",0,400,300,mimeType="video/mp4",durationMillis=120000)
        view.bind(video,GalleryVideoView.State(video.uri.toString(),5000,false));view.visibility=View.VISIBLE;layout(view)
        assertTrue(all(view).filterIsInstance<TextView>().any{it.text.toString()=="0:05/2:00"})
        val controls=GalleryVideoView::class.java.getDeclaredField("controls").apply{isAccessible=true}.get(view) as View
        assertNull(controls.background)
        val bitmap=Bitmap.createBitmap(controls.width,controls.height,Bitmap.Config.ARGB_8888);controls.draw(Canvas(bitmap))
        val folder=java.io.File("build/ui-review").apply{mkdirs()};java.io.File(folder,"transparent-video-controls.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle();c.pause().stop().destroy()
    }
    @Test fun fastScrollReachesEndAndReusesGridAdapter(){
        val c=Robolectric.buildActivity(Activity::class.java).setup();val grid=RecyclerView(c.get());val adapter=PhotoGridAdapter(c.get(),{},{})
        val layout=GridLayoutManager(c.get(),4);layout.spanSizeLookup=object:GridLayoutManager.SpanSizeLookup(){override fun getSpanSize(p:Int)=if(adapter.isHeader(p))4 else 1};grid.layoutManager=layout;grid.adapter=adapter
        val photos=(0..599).map{PhotoRecord(it.toLong(),Uri.parse("content://test/$it"),"$it.jpg",it*86400000L,400,300,album="Camera")}
        adapter.submitList(photos);layout(grid);val edge=GalleryFastScroll(c.get());layout(edge);edge.bind(grid){true}
        grid.scrollBy(0,400);assertEquals(View.VISIBLE,edge.visibility)
        var changes=0;adapter.registerAdapterDataObserver(object:RecyclerView.AdapterDataObserver(){override fun onChanged(){changes++}})
        val handle=GalleryFastScroll::class.java.getDeclaredField("handle").apply{isAccessible=true}.get(edge) as android.graphics.RectF
        // Drawing calculates the handle position used for its expanded touch target.
        val bitmap=Bitmap.createBitmap(1080,1920,Bitmap.Config.ARGB_8888);edge.draw(Canvas(bitmap))
        send(edge,MotionEvent.ACTION_DOWN,1070f,handle.centerY());send(edge,MotionEvent.ACTION_MOVE,1070f,1920f);layout(grid)
        assertTrue(layout.findLastVisibleItemPosition()>=adapter.itemCount-2)
        assertEquals(0,changes);assertSame(adapter,grid.adapter)
        send(edge,MotionEvent.ACTION_UP,1070f,1920f);edge.close();assertEquals(View.INVISIBLE,edge.visibility)
        bitmap.recycle();adapter.close();c.pause().stop().destroy()
    }
}
