package com.mosaic.gallery

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PeopleFolderActionsTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val photo=PhotoRecord(1,Uri.parse("content://folder/1"),"one.jpg",120000,400,300,"Camera")
    private fun field(a:Any,n:String)=a.javaClass.getDeclaredField(n).apply{isAccessible=true}
    private fun find(v:View,label:String):View? {if(v.contentDescription==label)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),label)?.let{return it};return null}
    private fun invoke(a:Any,n:String,vararg args:Any){val m=a.javaClass.declaredMethods.first{it.name==n && it.parameterCount==args.size};m.isAccessible=true;m.invoke(a,*args)}
    @Before fun reset(){app.deleteDatabase("faces.db");FaceJobs.publish(FaceJobs.State());GalleryData.invalidate();Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES);GalleryData.remember(app,GalleryRepository.Result(listOf(photo),0))}
    @After fun clean(){app.deleteDatabase("faces.db")}
    @Test fun unchangedGridSkipsUpdatesButDayAndAlbumChangesStillRefresh(){
        val adapter=PhotoGridAdapter(app,{},{})
        var changes=0;adapter.registerAdapterDataObserver(object:androidx.recyclerview.widget.RecyclerView.AdapterDataObserver(){override fun onChanged(){changes++}})
        adapter.submitList(listOf(photo),false);adapter.submitList(listOf(photo),false);assertEquals(1,changes)
        field(adapter,"labelsDay").setLong(adapter,Long.MIN_VALUE);adapter.submitList(listOf(photo),false);assertEquals(1,changes);assertNotEquals(Long.MIN_VALUE,field(adapter,"labelsDay").getLong(adapter))
        adapter.submitAlbums(emptyList());adapter.submitList(emptyList(),false);assertEquals(0,adapter.itemCount);adapter.close()
    }
    @Test fun groupPlaylistInvalidatesForGraphMediaAndPermissionChanges(){
        val token=GroupPhotoPlaylist.remember(app,listOf(photo));assertEquals(listOf(photo),GroupPhotoPlaylist.read(app,token));assertNull(GroupPhotoPlaylist.read(app,"wrong"))
        PeopleData.changed();assertNull(GroupPhotoPlaylist.read(app,token))
        val second=GroupPhotoPlaylist.remember(app,listOf(photo));GalleryData.invalidate();assertNull(GroupPhotoPlaylist.read(app,second))
        val third=GroupPhotoPlaylist.remember(app,listOf(photo));Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES);assertNull(GroupPhotoPlaylist.read(app,third))
    }
    @Test fun folderSelectionSharesOnlyChosenUrisAndBackExitsSelection(){
        val c=Robolectric.buildActivity(PeopleActivity::class.java,Intent(app,PeopleActivity::class.java).putExtra("person",1L)).create()
        val a=c.get();field(a,"folderPhotos").set(a,listOf(photo,photo.copy(id=2,uri=Uri.parse("content://folder/2"))))
        field(a,"selectingPhotos").setBoolean(a,true);invoke(a,"togglePhoto",photo)
        assertEquals(setOf(photo.uri.toString()),field(a,"selectedPhotos").get(a));assertTrue((field(a,"fullPhotos").get(a) as PhotoGridAdapter).selectionMode)
        find(a.window.decorView,"Share")!!.performClick();val chooser=Shadows.shadowOf(a).nextStartedActivity
        @Suppress("DEPRECATION") val share=chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        @Suppress("DEPRECATION") val streams=share.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        assertEquals(listOf(photo.uri),streams);assertEquals("image/*",share.type);assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION !=0)
        find(a.window.decorView,"Back")!!.performClick();assertFalse(a.isFinishing);assertFalse(field(a,"selectingPhotos").getBoolean(a));c.destroy()
    }
    @Test fun transientFailureKeepsVisibleResultsAndCancellationDoesNotShowPermissionError(){
        val c=Robolectric.buildActivity(PeopleActivity::class.java).create();val a=c.get()
        field(a,"loaded").setBoolean(a,true);val caption=field(a,"caption").get(a) as TextView;caption.text="Existing profiles"
        invoke(a,"handleReadFailure",IllegalStateException("temporary"));assertEquals("Existing profiles",caption.text)
        invoke(a,"handleReadFailure",android.os.OperationCanceledException());assertEquals("Existing profiles",caption.text)
        repeat(5){invoke(a,"handleReadFailure",IllegalStateException("temporary"))};assertEquals(3,field(a,"readFailures").getInt(a));c.destroy()
    }
    @Test fun revokedPermissionClearsFolderAndSelection(){
        val c=Robolectric.buildActivity(PeopleActivity::class.java,Intent(app,PeopleActivity::class.java).putExtra("person",1L)).create();val a=c.get()
        field(a,"folderPhotos").set(a,listOf(photo));field(a,"selectingPhotos").setBoolean(a,true);invoke(a,"togglePhoto",photo)
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES);invoke(a,"handleReadFailure",SecurityException())
        assertTrue((field(a,"folderPhotos").get(a) as List<*>).isEmpty());assertTrue((field(a,"selectedPhotos").get(a) as Set<*>).isEmpty());assertFalse(field(a,"loaded").getBoolean(a));c.destroy()
    }
    @Test fun selectedPhotoScopeIsRevalidatedWhenViewerReturnsFromBackground(){
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        val token=GroupPhotoPlaylist.remember(app,listOf(photo));assertNotNull(GroupPhotoPlaylist.read(app,token))
        GalleryData.backgrounded()
        val c=Robolectric.buildActivity(PhotoActivity::class.java,Intent(app,PhotoActivity::class.java).setData(photo.uri).putExtra("peopleSearch",true).putExtra("groupPlaylist",token)).create()
        assertNull(GroupPhotoPlaylist.read(app,token));c.destroy()
    }
    @Test fun viewerStartsWithValidatedGroupPhotosWithoutSearchIndexRead(){
        val token=GroupPhotoPlaylist.remember(app,listOf(photo))
        val c=Robolectric.buildActivity(PhotoActivity::class.java,Intent(app,PhotoActivity::class.java).setData(photo.uri).putExtra("peopleSearch",true).putExtra("groupPlaylist",token)).create()
        assertEquals(listOf(photo),field(c.get(),"photos").get(c.get()));assertEquals(photo,field(c.get(),"current").get(c.get()));c.destroy()
    }
    @Test fun groupOpenCarriesMimeTypeAndBoundedPlaylistToken(){
        val c=Robolectric.buildActivity(PeopleActivity::class.java,Intent(app,PeopleActivity::class.java).putExtra("person",1L)).create();val a=c.get();field(a,"folderPhotos").set(a,listOf(photo))
        invoke(a,"openPhoto",photo);val intent=Shadows.shadowOf(a).nextStartedActivity
        assertEquals(photo.mimeType,intent.getStringExtra("mimeType"));assertEquals(listOf(photo),GroupPhotoPlaylist.read(app,intent.getStringExtra("groupPlaylist")));assertFalse(intent.hasExtra("photoUris"));c.destroy()
    }
}
