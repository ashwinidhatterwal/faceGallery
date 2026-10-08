package com.mosaic.gallery

import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/** Transforms the live folder; its album grid remains underneath, with no screenshots or per-frame layout. */
object FolderTransition{
    fun bounds(root:View,view:View):Rect{
        val origin=IntArray(2);val point=IntArray(2);root.getLocationOnScreen(origin);view.getLocationOnScreen(point)
        return Rect(point[0]-origin[0],point[1]-origin[1],point[0]-origin[0]+view.width,point[1]-origin[1]+view.height)
    }
    fun play(host:FrameLayout,view:View,origin:Rect,opening:Boolean,finished:()->Unit):ValueAnimator{
        val w=host.width.coerceAtLeast(1);val h=host.height.coerceAtLeast(1)
        val valid=if(origin.isEmpty)Rect(w/3,h/3,w*2/3,h/3+w/3)else origin
        val small=maxOf(valid.width().toFloat()/w,valid.height().toFloat()/h).coerceIn(0.01f,1f)
        val dx=valid.exactCenterX()-w/2f;val dy=valid.exactCenterY()-h/2f
        val clip=Rect();var radius=0f
        view.pivotX=w/2f;view.pivotY=h/2f
        view.outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,outline:android.graphics.Outline){outline.setRoundRect(clip,radius)}}
        view.clipToOutline=true
        fun frame(progress:Float){
            val t=if(opening)progress else 1f-progress;val scale=small+(1f-small)*t
            view.scaleX=scale;view.scaleY=scale;view.translationX=dx*(1f-t);view.translationY=dy*(1f-t)
            val cw=valid.width()/small+(w-valid.width()/small)*t;val ch=valid.height()/small+(h-valid.height()/small)*t
            clip.set(((w-cw)/2).toInt(),((h-ch)/2).toInt(),((w+cw)/2).toInt(),((h+ch)/2).toInt())
            view.clipBounds=clip;radius=GalleryStyle.dp(view.context,16)*(1f-t)/scale;view.invalidateOutline()
            view.alpha=if(opening)1f else (t/0.18f).coerceIn(0f,1f)
        }
        frame(0f)
        return ValueAnimator.ofFloat(0f,1f).apply{
            duration=320;interpolator=android.view.animation.PathInterpolator(0.2f,0f,0f,1f)
            addUpdateListener{frame(it.animatedValue as Float)}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(animation:android.animation.Animator){view.scaleX=1f;view.scaleY=1f;view.translationX=0f;view.translationY=0f;view.clipBounds=null;view.clipToOutline=false;view.outlineProvider=ViewOutlineProvider.BACKGROUND;view.alpha=1f;finished()}});start()
        }
    }
}
