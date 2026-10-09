package com.mosaic.gallery

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/** A small edge handle with a large touch target; jumps reuse the current adapter and cache. */
class GalleryFastScroll(context:Context):View(context) {
    private var grid:RecyclerView?=null
    private var enabled:()->Boolean={false}
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG)
    private val handle=RectF()
    private var fraction=0f;private var dragging=false;private var grab=0f;private var label=""
    private val fade=Runnable{if(!dragging)animate().alpha(0f).setDuration(180).withEndAction{if(!dragging)visibility=INVISIBLE}.start()}
    private val listener=object:RecyclerView.OnScrollListener(){
        override fun onScrolled(view:RecyclerView,dx:Int,dy:Int){if(!dragging){sync();if(dy!=0 && usable())reveal()}}
        override fun onScrollStateChanged(view:RecyclerView,state:Int){if(state==RecyclerView.SCROLL_STATE_IDLE && !dragging)later()}
    }
    init {visibility=INVISIBLE;contentDescription="Scroll through photos";importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES;isFocusable=true}
    private fun dp(n:Int)=GalleryStyle.dp(context,n).toFloat()
    fun bind(view:RecyclerView,enabled:()->Boolean){
        this.enabled=enabled
        if(grid!==view){grid?.removeOnScrollListener(listener);grid=view;view.addOnScrollListener(listener);hide()}
        if(!enabled())hide()
    }
    private fun usable()=enabled() && grid?.let{(it.adapter?.itemCount?:0)>0 && (it.canScrollVertically(1)||it.canScrollVertically(-1))}==true
    private fun sync(){val g=grid?:return;val range=(g.computeVerticalScrollRange()-g.computeVerticalScrollExtent()).coerceAtLeast(1);fraction=(g.computeVerticalScrollOffset().toFloat()/range).coerceIn(0f,1f);invalidate()}
    private fun reveal(){removeCallbacks(fade);animate().cancel();visibility=VISIBLE;alpha=1f;invalidate();if(!dragging)later()}
    private fun later(){removeCallbacks(fade);postDelayed(fade,1500)}
    fun hide(){removeCallbacks(fade);animate().cancel();dragging=false;visibility=INVISIBLE;parent?.requestDisallowInterceptTouchEvent(false)}
    fun close(){hide();grid?.removeOnScrollListener(listener);grid=null}
    override fun onDetachedFromWindow(){hide();super.onDetachedFromWindow()}
    private fun geometry(){val top=dp(12);val travel=(height-dp(24)-dp(48)).coerceAtLeast(1f);val y=top+travel*fraction;handle.set(width-dp(24),y,width-dp(6),y+dp(48))}
    override fun onDraw(canvas:Canvas){
        geometry();ink.color=GalleryStyle.surface(context);ink.style=Paint.Style.FILL
        canvas.drawRoundRect(handle,dp(9),dp(9),ink);ink.style=Paint.Style.STROKE;ink.strokeWidth=dp(1);ink.color=GalleryStyle.accent(context);canvas.drawRoundRect(handle,dp(9),dp(9),ink)
        ink.strokeCap=Paint.Cap.ROUND;ink.strokeWidth=dp(2);ink.color=GalleryStyle.iconColor(context)
        for(y in listOf(-4f,4f))canvas.drawLine(handle.centerX()-dp(4),handle.centerY()+dp(y.toInt()),handle.centerX()+dp(4),handle.centerY()+dp(y.toInt()),ink)
        if(dragging && label.isNotBlank()){
            ink.style=Paint.Style.FILL;ink.textSize=dp(13);ink.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            val textWidth=ink.measureText(label);val bubble=RectF(handle.left-dp(12)-textWidth-dp(24),handle.centerY()-dp(18),handle.left-dp(12),handle.centerY()+dp(18))
            ink.color=GalleryStyle.surface(context);canvas.drawRoundRect(bubble,dp(12),dp(12),ink);ink.color=GalleryStyle.textColor(context);canvas.drawText(label,bubble.left+dp(12),bubble.centerY()-(ink.ascent()+ink.descent())/2,ink)
        }
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{geometry();if(!usable() || event.x<handle.left-dp(16) || event.y<handle.top-dp(12) || event.y>handle.bottom+dp(12))return false
                dragging=true;grab=event.y-handle.top;grid?.stopScroll();(grid as? PinchPhotoGrid)?.cancelResize();parent?.requestDisallowInterceptTouchEvent(true);reveal();return true}
            MotionEvent.ACTION_MOVE->{if(!dragging)return false;dragTo(event.y);return true}
            MotionEvent.ACTION_UP->{if(!dragging)return false;dragTo(event.y);dragging=false;parent?.requestDisallowInterceptTouchEvent(false);performClick();later();invalidate();return true}
            MotionEvent.ACTION_CANCEL->{if(!dragging)return false;dragging=false;parent?.requestDisallowInterceptTouchEvent(false);later();invalidate();return true}
        }
        return dragging
    }
    private fun dragTo(y:Float){
        val g=grid?:return;val count=g.adapter?.itemCount?:return
        fraction=((y-grab-dp(12))/(height-dp(24)-dp(48)).coerceAtLeast(1f)).coerceIn(0f,1f)
        val index=((count-1)*fraction).roundToInt().coerceIn(0,(count-1).coerceAtLeast(0))
        (g.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(index,0)
        label=(g.adapter as? PhotoGridAdapter)?.scrollLabel(index).orEmpty();invalidate()
    }
    override fun performClick():Boolean{super.performClick();return true}
}
