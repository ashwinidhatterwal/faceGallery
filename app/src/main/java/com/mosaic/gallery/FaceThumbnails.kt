package com.mosaic.gallery

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache

/** Small cropped faces, shared by screens. Views borrow them; eviction relies on normal bitmap ownership. */
internal object FaceThumbnails {
    private val cache=object:LruCache<String,Bitmap>(6*1024*1024){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount}
    private val decodeLocks=Array(16){Any()}
    private val generation=java.util.concurrent.atomic.AtomicLong()
    private fun key(context:Context,photo:PhotoRecord,face:FaceObservation)=PeopleData.access(context)+":"+GalleryData.version+":"+photo.uri+":"+FaceStore.fingerprint(photo)+":"+face
    fun get(context:Context,photo:PhotoRecord,face:FaceObservation):Bitmap?=if(AutoPeople.allowed(context))cache.get(key(context,photo,face))else null
    fun load(context:Context,photo:PhotoRecord,face:FaceObservation,decode:()->Bitmap?={PhotoImages.decode(context,photo.uri,480,250_000)}):Bitmap? {
        if(!AutoPeople.allowed(context))return null
        val stamp=key(context,photo,face);cache.get(stamp)?.let{return it}
        synchronized(decodeLocks[(stamp.hashCode() and Int.MAX_VALUE)%decodeLocks.size]){
            if(Thread.currentThread().isInterrupted || !AutoPeople.allowed(context) || stamp!=key(context,photo,face))return null
            cache.get(stamp)?.let{return it}
            val token=generation.get();val source=decode()?:return null
            val bitmap=try{FaceCrop.thumbnail(source,face)}finally{source.recycle()}
            synchronized(cache){
                if(token!=generation.get() || !AutoPeople.allowed(context) || stamp!=key(context,photo,face)){bitmap.recycle();return null}
                cache.put(stamp,bitmap)
            }
            return bitmap
        }
    }
    fun clear(){synchronized(cache){generation.incrementAndGet();cache.evictAll()}}
    fun full(face:FaceObservation)=face.copy(left=0f,top=0f,right=1f,bottom=1f)
}
