package com.mosaic.gallery

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object PhotoImages {
    fun decode(context: Context, uri: Uri, maxSide: Int = 2048, pixelBudget: Long = Long.MAX_VALUE): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val size = info.size
            val scale = minOf(1.0, maxSide.toDouble() / maxOf(size.width, size.height),
                kotlin.math.sqrt(pixelBudget.toDouble() / (size.width.toLong() * size.height)))
            decoder.setTargetSize(maxOf(1, (size.width * scale).toInt()), maxOf(1, (size.height * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    fun rotate(bitmap: Bitmap, turns: Int):Bitmap=rotateDegrees(bitmap,90f*(turns%4))
    fun rotateDegrees(bitmap:Bitmap,degrees:Float):Bitmap=if(kotlin.math.abs(degrees%360f)<0.01f)bitmap else
        Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,Matrix().apply{postRotate(degrees)},true).apply{setHasAlpha(bitmap.hasAlpha())}

    fun renderCopy(context:Context,source:Uri,degrees:Float,crop:CropBounds,adjustments:PhotoAdjustments):Bitmap{
        val budget=(Runtime.getRuntime().maxMemory()/32).coerceIn(1_000_000L,16_000_000L)
        var decoded:Bitmap?=null;var rotated:Bitmap?=null;var output:Bitmap?=null;var success=false
        try{
            decoded=decode(context,source,8192,budget);rotated=rotateDegrees(decoded,degrees)
            val region=crop.pixelRect(rotated.width,rotated.height)
            output=Bitmap.createBitmap(rotated,region.left,region.top,region.width(),region.height())
            if(adjustments!=PhotoAdjustments()){
                val cropped=output
                output=Bitmap.createBitmap(cropped.width,cropped.height,Bitmap.Config.ARGB_8888).apply{setHasAlpha(cropped.hasAlpha())}
                android.graphics.Canvas(output).drawBitmap(cropped,0f,0f,android.graphics.Paint().apply{colorFilter=android.graphics.ColorMatrixColorFilter(adjustments.matrix())})
                if(cropped!==decoded && cropped!==rotated)cropped.recycle()
            }
            success=true;return output
        }finally{listOfNotNull(decoded,rotated,output).distinct().filter{!success || it!==output}.forEach{it.recycle()}}
    }
    fun resized(bitmap:Bitmap,percent:Int):Bitmap{
        require(percent in 20..100)
        return if(percent==100)bitmap else Bitmap.createScaledBitmap(bitmap,maxOf(1,kotlin.math.round(bitmap.width*percent/100f).toInt()),maxOf(1,kotlin.math.round(bitmap.height*percent/100f).toInt()),true)
    }
    fun encodedSize(bitmap:Bitmap,quality:Int):Long{
        var bytes=0L
        val counter=object:java.io.OutputStream(){override fun write(value:Int){bytes++};override fun write(data:ByteArray,offset:Int,length:Int){bytes+=length}}
        check(bitmap.compress(if(bitmap.hasAlpha())Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,quality.coerceIn(10,100),counter))
        return bytes
    }
    fun saveCopy(context: Context, source: Uri, name: String, degrees: Float, crop: CropBounds, adjustments: PhotoAdjustments = PhotoAdjustments(), percent:Int=100,quality:Int=95): Pair<Uri, String> {
        var rendered: Bitmap? = null; var output: Bitmap? = null
        var destination: Uri? = null
        try {
            rendered=renderCopy(context,source,degrees,crop,adjustments)
            output=resized(rendered,percent)
            val png = output.hasAlpha()
            val extension = if (png) "png" else "jpg"
            val values = ContentValues().apply {
                val safeName = name.substringBeforeLast('.').replace('/', '_').replace('\\', '_').take(80).ifBlank { "Photo" }
                put(MediaStore.Images.Media.DISPLAY_NAME, "${safeName}_edit_${System.currentTimeMillis()}.$extension")
                put(MediaStore.Images.Media.MIME_TYPE, if (png) "image/png" else "image/jpeg")
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                put(MediaStore.Images.Media.WIDTH, output.width); put(MediaStore.Images.Media.HEIGHT, output.height)
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Mosaic")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                } else {
                    @Suppress("DEPRECATION")
                    val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Mosaic")
                    check(folder.isDirectory || folder.mkdirs()) { "Could not create the Mosaic folder" }
                    @Suppress("DEPRECATION")
                    put(MediaStore.Images.Media.DATA, File(folder, getAsString(MediaStore.Images.Media.DISPLAY_NAME)).absolutePath)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= 29)
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            destination = checkNotNull(context.contentResolver.insert(collection, values))
            checkNotNull(context.contentResolver.openOutputStream(destination)).use {
                check(output.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality.coerceIn(10,100), it))
            }
            if (Build.VERSION.SDK_INT >= 29) check(context.contentResolver.update(destination,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
            return destination to "${output.width} × ${output.height}"
        } catch (error: Exception) {
            destination?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            throw error
        } finally {
            listOfNotNull(rendered, output).distinct().forEach { it.recycle() }
        }
    }
}
