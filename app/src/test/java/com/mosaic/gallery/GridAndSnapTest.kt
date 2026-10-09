package com.mosaic.gallery

import android.Manifest
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.math.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w540dp-h960dp-xhdpi")
class GridAndSnapTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private var tick=0L
    private fun layout(view:View,w:Int=1080,h:Int=1500){view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h)}
    private fun send(view:View,action:Int,points:List<Pair<Float,Float>>){
        tick+=32;val now=SystemClock.uptimeMillis()
        val p=points.indices.map{MotionEvent.PointerProperties().apply{id=it;toolType=MotionEvent.TOOL_TYPE_FINGER}}.toTypedArray()
        val c=points.map{MotionEvent.PointerCoords().apply{x=it.first;y=it.second;pressure=1f;size=1f}}.toTypedArray()
        val event=MotionEvent.obtain(now,now+tick,action,points.size,p,c,0,0,1f,1f,0,0,0,0);view.dispatchTouchEvent(event);event.recycle()
    }
    private fun pair(cx:Float=540f,cy:Float=650f,r:Float=100f,angle:Float=0f):List<Pair<Float,Float>>{
        val a=angle*PI/180;val dx=(r*cos(a)).toFloat();val dy=(r*sin(a)).toFloat();return listOf(cx-dx to cy-dy,cx+dx to cy+dy)
    }
    private fun begin(view:View,p:List<Pair<Float,Float>> =pair()){send(view,MotionEvent.ACTION_DOWN,listOf(p[0]));send(view,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),p)}
    private fun end(view:View,p:List<Pair<Float,Float>>){send(view,MotionEvent.ACTION_POINTER_UP or (1 shl 8),p);send(view,MotionEvent.ACTION_UP,listOf(p[0]))}
    @Before fun reset(){Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES);app.getSharedPreferences("gallery-layout",0).edit().clear().commit()}
    @Test fun twistSettlesToNearestQuarterTurnAndFits(){
        val view=ZoomPhotoView(app);layout(view,800,600)
        for((angle,target) in listOf(60f to 90f,150f to 180f,-60f to -90f,-150f to -180f,30f to 0f)){
            view.show(Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888));begin(view,pair(400f,300f))
            val p=pair(400f,300f,angle=angle);send(view,MotionEvent.ACTION_MOVE,p);end(view,p)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));assertEquals(target,view.rotationDegrees,.01f)
            val rect=android.graphics.RectF(0f,0f,400f,300f);view.imageMatrix.mapRect(rect)
            assertTrue(rect.width()<=800.1f);assertTrue(rect.height()<=600.1f)
        }
    }
    @Test fun snappingAcrossZeroDoesNotAnimateAFullRevolution(){
        assertEquals(360f,ZoomPhotoView.nearestQuarterTurn(350f),0f);assertEquals(-360f,ZoomPhotoView.nearestQuarterTurn(-350f),0f)
        val view=ZoomPhotoView(app);layout(view,800,600);view.show(Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888))
        begin(view,pair(400f,300f));for(angle in listOf(70f,140f,210f,280f,350f))send(view,MotionEvent.ACTION_MOVE,pair(400f,300f,angle=angle))
        end(view,pair(400f,300f,angle=350f));Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));assertEquals(360f,view.rotationDegrees,.01f)
    }
    @Test fun pinchWithDriftNeverSnapsIntoAnUnwantedTurn(){
        val view=ZoomPhotoView(app);layout(view,800,600);view.show(Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888));begin(view,pair(400f,300f))
        val p=pair(400f,300f,180f,8f);send(view,MotionEvent.ACTION_MOVE,p);end(view,p)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));assertEquals(0f,view.rotationDegrees,0f)
        val m=FloatArray(9);view.imageMatrix.getValues(m);assertEquals(3.6f,m[0],.02f)
    }
    private fun photos()=(0..179).map{PhotoRecord(it.toLong(),Uri.parse("content://grid-test/$it"),"$it.jpg",(it/30+1)*86_400_000L,400,300,album="Camera")}
    @Test fun mainGridPinchReflowsSquareCellsFullWidthHeadersAndRemembersSize(){
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().visible();val activity=c.get()
        fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}
        field("allPhotos").set(activity,photos());MainActivity::class.java.getDeclaredMethod("render").apply{isAccessible=true}.invoke(activity)
        val grid=field("grid").get(activity) as PinchPhotoGrid;layout(grid)
        val adapter=grid.adapter as PhotoGridAdapter;val focus=grid.findChildViewUnder(540f,650f)!!;val uri=adapter.photoKey(grid.getChildAdapterPosition(focus))!!
        begin(grid);val p=pair(r=140f);send(grid,MotionEvent.ACTION_MOVE,p);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));layout(grid);assertEquals(1f,grid.scaleX,0f);assertEquals(1f,grid.scaleY,0f)
        end(grid,p);layout(grid);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));layout(grid)
        val manager=grid.layoutManager as GridLayoutManager;assertEquals(3,manager.spanCount);assertEquals(3,manager.spanSizeLookup.getSpanSize(0))
        assertNotNull(grid.findViewHolderForAdapterPosition(adapter.positionOf(uri)))
        for(i in 0 until grid.childCount){val child=grid.getChildAt(i);if(adapter.photoKey(grid.getChildAdapterPosition(child))!=null)assertEquals(child.width,child.height)}
        assertEquals(1f,grid.scaleX,.01f);assertEquals(3,app.getSharedPreferences("gallery-layout",0).getInt("photoColumns",0))
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        c.stop().destroy()
        val next=Robolectric.buildActivity(MainActivity::class.java).create().start().visible();assertEquals(3,field("photoColumns").getInt(next.get()));next.stop().destroy()
    }
    @Test fun shrinkGestureStopsAtEightColumnsAndDoesNotReloadData(){
        val grid=PinchPhotoGrid(app,{true},{})
        val adapter=PhotoGridAdapter(app,{},{});grid.adapter=adapter
        val manager=GridLayoutManager(app,4);manager.spanSizeLookup=object:GridLayoutManager.SpanSizeLookup(){override fun getSpanSize(p:Int)=if(adapter.isHeader(p))manager.spanCount else 1};grid.layoutManager=manager
        adapter.submitList(photos());layout(grid);var notifications=0;adapter.registerAdapterDataObserver(object:androidx.recyclerview.widget.RecyclerView.AdapterDataObserver(){override fun onChanged(){notifications++}})
        begin(grid);val p=pair(r=25f);send(grid,MotionEvent.ACTION_MOVE,p);end(grid,p);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));assertEquals(8,manager.spanCount);assertEquals(0,notifications);adapter.close()
    }
    @Test fun largeGestureStopsAtTwoColumns(){
        val grid=PinchPhotoGrid(app,{true},{})
        val adapter=PhotoGridAdapter(app,{},{});grid.adapter=adapter;grid.layoutManager=GridLayoutManager(app,4);adapter.submitList(photos());layout(grid)
        begin(grid);val p=pair(r=400f);send(grid,MotionEvent.ACTION_MOVE,p);end(grid,p);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));assertEquals(2,(grid.layoutManager as GridLayoutManager).spanCount);adapter.close()
    }
    @Test fun cancelRestoresGridWithoutChangingPreference(){
        var changes=0;val grid=PinchPhotoGrid(app,{true},{changes++})
        val adapter=PhotoGridAdapter(app,{},{});grid.adapter=adapter;grid.layoutManager=GridLayoutManager(app,4);adapter.submitList(photos());layout(grid)
        begin(grid);send(grid,MotionEvent.ACTION_MOVE,pair(r=170f));send(grid,MotionEvent.ACTION_CANCEL,pair(r=170f))
        assertEquals(4,(grid.layoutManager as GridLayoutManager).spanCount);assertEquals(1f,grid.scaleX,0f);assertEquals(0,changes);adapter.close()
    }
    @Test fun albumsAndSelectionModeDoNotResize(){
        val c=Robolectric.buildActivity(MainActivity::class.java).create().start().visible();val a=c.get()
        fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}
        field("allPhotos").set(a,photos())
        fun render(){MainActivity::class.java.getDeclaredMethod("render").apply{isAccessible=true}.invoke(a)}
        try{
            for((page,selecting) in listOf("Albums" to false,"Photos" to true)){
                field("page").set(a,page);field("selecting").setBoolean(a,selecting);render();val grid=field("grid").get(a) as PinchPhotoGrid;layout(grid)
                val count=(grid.layoutManager as GridLayoutManager).spanCount;begin(grid);val p=pair(r=150f);send(grid,MotionEvent.ACTION_MOVE,p);end(grid,p)
                assertEquals(count,(grid.layoutManager as GridLayoutManager).spanCount);assertEquals(1f,grid.scaleX,0f)
            }
        }finally{c.stop().destroy()}
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun nativeGridPreviewUsesCachedSquareTilesAfterReflow(){
        val data=photos();val adapter=PhotoGridAdapter(app,{},{});val grid=PinchPhotoGrid(app,{true},{})
        val manager=GridLayoutManager(app,4);manager.spanSizeLookup=object:GridLayoutManager.SpanSizeLookup(){override fun getSpanSize(p:Int)=if(adapter.isHeader(p))manager.spanCount else 1}
        grid.adapter=adapter;grid.layoutManager=manager;grid.setBackgroundColor(GalleryStyle.canvas(app))
        val cache=PhotoGridAdapter::class.java.getDeclaredField("cache").apply{isAccessible=true}.get(adapter) as android.util.LruCache<String,Bitmap>
        val key=PhotoGridAdapter::class.java.getDeclaredMethod("imageKey",PhotoRecord::class.java).apply{isAccessible=true}
        for((i,p) in data.withIndex())cache.put(key.invoke(adapter,p) as String,Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888).apply{eraseColor(listOf(0xff52779d.toInt(),0xff819872.toInt(),0xffc0a880.toInt())[i%3])})
        try{
            adapter.submitList(data);layout(grid);begin(grid);val p=pair(r=140f);send(grid,MotionEvent.ACTION_MOVE,p);end(grid,p);layout(grid)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));layout(grid);assertEquals(3,manager.spanCount)
            val image=Bitmap.createBitmap(grid.width,grid.height,Bitmap.Config.ARGB_8888);grid.draw(Canvas(image))
            val file=java.io.File("build/reports/ui/resizable-grid.png");file.parentFile?.mkdirs();file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
            for(i in 0 until grid.childCount){val child=grid.getChildAt(i);if(adapter.photoKey(grid.getChildAdapterPosition(child))!=null){assertEquals(child.width,child.height);assertTrue(child.width>300)}}
            image.recycle()
        }finally{grid.cancelResize();adapter.close()}
    }


    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun liveResizeKeepsImagesAlignedAndCoalescesMovesWithoutReloading(){
        val data=photos();val adapter=PhotoGridAdapter(app,{},{});val grid=PinchPhotoGrid(app,{true},{})
        val manager=GridLayoutManager(app,4);manager.spanSizeLookup=object:GridLayoutManager.SpanSizeLookup(){override fun getSpanSize(p:Int)=if(adapter.isHeader(p))manager.spanCount else 1}
        grid.adapter=adapter;grid.layoutManager=manager
        val cache=PhotoGridAdapter::class.java.getDeclaredField("cache").apply{isAccessible=true}.get(adapter) as android.util.LruCache<String,Bitmap>
        val key=PhotoGridAdapter::class.java.getDeclaredMethod("imageKey",PhotoRecord::class.java).apply{isAccessible=true}
        for(p in data)cache.put(key.invoke(adapter,p) as String,Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888))
        val owner=Robolectric.buildActivity(Activity::class.java).setup().visible();owner.get().setContentView(grid)
        try{
            adapter.submitList(data);layout(grid)
            val old=(0 until grid.childCount).map{grid.getChildAt(it)}.mapNotNull{v->adapter.photoKey(grid.getChildAdapterPosition(v))?.let{it to ((v as android.widget.ImageView).drawable as android.graphics.drawable.BitmapDrawable).bitmap}}.toMap()
            val headerTop=grid.getChildAt(0).top
            var changes=0;adapter.registerAdapterDataObserver(object:androidx.recyclerview.widget.RecyclerView.AdapterDataObserver(){override fun onChanged(){changes++}})
            begin(grid)
            val size=PinchPhotoGrid::class.java.getDeclaredField("cell").apply{isAccessible=true}
            for(r in listOf(105f,110f,115f))send(grid,MotionEvent.ACTION_MOVE,pair(r=r))
            assertEquals(270,size.getInt(grid)) // No layout work per raw touch event.
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));layout(grid)
            assertEquals(311,size.getInt(grid));assertEquals(headerTop,grid.getChildAt(0).top)
            for(r in listOf(130f,160f,115f,85f,65f,115f)){
                send(grid,MotionEvent.ACTION_MOVE,pair(cx=600f,cy=720f,r=r))
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));layout(grid)
                assertEquals(1f,grid.scaleX,0f);assertEquals(0f,grid.translationX,0f);assertEquals(0f,grid.translationY,0f)
                val rows=mutableMapOf<Int,MutableList<View>>()
                for(i in 0 until grid.childCount){val v=grid.getChildAt(i);val uri=adapter.photoKey(grid.getChildAdapterPosition(v))?:continue
                    assertEquals(v.width,v.height);assertNotNull((v as android.widget.ImageView).drawable)
                    old[uri]?.let{assertSame(it,(v.drawable as android.graphics.drawable.BitmapDrawable).bitmap)}
                    rows.getOrPut(v.top){mutableListOf()}.add(v)
                }
                for(row in rows.values){assertEquals(0,row.first().left);for(i in 1 until row.size)assertEquals(row[i-1].right+GalleryStyle.dp(app,1),row[i].left)}
            }
            assertEquals(0,changes)
            end(grid,pair(r=115f));Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));layout(grid)
            assertEquals(3,manager.spanCount)
            // Start another pinch: span caches must not contain the previous gesture's widths.
            begin(grid);send(grid,MotionEvent.ACTION_MOVE,pair(r=80f));Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));layout(grid)
            assertEquals(288,size.getInt(grid));send(grid,MotionEvent.ACTION_CANCEL,pair(r=80f));assertEquals(3,manager.spanCount)
        }finally{grid.cancelResize();adapter.close();owner.pause().stop().destroy()}
    }

    @Test fun oddScreenWidthsDoNotWrapAnExtraRowWhenPinchStarts(){
        for(columns in 2..8){
            val grid=PinchPhotoGrid(app,{true},{})
            val adapter=PhotoGridAdapter(app,{},{});grid.adapter=adapter
            val manager=GridLayoutManager(app,columns);manager.spanSizeLookup=object:GridLayoutManager.SpanSizeLookup(){override fun getSpanSize(p:Int)=if(adapter.isHeader(p))manager.spanCount else 1};grid.layoutManager=manager
            try{
                adapter.submitList(photos());layout(grid,1082);begin(grid);layout(grid,1082)
                val tiles=(0 until grid.childCount).map{grid.getChildAt(it)}.filter{adapter.photoKey(grid.getChildAdapterPosition(it))!=null}
                assertEquals(columns,tiles.count{it.top==tiles.first().top})
                send(grid,MotionEvent.ACTION_CANCEL,pair());assertEquals(columns,manager.spanCount)
            }finally{grid.cancelResize();adapter.close()}
        }
    }

    @Test fun disabledGridDoesNotCancelAnAlbumTransitionTransform(){
        val grid=PinchPhotoGrid(app,{false},{})
        grid.scaleX=.7f;grid.scaleY=.7f;grid.translationX=25f
        send(grid,MotionEvent.ACTION_DOWN,listOf(300f to 300f))
        assertEquals(.7f,grid.scaleX,0f);assertEquals(.7f,grid.scaleY,0f);assertEquals(25f,grid.translationX,0f)
        send(grid,MotionEvent.ACTION_CANCEL,listOf(300f to 300f))
    }

}
