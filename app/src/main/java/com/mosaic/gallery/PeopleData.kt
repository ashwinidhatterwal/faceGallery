package com.mosaic.gallery

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.*
import android.provider.MediaStore
import android.provider.ContactsContract
import java.util.concurrent.atomic.AtomicLong

/** Metadata and identity revisions; cached values never hold an Activity or bitmap. */
object PeopleData {
    private val revision=AtomicLong()
    val version get()=revision.get()
    private val main=Handler(Looper.getMainLooper())
    private val listeners=mutableSetOf<()->Unit>()
    private val notify=Runnable{listeners.toList().forEach{it()}}
    private val faceRevision=AtomicLong()
    val facesVersion get()=faceRevision.get()
    private val affected=mutableMapOf<String,Long>()
    @Synchronized fun faceVersion(uri:String)=affected[uri]?:0L
    fun facesChanged(uri:String){val value=faceRevision.incrementAndGet();synchronized(this){affected[uri]=value;if(affected.size>2048)affected.remove(affected.keys.first())};main.removeCallbacks(faceNotify);main.postDelayed(faceNotify,250)}
    private val faceListeners=mutableSetOf<()->Unit>()
    private val faceNotify=Runnable{faceListeners.toList().forEach{it()}}
    fun observeFaces(listener:()->Unit){faceListeners+=listener}
    fun removeFaces(listener:()->Unit){faceListeners-=listener}
    fun changed(){revision.incrementAndGet();main.removeCallbacks(notify);main.postDelayed(notify,250)}
    fun observe(listener:()->Unit){listeners+=listener}
    fun remove(listener:()->Unit){listeners-=listener}
    fun access(context:Context):String {
        fun granted(name:String)=context.checkSelfPermission(name)==PackageManager.PERMISSION_GRANTED
        return "${context.filesDir}:"+listOf(if(Build.VERSION.SDK_INT>=33)Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.READ_CONTACTS,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED).joinToString{granted(it).toString()}
    }
}
class PeopleCache<T> {
    private data class Entry<T>(val access:String,val revision:Long,val media:Long,val data:T)
    private var entry:Entry<T>?=null
    @Synchronized fun get(context:Context):T?=entry?.takeIf{AutoPeople.allowed(context) && it.access==PeopleData.access(context) && it.revision==PeopleData.version && it.media==GalleryData.version}?.data
    @Synchronized fun preview(context:Context):T?=entry?.takeIf{AutoPeople.allowed(context) && it.access==PeopleData.access(context) && it.media==GalleryData.version}?.data
    @Synchronized fun put(context:Context,revision:Long,data:T,media:Long=GalleryData.version){if(revision==PeopleData.version && media==GalleryData.version && AutoPeople.allowed(context))entry=Entry(PeopleData.access(context),revision,media,data)}
}
object GalleryData {
    private var result:GalleryRepository.Result?=null;private var access="";private var saved=0L
    private var generation=0L;private var valid=false
    private val loadLock=Any()
    val version get()=synchronized(this){generation}
    // No provider or disk I/O under this monitor: media observers run on the UI thread.
    @Synchronized fun invalidate(){generation++;valid=false}
    @Synchronized fun remember(context:Context,next:GalleryRepository.Result){publish(context,next)}
    private fun publish(context:Context,next:GalleryRepository.Result){
        if(access!=PeopleData.access(context) || result?.photos!=next.photos)PeopleData.changed()
        result=next;access=PeopleData.access(context);saved=SystemClock.elapsedRealtime();valid=true
    }
    fun load(context:Context,signal:CancellationSignal,force:Boolean=false):GalleryRepository.Result = synchronized(loadLock) {
        signal.throwIfCanceled()
        val token=synchronized(this){
            if(!force && valid && AutoPeople.allowed(context) && access==PeopleData.access(context) && SystemClock.elapsedRealtime()-saved<120_000)result?.let{return it}
            generation
        }
        val next=GalleryRepository(context).loadPhotos(signal);signal.throwIfCanceled()
        synchronized(this){if(token==generation)publish(context,next)}
        if(token==version)PhotoIndex.save(context,next)
        return next
    }
    @Synchronized fun peek(context:Context):GalleryRepository.Result?=result?.takeIf{valid && AutoPeople.allowed(context) && access==PeopleData.access(context)}
    // Selected-photo permissions can change while the app is away; never trust an old selection.
    fun resumed(context:Context){if(!PhotoIndex.allowed(context))invalidate()}
}
class MosaicApplication:Application() {
    override fun onLowMemory(){FaceThumbnails.clear();super.onLowMemory()}
    @Suppress("DEPRECATION") override fun onTrimMemory(level:Int){if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)FaceThumbnails.clear();super.onTrimMemory(level)}
    override fun onCreate(){super.onCreate()
        val main=Handler(Looper.getMainLooper())
        val schedule=Runnable{AutoPeople.ensure(this@MosaicApplication)}
        val media=object:ContentObserver(main){override fun onChange(selfChange:Boolean){GalleryData.invalidate();main.removeCallbacks(schedule);main.postDelayed(schedule,2_000)}}
        runCatching{contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,true,media)}
        val contacts=object:ContentObserver(main){override fun onChange(selfChange:Boolean){PeopleData.changed()}}
        runCatching{contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI,true,contacts)}
    }
}
