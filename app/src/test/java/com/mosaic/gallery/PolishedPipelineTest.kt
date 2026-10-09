package com.mosaic.gallery

import android.net.Uri
import android.os.CancellationSignal
import android.util.AtomicFile
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PolishedPipelineTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val photo=PhotoRecord(1,Uri.parse("content://polish/1"),"one.jpg",120000,400,300,"Camera",123)
    private val face=FaceObservation(.1f,.1f,.4f,.5f,0f,0f,0f,120f,.95f,"Anchor")
    private fun vector(i:Int)=FloatArray(128){if(it==i)1f else 0f}
    @Before fun reset(){app.deleteDatabase("faces.db")}
    @After fun clean(){app.deleteDatabase("faces.db")}
    @Test fun deadlineStopsNextOperationButKeepsFinishedDetection(){FaceStore(app).use{store->
        var start=true;var called=0
        FaceProcessing.run(store,listOf(photo,photo.copy(id=2,uri=Uri.parse("content://polish/2"))),{start},{called++;start=false;listOf(face)},canCommit={true})
        assertEquals(1,called);assertEquals(1,store.summary().faces)
    }}
    @Test fun actualCancellationDiscardsUncommittedDetection(){FaceStore(app).use{store->FaceProcessing.run(store,listOf(photo),{true},{listOf(face)},canCommit={false});assertEquals(0,store.summary().faces)}}
    @Test fun deadlineKeepsFinishedEncoding(){FaceStore(app).use{store->store.save(photo,listOf(face));var start=true;SignatureProcessing.run(store,photo,listOf(face),{start},canCommit={true}){start=false;vector(0)};assertEquals(1,store.signatureSummary().ready)}}
    @Test fun contentChangesDoNotDeleteConfirmedEvidence(){FaceStore(app).use{store->store.save(photo,listOf(face));store.saveSignature(photo.uri.toString(),0,vector(0));PeopleStore(store).nameFace(GroupRules.Key(photo.uri.toString(),0),"Ankita");store.retain(listOf(photo.copy(modifiedMillis=99)),false);assertEquals(1L,android.database.DatabaseUtils.longForQuery(store.readableDatabase,"SELECT COUNT(*) FROM membership WHERE manual=1",null));assertTrue(store.observations(photo.uri.toString()).isEmpty());assertEquals(1,store.pending(listOf(photo.copy(modifiedMillis=99))).size)}}
    @Test fun metadataEditRestoresUserIdentity(){FaceStore(app).use{store->store.save(photo,listOf(face));store.saveSignature(photo.uri.toString(),0,vector(0));PeopleStore(store).nameFace(GroupRules.Key(photo.uri.toString(),0),"Ankita");store.save(photo.copy(modifiedMillis=999),listOf(face));store.saveSignature(photo.uri.toString(),0,vector(0));store.restoreAssertions(photo.uri.toString());assertTrue(PeopleStore(store).members().single().manual);assertEquals("Ankita",PeopleStore(store).label(PeopleStore(store).members().single().person!!))}}
    @Test fun detectorReorderingRestoresByEvidenceInsteadOfOrdinal(){FaceStore(app).use{store->
        store.save(photo,listOf(face,face.copy(left=.6f,right=.9f)));store.saveSignature(photo.uri.toString(),0,vector(0));store.saveSignature(photo.uri.toString(),1,vector(1));PeopleStore(store).nameFace(GroupRules.Key(photo.uri.toString(),0),"Ankita")
        store.save(photo.copy(sizeBytes=321,modifiedMillis=999),listOf(face.copy(left=.6f,right=.9f),face));store.saveSignature(photo.uri.toString(),0,vector(1));store.saveSignature(photo.uri.toString(),1,vector(0));store.restoreAssertions(photo.uri.toString())
        val rows=PeopleStore(store).members();assertEquals(1,rows.single{it.manual}.key.ordinal)
    }}
    @Test fun ambiguousEditedFacesNeverInheritAConfirmation(){FaceStore(app).use{store->
        store.save(photo,listOf(face));store.saveSignature(photo.uri.toString(),0,vector(0));PeopleStore(store).nameFace(GroupRules.Key(photo.uri.toString(),0),"Ankita")
        store.save(photo.copy(sizeBytes=321),listOf(face,face.copy(left=.6f,right=.9f)));repeat(2){store.saveSignature(photo.uri.toString(),it,vector(0))};store.restoreAssertions(photo.uri.toString());assertFalse(PeopleStore(store).members().any{it.manual})
        assertEquals(1,android.database.DatabaseUtils.longForQuery(store.readableDatabase,"SELECT COUNT(*) FROM identity_assertions",null))
    }}
    @Test fun transientDetectionRetriesWithoutManualScan(){var clock=1_000_000L;FaceStore(app,{clock}).use{store->store.save(photo,emptyList(),"IOException");assertTrue(store.pending(listOf(photo),false).isEmpty());clock+=120_000;assertEquals(listOf(photo),store.pending(listOf(photo),false));store.save(photo,listOf(face));assertTrue(store.pending(listOf(photo),false).isEmpty());assertNull(store.nextRetryDelay())}}
    @Test fun repeatedCorruptInputStopsAfterThreeAttempts(){var clock=1_000_000L;FaceStore(app,{clock}).use{store->repeat(3){store.save(photo,emptyList(),"decode");clock+=3_600_000};assertTrue(store.pending(listOf(photo),false).isEmpty());assertNull(store.nextRetryDelay());assertEquals(1,store.pending(listOf(photo.copy(sizeBytes=234)),false).size)}}
    @Test fun unrelatedEncodingDoesNotRefreshThePeopleGrid(){FaceStore(app).use{store->store.save(photo,listOf(face));val graph=PeopleData.version;val faces=PeopleData.faceVersion(photo.uri.toString());store.saveSignature(photo.uri.toString(),0,vector(0));assertEquals(graph,PeopleData.version);assertTrue(PeopleData.faceVersion(photo.uri.toString())>faces)}}
    @Test fun pairCursorAndCompetitorsSurviveProcessRestart(){
        val file=AtomicFile(File(app.cacheDir,"pairs.json"));file.delete()
        val first=IdentityComparisonCache(listOf(1,2,3),.6f,file,55);first.i=1;first.j=2;first.offer(IdentityComparisonCache.Match(1,2,.92f,true,true));first.offer(IdentityComparisonCache.Match(1,3,.90f,true,true));first.saveScan()
        val restored=IdentityComparisonCache(listOf(1,2,3),.6f,file,55);assertEquals(1,restored.i);assertEquals(2,restored.j);assertEquals(2,restored.rankings[1L]!!.size)
        val changed=IdentityComparisonCache(listOf(1,2,3),.6f,file,56);assertEquals(0,changed.i);assertTrue(changed.rankings.isEmpty());file.delete()
    }
    @Test fun similarFaceHeapUsesAccessibleCandidatesBeforeLimit(){FaceStore(app).use{store->
        store.save(photo,listOf(face));store.saveSignature(photo.uri.toString(),0,vector(0));val allowed=mutableSetOf<String>()
        for(i in 2..25){val p=photo.copy(id=i.toLong(),uri=Uri.parse("content://polish/$i"));store.save(p,listOf(face));store.saveSignature(p.uri.toString(),0,vector(0));if(i==25)allowed+=p.uri.toString()}
        val result=store.similar(photo.uri.toString(),0,eligible=allowed);assertEquals(1,result.size);assertEquals("content://polish/25",result.single().uri)
    }}
    @Test fun acceptingOneFaceKeepsSuggestionsForTheOtherFaces(){FaceStore(app).use{store->
        val source=photo.copy(sizeBytes=321)
        val others=(2..4).map{photo.copy(id=it.toLong(),uri=Uri.parse("content://polish/$it"))}
        store.save(source,listOf(face,face.copy(left=.4f,right=.6f),face.copy(left=.7f,right=.9f)))
        repeat(3){store.saveSignature(source.uri.toString(),it,vector(it))}
        val people=PeopleStore(store)
        repeat(3){people.correct(setOf(GroupRules.Key(source.uri.toString(),it)),create=true)}
        val targets=others.mapIndexed{index,p->store.save(p,listOf(face));store.saveSignature(p.uri.toString(),0,vector(index));people.nameFace(GroupRules.Key(p.uri.toString(),0),listOf("Ankita","Ashwini","Ravi")[index]);people.members().first{it.key.uri==p.uri.toString()}.person!!}
        val access=(others+source).map{it.uri.toString()}.toSet()
        assertEquals(listOf("Ankita","Ashwini","Ravi"),PhotoPeople.read(people,source.uri.toString(),access).map{it.suggestedName})
        people.confirmIdentity(GroupRules.Key(source.uri.toString(),0),targets[0])
        val after=PhotoPeople.read(people,source.uri.toString(),access)
        assertEquals("Ankita",after[0].name);assertEquals(listOf("Ashwini","Ravi"),after.drop(1).map{it.suggestedName})
    }}

    @Test fun invalidationDoesNotWaitForABlockedGalleryProvider(){
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        GalleryData.invalidate()
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1);var queries=0
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media",object:android.content.ContentProvider(){
            override fun onCreate()=true
            override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):android.database.Cursor {
                if("video" in uri.pathSegments)return android.database.MatrixCursor(projection!!)
                val count=++queries;entered.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS)
                return android.database.MatrixCursor(projection!!).apply{addRow(arrayOf<Any>(count,"one.jpg",1000,1,400,300,"Camera",0,"",1))}
            }
            override fun getType(uri:Uri):String?=null
            override fun insert(uri:Uri,v:android.content.ContentValues?):Uri?=null
            override fun delete(uri:Uri,s:String?,a:Array<out String>?)=0
            override fun update(uri:Uri,v:android.content.ContentValues?,s:String?,a:Array<out String>?)=0
        })
        val worker=java.util.concurrent.Executors.newFixedThreadPool(2)
        try{
            val load=worker.submit<GalleryRepository.Result>{GalleryData.load(app,CancellationSignal(),true)}
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
            worker.submit{GalleryData.invalidate()}.get(1,java.util.concurrent.TimeUnit.SECONDS)
            release.countDown();load.get(2,java.util.concurrent.TimeUnit.SECONDS)
            GalleryData.load(app,CancellationSignal());assertEquals(2,queries)
        }finally{release.countDown();worker.shutdownNow();Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE)}
    }

}
