package com.mosaic.gallery

import android.view.MotionEvent
import kotlin.math.*

/** A stable pointer pair; span and midpoint always follow fingers, rotation needs intent. */
internal class PhotoTransformGesture(private val minimumSpan:Float,
    private val changed:(Float,Float,Float,Float,Float,Float)->Unit) {
    private var first=-1;private var second=-1
    val active get()=second>=0
    private var px=0f;private var py=0f;private var span=0f;private var angle=0f
    private var totalAngle=0f;private var intentSpan=0f
    private var zoomIntent=false;private var rotating=false
    fun reset(){first=-1;second=-1;zoomIntent=false;rotating=false;totalAngle=0f}
    private fun begin(event:MotionEvent,skip:Int=-1){
        reset()
        val a=(0 until event.pointerCount).firstOrNull{it!=skip}?:return
        val b=(0 until event.pointerCount).firstOrNull{it!=skip && it!=a}?:return
        first=event.getPointerId(a);second=event.getPointerId(b)
        px=(event.getX(a)+event.getX(b))/2;py=(event.getY(a)+event.getY(b))/2
        val dx=event.getX(b)-event.getX(a);val dy=event.getY(b)-event.getY(a)
        span=hypot(dx,dy);intentSpan=span;angle=atan2(dy,dx)*180f/PI.toFloat()
    }
    fun onTouch(event:MotionEvent){
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->reset()
            MotionEvent.ACTION_POINTER_DOWN->if(!active)begin(event)
            MotionEvent.ACTION_POINTER_UP->{val lifted=event.getPointerId(event.actionIndex);if(lifted==first || lifted==second)begin(event,event.actionIndex)}
            MotionEvent.ACTION_MOVE->{
                if(!active && event.pointerCount>=2){begin(event);return}
                val a=event.findPointerIndex(first);val b=event.findPointerIndex(second)
                if(a<0 || b<0)return
                val dx=event.getX(b)-event.getX(a);val dy=event.getY(b)-event.getY(a)
                val nextSpan=hypot(dx,dy);val nextAngle=atan2(dy,dx)*180f/PI.toFloat()
                val nx=(event.getX(a)+event.getX(b))/2;val ny=(event.getY(a)+event.getY(b))/2
                if(span<minimumSpan || nextSpan<minimumSpan){begin(event);return}
                val delta=((nextAngle-angle+540f)%360f)-180f
                totalAngle+=delta
                val scaleIntent=abs(ln(nextSpan/intentSpan))
                var turn=if(rotating)delta else 0f
                if(!rotating){
                    // Early span change protects a pinch from angular drift. A later twist can
                    // still take over after the user holds the span steady and deliberately turns.
                    val threshold=if(zoomIntent)15f else 12f
                    if(abs(totalAngle)>=threshold && abs(totalAngle)*PI/180>scaleIntent*1.5){
                        rotating=true
                        turn=sign(totalAngle)*(abs(totalAngle)-threshold) // no threshold-sized jump
                    }else if(scaleIntent>.07f || (zoomIntent && scaleIntent>.04f)){
                        zoomIntent=true;intentSpan=nextSpan;totalAngle=0f
                    }
                }
                changed(px,py,nx,ny,nextSpan/span,turn)
                px=nx;py=ny;span=nextSpan;angle=nextAngle
            }
        }
    }
}
