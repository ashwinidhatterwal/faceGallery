package com.mosaic.gallery

import android.graphics.*
import kotlin.math.sqrt

/** Least-squares similarity alignment to the conventional 112-pixel five-point face template. */
object FaceAlignment {
    val template=listOf(38.2946f,51.6963f,73.5318f,51.5014f,56.0252f,71.7366f,41.5493f,92.3655f,70.7299f,92.2041f)
    fun transform(points:List<Float>,width:Int,height:Int):FloatArray? {
        if(points.size!=10 || points.any{!it.isFinite() || it !in 0f..1f} || width<=0 || height<=0)return null
        val source=points.mapIndexed{i,v->v*if(i%2==0)width else height}
        val sx=(0..4).sumOf{source[2*it].toDouble()}/5;val sy=(0..4).sumOf{source[2*it+1].toDouble()}/5
        val tx=(0..4).sumOf{template[2*it].toDouble()}/5;val ty=(0..4).sumOf{template[2*it+1].toDouble()}/5
        var denominator=0.0;var dot=0.0;var cross=0.0
        for(i in 0..4){val x=source[2*i]-sx;val y=source[2*i+1]-sy;val u=template[2*i]-tx;val v=template[2*i+1]-ty;denominator+=x*x+y*y;dot+=x*u+y*v;cross+=x*v-y*u}
        if(denominator<1e-6)return null
        val a=dot/denominator;val b=cross/denominator;var residual=0.0
        for(i in 0..4){val x=source[2*i]-sx;val y=source[2*i+1]-sy;val dx=a*x-b*y+tx-template[2*i];val dy=b*x+a*y+ty-template[2*i+1];residual+=dx*dx+dy*dy}
        if(sqrt(residual/5)>8.0 || a*a+b*b<1e-10)return null
        return floatArrayOf(a.toFloat(),(-b).toFloat(),(tx-a*sx+b*sy).toFloat(),b.toFloat(),a.toFloat(),(ty-b*sx-a*sy).toFloat(),0f,0f,1f)
    }
    fun crop(bitmap:Bitmap,face:FaceObservation):Bitmap? {
        val values=transform(face.landmarks,bitmap.width,bitmap.height)?:return null
        val matrix=Matrix().apply{setValues(values)};val inverse=Matrix();if(!matrix.invert(inverse))return null
        // Avoid inventing padding for a face cut off at an image edge.
        val corners=floatArrayOf(0f,0f,111f,0f,0f,111f,111f,111f);inverse.mapPoints(corners)
        if(corners.indices.any{corners[it]<-.01f || corners[it]>(if(it%2==0)bitmap.width-1f else bitmap.height-1f)+.01f})return null
        return Bitmap.createBitmap(112,112,Bitmap.Config.ARGB_8888).also{Canvas(it).drawBitmap(bitmap,matrix,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))}
    }
    fun overlap(a:FaceObservation,b:FaceObservation):Float {
        val intersection=(minOf(a.right,b.right)-maxOf(a.left,b.left)).coerceAtLeast(0f)*(minOf(a.bottom,b.bottom)-maxOf(a.top,b.top)).coerceAtLeast(0f)
        val union=(a.right-a.left)*(a.bottom-a.top)+(b.right-b.left)*(b.bottom-b.top)-intersection
        return if(union>0)intersection/union else 0f
    }
    private fun uniqueBest(scores:List<Float>):Int? {
        val ranked=scores.indices.sortedByDescending{scores[it]}
        val best=ranked.firstOrNull()?:return null
        return if(ranked.size>1 && scores[best]-scores[ranked[1]]<.05f)null else best
    }
    /** Mutual unique best matches avoid attaching a fresh landmark set to the wrong stored face. */
    fun recover(stored:List<FaceObservation>,fresh:List<FaceObservation>):List<FaceObservation> = stored.mapIndexed{index,old->
        if(old.landmarks.size==10)old else {
            val best=uniqueBest(fresh.map{overlap(old,it)})
            val reverse=best?.let{candidate->uniqueBest(stored.map{overlap(it,fresh[candidate])})}
            if(best!=null && reverse==index && overlap(old,fresh[best])>=.6f)old.copy(landmarks=fresh[best].landmarks)else old
        }
    }
}
