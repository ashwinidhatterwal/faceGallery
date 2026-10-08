package com.mosaic.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.ImageView
import kotlin.math.min

// Native Activity/theme intentionally uses framework ImageView.
@android.annotation.SuppressLint("AppCompatCustomView")
class ZoomPhotoView(context: Context) : ImageView(context) {
    private val transform = Matrix()
    private var photo: Bitmap? = null
    private var baseScale = 1f
    private var zoom = 1f
    var rotationDegrees=0f;private set
    private var multiTouch = false
    private var pannable = false
    private var gestureWasFitted=false
    private var gestureScale=1f
    private var x = 0f
    private var y = 0f
    var onTap: () -> Unit = {}
    val canDismiss get()=photo!=null && !pannable && zoom<=1.05f && !multiTouch && !fingers.active
    private var settleAnimator: android.animation.ValueAnimator? = null
    private var zoomAnimator: android.animation.ValueAnimator? = null
    private val fingers = PhotoTransformGesture(24f * resources.displayMetrics.density) { oldX, oldY, newX, newY, ratio, turn ->
        // Keep the same image point under the moving midpoint, including during rotation.
        gestureScale*=ratio
        val anchor = unrotated(oldX, oldY)
        val scale = baseScale * zoom
        val sourceX = (anchor.first - x) / scale
        val sourceY = (anchor.second - y) / scale
        rotationDegrees = (rotationDegrees + turn) % 360f
        zoom = (zoom * ratio).coerceIn(minimumZoom(), 8f)
        val focus = unrotated(newX, newY)
        x = focus.first - sourceX * baseScale * zoom
        y = focus.second - sourceY * baseScale * zoom
        // Bounds are settled after release; clamping here would fight the fingers.
        update(false)
    }
    private var remainingX = 0f
    private var remainingY = 0f
    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { if(multiTouch)return false;performClick(); onTap(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomAnimator?.cancel(); settleAnimator?.cancel()
            val focus=unrotated(e.x,e.y)
            val from=zoom;val target=if(pannable || zoom>1.05f)minimumZoom()else 3f
            zoomAnimator=android.animation.ValueAnimator.ofFloat(from,target).apply {
                duration=220;interpolator=android.view.animation.DecelerateInterpolator()
                addUpdateListener { val next=it.animatedValue as Float;val ratio=next/zoom
                    x=focus.first-(focus.first-x)*ratio;y=focus.second-(focus.second-y)*ratio;zoom=next;update() }
                start()
            }
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (pannable && !fingers.active) {
                val angle=Math.toRadians(rotationDegrees.toDouble());val c=kotlin.math.cos(angle).toFloat();val s=kotlin.math.sin(angle).toFloat()
                x-=c*distanceX+s*distanceY;y-= -s*distanceX+c*distanceY;update()
            }
            return true
        }
    })
    init { scaleType = ScaleType.MATRIX; setBackgroundColor(GalleryStyle.canvas(context)) }
    fun rotateQuarterTurn(){
        zoomAnimator?.cancel();settleAnimator?.cancel()
        rotationDegrees=nearestQuarterTurn(rotationDegrees)+90f;zoom=1f;fit()
    }
    private fun setPhotoDrawable(bitmap:Bitmap?){
        // Matrix geometry uses pixel dimensions; BitmapDrawable must not apply screen-density scaling too.
        setImageDrawable(bitmap?.let{android.graphics.drawable.BitmapDrawable(resources,it).apply{setTargetDensity(it.density)}})
    }
    fun show(bitmap: Bitmap?) { zoomAnimator?.cancel(); settleAnimator?.cancel(); fingers.reset(); multiTouch=false; photo = bitmap; zoom=1f;rotationDegrees=0f; imageMatrix=Matrix();setPhotoDrawable(bitmap); if(bitmap==null){transform.reset();imageMatrix=transform}else fit() }
    fun upgrade(bitmap: Bitmap) {
        val old=photo
        if(old==null || width==0 || height==0){show(bitmap);return}
        val centerX=(width/2f-x)/(old.width*baseScale*zoom)
        val centerY=(height/2f-y)/(old.height*baseScale*zoom)
        photo=bitmap;setPhotoDrawable(bitmap)
        baseScale=old.width * baseScale / bitmap.width
        x=width/2f-centerX*bitmap.width*baseScale*zoom
        y=height/2f-centerY*bitmap.height*baseScale*zoom
        update(!multiTouch)
    }
    fun resetToFit() {
        zoomAnimator?.cancel(); settleAnimator?.cancel()
        if(android.os.Build.VERSION.SDK_INT>=29)animateTransform(null)
        imageMatrix=Matrix();fit()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { zoomAnimator?.cancel();settleAnimator?.cancel();fit() }
    private fun fit() {
        val bitmap = photo ?: return
        if (width == 0 || height == 0) return
        baseScale = fitScale(bitmap)
        zoom = 1f; x = (width - bitmap.width * baseScale) / 2; y = (height - bitmap.height * baseScale) / 2
        update()
    }
    private fun unrotated(px:Float,py:Float):Pair<Float,Float>{
        val angle=Math.toRadians(rotationDegrees.toDouble());val c=kotlin.math.cos(angle).toFloat();val s=kotlin.math.sin(angle).toFloat()
        val dx=px-width/2f;val dy=py-height/2f
        return (width/2f+c*dx+s*dy) to (height/2f-s*dx+c*dy)
    }
    private fun fitScale(bitmap:Bitmap):Float{
        val angle=Math.toRadians(rotationDegrees.toDouble());val c=kotlin.math.abs(kotlin.math.cos(angle));val s=kotlin.math.abs(kotlin.math.sin(angle))
        return min((width/(bitmap.width*c+bitmap.height*s)).toFloat(),(height/(bitmap.width*s+bitmap.height*c)).toFloat())
    }
    private fun minimumZoom()=photo?.let{min(1f,fitScale(it)/baseScale)}?:1f
    private fun update(constrain: Boolean = true) {
        val bitmap = photo ?: return
        val scale = baseScale * zoom
        val w = bitmap.width * scale; val h = bitmap.height * scale
        val angle=Math.toRadians(rotationDegrees.toDouble());val c=kotlin.math.cos(angle).toFloat();val s=kotlin.math.sin(angle).toFloat()
        val cx=x+w/2-width/2;val cy=y+h/2-height/2
        val maxX=((kotlin.math.abs(w*c)+kotlin.math.abs(h*s)-width)/2).coerceAtLeast(0f)
        val maxY=((kotlin.math.abs(w*s)+kotlin.math.abs(h*c)-height)/2).coerceAtLeast(0f)
        pannable=maxX>.5f || maxY>.5f
        val screenX=if(constrain)(c*cx-s*cy).coerceIn(-maxX,maxX)else c*cx-s*cy;val screenY=if(constrain)(s*cx+c*cy).coerceIn(-maxY,maxY)else s*cx+c*cy
        x=width/2f+c*screenX+s*screenY-w/2;y=height/2f-s*screenX+c*screenY-h/2
        transform.reset(); transform.postScale(scale, scale); transform.postTranslate(x, y);transform.postRotate(rotationDegrees,width/2f,height/2f)
        imageMatrix = transform
    }
    private fun settleBounds() {
        val bitmap=photo?:return
        val fromX=x;val fromY=y;val fromZoom=zoom;val fromAngle=rotationDegrees
        val targetAngle=nearestQuarterTurn(fromAngle)
        // At fit size, fit the settled orientation too. Keep magnified details magnified.
        val fitted=zoom<=minimumZoom()+.05f || (gestureWasFitted && kotlin.math.abs(kotlin.math.ln(gestureScale))<.05f)
        rotationDegrees=targetAngle
        val targetZoom=if(fitted)fitScale(bitmap)/baseScale else zoom.coerceAtLeast(minimumZoom())
        zoom=targetZoom
        update();val targetX=x;val targetY=y
        x=fromX;y=fromY;zoom=fromZoom;rotationDegrees=fromAngle;update(false)
        if(kotlin.math.abs(fromX-targetX)<.5f && kotlin.math.abs(fromY-targetY)<.5f && kotlin.math.abs(fromAngle-targetAngle)<.01f && kotlin.math.abs(fromZoom-targetZoom)<.001f){
            rotationDegrees=targetAngle;zoom=targetZoom;x=targetX;y=targetY;update();return
        }
        settleAnimator=android.animation.ValueAnimator.ofFloat(0f,1f).apply {
            duration=200;interpolator=android.view.animation.DecelerateInterpolator()
            addUpdateListener{val f=it.animatedValue as Float
                x=fromX+(targetX-fromX)*f;y=fromY+(targetY-fromY)*f
                zoom=fromZoom+(targetZoom-fromZoom)*f;rotationDegrees=fromAngle+(targetAngle-fromAngle)*f;update(false)
            }
            start()
        }
    }
    companion object {
        // Keep the equivalent unwrapped angle so crossing 0 never animates a full revolution.
        internal fun nearestQuarterTurn(angle:Float)=kotlin.math.round(angle/90f)*90f
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { multiTouch = false; zoomAnimator?.cancel();settleAnimator?.cancel();gestureWasFitted=!pannable;gestureScale=1f }
        if (event.pointerCount > 1 && !multiTouch) {
            multiTouch = true
            val cancel=MotionEvent.obtain(event);cancel.action=MotionEvent.ACTION_CANCEL
            gesture.onTouchEvent(cancel);cancel.recycle()
        }
        parent?.requestDisallowInterceptTouchEvent(multiTouch || pannable || zoom > 1.05f)
        if (photo != null) {
            fingers.onTouch(event)
            if(event.actionMasked==MotionEvent.ACTION_POINTER_UP && event.pointerCount==2){
                val remaining=if(event.actionIndex==0)1 else 0
                remainingX=event.getX(remaining);remainingY=event.getY(remaining)
            }else if(event.actionMasked==MotionEvent.ACTION_MOVE && event.pointerCount==1 && multiTouch){
                val old=unrotated(remainingX,remainingY);val next=unrotated(event.x,event.y)
                x+=next.first-old.first;y+=next.second-old.second
                remainingX=event.x;remainingY=event.y;update(false)
            }
            if (event.pointerCount == 1 && !multiTouch) gesture.onTouchEvent(event)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL){
            if(multiTouch)settleBounds()
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
    override fun onDetachedFromWindow(){zoomAnimator?.cancel(); settleAnimator?.cancel();super.onDetachedFromWindow()}
    override fun performClick(): Boolean { super.performClick(); return true }
}
