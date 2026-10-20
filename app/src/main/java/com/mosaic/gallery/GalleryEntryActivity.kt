package com.mosaic.gallery

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore

/** Narrow exported boundary. External callers cannot inject internal viewer/search extras. */
class GalleryEntryActivity:Activity(){
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val request=intent;val uri=request.data
        val review=request.action in setOf(MediaStore.ACTION_REVIEW,"com.android.camera.action.REVIEW")
        if(request.action !in setOf(Intent.ACTION_VIEW,MediaStore.ACTION_REVIEW,"com.android.camera.action.REVIEW") || uri==null || uri.scheme !in setOf("content","file")){
            finish();return
        }
        val target=Intent(this,PhotoActivity::class.java).setDataAndType(uri,request.type)
            .putExtra("externalMedia",true).putExtra("cameraOnly",review)
        target.clipData=ClipData.newRawUri("Media",uri)
        target.addFlags(request.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if(review){
            val brightness=request.getFloatExtra(MediaStore.EXTRA_BRIGHTNESS,-1f)
            if(brightness.isFinite() && brightness in 0f..1f)target.putExtra("reviewBrightness",brightness)
        }
        runCatching{startActivity(target)}
        finish()
    }
}
