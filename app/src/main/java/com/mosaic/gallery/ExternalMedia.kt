package com.mosaic.gallery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.MediaStore
import android.provider.OpenableColumns

/** Reads the granted URI itself, including FileProvider media not present in MediaStore. */
internal object ExternalMedia {
    fun record(c:Context,uri:Uri,mimeHint:String?,signal:CancellationSignal):PhotoRecord {
        signal.throwIfCanceled()
        val mime=runCatching{c.contentResolver.getType(uri)}.getOrNull()?.takeIf{it.startsWith("image/") || it.startsWith("video/")}
            ?:mimeHint?.takeIf{it.startsWith("image/") || it.startsWith("video/")}?:"image/*"
        var record=PhotoRecord(runCatching{ContentUris.parseId(uri)}.getOrDefault(0),uri,uri.lastPathSegment.orEmpty(),0,0,0,mimeType=mime)
        fun query(fields:Array<String>){
            c.contentResolver.query(uri,fields,null,null,null,signal)?.use{cursor->if(cursor.moveToFirst()){
                fun text(key:String)=cursor.getColumnIndex(key).let{if(it>=0 && !cursor.isNull(it))cursor.getString(it).orEmpty()else ""}
                fun number(key:String)=cursor.getColumnIndex(key).let{if(it>=0 && !cursor.isNull(it))cursor.getLong(it)else 0L}
                record=record.copy(displayName=text(OpenableColumns.DISPLAY_NAME).ifBlank{record.displayName},sizeBytes=number(OpenableColumns.SIZE),
                    dateTakenMillis=number(MediaStore.Images.Media.DATE_TAKEN).takeIf{it>0}?:number(MediaStore.MediaColumns.DATE_ADDED)*1000,
                    width=number(MediaStore.MediaColumns.WIDTH).toInt(),height=number(MediaStore.MediaColumns.HEIGHT).toInt(),
                    modifiedMillis=number(MediaStore.MediaColumns.DATE_MODIFIED)*1000,album=text(MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
                    durationMillis=number(MediaStore.Video.Media.DURATION))
            }}
        }
        runCatching{query(arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE,MediaStore.Images.Media.DATE_TAKEN,MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.WIDTH,MediaStore.MediaColumns.HEIGHT,MediaStore.MediaColumns.DATE_MODIFIED,MediaStore.Images.Media.BUCKET_DISPLAY_NAME,MediaStore.Video.Media.DURATION))}
            .onFailure{signal.throwIfCanceled();runCatching{query(arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE))}}
        signal.throwIfCanceled();return record
    }
    fun includeOpened(available:List<PhotoRecord>,opened:PhotoRecord):List<PhotoRecord> =
        if(available.any{it.uri==opened.uri})available else listOf(opened)+available
}
