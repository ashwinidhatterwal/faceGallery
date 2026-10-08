package com.mosaic.gallery

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.min

data class FaceObservation(val left:Float,val top:Float,val right:Float,val bottom:Float,
    val yaw:Float,val pitch:Float,val roll:Float,val sharpness:Float,val score:Float,val authority:String,val landmarks:List<Float> = emptyList())

/** Conservative heuristics for later embedding selection, not detector confidence or identity probability. */
object FaceQuality {
    fun score(side:Int,sharpness:Float,yaw:Float,pitch:Float,roll:Float):Pair<Float,String>{
        val resolution=(side/180f).coerceIn(0f,1f)
        val focus=(sharpness/120f).coerceIn(0f,1f)
        val pose=(1f-maxOf(abs(yaw)/75f,abs(pitch)/60f,abs(roll)/70f)).coerceIn(0f,1f)
        val score=(0.4f*resolution+0.35f*focus+0.25f*pose).coerceIn(0f,1f)
        val authority=when{
            side<60 || sharpness<8f || abs(yaw)>65 || abs(pitch)>50 -> "Shadow"
            side>=120 && sharpness>=40f && abs(yaw)<=25 && abs(pitch)<=20 && abs(roll)<=25 && score>=0.7f -> "Anchor"
            else -> "Support"
        }
        return score to authority
    }
    fun observation(bitmap:Bitmap,box:Rect,yaw:Float,pitch:Float,roll:Float):FaceObservation?{
        val rect=Rect(box);if(!rect.intersect(0,0,bitmap.width,bitmap.height) || rect.width()<2 || rect.height()<2)return null
        val n=32;val samples=FloatArray(n*n)
        for(y in 0 until n)for(x in 0 until n){
            val pixel=bitmap.getPixel(rect.left+x*(rect.width()-1)/(n-1),rect.top+y*(rect.height()-1)/(n-1))
            samples[y*n+x]=0.299f*android.graphics.Color.red(pixel)+0.587f*android.graphics.Color.green(pixel)+0.114f*android.graphics.Color.blue(pixel)
        }
        var sum=0.0;var squares=0.0
        for(y in 1 until n-1)for(x in 1 until n-1){val i=y*n+x;val v=4*samples[i]-samples[i-1]-samples[i+1]-samples[i-n]-samples[i+n];sum+=v;squares+=v*v}
        val count=(n-2)*(n-2);val sharpness=(squares/count-(sum/count)*(sum/count)).coerceAtLeast(0.0).toFloat()
        val (quality,authority)=score(min(rect.width(),rect.height()),sharpness,yaw,pitch,roll)
        return FaceObservation(rect.left.toFloat()/bitmap.width,rect.top.toFloat()/bitmap.height,rect.right.toFloat()/bitmap.width,rect.bottom.toFloat()/bitmap.height,yaw,pitch,roll,sharpness,quality,authority)
    }
}
