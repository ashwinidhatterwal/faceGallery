package com.mosaic.gallery

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.ProgressBar
import androidx.recyclerview.widget.RecyclerView
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28])
class PhotoIndexTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val photo=PhotoRecord(1,Uri.parse("content://media/external/images/media/1"),"example.jpg",123,400,300,"Camera",123456,"/storage/DCIM/Camera/example.jpg")
    @Before fun start(){RecognitionConsent.accept(app);shadowOf(app).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE);PhotoIndex.clear(app)}
    @After fun end(){PhotoIndex.clear(app);shadowOf(app).denyPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)}
    @Test fun diskIndexRestoresAllMetadataAfterMemoryLoss(){
        val result=GalleryRepository.Result(listOf(photo),0);PhotoIndex.save(app,result);PhotoIndex.forgetMemory()
        assertEquals(result,PhotoIndex.read(app));assertEquals(result,PhotoIndex.current(app))
    }
    @Test fun corruptIndexFallsBackToFreshLoad(){File(app.filesDir,"photo-index.bin").writeBytes(byteArrayOf(1,2,3));assertNull(PhotoIndex.read(app))}
    @Test fun permissionLossNeverPublishesSavedFullIndex(){
        PhotoIndex.save(app,GalleryRepository.Result(listOf(photo),0));shadowOf(app).denyPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        assertNull(PhotoIndex.current(app));assertNull(PhotoIndex.read(app));assertFalse(File(app.filesDir,"photo-index.bin").exists())
    }
    @Test @Config(sdk=[35]) fun selectedPhotoAccessCannotReuseFullLibraryIndex(){
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        PhotoIndex.save(app,GalleryRepository.Result(listOf(photo),0))
        shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertNull(PhotoIndex.current(app));assertNull(PhotoIndex.read(app))
    }
    @Test fun emptyGalleryIsAValidSavedIndex(){
        val result=GalleryRepository.Result(emptyList(),0);PhotoIndex.save(app,result);PhotoIndex.forgetMemory();assertEquals(result,PhotoIndex.read(app))
    }
    @Test fun disconnectedStorageCannotOverwriteCompleteDiskIndex(){
        val result=GalleryRepository.Result(listOf(photo),0);PhotoIndex.save(app,result)
        PhotoIndex.save(app,GalleryRepository.Result(emptyList(),1));PhotoIndex.forgetMemory();assertEquals(result,PhotoIndex.read(app))
    }
    @Test fun startupDisplaysSavedGalleryWhileMediaQueryIsStillBlocked(){
        PhotoIndex.save(app,GalleryRepository.Result(listOf(photo),0));PhotoIndex.forgetMemory()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val provider=object:ContentProvider(){
            override fun onCreate()=true
            override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?{entered.countDown();release.await(5,TimeUnit.SECONDS);return null}
            override fun getType(uri:Uri):String?=null
            override fun insert(uri:Uri,values:ContentValues?):Uri?=null
            override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=0
            override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
        }
        ShadowContentResolver.registerProviderInternal("media",provider)
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        try{
            assertTrue(entered.await(5,TimeUnit.SECONDS));shadowOf(Looper.getMainLooper()).idle()
            fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(activity)
            assertEquals(listOf(photo),field("allPhotos"));assertTrue((field("grid") as RecyclerView).adapter!!.itemCount>0)
            assertEquals(View.GONE,(field("progress") as ProgressBar).visibility)
        }finally{release.countDown();controller.pause().stop().destroy()}
    }
}
