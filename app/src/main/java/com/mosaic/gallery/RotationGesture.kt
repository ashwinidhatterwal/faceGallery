package com.mosaic.gallery

import android.view.MotionEvent
import kotlin.math.atan2

/** Tracks pointer identities and wraps angle changes across -180/180 degrees. */
class RotationGesture(private val changed:(Float)->Unit){
    private var first=-1;private var second=-1;private var previous=0f
    fun onTouch(event:MotionEvent){
        if(event.actionMasked==MotionEvent.ACTION_DOWN || event.actionMasked==MotionEvent.ACTION_CANCEL || event.actionMasked==MotionEvent.ACTION_UP){first=-1;second=-1}
        if(event.actionMasked==MotionEvent.ACTION_POINTER_DOWN && second<0 && event.pointerCount>=2){
            first=event.getPointerId(0);second=event.getPointerId(event.actionIndex);previous=angle(event);return
        }
        if(event.actionMasked==MotionEvent.ACTION_POINTER_UP){
            val id=event.getPointerId(event.actionIndex);if(id==first || id==second){first=-1;second=-1};return
        }
        if(event.actionMasked==MotionEvent.ACTION_MOVE && second>=0){
            if(event.findPointerIndex(first)<0 || event.findPointerIndex(second)<0)return
            val next=angle(event);val delta=((next-previous+540f)%360f)-180f;previous=next;changed(delta)
        }
    }
    private fun angle(event:MotionEvent):Float{
        val a=event.findPointerIndex(first);val b=event.findPointerIndex(second)
        return Math.toDegrees(atan2((event.getY(b)-event.getY(a)).toDouble(),(event.getX(b)-event.getX(a)).toDouble())).toFloat()
    }
}
