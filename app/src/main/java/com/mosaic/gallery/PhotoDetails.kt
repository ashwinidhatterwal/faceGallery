package com.mosaic.gallery

import android.app.Activity
import android.app.AlertDialog
import android.provider.MediaStore
import android.text.format.Formatter
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

object PhotoDetails{
    fun text(activity:Activity,photo:PhotoRecord):String{
        val date=if(photo.dateTakenMillis>0)DateFormat.getDateTimeInstance().format(Date(photo.dateTakenMillis))else "Unknown date"
        val size=if(photo.sizeBytes>=0)Formatter.formatShortFileSize(activity,photo.sizeBytes)+" (${photo.sizeBytes} bytes)"else "Unavailable"
        return "${photo.displayName}\n${photo.width} × ${photo.height}\n$date\nSize: $size\nPath: ${photo.path.ifBlank{photo.uri.toString()}}"
    }
    fun show(activity:Activity,photo:PhotoRecord){
        val dialog=AlertDialog.Builder(activity).setTitle("Photo details").setMessage("Loading details…").setPositiveButton("Close",null).show()
        val worker=Executors.newSingleThreadExecutor()
        worker.execute{
            val updated=runCatching{
                val columns=arrayOf(MediaStore.Images.Media.DISPLAY_NAME,MediaStore.Images.Media.WIDTH,MediaStore.Images.Media.HEIGHT,MediaStore.Images.Media.DATE_TAKEN,MediaStore.Images.Media.SIZE,MediaStore.Images.Media.DATA)
                activity.contentResolver.query(photo.uri,columns,null,null,null)?.use{cursor->
                    if(cursor.moveToFirst())photo.copy(displayName=cursor.getString(0).orEmpty(),width=cursor.getInt(1),height=cursor.getInt(2),dateTakenMillis=cursor.getLong(3),sizeBytes=cursor.getLong(4),path=cursor.getString(5).orEmpty())else photo
                }?:photo
            }.getOrDefault(photo)
            activity.runOnUiThread{if(!activity.isDestroyed && dialog.isShowing){dialog.setMessage(text(activity,updated));dialog.findViewById<android.widget.TextView>(android.R.id.message)?.setTextIsSelectable(true)}}
        };worker.shutdown()
    }
}
