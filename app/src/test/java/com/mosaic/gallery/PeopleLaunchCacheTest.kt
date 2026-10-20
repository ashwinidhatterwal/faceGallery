package com.mosaic.gallery

import android.Manifest
import android.net.Uri
import androidx.recyclerview.widget.RecyclerView
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PeopleLaunchCacheTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val photo=PhotoRecord(1,Uri.parse("content://launch/photo/1"),"cover.jpg",1000,800,600,mimeType="image/jpeg")
    private val face=FaceObservation(.1f,.2f,.5f,.6f,0f,0f,0f,80f,.9f,"Anchor",listOf(.2f,.3f))
    private val profile get()=PeopleLaunchCache.Profile(7,"Family","30000 photos",photo,face,0)
    private fun diskRead():List<*>?=PeopleLaunchCache.javaClass.getDeclaredMethod("read",android.content.Context::class.java).apply{isAccessible=true}.invoke(PeopleLaunchCache,app) as List<*>?
    @Before fun setup(){
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,Manifest.permission.READ_CONTACTS)
        PeopleLaunchCache.clear(app);PeopleData.changed();GalleryData.invalidate();FaceJobs.publish(FaceJobs.State())
        val cache=PeopleActivity::class.java.getDeclaredField("cache").apply{isAccessible=true}.get(null)
        cache.javaClass.getDeclaredField("entry").apply{isAccessible=true}.set(cache,null)
    }
    @After fun cleanup(){PeopleLaunchCache.clear(app)}
    @Test fun launchDataSurvivesColdStartWithoutDatabaseOrFullPhotoIndex(){
        PeopleLaunchCache.save(app,listOf(profile));PeopleLaunchCache.forgetMemory();PhotoIndex.clear(app);app.deleteDatabase("faces.db")
        assertEquals(listOf(profile),diskRead())
        assertTrue(java.io.File(app.noBackupFilesDir,"people-launch.bin").length()<1024)
    }
    @Test fun firstPeopleFrameUsesCachedGroupsBeforeAsyncLibraryRefresh(){
        PeopleLaunchCache.save(app,listOf(profile))
        val c=Robolectric.buildActivity(PeopleActivity::class.java).create().start().resume()
        val grid=c.get().javaClass.getDeclaredField("grid").apply{isAccessible=true}.get(c.get()) as RecyclerView
        assertEquals(1,grid.adapter?.itemCount)
        assertTrue(c.get().javaClass.getDeclaredField("launchPreview").apply{isAccessible=true}.getBoolean(c.get()))
        c.pause().stop().destroy()
    }
    @Test fun selectedOnlyOrRevokedPermissionsNeverRestoreFullLibraryProfiles(){
        PeopleLaunchCache.save(app,listOf(profile));Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        assertNull(PeopleLaunchCache.peek(app));assertNull(diskRead())
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertNull(PeopleLaunchCache.peek(app));assertNull(diskRead())
    }
    @Test fun changedContactPermissionAndExplicitResetInvalidateProfiles(){
        PeopleLaunchCache.save(app,listOf(profile));Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        assertNull(PeopleLaunchCache.peek(app));assertNull(diskRead())
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS);PeopleActivity.invalidateSaved(app)
        assertNull(PeopleLaunchCache.peek(app));assertNull(diskRead())
    }
    @Test fun corruptCacheFallsBackWithoutAffectingOriginalData(){
        PeopleLaunchCache.save(app,listOf(profile));PeopleLaunchCache.forgetMemory()
        java.io.File(app.noBackupFilesDir,"people-launch.bin").writeBytes(byteArrayOf(1,2,3))
        assertNull(diskRead())
        PeopleLaunchCache.save(app,listOf(profile));assertEquals(listOf(profile),diskRead())
    }
    @Test fun clearingRecognitionAlsoClearsDisplayOnlyNamesAndCovers(){
        PeopleLaunchCache.save(app,listOf(profile));FaceStore(app).use{it.clear()}
        assertNull(PeopleLaunchCache.peek(app));assertNull(diskRead())
    }
}
