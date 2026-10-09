package com.mosaic.gallery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatSeekBar

/** Animate only the timeline, extrapolating between player position samples. */
class VideoSeekBar(context:Context):AppCompatSeekBar(context) {
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeCap=Paint.Cap.ROUND}
    private var position=0L;private var duration=0L;private var sampled=0L
    private var playing=false;private var speed=1f;private var tracking=false
    var cancelled=false;private set
    init {max=10000;splitTrack=false;setPadding(dp(12),0,dp(12),0);contentDescription="Video position"}
    private fun dp(value:Int)=GalleryStyle.dp(context,value)
    fun playback(position:Long,duration:Long,buffered:Long,playing:Boolean,speed:Float=1f){
        this.position=position;this.duration=duration;this.playing=playing;this.speed=speed;sampled=SystemClock.uptimeMillis()
        if(!tracking && duration>0){progress=(position*max/duration).toInt().coerceIn(0,max);secondaryProgress=(buffered*max/duration).toInt().coerceIn(0,max)}
        invalidate()
    }
    override fun onDraw(canvas:Canvas){
        val left=paddingLeft.toFloat();val right=(width-paddingRight).toFloat();val y=height/2f
        val now=position+if(playing)(SystemClock.uptimeMillis()-sampled)*speed else 0f
        val fraction=if(tracking || duration<=0)progress/max.toFloat()else (now/duration).coerceIn(0f,1f)
        ink.strokeWidth=dp(3).toFloat();ink.color=0x55ffffff;canvas.drawLine(left,y,right,y,ink)
        ink.color=0x88ffffff.toInt();canvas.drawLine(left,y,left+(right-left)*secondaryProgress/max,y,ink)
        ink.color=Color.WHITE;canvas.drawLine(left,y,left+(right-left)*fraction,y,ink)
        canvas.drawCircle(left+(right-left)*fraction,y,dp(if(tracking)6 else 4).toFloat(),ink)
        if(playing && !tracking && isShown && isAttachedToWindow)postInvalidateOnAnimation()
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN){cancelled=false;tracking=true;parent?.requestDisallowInterceptTouchEvent(true)}
        if(event.actionMasked==MotionEvent.ACTION_CANCEL)cancelled=true
        val handled=super.onTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL){tracking=false;parent?.requestDisallowInterceptTouchEvent(false);invalidate()}
        return handled
    }
}
