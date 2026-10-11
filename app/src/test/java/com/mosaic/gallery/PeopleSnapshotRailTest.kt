package com.mosaic.gallery

import android.Manifest
import android.net.Uri
import android.os.CancellationSignal
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PeopleSnapshotRailTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val photo=PhotoRecord(901,Uri.parse("content://snapshot/901"),"one.jpg",120000,400,300,"Camera")
    private val face=FaceObservation(.1f,.1f,.7f,.8f,0f,0f,0f,100f,.9f,"Anchor")
    private fun companion(name:String,vararg args:Any):Any? {
        val c=PeopleActivity.Companion;val m=c.javaClass.declaredMethods.first{it.name==name && it.parameterCount==args.size};m.isAccessible=true;return m.invoke(c,*args)
    }
    private fun read():Any=companion("readData",app,listOf(photo),CancellationSignal(),false,{true})!!
    private fun field(a:Any,n:String)=a.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(a)
    private fun forget(){val c=PeopleActivity::class.java.getDeclaredField("cache").apply{isAccessible=true}.get(null);c.javaClass.getDeclaredField("entry").apply{isAccessible=true}.set(c,null);PhotoIndex.forgetMemory()}
    private fun walk(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{walk(v.getChildAt(it))}else emptyList())
    private fun seed():Long=FaceStore(app).use{f->f.save(photo,listOf(face));f.saveSignature(photo.uri.toString(),0,FloatArray(128){if(it==0)1f else 0f});PeopleStore(f).correct(setOf(GroupRules.Key(photo.uri.toString(),0)),create=true);PeopleStore(f).members().single().person!!}
    @Before fun setup(){
        app.deleteDatabase("faces.db");PeopleActivity.invalidateSaved(app);forget();PeopleData.changed()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        FaceJobs.publish(FaceJobs.State());GalleryData.remember(app,GalleryRepository.Result(listOf(photo),0));PhotoIndex.save(app,GalleryRepository.Result(listOf(photo),0))
        app.getSharedPreferences("media-tags",0).edit().clear().commit();MediaTags::class.java.getDeclaredField("saved").apply{isAccessible=true}.set(null,null)
    }
    @After fun clean(){PeopleActivity.invalidateSaved(app);forget();app.deleteDatabase("faces.db")}
    @Test fun savedSnapshotRestoresMembersLabelsAndPhotoMetadataAfterMemoryLoss(){
        val id=seed();FaceStore(app).use{PeopleStore(it).rename(id,"Saved person")};val data=read();assertTrue(java.io.File(app.noBackupFilesDir,"people-view.bin").length()>0)
        forget();val restored=companion("saved",app)!!
        assertEquals(field(data,"rows"),field(restored,"rows"));assertEquals(field(data,"labels"),field(restored,"labels"));assertEquals(field(data,"photos"),field(restored,"photos"))
        assertEquals("Saved person",(field(restored,"labels") as Map<*,*>)[id])
    }
    @Test fun onlyChangedGroupsRebuildTheirReferences(){
        val id=seed();val second=photo.copy(id=902,uri=Uri.parse("content://snapshot/902"),dateTakenMillis=240000)
        FaceStore(app).use{f->f.save(second,listOf(face));f.saveSignature(second.uri.toString(),0,FloatArray(128){if(it==0)1f else 0f});PeopleStore(f).correct(setOf(GroupRules.Key(second.uri.toString(),0)),create=true)}
        val photos=listOf(photo,second);GalleryData.remember(app,GalleryRepository.Result(photos,0));PhotoIndex.save(app,GalleryRepository.Result(photos,0))
        fun load()=companion("readData",app,photos,CancellationSignal(),false,{true})!!
        load();val before=PeopleReadDiagnostics.read(app).getInt("reference_builds")
        FaceStore(app).use{PeopleStore(it).rename(id,"New label")};PeopleData.changed();val renamed=load();assertEquals("New label",(field(renamed,"labels") as Map<*,*>)[id])
        assertEquals(before,PeopleReadDiagnostics.read(app).getInt("reference_builds"));assertTrue(PeopleReadDiagnostics.read(app).getInt("reference_reuses")>=2)
        FaceStore(app).use{f->f.writableDatabase.execSQL("UPDATE embeddings SET vector=? WHERE uri=?",arrayOf(FaceVectors.pack(FloatArray(128){if(it==1)1f else 0f}),photo.uri.toString()))};PeopleData.changed();load()
        assertEquals(before+1,PeopleReadDiagnostics.read(app).getInt("reference_builds"))
    }
    @Test fun savedSearchSharesVerifiedPeopleGraphWithoutRebuildingReferences(){
        seed();read();val before=PeopleReadDiagnostics.read(app).getInt("snapshot_builds")
        assertNotNull(PeopleActivity.searchIndex(app,listOf(photo)));PeopleSearch.cachedRead(app,listOf(photo))
        assertEquals(before,PeopleReadDiagnostics.read(app).getInt("snapshot_builds"))
    }
    @Test fun cropPreviewSurvivesMemoryClearWithoutOriginalPhotoDecode(){
        var decoded=0
        fun decode():android.graphics.Bitmap{decoded++;return android.graphics.Bitmap.createBitmap(400,300,android.graphics.Bitmap.Config.ARGB_8888)}
        val first=FaceThumbnails.load(app,photo,face,::decode)!!;FaceThumbnails.clear()
        val second=FaceThumbnails.load(app,photo,face,::decode)!!
        assertEquals(1,decoded);assertEquals(first.width,second.width);assertNotSame(first,second)
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES);assertNull(FaceThumbnails.load(app,photo,face,::decode));assertEquals(1,decoded)
    }
    @Test fun savedPreviewIsNotConsideredVerifiedUntilBackgroundRefresh(){
        seed();read();forget();val saved=companion("saved",app)!!
        @Suppress("UNCHECKED_CAST") val cache=PeopleActivity::class.java.getDeclaredField("cache").apply{isAccessible=true}.get(null) as PeopleCache<Any>
        cache.putPreview(app,saved);assertNull(cache.get(app));assertSame(saved,cache.preview(app,true))
    }
    @Test fun savedCacheCannotBeUsedWithSelectedOnlyOrRevokedAccess(){
        seed();read();forget();Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertNull(companion("saved",app));Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);assertNull(companion("saved",app))
    }
    @Test fun mediaFingerprintChangeRejectsSavedSnapshot(){
        seed();read();forget();PhotoIndex.save(app,GalleryRepository.Result(listOf(photo.copy(modifiedMillis=99)),0));assertNull(companion("saved",app))
    }
    @Test fun changedDatabaseRejectsFreshReuseButUnchangedMetadataUsesSavedResult(){
        val id=seed();read();forget()
        FaceStore(app).use{f->f.writableDatabase.execSQL("UPDATE people SET label='New name',name_rank=1 WHERE id=?",arrayOf(id))}
        PeopleData.changed();val next=read();assertEquals("New name",(field(next,"labels") as Map<*,*>)[id])
        val previousRevision=PeopleData.version;forget();read();assertEquals(previousRevision,PeopleData.version)
    }
    @Test fun revisionTracksCommittedChangesButNotNoopsOrRolledBackChanges(){
        val id=seed();FaceStore(app).use{f->val db=f.writableDatabase;val before=PeopleRevision.value(db)
            db.execSQL("UPDATE people SET label=label WHERE id=?",arrayOf(id));assertEquals(before,PeopleRevision.value(db))
            db.beginTransaction();db.execSQL("UPDATE people SET label='Rolled back' WHERE id=?",arrayOf(id));assertNotEquals(before,PeopleRevision.value(db));db.endTransaction();assertEquals(before,PeopleRevision.value(db))
            db.execSQL("UPDATE people SET label='Committed' WHERE id=?",arrayOf(id));assertNotEquals(before,PeopleRevision.value(db))
        }
    }
    @Test fun additiveVersionElevenMigrationKeepsGroupsAndNames(){
        val id=seed();FaceStore(app).use{f->PeopleStore(f).rename(id,"Keep me");val db=f.writableDatabase
            val triggers=buildList{db.rawQuery("SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'people_ui_%'",null).use{while(it.moveToNext())add(it.getString(0))}}
            triggers.forEach{db.execSQL("DROP TRIGGER $it")};db.execSQL("DROP TABLE people_ui_revision");db.version=11
        }
        FaceStore(app).use{f->assertEquals(12,f.readableDatabase.version);assertEquals("Keep me",PeopleStore(f).label(id));assertEquals(1,PeopleStore(f).members().size);assertEquals("0",PeopleRevision.value(f.readableDatabase))}
    }
    @Test fun duplicateUriKeepsFirstPhotoSearchMetadata(){
        val duplicate=photo.copy(displayName="holiday.jpg")
        val index=PeopleSearch.Index(emptyMap(),emptyMap(),emptyMap())
        assertTrue(PeopleSearch.filter(listOf(photo,duplicate),index,PeopleSearch.Query("holiday")).isEmpty())
        assertEquals(listOf(photo),PeopleSearch.filter(listOf(photo,duplicate),index,PeopleSearch.Query("one")))
    }
    @Test fun todayLabelsUseDeviceTimezoneWhenSearchingInAnotherZone(){
        val previousZone=java.util.TimeZone.getDefault();val zone=java.time.ZoneId.of("Asia/Kolkata")
        try{java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone));val today=java.time.LocalDate.now(zone)
            val one=photo.copy(dateTakenMillis=today.atTime(0,30).atZone(zone).toInstant().toEpochMilli())
            val two=photo.copy(id=902,uri=Uri.parse("content://snapshot/yesterday"),dateTakenMillis=today.minusDays(1).atTime(18,30).atZone(zone).toInstant().toEpochMilli())
            val index=PeopleSearch.Index(emptyMap(),emptyMap(),emptyMap())
            assertEquals(listOf(one),PeopleSearch.filter(listOf(one,two),index,PeopleSearch.Query("today"),java.time.ZoneOffset.UTC))
            assertEquals(listOf(two),PeopleSearch.filter(listOf(one,two),index,PeopleSearch.Query("yesterday"),java.time.ZoneOffset.UTC))
        }finally{java.util.TimeZone.setDefault(previousZone)}
    }
    @Test fun typingReusesSearchTextButChangedTagsCreateANewCorpus(){
        val index=PeopleSearch.Index(emptyMap(),emptyMap(),emptyMap(),mapOf(photo.uri.toString() to listOf("Holiday")))
        assertEquals(listOf(photo),PeopleSearch.filter(listOf(photo),index,PeopleSearch.Query("hol")))
        val corpus=index.corpus;assertNotNull(corpus)
        assertEquals(listOf(photo),PeopleSearch.filter(listOf(photo),index,PeopleSearch.Query("holiday")));assertSame(corpus,index.corpus)
        val changed=index.copy(tags=mapOf(photo.uri.toString() to listOf("Family")))
        assertTrue(PeopleSearch.filter(listOf(photo),changed,PeopleSearch.Query("holiday")).isEmpty());assertNotSame(corpus,changed.corpus)
    }
    @Test fun railUsesFourCoversAndDoesNotDuplicateTagsInAlbumGrid(){
        val photos=(1..6).map{photo.copy(id=it.toLong(),uri=Uri.parse("content://rail/$it"))};MediaTags.update(app,photos.map{it.uri.toString()}.toSet(),setOf("Holiday"),emptySet())
        val folder=TagRail.folders(app,photos.take(5)).single();assertEquals(5,folder.count);assertEquals(photos.take(4),folder.covers)
        val adapter=PhotoGridAdapter(app,{},{});adapter.submitAlbums(photos)
        val items=field(adapter,"items") as List<*>;assertFalse(items.any{(field(it!!,"album") as? String)?.startsWith(MediaTags.PREFIX)==true});adapter.close()
    }
    @Test fun tagEditorHasOneFieldCompactSuggestionsAndNoDescriptionOrPlace(){
        MediaTags.update(app,setOf(photo.uri.toString()),setOf("Holiday","Family"),emptySet())
        val c=Robolectric.buildActivity(android.app.Activity::class.java).setup();val editor=TagEditor.content(c.get(),setOf(photo.uri.toString())){}
        c.get().setContentView(editor);org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val views=walk(editor);assertEquals(1,views.filterIsInstance<EditText>().size);assertEquals("Add a tag",views.filterIsInstance<EditText>().single().hint.toString())
        assertTrue(views.any{it.contentDescription=="Save tags"});assertFalse(views.filterIsInstance<android.widget.TextView>().any{it.text.contains("Tags appear") || it.text.contains("Place")})
        assertTrue(views.filterIsInstance<EditText>().single().hasFocus());c.destroy()
    }
}
