package com.mosaic.gallery

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore

class GalleryRepository(private val context:Context) {
    data class Result(val photos:List<PhotoRecord>,val unreadableVolumes:Int)
    fun loadPhotos(signal:CancellationSignal):Result {
        val volumes=if(Build.VERSION.SDK_INT>=29)MediaStore.getExternalVolumeNames(context).sorted()else listOf("external")
        val photos=ArrayList<PhotoRecord>();var failures=0;var reads=0
        for(video in listOf(false,true)){
            if(if(video)!MediaAccess.videos(context)else !MediaAccess.photos(context))continue
            for(volume in volumes){
                signal.throwIfCanceled();reads++
                val collection=if(video){if(Build.VERSION.SDK_INT>=29)MediaStore.Video.Media.getContentUri(volume)else MediaStore.Video.Media.EXTERNAL_CONTENT_URI}
                    else {if(Build.VERSION.SDK_INT>=29)MediaStore.Images.Media.getContentUri(volume)else MediaStore.Images.Media.EXTERNAL_CONTENT_URI}
                val base=arrayOf(MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.Images.Media.DATE_TAKEN,MediaStore.MediaColumns.DATE_ADDED,MediaStore.MediaColumns.WIDTH,MediaStore.MediaColumns.HEIGHT,MediaStore.Images.Media.BUCKET_DISPLAY_NAME,MediaStore.MediaColumns.SIZE,MediaStore.MediaColumns.DATA,MediaStore.MediaColumns.DATE_MODIFIED)
                val projection=if(video)base+arrayOf(MediaStore.Video.Media.DURATION,MediaStore.MediaColumns.MIME_TYPE)else base
                runCatching{
                    checkNotNull(context.contentResolver.query(collection,projection,null,null,null,signal)).use{c->while(c.moveToNext()){
                        signal.throwIfCanceled()
                        photos+=PhotoRecord(c.getLong(0),ContentUris.withAppendedId(collection,c.getLong(0)),c.getString(1).orEmpty(),c.getLong(2).takeIf{it>0}?:c.getLong(3)*1000L,c.getInt(4),c.getInt(5),c.getString(6).orEmpty(),c.getLong(7),c.getString(8).orEmpty(),c.getLong(9)*1000L,
                            if(video)c.getString(11).orEmpty().ifBlank{"video/*"}else "image/*",if(video)c.getLong(10).coerceAtLeast(0)else 0)
                    }}
                }.onFailure{signal.throwIfCanceled();if(it is SecurityException)throw it;failures++}
            }
        }
        check(reads>0 && failures<reads){"No media storage could be read"}
        return Result(photos.distinctBy{it.uri}.sortedWith(compareByDescending<PhotoRecord>{it.dateTakenMillis}.thenBy{it.uri.toString()}),failures)
    }
}
