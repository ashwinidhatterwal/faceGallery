package com.mosaic.gallery

import android.Manifest
import android.app.Activity
import android.content.*
import android.database.*
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.*
import android.util.LruCache
import android.view.View
import android.widget.FrameLayout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ReuseEfficiencyTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val face=FaceObservation(.1f,.1f,.4f,.5f,0f,0f,0f,120f,.95f,"Anchor")
    private val photo=PhotoRecord(1,Uri.parse("content://reuse/1"),"face.jpg",120000,400,300)
    @Before fun reset(){Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE);FaceThumbnails.clear();GalleryData.invalidate();PeopleData.changed();app.deleteDatabase("faces.db")}
    @Test fun simultaneousFaceRequestsDecodeOnlyOnce(){
        val jobs=Executors.newFixedThreadPool(2);val started=CountDownLatch(1);val release=CountDownLatch(1);val calls=AtomicInteger()
        fun decode():Bitmap{calls.incrementAndGet();started.countDown();check(release.await(5,TimeUnit.SECONDS));return Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888)}
        try{
            val first=jobs.submit<Bitmap?>{FaceThumbnails.load(app,photo,face,::decode)};assertTrue(started.await(5,TimeUnit.SECONDS))
            val second=jobs.submit<Bitmap?>{FaceThumbnails.load(app,photo,face,::decode)};release.countDown()
            val a=first.get(5,TimeUnit.SECONDS);assertNotNull(a);assertSame(a,second.get(5,TimeUnit.SECONDS));assertEquals(1,calls.get())
        }finally{release.countDown();jobs.shutdownNow()}
    }
    @Test fun memoryTrimCannotRepublishAnInflightBitmap(){
        val bitmap=FaceThumbnails.load(app,photo,face){FaceThumbnails.clear();Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888)}
        assertNull(bitmap);assertNull(FaceThumbnails.get(app,photo,face))
    }
    @Test fun oldMediaReadCannotBePublishedAsNewCacheData(){
        val cache=PeopleCache<String>();val media=GalleryData.version;GalleryData.invalidate();cache.put(app,PeopleData.version,"old",media);assertNull(cache.get(app))
        cache.put(app,PeopleData.version,"new",GalleryData.version);assertEquals("new",cache.get(app))
    }
    @Test fun concurrentColdGalleryReadsShareOneProviderQuery(){
        val jobs=Executors.newFixedThreadPool(2);val started=CountDownLatch(1);val release=CountDownLatch(1);val queries=AtomicInteger()
        ShadowContentResolver.registerProviderInternal("media",object:ContentProvider(){
            override fun onCreate()=true
            override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):Cursor{
                if("video" in uri.pathSegments)return android.database.MatrixCursor(p!!)
                queries.incrementAndGet();started.countDown();check(release.await(5,TimeUnit.SECONDS))
                return MatrixCursor(p!!).apply{addRow(arrayOf<Any>(1,"1.jpg",120000,120,400,300,"Camera",1000,"",1))}
            }
            override fun getType(uri:Uri)="image/jpeg"
            override fun insert(uri:Uri,v:ContentValues?):Uri?=null
            override fun delete(uri:Uri,s:String?,a:Array<out String>?)=0
            override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
        })
        try{
            val first=jobs.submit<GalleryRepository.Result>{GalleryData.load(app,CancellationSignal())};assertTrue(started.await(5,TimeUnit.SECONDS))
            val second=jobs.submit<GalleryRepository.Result>{GalleryData.load(app,CancellationSignal())};release.countDown()
            assertSame(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS));assertEquals(1,queries.get())
            GalleryData.invalidate();GalleryData.load(app,CancellationSignal());assertEquals(2,queries.get())
        }finally{release.countDown();jobs.shutdownNow()}
    }
    @Test fun gridReusesUnchangedThumbnailsAndRejectsChangedPhoto(){
        val c=Robolectric.buildActivity(Activity::class.java).setup();val adapter=PhotoGridAdapter(c.get(),{},{});val parent=FrameLayout(c.get())
        try{
            adapter.submitList(listOf(photo));val holder=adapter.onCreateViewHolder(parent,adapter.getItemViewType(1))
            val method=PhotoGridAdapter::class.java.getDeclaredMethod("imageKey",PhotoRecord::class.java).apply{isAccessible=true}
            val cache=PhotoGridAdapter::class.java.getDeclaredField("cache").apply{isAccessible=true}.get(adapter) as LruCache<String,Bitmap>
            val bitmap=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);cache.put(method.invoke(adapter,photo) as String,bitmap)
            adapter.onBindViewHolder(holder,1);assertSame(bitmap,(holder.image!!.drawable as BitmapDrawable).bitmap)
            adapter.submitList(listOf(photo,photo.copy(id=2,uri=Uri.parse("content://reuse/2"))))
            adapter.onBindViewHolder(holder,1);assertSame(bitmap,(holder.image!!.drawable as BitmapDrawable).bitmap)
            adapter.submitList(listOf(photo.copy(modifiedMillis=55)));adapter.onBindViewHolder(holder,1);assertNull((holder.image!!.drawable as? BitmapDrawable)?.bitmap)
        }finally{adapter.close();c.pause().stop().destroy()}
    }
    @Test fun reopeningFacePanelUsesResultsWithoutASecondGalleryRead(){
        FaceStore(app).use{it.save(photo,listOf(face))};GalleryData.remember(app,GalleryRepository.Result(listOf(photo),0))
        val c=Robolectric.buildActivity(Activity::class.java).setup();val names=PeopleNames(c.get());val host=FrameLayout(c.get());val sheet=PhotoPeopleSheet(c.get(),names,host)
        val bitmap=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888)
        fun caption()=PhotoPeopleSheet::class.java.getDeclaredField("caption").apply{isAccessible=true}.get(sheet) as android.widget.TextView
        fun idle(){val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(caption().text.toString().isEmpty() && System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.yield()};assertEquals("Tap a face. Hold to correct.",caption().text.toString())}
        try{
            sheet.show(photo,bitmap);idle();sheet.dismiss()
            // An immediate caption proves the worker's completed metadata is reused on reopen.
            sheet.show(photo,bitmap);assertEquals("Tap a face. Hold to correct.",caption().text.toString());assertFalse(bitmap.isRecycled)
        }finally{sheet.close();names.close();bitmap.recycle();c.pause().stop().destroy()}
    }
}
