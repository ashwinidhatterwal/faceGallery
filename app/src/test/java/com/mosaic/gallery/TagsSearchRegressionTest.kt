package com.mosaic.gallery

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.os.Looper
import android.view.View
import android.widget.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.*
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class TagsSearchRegressionTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private fun photo(id:Int,album:String="Camera")=PhotoRecord(id.toLong(),Uri.parse("content://tags/$id"),"$id.jpg",LocalDate.of(2026,10,6).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),400,300,album)
    @Before fun setup(){
        app.deleteDatabase("faces.db");Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        app.getSharedPreferences("media-tags",0).edit().clear().commit()
        MediaTags::class.java.getDeclaredField("saved").apply{isAccessible=true}.set(null,null)
        FaceJobs.publish(FaceJobs.State());PeopleData.changed();GalleryData.remember(app,GalleryRepository.Result(listOf(photo(1),photo(2)),0))
    }
    @After fun tearDown(){app.deleteDatabase("faces.db")}
    @Test fun tagsNormalizePreserveOtherTagsAndDoNotInvalidateRecognition(){
        val revision=PeopleData.version;val faces=PeopleData.facesVersion
        MediaTags.update(app,setOf(photo(1).uri.toString()),setOf("  Family   trip  "),emptySet())
        MediaTags.update(app,setOf(photo(1).uri.toString(),photo(2).uri.toString()),setOf("FAMILY TRIP","Beach"),emptySet())
        val next=MediaTags.read(app);assertEquals(2,next.names.size);assertEquals(setOf("family trip","beach"),next.media[photo(1).uri.toString()])
        MediaTags.update(app,setOf(photo(1).uri.toString()),emptySet(),setOf("Beach"));assertEquals(setOf("family trip"),MediaTags.read(app).media[photo(1).uri.toString()]);assertTrue("beach" in MediaTags.read(app).media[photo(2).uri.toString()].orEmpty())
        assertEquals(revision,PeopleData.version);assertEquals(faces,PeopleData.facesVersion)
    }
    @Test fun namesPersistAndPreviouslyUsedNamesRemainSuggestions(){
        val uri=photo(1).uri.toString();MediaTags.update(app,setOf(uri),setOf("Wedding"),emptySet(),"Jaipur")
        MediaTags::class.java.getDeclaredField("saved").apply{isAccessible=true}.set(null,null)
        assertEquals(listOf("Wedding"),MediaTags.read(app).labels(uri));assertEquals("Jaipur",MediaTags.read(app).places[uri])
        MediaTags.update(app,setOf(uri),emptySet(),setOf("Wedding"));assertTrue(MediaTags.read(app).labels(uri).isEmpty());assertEquals("Wedding",MediaTags.read(app).names["wedding"])
    }
    @Test fun unchangedDeltaDoesNotIncrementTagRevision(){
        val uri=photo(1).uri.toString();MediaTags.update(app,setOf(uri),setOf("Trip"),emptySet());val revision=MediaTags.version
        MediaTags.update(app,setOf(uri),setOf("trip"),emptySet());assertEquals(revision,MediaTags.version)
    }
    @Test fun tagAlbumsOnlyContainAccessibleMedia(){
        val photos=listOf(photo(1),photo(2));MediaTags.update(app,photos.map{it.uri.toString()}.toSet(),setOf("Holiday"),emptySet())
        val adapter=PhotoGridAdapter(app,{},{});adapter.submitAlbums(listOf(photos.first()))
        val items=PhotoGridAdapter::class.java.getDeclaredField("items").apply{isAccessible=true}.get(adapter) as List<*>
        val folder=items.first{it!!.javaClass.getDeclaredField("album").apply{isAccessible=true}.get(it)=="@tag:holiday"}!!
        assertEquals(1,folder.javaClass.getDeclaredField("count").apply{isAccessible=true}.get(folder));adapter.close()
    }
    @Test fun universalSearchCombinesPeopleTagsDatePlaceAndDateRange(){
        val p=photo(1);val other=photo(2);val uri=p.uri.toString()
        val index=PeopleSearch.Index(mapOf(uri to setOf(7L)),mapOf(7L to "Alice"),mapOf(7L to 7L),mapOf(uri to listOf("Holiday")),mapOf(uri to "Jaipur"))
        assertEquals(listOf(p),PeopleSearch.filter(listOf(p,other),index,PeopleSearch.Query("alice holiday Jaipur October 2026"),ZoneOffset.UTC))
        assertEquals(listOf(p),PeopleSearch.filter(listOf(p,other),index,PeopleSearch.Query("2026-10-06 Holiday",from=LocalDate.of(2026,10,6),through=LocalDate.of(2026,10,6)),ZoneOffset.UTC))
        assertTrue(PeopleSearch.filter(listOf(p),index,PeopleSearch.Query("2026-10-07"),ZoneOffset.UTC).isEmpty())
    }
    @Test fun tagEditsRefreshSearchWithoutChangingGraph(){
        val photos=listOf(photo(1));val before=PeopleSearch.cachedRead(app,photos);val revision=PeopleData.version
        MediaTags.update(app,setOf(photos.first().uri.toString()),setOf("New tag"),emptySet());val after=PeopleSearch.cachedRead(app,photos)
        assertTrue(before.tags.isEmpty());assertEquals(listOf("New tag"),after.tags[photos.first().uri.toString()]);assertEquals(revision,PeopleData.version)
    }
    @Test fun oldPlaylistExpiresWhenTagFilterChanges(){
        val token=GroupPhotoPlaylist.remember(app,listOf(photo(1)));assertNotNull(GroupPhotoPlaylist.read(app,token))
        MediaTags.update(app,setOf(photo(1).uri.toString()),setOf("Tag"),emptySet());assertNull(GroupPhotoPlaylist.read(app,token))
    }
    private fun walk(v:View):List<View> = listOf(v)+(if(v is android.view.ViewGroup)(0 until v.childCount).flatMap{walk(v.getChildAt(it))}else emptyList())
    @Test fun bulkEditorKeepsIndividualTagsAndSavesUnsubmittedInput(){
        val one=photo(1).uri.toString();val two=photo(2).uri.toString()
        MediaTags.update(app,setOf(one),setOf("Original"),emptySet())
        val c=Robolectric.buildActivity(MainActivity::class.java).create();var done=false
        val editor=TagEditor.content(c.get(),setOf(one,two)){done=true};val views=walk(editor)
        (views.filterIsInstance<EditText>().first()).setText("Together")
        views.filterIsInstance<Button>().first{it.text=="Save tags"}.performClick()
        assertTrue(done);assertEquals(setOf("original","together"),MediaTags.read(app).media[one]);assertEquals(setOf("together"),MediaTags.read(app).media[two]);c.destroy()
    }
    @Test fun viewerTagCapsuleOpensDrawerAndBackClosesIt(){
        val c=Robolectric.buildActivity(PhotoActivity::class.java,Intent(app,PhotoActivity::class.java).setData(photo(1).uri)).create().start().visible();val a=c.get()
        val capsule=PhotoActivity::class.java.getDeclaredField("tagCapsule").apply{isAccessible=true}.get(a) as View;capsule.performClick()
        assertTrue(PhotoActivity::class.java.getDeclaredField("tagsOpen").apply{isAccessible=true}.getBoolean(a))
        PhotoActivity::class.java.getDeclaredMethod("closePhoto").apply{isAccessible=true}.invoke(a)
        assertFalse(a.isFinishing);assertFalse(PhotoActivity::class.java.getDeclaredField("tagsOpen").apply{isAccessible=true}.getBoolean(a));c.destroy()
    }
    @Test fun tagDrawerExpandsAboveKeyboardAndKeepsSaveInsidePanel(){
        val c=Robolectric.buildActivity(PhotoActivity::class.java,Intent(app,PhotoActivity::class.java).setData(photo(1).uri)).create().start().visible();val a=c.get()
        fun field(name:String)=PhotoActivity::class.java.getDeclaredField(name).apply{isAccessible=true}
        val root=field("viewerRoot").get(a) as View
        root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2200,View.MeasureSpec.EXACTLY));root.layout(0,0,1080,2200)
        (field("tagCapsule").get(a) as View).performClick();field("keyboardInset").setInt(a,800)
        val chrome=field("chrome").get(a) as View;chrome.setPadding(0,0,0,800)
        PhotoActivity::class.java.getDeclaredMethod("layoutPeople").apply{isAccessible=true}.invoke(a)
        val panel=field("peopleHost").get(a) as View;val margins=panel.layoutParams as FrameLayout.LayoutParams
        assertTrue(margins.height>500);assertEquals(1400,margins.topMargin+margins.height)
        c.destroy()
    }
    @Test fun fullPermissionPeoplePreviewSurvivesMediaRefreshButSelectedPermissionDoesNot(){
        val cache=PeopleCache<String>();cache.put(app,PeopleData.version,"saved");GalleryData.invalidate()
        assertNull(cache.preview(app));assertEquals("saved",cache.preview(app,allowMediaRefresh=true))
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES);Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertNull(cache.preview(app,allowMediaRefresh=true))
    }
    @Test fun peopleSnapshotsWaitBeforeStartingSqlTransactionAndSignalBackgroundYield(){
        val pool=Executors.newFixedThreadPool(2);val started=CountDownLatch(1);val release=CountDownLatch(1)
        try{
            val writer=pool.submit{FaceWork.write{started.countDown();assertTrue(release.await(5,TimeUnit.SECONDS))}}
            assertTrue(started.await(5,TimeUnit.SECONDS))
            val reader=pool.submit<Int>{FaceStore(app).use{it.snapshot{it.summary().done}}}
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(!FaceWork.readRequested && System.nanoTime()<deadline)Thread.yield()
            assertTrue(FaceWork.readRequested);assertFalse(reader.isDone);release.countDown();writer.get(5,TimeUnit.SECONDS);assertEquals(0,reader.get(5,TimeUnit.SECONDS));assertFalse(FaceWork.readRequested)
        }finally{release.countDown();pool.shutdownNow()}
    }
    @Test fun canceledDeletionLeavesTagsButConfirmedDeletionClearsMetadata(){
        val uri=photo(1).uri.toString();MediaTags.update(app,setOf(uri),setOf("Keep"),emptySet(),"Jaipur")
        val c=Robolectric.buildActivity(MainActivity::class.java).create();val deletion=PhotoDeletion(c.get()){}
        val saved=android.os.Bundle().apply{putStringArrayList("deleteUris",arrayListOf(uri));putInt("deleteBatch",1)};deletion.restore(saved)
        assertTrue(deletion.onActivityResult(3001,android.app.Activity.RESULT_CANCELED));assertEquals(listOf("Keep"),MediaTags.read(app).labels(uri))
        deletion.restore(saved);assertTrue(deletion.onActivityResult(3001,android.app.Activity.RESULT_OK));assertTrue(MediaTags.read(app).labels(uri).isEmpty());assertNull(MediaTags.read(app).places[uri]);deletion.close();c.destroy()
    }
    @Test fun diagnosticsContainFailureTypeWithoutPrivateMessage(){
        app.getSharedPreferences("people-read-diagnostics",0).edit().clear().commit();PeopleReadDiagnostics.record(app,IllegalStateException("private contact and photo URI"))
        val report=PeopleReadDiagnostics.read(app);assertEquals(1,report.getInt("failure_count"));assertEquals(IllegalStateException::class.java.name,report.getString("last_type"));assertFalse(report.toString().contains("private contact"))
    }
}
