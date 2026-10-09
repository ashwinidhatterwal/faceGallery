package com.mosaic.gallery

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore

class GalleryRepository(private val context:Context) {
    data class Result(val photos:List<PhotoRecord>,val unreadableVolumes:Int)
    fun loadPhotos(signal:CancellationSignal):Result {
        val mounted=if(Build.VERSION.SDK_INT>=29)MediaStore.getExternalVolumeNames(context).sorted()else listOf("external")
        val unknownVolumes=mounted.isEmpty()
        val volumes=mounted.ifEmpty{listOf(MediaStore.VOLUME_EXTERNAL_PRIMARY)}
        val photos=ArrayList<PhotoRecord>();var failures=0;var reads=0
        for(video in listOf(false,true)){
            if(if(video)!MediaAccess.videos(context)else !MediaAccess.photos(context))continue
            for(volume in volumes){
                signal.throwIfCanceled();reads++
                val collection=if(video){if(Build.VERSION.SDK_INT>=29)MediaStore.Video.Media.getContentUri(volume)else MediaStore.Video.Media.EXTERNAL_CONTENT_URI}
                    else {if(Build.VERSION.SDK_INT>=29)MediaStore.Images.Media.getContentUri(volume)else MediaStore.Images.Media.EXTERNAL_CONTENT_URI}
                val base=arrayOf(MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.Images.Media.DATE_TAKEN,MediaStore.MediaColumns.DATE_ADDED,MediaStore.MediaColumns.WIDTH,MediaStore.MediaColumns.HEIGHT,MediaStore.Images.Media.BUCKET_DISPLAY_NAME,MediaStore.MediaColumns.SIZE,MediaStore.MediaColumns.DATA,MediaStore.MediaColumns.DATE_MODIFIED)
                val projection=if(video)base+arrayOf(MediaStore.Video.Media.DURATION,MediaStore.MediaColumns.MIME_TYPE)else base
                fun query(fields:Array<String>){
                    checkNotNull(context.contentResolver.query(collection,fields,null,null,null,signal)).use{c->
                        fun number(name:String):Long=c.getColumnIndex(name).let{if(it>=0 && !c.isNull(it))c.getLong(it)else 0}
                        fun text(name:String):String=c.getColumnIndex(name).let{if(it>=0 && !c.isNull(it))c.getString(it).orEmpty()else ""}
                        while(c.moveToNext()){
                            signal.throwIfCanceled()
                            photos+=PhotoRecord(number(MediaStore.MediaColumns._ID),ContentUris.withAppendedId(collection,number(MediaStore.MediaColumns._ID)),text(MediaStore.MediaColumns.DISPLAY_NAME),number(MediaStore.Images.Media.DATE_TAKEN).takeIf{it>0}?:number(MediaStore.MediaColumns.DATE_ADDED)*1000L,number(MediaStore.MediaColumns.WIDTH).toInt(),number(MediaStore.MediaColumns.HEIGHT).toInt(),text(MediaStore.Images.Media.BUCKET_DISPLAY_NAME),number(MediaStore.MediaColumns.SIZE),text(MediaStore.MediaColumns.DATA),number(MediaStore.MediaColumns.DATE_MODIFIED)*1000L,
                                if(video)text(MediaStore.MediaColumns.MIME_TYPE).ifBlank{"video/*"}else "image/*",if(video)number(MediaStore.Video.Media.DURATION).coerceAtLeast(0)else 0)
                        }
                    }
                }
                val start=photos.size
                runCatching{query(projection)}.onFailure{e->
                    signal.throwIfCanceled();while(photos.size>start)photos.removeAt(photos.lastIndex)
                    // Video metadata may fall back; incomplete image metadata must never replace saved face fingerprints.
                    val basic=arrayOf(MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.DATE_ADDED)
                    if(!video || e is SecurityException || runCatching{query(basic)}.isFailure){signal.throwIfCanceled();while(photos.size>start)photos.removeAt(photos.lastIndex);failures++}
                }
            }
        }
        check(reads>0 && failures<reads){"No media storage could be read"}
        return Result(photos.distinctBy{it.uri}.sortedWith(compareByDescending<PhotoRecord>{it.dateTakenMillis}.thenBy{it.uri.toString()}),failures+if(unknownVolumes)1 else 0)
    }
}
