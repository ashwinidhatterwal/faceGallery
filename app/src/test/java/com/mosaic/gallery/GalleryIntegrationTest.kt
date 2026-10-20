package com.mosaic.gallery

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.provider.MediaStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class GalleryIntegrationTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val uri=Uri.parse("content://camera/new/17")
    private val photo=PhotoRecord(17,uri,"new.jpg",1234,800,600,mimeType="image/jpeg")
    @Test fun androidCanResolveViewerCameraGalleryAndPickerEntryPoints(){
        @Suppress("DEPRECATION") fun resolves(intent:Intent,name:String)=app.packageManager.queryIntentActivities(intent,0).any{it.activityInfo.name=="com.mosaic.gallery.$name"}
        for(type in listOf("image/jpeg","video/mp4")){
            assertTrue(resolves(Intent(Intent.ACTION_VIEW).setDataAndType(uri,type),"GalleryEntryActivity"))
            assertTrue(resolves(Intent(MediaStore.ACTION_REVIEW).setDataAndType(uri,type),"GalleryEntryActivity"))
            assertTrue(resolves(Intent("com.android.camera.action.REVIEW").setDataAndType(uri,type),"GalleryEntryActivity"))
            assertTrue(resolves(Intent(Intent.ACTION_GET_CONTENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE),"MediaPickerActivity"))
        }
        assertTrue(resolves(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY),"MainActivity"))
        assertTrue(resolves(Intent(Intent.ACTION_PICK).setType("vnd.android.cursor.dir/image"),"MediaPickerActivity"))
        // Secure review has a separate restricted contract; never advertise an unrestricted viewer for it.
        assertFalse(resolves(Intent(MediaStore.ACTION_REVIEW_SECURE).setDataAndType(uri,"image/jpeg"),"GalleryEntryActivity"))
    }
    @Test fun externalBoundaryForwardsReadGrantAndMimeButDropsPrivateExtras(){
        val incoming=Intent(MediaStore.ACTION_REVIEW).setDataAndType(uri,"video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .putExtra("peopleSearch",true).putExtra("searchPerson",99L).putExtra(MediaStore.EXTRA_BRIGHTNESS,.7f)
        val controller=Robolectric.buildActivity(GalleryEntryActivity::class.java,incoming).create()
        val target=Shadows.shadowOf(controller.get()).nextStartedActivity
        assertEquals(PhotoActivity::class.java.name,target.component?.className);assertEquals(uri,target.data);assertEquals("video/mp4",target.type)
        assertTrue(target.getBooleanExtra("cameraOnly",false));assertTrue(target.getBooleanExtra("externalMedia",false))
        assertFalse(target.hasExtra("peopleSearch"));assertFalse(target.hasExtra("searchPerson"))
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,target.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        assertEquals(uri,target.clipData?.getItemAt(0)?.uri);controller.destroy()
    }
    @Test fun externalBoundaryRejectsNetworkUris(){
        val c=Robolectric.buildActivity(GalleryEntryActivity::class.java,Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("https://example.com/private.jpg"),"image/jpeg")).create()
        assertTrue(c.get().isFinishing);assertNull(Shadows.shadowOf(c.get()).nextStartedActivity);c.destroy()
    }
    @Test fun justCapturedUriRemainsVisibleBeforeMediaStoreIndexesIt(){
        val old=photo.copy(id=16,uri=Uri.parse("content://camera/old/16"))
        assertEquals(listOf(photo,old),ExternalMedia.includeOpened(listOf(old),photo))
        assertEquals(listOf(photo),ExternalMedia.includeOpened(listOf(photo),photo))
    }
    @Test fun grantedProviderUriUsesItsOwnMetadataWithoutBroadLibraryPermission(){
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("camera",object:android.content.ContentProvider(){
            override fun onCreate()=true
            override fun getType(uri:Uri)="video/mp4"
            override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):android.database.Cursor{
                if(p!!.size>2)throw IllegalArgumentException("Openable provider only")
                return android.database.MatrixCursor(p).apply{addRow(arrayOf<Any>("capture.mp4",12345L))}
            }
            override fun insert(uri:Uri,v:android.content.ContentValues?):Uri?=null
            override fun update(uri:Uri,v:android.content.ContentValues?,s:String?,a:Array<out String>?)=0
            override fun delete(uri:Uri,s:String?,a:Array<out String>?)=0
        })
        val record=ExternalMedia.record(app,uri,"image/*",CancellationSignal())
        assertEquals(uri,record.uri);assertEquals("capture.mp4",record.displayName);assertEquals(12345L,record.sizeBytes);assertTrue(record.isVideo)
    }
    @Test fun pickerHonorsMimeFiltersAndLegacyVideoRequests(){
        val request=MediaPickerRequest.from(Intent(Intent.ACTION_GET_CONTENT).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("image/png","video/mp4")).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true))
        assertTrue(request.multiple);assertFalse(request.accepts(photo));assertTrue(request.accepts(photo.copy(mimeType="image/png")));assertTrue(request.accepts(photo.copy(mimeType="video/mp4")))
        val legacy=MediaPickerRequest.from(Intent(Intent.ACTION_PICK).setType("vnd.android.cursor.dir/video"))
        assertFalse(legacy.accepts(photo));assertTrue(legacy.accepts(photo.copy(mimeType="video/mp4")))
    }
    @Test fun pickerReturnsOnlyChosenUrisWithReadGrants(){
        val c=Robolectric.buildActivity(MediaPickerActivity::class.java,Intent(Intent.ACTION_GET_CONTENT).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)).create()
        val a=c.get();val second=photo.copy(id=18,uri=Uri.parse("content://camera/new/18"))
        a.javaClass.getDeclaredField("photos").apply{isAccessible=true}.set(a,listOf(photo,second,photo.copy(id=19,uri=Uri.parse("content://camera/new/19"))))
        @Suppress("UNCHECKED_CAST") val selected=a.javaClass.getDeclaredField("selected").apply{isAccessible=true}.get(a) as MutableSet<String>
        selected.addAll(listOf(photo.uri.toString(),second.uri.toString()));a.javaClass.getDeclaredMethod("complete").apply{isAccessible=true}.invoke(a)
        val shadow=Shadows.shadowOf(a);assertEquals(Activity.RESULT_OK,shadow.resultCode)
        val result=shadow.resultIntent;assertEquals(photo.uri,result.data);assertEquals(2,result.clipData?.itemCount)
        assertEquals(second.uri,result.clipData?.getItemAt(1)?.uri);assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,result.flags)
        c.destroy()
    }
}
