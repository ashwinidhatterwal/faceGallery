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
        val distance=GalleryStyle.dp(view.context,18).toFloat()
        view.clipBounds=null;view.clipToOutline=false;view.scaleX=1f;view.scaleY=1f
        view.setBackgroundColor(GalleryStyle.canvas(view.context))
        fun frame(progress:Float){
            val t=if(opening)progress else 1f-progress
            view.alpha=t;view.translationY=distance*(1f-t)
        }
        frame(0f)
        return ValueAnimator.ofFloat(0f,1f).apply{
            duration=220;interpolator=android.view.animation.PathInterpolator(.2f,0f,0f,1f)
            addUpdateListener{frame(it.animatedValue as Float)}
            addListener(object:android.animation.AnimatorListenerAdapter(){
                private var delivered=false
                override fun onAnimationEnd(animation:android.animation.Animator){
                    if(delivered)return;delivered=true
                    // Keep closing content transparent until its owner removes it.
                    if(opening){view.alpha=1f;view.translationY=0f}
                    finished()
                }
            });start()
        }
    }
}
