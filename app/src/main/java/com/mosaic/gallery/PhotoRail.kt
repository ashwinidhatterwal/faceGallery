package com.mosaic.gallery

import android.content.Context
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/** Native scrolling with a fixed centre; user browsing and pager focus have one owner. */
class PhotoRail(context:Context):RecyclerView(context){
    var onCentered:(Int)->Unit={}
    private val layout=LinearLayoutManager(context,HORIZONTAL,false)
    private val snap=LinearSnapHelper()
    private var browsing=false
    private var reported=NO_POSITION
    private var focused=NO_POSITION
    private var pendingFocus=NO_POSITION
    init{
        layoutManager=layout;itemAnimator=null;clipToPadding=false
        overScrollMode=View.OVER_SCROLL_NEVER;setBackgroundColor(GalleryStyle.canvas(context))
        snap.attachToRecyclerView(this)
        addOnScrollListener(object:OnScrollListener(){
            override fun onScrolled(view:RecyclerView,dx:Int,dy:Int){
                shade()
                if(browsing)reportCentre()
            }
            override fun onScrollStateChanged(view:RecyclerView,state:Int){
                if(state==SCROLL_STATE_DRAGGING)browsing=true
                if(state==SCROLL_STATE_IDLE)post{
                    if(scrollState!=SCROLL_STATE_IDLE)return@post
                    val target=snap.findSnapView(layout) ?: return@post
                    if(abs(snap.calculateDistanceToFinalSnap(layout,target)?.get(0)?:0)>1)return@post
                    if(browsing)reportCentre()
                    browsing=false;shade()
                }
            }
        })
    }
    override fun onTouchEvent(event:MotionEvent):Boolean{
        if(event.actionMasked==MotionEvent.ACTION_DOWN){focused=NO_POSITION;reported=NO_POSITION}
        return super.onTouchEvent(event)
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
        super.onSizeChanged(w,h,oldw,oldh)
        val inset=((w-GalleryStyle.dp(context,50))/2).coerceAtLeast(0)
        setPadding(inset,0,inset,0)
        if(focused!=NO_POSITION && pendingFocus==NO_POSITION)pendingFocus=focused

    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){
        if(pendingFocus!=NO_POSITION){focused=pendingFocus;reported=focused;pendingFocus=NO_POSITION;layout.scrollToPositionWithOffset(focused,0)}
        super.onLayout(changed,l,t,r,b);shade()
    }
    fun focus(position:Int,animate:Boolean=true){
        if(position<0 || position>=(adapter?.itemCount?:0) || browsing)return
        if(width==0 || isLayoutRequested){pendingFocus=position;requestLayout();return}
        if(position==focused && animate)return
        focused=position;reported=position
        if(!animate){layout.scrollToPositionWithOffset(position,0);post{shade()};return}
        val scroller=object:LinearSmoothScroller(context){
            override fun calculateDxToMakeVisible(view:View,snapPreference:Int):Int=
                width/2-(layout.getDecoratedLeft(view)+layout.getDecoratedRight(view))/2
            override fun calculateSpeedPerPixel(metrics:android.util.DisplayMetrics)=70f/metrics.densityDpi
        }
        scroller.targetPosition=position;layout.startSmoothScroll(scroller)
    }
    private fun reportCentre(){
        val view=snap.findSnapView(layout) ?: return
        val position=getChildAdapterPosition(view)
        if(position!=NO_POSITION && position!=reported){reported=position;onCentered(position)}
    }
    private fun shade(){
        val reach=GalleryStyle.dp(context,68).toFloat()
        for(i in 0 until childCount){
            val view=getChildAt(i)
            val focus=(1f-abs((view.left+view.right)/2f-width/2f)/reach).coerceIn(0f,1f)
            view.scaleX=0.78f+0.22f*focus;view.scaleY=view.scaleX;view.alpha=0.52f+0.48f*focus
        }
    }
}
