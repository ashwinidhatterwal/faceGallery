package com.mosaic.gallery

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

class CropPhotoView(context: Context) : View(context) {
    var crop = CropBounds()
        set(value) { field = value; invalidate() }
    var bitmap: Bitmap? = null
        set(value) { field = value; invalidate() }
    var ratio:Float?=null
    var onFinished:()->Unit={}
    var onRotationFinished:(Float)->Unit={}
    var orientationDegrees=0f
    private var rotationAnimator:android.animation.ValueAnimator?=null
    private var gestureRotation=0f
    private var rotating=false
    private var suppressCrop=false
    private val rotationGesture=RotationGesture{delta->gestureRotation+=delta;invalidate()}
    var adjustments=PhotoAdjustments()
        set(value){field=value;invalidate()}
    fun preset(value:Float?) {
        ratio=value;val photo=bitmap?:return
        if(value==null){crop=CropBounds();return}
        val normalized=value*photo.height/photo.width
        crop=if(normalized<=1f)CropBounds((1f-normalized)/2,0f,(1f+normalized)/2,1f)
            else CropBounds(0f,(1f-1f/normalized)/2,1f,(1f+1f/normalized)/2)
    }
    private val imageRect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var corner = -1
    private var downX = 0f; private var downY = 0f
    private var starting = CropBounds()
    init { setBackgroundColor(GalleryStyle.canvas(context)); contentDescription = "Crop selection; drag a corner to resize or drag inside to move" }
    override fun onDraw(canvas: Canvas) {
        val photo = bitmap ?: return
        val inset = GalleryStyle.dp(context, 24).toFloat()
        val angle=Math.toRadians(gestureRotation.toDouble())
        val boundW=photo.width*kotlin.math.abs(kotlin.math.cos(angle))+photo.height*kotlin.math.abs(kotlin.math.sin(angle))
        val boundH=photo.width*kotlin.math.abs(kotlin.math.sin(angle))+photo.height*kotlin.math.abs(kotlin.math.cos(angle))
        val rotatingScale=min((width-inset*2)/boundW,(height-inset*2)/boundH).toFloat()
        val w = photo.width * rotatingScale; val h = photo.height * rotatingScale
        canvas.save();canvas.rotate(gestureRotation,width/2f,height/2f)
        imageRect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        paint.colorFilter=ColorMatrixColorFilter(adjustments.matrix());canvas.drawBitmap(photo, null, imageRect, paint);paint.colorFilter=null
        val box = RectF(imageRect.left + w * crop.left, imageRect.top + h * crop.top,
            imageRect.left + w * crop.right, imageRect.top + h * crop.bottom)
        paint.color = 0x99000000.toInt(); paint.style = Paint.Style.FILL
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, box.top, paint)
        canvas.drawRect(imageRect.left, box.bottom, imageRect.right, imageRect.bottom, paint)
        canvas.drawRect(imageRect.left, box.top, box.left, box.bottom, paint)
        canvas.drawRect(box.right, box.top, imageRect.right, box.bottom, paint)
        paint.color = GalleryStyle.accent(context); paint.style = Paint.Style.STROKE; paint.strokeWidth = resources.displayMetrics.density * 1.5f
        canvas.drawRect(box, paint)
        paint.color = 0x77ffffff; paint.strokeWidth = resources.displayMetrics.density * .75f
        for (i in 1..2) {
            canvas.drawLine(box.left + box.width() * i / 3, box.top, box.left + box.width() * i / 3, box.bottom, paint)
            canvas.drawLine(box.left, box.top + box.height() * i / 3, box.right, box.top + box.height() * i / 3, paint)
        }
        paint.color = GalleryStyle.accent(context); paint.style = Paint.Style.STROKE; paint.strokeWidth = resources.displayMetrics.density * 3
        val length = 14 * resources.displayMetrics.density
        val handles = listOf(box.left to box.top, box.right to box.top, box.right to box.bottom, box.left to box.bottom)
        handles.forEachIndexed { index, (x,y) ->
            canvas.drawLine(x,y,x + if(index==0||index==3)length else -length,y,paint)
            canvas.drawLine(x,y,x,y + if(index==0||index==1)length else -length,paint)
        }
        paint.style = Paint.Style.FILL;canvas.restore()

    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || bitmap == null || imageRect.isEmpty) return false
        if(event.actionMasked==MotionEvent.ACTION_DOWN){rotationAnimator?.end();suppressCrop=false;gestureRotation=0f}
        rotationGesture.onTouch(event)
        if(event.actionMasked==MotionEvent.ACTION_POINTER_DOWN){rotating=true;suppressCrop=true;parent?.requestDisallowInterceptTouchEvent(true)}
        if(rotating && (event.actionMasked==MotionEvent.ACTION_POINTER_UP || event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL)){
            rotating=false
            val target=if(event.actionMasked==MotionEvent.ACTION_CANCEL)0f else QuarterTurns.nearest(orientationDegrees+gestureRotation)-orientationDegrees
            rotationAnimator=android.animation.ValueAnimator.ofFloat(gestureRotation,target).apply{
                duration=200;interpolator=android.view.animation.PathInterpolator(0.2f,0f,0f,1f)
                addUpdateListener{gestureRotation=it.animatedValue as Float;invalidate()}
                addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(animation:android.animation.Animator){gestureRotation=0f;if(kotlin.math.abs(target)>0.1f)onRotationFinished(target);invalidate()}});start()
            }
        }
        if(suppressCrop){if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL)parent?.requestDisallowInterceptTouchEvent(false);return true}
        val x = ((event.x - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)
        val y = ((event.y - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; starting = crop
                val threshold = 28 * resources.displayMetrics.density
                val corners = listOf(crop.left to crop.top, crop.right to crop.top, crop.right to crop.bottom, crop.left to crop.bottom)
                corner = corners.indexOfFirst { abs((x - it.first) * imageRect.width()) < threshold &&
                    abs((y - it.second) * imageRect.height()) < threshold }
                if (corner == -1 && !(x in crop.left..crop.right && y in crop.top..crop.bottom)) corner=-2
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if(corner==-2)return true
                val minSize = .02f
                val free = when (corner) {
                    0 -> crop.copy(left = x.coerceAtMost(crop.right - minSize), top = y.coerceAtMost(crop.bottom - minSize))
                    1 -> crop.copy(right = x.coerceAtLeast(crop.left + minSize), top = y.coerceAtMost(crop.bottom - minSize))
                    2 -> crop.copy(right = x.coerceAtLeast(crop.left + minSize), bottom = y.coerceAtLeast(crop.top + minSize))
                    3 -> crop.copy(left = x.coerceAtMost(crop.right - minSize), bottom = y.coerceAtLeast(crop.top + minSize))
                    else -> {
                        val dx = (x - downX).coerceIn(-starting.left, 1f - starting.right)
                        val dy = (y - downY).coerceIn(-starting.top, 1f - starting.bottom)
                        starting.copy(left = starting.left + dx, right = starting.right + dx,
                            top = starting.top + dy, bottom = starting.bottom + dy)
                    }
                }
                val aspect=ratio
                crop=if(aspect==null||corner<0)free else {
                    val normalized=aspect*bitmap!!.height/bitmap!!.width
                    val anchorX=if(corner==0||corner==3)starting.right else starting.left
                    val anchorY=if(corner==0||corner==1)starting.bottom else starting.top
                    val maxW=if(corner==0||corner==3)anchorX else 1f-anchorX
                    val maxH=if(corner==0||corner==1)anchorY else 1f-anchorY
                    val w=minOf(kotlin.math.abs(x-anchorX).coerceAtLeast(.02f),maxW,maxH*normalized)
                    val h=w/normalized
                    CropBounds(if(corner==0||corner==3)anchorX-w else anchorX,if(corner==0||corner==1)anchorY-h else anchorY,
                        if(corner==0||corner==3)anchorX else anchorX+w,if(corner==0||corner==1)anchorY else anchorY+h)
                }
            }
            MotionEvent.ACTION_UP -> {parent?.requestDisallowInterceptTouchEvent(false);performClick();onFinished()}
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
    override fun onDetachedFromWindow(){rotationAnimator?.removeAllListeners();rotationAnimator?.cancel();super.onDetachedFromWindow()}
    override fun performClick(): Boolean { super.performClick(); return true }
}
