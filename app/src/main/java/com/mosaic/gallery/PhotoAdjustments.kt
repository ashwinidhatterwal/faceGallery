package com.mosaic.gallery

import android.graphics.ColorMatrix

data class PhotoAdjustments(val brightness:Float=0f,val contrast:Float=1f,val saturation:Float=1f,val filter:String="Original") {
    fun matrix():ColorMatrix {
        val offset=128f*(1f-contrast)+brightness*255f
        val matrix=ColorMatrix(floatArrayOf(contrast,0f,0f,0f,offset,0f,contrast,0f,0f,offset,0f,0f,contrast,0f,offset,0f,0f,0f,1f,0f))
        matrix.postConcat(ColorMatrix().apply{setSaturation(if(filter=="B&W")0f else saturation)})
        if(filter=="Warm"||filter=="Cool"){
            val red=if(filter=="Warm")1.08f else .92f;val blue=if(filter=="Warm").92f else 1.08f
            matrix.postConcat(ColorMatrix(floatArrayOf(red,0f,0f,0f,0f,0f,1f,0f,0f,0f,0f,0f,blue,0f,0f,0f,0f,0f,1f,0f)))
        }
        return matrix
    }
}
