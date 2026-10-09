package com.mosaic.gallery

import android.Manifest
import android.app.Activity
import android.content.*
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.view.View
import android.view.ViewGroup
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class VideoLibraryTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val video=PhotoRecord(1,Uri.parse("content://media/external/video/media/1"),"clip.mp4",500000,1920,1080,mimeType="video/mp4",durationMillis=61000)
    @Before fun setup(){Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE);PhotoIndex.clear(app);app.deleteDatabase("faces.db")}
    @After fun cleanup(){PhotoIndex.clear(app);app.deleteDatabase("faces.db")}
    @Test fun libraryCombinesPhotosAndVideosWithoutCollidingIds(){
        ShadowContentResolver.registerProviderInternal("media",object:ContentProvider(){
            override fun onCreate()=true
            override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?)=MatrixCursor(p!!).apply{
                if("video" in uri.pathSegments)addRow(arrayOf<Any>(1,"clip.mp4",500000,500,1920,1080,"Camera",100000,"",500,61000,"video/mp4"))
                else addRow(arrayOf<Any>(1,"photo.jpg",300000,300,400,300,"Camera",1000,"",300))
            }
            override fun getType(uri:Uri)=if("video" in uri.pathSegments)"video/mp4"else"image/jpeg"
            override fun insert(uri:Uri,v:ContentValues?):Uri?=null
            override fun delete(uri:Uri,s:String?,a:Array<out String>?)=0
            override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
        })
        val result=GalleryRepository(app).loadPhotos(CancellationSignal())
        assertEquals(0,result.unreadableVolumes);assertEquals(2,result.photos.size)
        assertTrue(result.photos.first().isVideo);assertEquals(61000L,result.photos.first().durationMillis)
        assertFalse(result.photos.last().isVideo);assertNotEquals(result.photos.first().uri,result.photos.last().uri)
    }
    @Test fun savedIndexPreservesVideoDurationAndType(){
        PhotoIndex.save(app,GalleryRepository.Result(listOf(video),0));PhotoIndex.forgetMemory()
        assertEquals(video,PhotoIndex.read(app)!!.photos.single())
    }
    @Test fun videosNeverEnterFaceDetectionOrSignatureWork(){
        val image=video.copy(uri=Uri.parse("content://media/external/images/media/1"),mimeType="image/jpeg",durationMillis=0)
        FaceStore(app).use{f->assertEquals(listOf(image),f.pending(listOf(video,image)));f.save(image,emptyList());assertTrue(f.pending(listOf(video,image),false).isEmpty());assertTrue(f.pendingSignatures(listOf(video)).isEmpty());assertEquals(1,f.summary().done)}
    }
    @Test @Config(sdk=[35]) fun videoOnlyAccessDoesNotEnableRecognition(){
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        assertTrue(MediaAccess.allowed(app));assertFalse(AutoPeople.allowed(app));assertTrue(MediaAccess.cacheable(app))
    }
    @Test @Config(sdk=[35]) fun revokedVideoPermissionCannotReuseFullIndex(){
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO)
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        PhotoIndex.save(app,GalleryRepository.Result(listOf(video),0));assertNotNull(PhotoIndex.current(app))
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        assertNull(PhotoIndex.current(app));assertNull(PhotoIndex.read(app))
    }
    @Test fun restoredVideoPositionPreservesManualPause(){
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val view=GalleryVideoView(controller.get());view.bind(video,GalleryVideoView.State(video.uri.toString(),45000,false))
        assertEquals(45000L,view.state().position);assertFalse(view.state().playing)
        view.stopPlayback();assertEquals(View.GONE,view.visibility);assertEquals(45000L,view.state().position)
        controller.pause().stop().destroy()
    }
    @Test fun timelineLabelsHandleHoursAndUnknownDuration(){assertEquals("0:00",GalleryMedia.time(-1));assertEquals("1:01",GalleryMedia.time(61000));assertEquals("1:01:01",GalleryMedia.time(3661000))}
}
