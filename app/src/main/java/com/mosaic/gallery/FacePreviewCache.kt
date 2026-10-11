package com.mosaic.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.File

/** Bounded, private UI crops. Recognition never uses these compressed previews. */
internal object FacePreviewCache {
    private val writes=java.util.concurrent.atomic.AtomicInteger()
    private fun directory(context:Context)=File(context.cacheDir,"face-previews")
    private fun file(context:Context,key:String):File {
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        return File(directory(context),"$digest.jpg")
    }
    fun read(context:Context,key:String):Bitmap? {
        if(!MediaAccess.cacheable(context) || !MediaAccess.photos(context))return null
        val file=file(context,key);if(file.length() !in 1L..256_000L)return null
        return runCatching{BitmapFactory.decodeFile(file.absolutePath)?.takeIf{it.width<=320 && it.height<=320}.also{file.setLastModified(System.currentTimeMillis())}}.getOrNull()
    }
    fun save(context:Context,key:String,bitmap:Bitmap){
        if(!MediaAccess.cacheable(context) || !MediaAccess.photos(context) || Thread.currentThread().isInterrupted)return
        val target=file(context,key);target.parentFile?.mkdirs();val atomic=AtomicFile(target);var stream:java.io.FileOutputStream?=null
        runCatching{stream=atomic.startWrite();check(bitmap.compress(Bitmap.CompressFormat.JPEG,88,stream!!));atomic.finishWrite(stream)}.onFailure{atomic.failWrite(stream)}
        if(writes.incrementAndGet()%32==0)prune(context)
    }
    @Synchronized private fun prune(context:Context){
        val files=directory(context).listFiles()?.filter{it.name.endsWith(".jpg")}.orEmpty()
        var bytes=files.sumOf{it.length()};var count=files.size
        if(count<=2048 && bytes<=32L*1024*1024)return
        for(file in files.sortedBy{it.lastModified()}){
            if(count<=2048 && bytes<=32L*1024*1024)break
            val length=file.length();if(file.delete()){bytes-=length;count--}
        }
    }
}
