package com.mosaic.gallery

import android.Manifest
import android.content.*
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class MediaRegressionTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val image=PhotoRecord(1,Uri.parse("content://media/external/images/media/1"),"one.jpg",1000,400,300,"Camera")
    @Before fun setup(){RecognitionConsent.accept(RuntimeEnvironment.getApplication());
        app.getSharedPreferences("startup-access",0).edit().clear().commit()
        app.getSharedPreferences("automatic-people",0).edit().clear().commit();AutoPeople.pause(app)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,Manifest.permission.READ_CONTACTS)
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        GalleryData.invalidate();PhotoIndex.clear(app)
    }
    @Test fun upgradeRequestsBothMediaTypesDespiteSelectedAccessFlag(){
        app.getSharedPreferences("startup-access",0).edit().putBoolean("videos-asked",true).commit()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup()
        assertArrayEquals(MediaAccess.permissions(),Shadows.shadowOf(c.get()).lastRequestedPermission.requestedPermissions)
        c.pause().stop().destroy()
        val next=Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNull(Shadows.shadowOf(next.get()).lastRequestedPermission);next.pause().stop().destroy()
    }
    @Test fun internalSearchNavigationReusesPeopleCacheWithPartialVideoAccess(){
        GalleryData.remember(app,GalleryRepository.Result(listOf(image),0));GalleryData.resumed(app)
        val cache=PeopleCache<String>();cache.put(app,PeopleData.version,"profiles");val before=GalleryData.version
        repeat(4){GalleryData.resumed(app);assertEquals(before,GalleryData.version);assertEquals("profiles",cache.get(app))}
        GalleryData.backgrounded();GalleryData.resumed(app)
        assertTrue(GalleryData.version>before);assertNull(cache.get(app))
    }
    @Test fun accessRevocationInvalidatesCacheEvenWithoutLeavingTheApp(){
        GalleryData.remember(app,GalleryRepository.Result(listOf(image),0));GalleryData.resumed(app);val before=GalleryData.version
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        GalleryData.resumed(app);assertTrue(GalleryData.version>before);assertNull(GalleryData.peek(app))
    }
    @Test fun videoQueryFallsBackWhenVendorRejectsOptionalMetadata(){
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        var fallback=false
        ShadowContentResolver.registerProviderInternal("media",object:ContentProvider(){
            override fun onCreate()=true
            override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):android.database.Cursor{
                if("video" in uri.pathSegments && p!!.size>3)throw IllegalArgumentException("Unsupported legacy column")
                return MatrixCursor(p!!).apply{if("video" in uri.pathSegments){fallback=true;addRow(arrayOf<Any>(7,"clip.mp4",100))}}
            }
            override fun getType(uri:Uri)="video/mp4"
            override fun insert(uri:Uri,v:ContentValues?):Uri?=null
            override fun delete(uri:Uri,s:String?,a:Array<out String>?)=0
            override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
        })
        val result=GalleryRepository(app).loadPhotos(CancellationSignal());assertTrue(fallback);assertEquals(1,result.unreadableVolumes);assertTrue(result.photos.single().isVideo);assertTrue(result.photos.single().uri.pathSegments.contains("external_primary"))
    }
    @Test fun deniedVideoProviderDoesNotHideAccessiblePhotos(){
        ShadowContentResolver.registerProviderInternal("media",object:ContentProvider(){
            override fun onCreate()=true
            override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?)=if("video" in uri.pathSegments)throw SecurityException("Video access denied")else MatrixCursor(p!!).apply{addRow(arrayOf<Any>(1,"one.jpg",1000,1,400,300,"Camera",0,"",1))}
            override fun getType(uri:Uri)="image/jpeg"
            override fun insert(uri:Uri,v:ContentValues?):Uri?=null
            override fun delete(uri:Uri,s:String?,a:Array<out String>?)=0
            override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
        })
        val result=GalleryRepository(app).loadPhotos(CancellationSignal());assertEquals(1,result.photos.size);assertFalse(result.photos.single().isVideo);assertEquals(2,result.unreadableVolumes)
    }
    @Test fun failedPhotoMetadataNeverFallsBackToAnIncompleteFaceFingerprint(){
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO);var imageReads=0
        app.deleteDatabase("faces.db")
        val face=FaceObservation(.1f,.1f,.4f,.5f,0f,0f,0f,120f,.95f,"Anchor")
        ShadowContentResolver.registerProviderInternal("media",object:ContentProvider(){
            override fun onCreate()=true
            override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):android.database.Cursor{
                if("images" in uri.pathSegments){imageReads++;throw IllegalArgumentException("Photo metadata unavailable")}
                return MatrixCursor(p!!).apply{addRow(arrayOf<Any>(1,"clip.mp4",1000,1,400,300,"Camera",0,"",1,1000,"video/mp4"))}
            }
            override fun getType(uri:Uri)="video/mp4"
            override fun insert(uri:Uri,v:ContentValues?):Uri?=null
            override fun delete(uri:Uri,s:String?,a:Array<out String>?)=0
            override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
        })
        FaceStore(app).use{store->store.save(image,listOf(face));val result=GalleryRepository(app).loadPhotos(CancellationSignal());assertEquals(1,imageReads);assertTrue(result.unreadableVolumes>0);assertTrue(result.photos.all{it.isVideo});store.retain(result.photos.filterNot{it.isVideo},false);assertEquals(1,store.summary().faces);assertTrue(store.pending(listOf(image),false).isEmpty())}
        app.deleteDatabase("faces.db")
    }
    @Test fun menuUsesAnchorPositionInsteadOfBottomSheetGravity(){
        val c=Robolectric.buildActivity(android.app.Activity::class.java).setup();val root=android.widget.FrameLayout(c.get());val anchor=View(c.get());root.addView(anchor,android.widget.FrameLayout.LayoutParams(48,48).apply{leftMargin=280;topMargin=100});c.get().setContentView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(780,View.MeasureSpec.EXACTLY));root.layout(0,0,360,780)
        val d=GalleryMenu.show(c.get(),"Gallery",listOf(GalleryMenu.Action("settings","Settings"){}),anchor)
        assertEquals(Gravity.TOP or Gravity.LEFT,d.window!!.attributes.gravity);assertEquals(R.style.GalleryAnchoredMenuAnimation,d.window!!.attributes.windowAnimations)
        assertTrue(d.window!!.attributes.y>=0);d.dismiss();c.pause().stop().destroy()
    }
}
