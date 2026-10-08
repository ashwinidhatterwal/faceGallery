package com.mosaic.gallery

import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/** Resize native cells, never the scrolling surface. Pixel spans allow continuous row wrapping. */
internal class PinchPhotoGrid(context:Context,private val enabled:()->Boolean,private val remembered:(Int)->Unit):RecyclerView(context) {
    private var consuming=false;private var resizing=false
    private var originalColumns=4;private var factor=1f;private var available=0;private var cell=0
    private var anchorPosition=NO_POSITION;private var anchorOffset=0
    private var originalLookup:GridLayoutManager.SpanSizeLookup?=null
    private var settling:ValueAnimator?=null
    private var framePending=false
    private val frame=Runnable {framePending=false;if(resizing)resize((available.toFloat()/originalColumns*factor).roundToInt())}
    private val pixelLookup=object:GridLayoutManager.SpanSizeLookup(){
        override fun getSpanSize(position:Int)=if((adapter as? PhotoGridAdapter)?.isHeader(position)==true)available else cell
    }.apply{setSpanIndexCacheEnabled(true);setSpanGroupIndexCacheEnabled(true)}
    private val fingers=PhotoTransformGesture(GalleryStyle.dp(context,24).toFloat()){_,_,_,_,ratio,_->
        if(resizing){
            factor=(factor*ratio).coerceIn(originalColumns/8f,originalColumns/2f)
            if(!framePending){framePending=true;postOnAnimation(frame)}
        }
    }
    private fun start(event:MotionEvent):Boolean {
        val layout=layoutManager as? GridLayoutManager?:return false
        val data=adapter as? PhotoGridAdapter?:return false
        if(data.itemCount==0 || width==0)return false
        finishSettle();stopScroll()
        originalColumns=layout.spanCount;factor=1f;available=width-paddingLeft-paddingRight
        if(available<64)return false
        // Pin the first visible row, rather than translating the grid with the finger midpoint.
        anchorPosition=layout.findFirstVisibleItemPosition()
        anchorOffset=layout.findViewByPosition(anchorPosition)?.let{layout.getDecoratedTop(it)-paddingTop}?:0
        originalLookup=layout.spanSizeLookup;cell=available/originalColumns
        val cancel=MotionEvent.obtain(event);cancel.action=MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel);cancel.recycle()
        consuming=true;resizing=true;parent?.requestDisallowInterceptTouchEvent(true)
        // Keep recent offscreen holders warm when rows wrap back and forth.
        setItemViewCacheSize(64)
        pixelLookup.invalidateSpanIndexCache();pixelLookup.invalidateSpanGroupIndexCache()
        layout.spanSizeLookup=pixelLookup;layout.spanCount=available
        fingers.reset();fingers.onTouch(event)
        return true
    }
    private fun resize(size:Int){
        val layout=layoutManager as? GridLayoutManager?:return
        val next=size.coerceIn(available/8,available/2)
        if(next==cell)return
        cell=next;pixelLookup.invalidateSpanIndexCache();pixelLookup.invalidateSpanGroupIndexCache()
        if(anchorPosition>=0)layout.scrollToPositionWithOffset(anchorPosition,anchorOffset)
        layout.requestLayout()
    }
    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN){finishSettle();cancelResize();consuming=false}
        if(!consuming && event.actionMasked==MotionEvent.ACTION_POINTER_DOWN && enabled() && start(event))return true
        if(consuming){
            if(resizing && !enabled())cancelResize()
            if(resizing)fingers.onTouch(event)
            when(event.actionMasked){
                MotionEvent.ACTION_POINTER_UP->if(resizing && !fingers.active)settle()
                MotionEvent.ACTION_UP->{if(resizing)settle();consuming=false;parent?.requestDisallowInterceptTouchEvent(false)}
                MotionEvent.ACTION_CANCEL->{cancelResize();consuming=false;parent?.requestDisallowInterceptTouchEvent(false)}
            }
            return true
        }
        return super.dispatchTouchEvent(event)
    }
    private fun settle(){
        removeCallbacks(frame);framePending=false
        resize((available.toFloat()/originalColumns*factor).roundToInt())
        resizing=false;fingers.reset()
        val columns=(originalColumns/factor).roundToInt().coerceIn(2,8)
        settling=ValueAnimator.ofInt(cell,available/columns).apply {
            duration=120
            addUpdateListener{resize(it.animatedValue as Int)}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(animation:android.animation.Animator){restore(columns);settling=null}})
            start()
        }
    }
    private fun restore(columns:Int){
        val lookup=originalLookup?:return
        originalLookup=null
        (layoutManager as? GridLayoutManager)?.let{layout->
            layout.spanCount=columns;layout.spanSizeLookup=lookup
            lookup.invalidateSpanIndexCache();lookup.invalidateSpanGroupIndexCache()
            if(anchorPosition>=0)layout.scrollToPositionWithOffset(anchorPosition,anchorOffset)
        }
        setItemViewCacheSize(20)
        if(columns!=originalColumns)remembered(columns)
    }
    private fun finishSettle(){settling?.end();settling=null}
    fun cancelResize(){
        removeCallbacks(frame);framePending=false;resizing=false;fingers.reset()
        // Cancel does not save a half-completed resize.
        settling?.removeAllListeners();settling?.cancel();settling=null
        restore(originalColumns)
    }
    override fun onDetachedFromWindow(){cancelResize();super.onDetachedFromWindow()}
}
