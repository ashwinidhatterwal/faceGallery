package com.mosaic.gallery

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.CancellationSignal
import android.util.Size

object GalleryMedia {
    fun thumbnail(c:Context,photo:PhotoRecord,side:Int,signal:CancellationSignal=CancellationSignal()):Bitmap {
        signal.throwIfCanceled()
        if(Build.VERSION.SDK_INT>=29){
            val fast=runCatching{c.contentResolver.loadThumbnail(photo.uri,Size(side,side),signal)}.getOrNull()
            signal.throwIfCanceled();if(fast!=null)return fast
        }
        if(!photo.isVideo)return PhotoImages.decode(c,photo.uri,side,side.toLong()*side*2)
        val retriever=MediaMetadataRetriever()
        try{
            retriever.setDataSource(c,photo.uri);signal.throwIfCanceled()
            val frame=checkNotNull(retriever.getScaledFrameAtTime(-1,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,side,side)){"Video preview unavailable"}
            if(signal.isCanceled){frame.recycle();signal.throwIfCanceled()}
            return frame
        }finally{retriever.release()}
    }
    fun time(milliseconds:Long):String {
        val seconds=(milliseconds.coerceAtLeast(0)/1000)
        return if(seconds>=3600)"%d:%02d:%02d".format(seconds/3600,seconds/60%60,seconds%60)else "%d:%02d".format(seconds/60,seconds%60)
    }
}
