package com.mosaic.gallery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Gallery access and recognition access are deliberately separate. */
object MediaAccess {
    private fun granted(c:Context,p:String)=c.checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED
    fun fullPhotos(c:Context)=granted(c,if(Build.VERSION.SDK_INT>=33)Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
    fun fullVideos(c:Context)=granted(c,if(Build.VERSION.SDK_INT>=33)Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE)
    fun selected(c:Context)=Build.VERSION.SDK_INT>=34 && granted(c,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    fun photos(c:Context)=fullPhotos(c)||selected(c)
    fun videos(c:Context)=fullVideos(c)||selected(c)
    fun allowed(c:Context)=photos(c)||videos(c)
    fun scope(c:Context)="${fullPhotos(c)}:${fullVideos(c)}:${selected(c)}"
    fun cacheable(c:Context)=allowed(c) && (!selected(c) || (fullPhotos(c)&&fullVideos(c)))
    fun permissions()=if(Build.VERSION.SDK_INT>=34)arrayOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)else if(Build.VERSION.SDK_INT>=33)arrayOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO)else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}
