package com.mosaic.gallery

import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/** One continuous range, based on the selection at finger-down; reversal restores its baseline. */
class GlideSelection(private val grid:RecyclerView,private val active:()->Boolean,
    private val key:(Int)->String?,private val selected:()->Set<String>,private val changed:(Set<String>)->Unit):RecyclerView.SimpleOnItemTouchListener(){
    private var anchor=RecyclerView.NO_POSITION
    private var last=RecyclerView.NO_POSITION
    private var baseline=emptySet<String>()
    private var adding=true
    private var dragging=false
    private var blocked=false
    private var downX=0f;private var downY=0f;private var x=0f;private var y=0f
    private val slop=ViewConfiguration.get(grid.context).scaledTouchSlop
    private val scroll=object:Runnable{
        override fun run(){
            if(!dragging)return
            val edge=GalleryStyle.dp(grid.context,48).toFloat()
            val direction=when{y<edge->(y/edge-1f).coerceAtLeast(-1f);y>grid.height-edge->((y-grid.height+edge)/edge).coerceAtMost(1f);else->0f}
            if(direction!=0f){grid.scrollBy(0,(direction*GalleryStyle.dp(grid.context,18)).toInt());update()}
            grid.postOnAnimation(this)
        }
    }
    private fun position()=grid.findChildViewUnder(x.coerceIn(0f,(grid.width-1).coerceAtLeast(0).toFloat()),y.coerceIn(0f,(grid.height-1).coerceAtLeast(0).toFloat()))?.let{grid.getChildAdapterPosition(it)}?:RecyclerView.NO_POSITION
    override fun onInterceptTouchEvent(view:RecyclerView,event:MotionEvent):Boolean{
        x=event.x;y=event.y
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{finish();downX=x;downY=y;blocked=false;anchor=position();baseline=selected().toSet();adding=!active() || key(anchor) !in baseline;last=RecyclerView.NO_POSITION}
            MotionEvent.ACTION_POINTER_DOWN->{blocked=true;finish()}
            MotionEvent.ACTION_MOVE->if(!blocked && active() && key(anchor)!=null && (abs(x-downX)>slop || abs(y-downY)>slop)){
                dragging=true;grid.parent?.requestDisallowInterceptTouchEvent(true);update();grid.removeCallbacks(scroll);grid.postOnAnimation(scroll);return true
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->finish()
        }
        return false
    }
    override fun onTouchEvent(view:RecyclerView,event:MotionEvent){
        x=event.x;y=event.y
        when(event.actionMasked){MotionEvent.ACTION_MOVE->update();MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->finish()}
    }
    private fun update(){
        val position=position();if(position==RecyclerView.NO_POSITION || key(position)==null || position==last)return
        last=position
        val range=(minOf(anchor,position)..maxOf(anchor,position)).mapNotNull(key).toSet()
        changed(if(adding)baseline+range else baseline-range)
    }
    fun detach(){finish();grid.removeOnItemTouchListener(this)}
    fun finish(){dragging=false;grid.removeCallbacks(scroll);grid.parent?.requestDisallowInterceptTouchEvent(false)}
}
