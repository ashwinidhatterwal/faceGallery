package com.mosaic.gallery

import android.app.Activity
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35])
class GalleryBasicsTest {
    @Test fun quarterTurnsHandleNegativeAnglesAndWrapContinuously(){
        for((angle,target) in listOf(60f to 90f,170f to 180f,350f to 360f,-60f to -90f,-170f to -180f,410f to 450f,20f to 0f))assertEquals(target,QuarterTurns.nearest(angle),0.01f)
    }
    @Test fun detailsIncludeExactBytesAndStoragePath(){
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val photo=PhotoRecord(1,Uri.parse("content://photos/1"),"photo.jpg",0,400,300,sizeBytes=123456,path="/storage/emulated/0/DCIM/photo.jpg")
        val text=PhotoDetails.text(activity,photo)
        assertTrue(text.contains("123456 bytes"));assertTrue(text.contains("Path: /storage/emulated/0/DCIM/photo.jpg"));assertTrue(text.contains("400 × 300"))
    }
    @Test fun detailsUseProviderUriWhenStoragePathUnavailable(){
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        assertTrue(PhotoDetails.text(activity,PhotoRecord(1,Uri.parse("content://photos/1"),"photo",0,0,0)).contains("Path: content://photos/1"))
    }
    private fun selection(initial:Set<String>,run:(GlideSelection,RecyclerView,()->Set<String>)->Unit){
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val grid=RecyclerView(activity);grid.layoutManager=GridLayoutManager(activity,3)
        grid.adapter=object:RecyclerView.Adapter<RecyclerView.ViewHolder>(){
            override fun getItemCount()=9
            override fun onCreateViewHolder(parent:android.view.ViewGroup,type:Int)=object:RecyclerView.ViewHolder(TextView(activity).apply{layoutParams=RecyclerView.LayoutParams(100,100)}){}
            override fun onBindViewHolder(holder:RecyclerView.ViewHolder,position:Int){}
        }
        activity.setContentView(grid);grid.measure(View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY));grid.layout(0,0,300,300)
        var selected=initial
        val glide=GlideSelection(grid,{true},{if(it in 0..8 && it!=3)it.toString()else null},{selected},{selected=it})
        run(glide,grid,{selected});glide.detach()
    }
    private fun event(action:Int,x:Float,y:Float)=MotionEvent.obtain(0,20,action,x,y,0)
    @Test fun glideAddsRangeSkipsHeadersAndReversesBeforeRelease()=selection(emptySet()){glide,grid,selected->
        event(MotionEvent.ACTION_DOWN,50f,50f).also{glide.onInterceptTouchEvent(grid,it);it.recycle()}
        event(MotionEvent.ACTION_MOVE,250f,150f).also{assertTrue(glide.onInterceptTouchEvent(grid,it));it.recycle()}
        assertEquals(setOf("0","1","2","4","5"),selected())
        event(MotionEvent.ACTION_MOVE,150f,50f).also{glide.onTouchEvent(grid,it);it.recycle()}
        assertEquals(setOf("0","1"),selected())
    }
    @Test fun glideFromSelectedPhotoRemovesRangePreservingOutsideSelection()=selection(setOf("0","1","2","8")){glide,grid,selected->
        event(MotionEvent.ACTION_DOWN,50f,50f).also{glide.onInterceptTouchEvent(grid,it);it.recycle()}
        event(MotionEvent.ACTION_MOVE,250f,50f).also{glide.onInterceptTouchEvent(grid,it);it.recycle()}
        assertEquals(setOf("8"),selected())
    }
}
