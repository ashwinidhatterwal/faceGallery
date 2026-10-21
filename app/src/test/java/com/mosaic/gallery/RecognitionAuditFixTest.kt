package com.mosaic.gallery

import android.Manifest
import android.app.job.JobParameters
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.os.Looper
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RecognitionAuditFixTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private fun photo(id:Int)=PhotoRecord(id.toLong(),Uri.parse("content://audit-fixes/$id"),"$id.jpg",id*120000L,400,300,"Camera")
    private val face=FaceObservation(.1f,.1f,.4f,.5f,0f,0f,0f,120f,.95f,"Anchor")
    private fun vector()=FloatArray(128){if(it==0)1f else 0f}
    @Before fun setup(){app.deleteDatabase("faces.db");RecognitionConsent.accept(app);FaceJobs.publish(FaceJobs.State());app.getSharedPreferences("automatic-people",0).edit().clear().commit();AutoPeople.resume(app);Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES);Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)}
    @After fun cleanup(){FaceJobs.publish(FaceJobs.State());app.deleteDatabase("faces.db")}
    private fun seed(f:FaceStore,id:Int=1):Long {f.save(photo(id),listOf(face));f.saveSignature(photo(id).uri.toString(),0,vector());val p=PeopleStore(f);return p.record(p.members().first{it.key.uri==photo(id).uri.toString()},GroupRules.Decision(seed=true,status="known"))!!}
    private fun finish(){val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(FaceWork.automatic && System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.yield()};assertFalse(FaceWork.automatic)}
    private fun params()=ReflectionHelpers.newInstance(JobParameters::class.java).also{ReflectionHelpers.setField(it,"jobId",AutoPeople.BATCH)}
    @Test fun zeroFaceResultsNeverDirtyCompletedMatching(){FaceStore(app).use{f->
        val id=seed(f);PeopleStore(f).rename(id,"Saved person");RecognitionState.grouped(f.writableDatabase,f.evidenceRevision());val before=f.evidenceRevision()
        (2..21).forEach{f.save(photo(it),emptyList())};assertEquals(before,f.evidenceRevision());assertFalse(RecognitionState.needed(f.readableDatabase))
        assertEquals(21,f.workCounts((1..21).map(::photo)).done);assertEquals("Saved person",PeopleStore(f).names().values.single())
        f.retain((1..21).map(::photo).filter{it.id!=2L});assertEquals(before,f.evidenceRevision())
        f.save(photo(1),emptyList());assertTrue(RecognitionState.needed(f.readableDatabase))
    }}
    class EmptyPipeline:AutoPeopleJob(){var records=emptyList<PhotoRecord>();var detections=0;var encodes=0
        override fun photos(signal:CancellationSignal)=GalleryRepository.Result(records,0)
        override fun detect(store:FaceStore,pending:List<PhotoRecord>,keepGoing:()->Boolean){FaceProcessing.run(store,pending,keepGoing,{detections++;emptyList()})}
        override fun signatures(store:FaceStore,pending:List<PhotoRecord>,keepGoing:()->Boolean){encodes+=pending.size}
    }
    @Test fun twentyNewEmptyPhotosFinishWithoutAnyMatchingPhase(){
        FaceStore(app).use{f->val id=seed(f);PeopleStore(f).rename(id,"Saved");RecognitionState.grouped(f.writableDatabase,f.evidenceRevision())}
        val before=AutoPeople.revision(app);val phases=mutableListOf<String>();val observer:(FaceJobs.State)->Unit={phases+=it.message};FaceJobs.observe(observer)
        val c=Robolectric.buildService(EmptyPipeline::class.java).create();val job=c.get();job.records=(1..21).map(::photo)
        try{repeat(4){assertTrue(job.onStartJob(params()));finish()};assertEquals(20,job.detections);assertEquals(0,job.encodes);assertEquals(before,AutoPeople.revision(app));assertFalse(phases.any{it.contains("saved matches") || it.contains("Matching faces") || it.contains("Comparing people")})}finally{FaceJobs.remove(observer);c.destroy()}
    }
    @Test fun refinementPlanSurvivesZeroFaceAdditionsWithoutRebuildingGraph(){FaceStore(app).use{f->
        val id=seed(f);PeopleStore(f).rename(id,"Saved");val builds=SignatureRefinement.graphBuilds
        assertTrue(SignatureRefinement.candidates(f,listOf(photo(1))).isEmpty());assertEquals(builds+1,SignatureRefinement.graphBuilds)
        (2..21).forEach{f.save(photo(it),emptyList())}
        assertTrue(SignatureRefinement.candidates(f,(1..21).map(::photo)).isEmpty());assertEquals(builds+1,SignatureRefinement.graphBuilds)
    }}
    @Test fun dirtyEvidenceIsTransactionalAndIndependentOfPreferences(){
        FaceStore(app).use{f->val before=f.evidenceRevision();f.writableDatabase.beginTransaction();try{f.save(photo(1),listOf(face));assertTrue(f.evidenceRevision()>before)}finally{f.writableDatabase.endTransaction()};assertEquals(before,f.evidenceRevision());f.save(photo(1),listOf(face))}
        val revision=AutoPeople.revision(app);app.getSharedPreferences("automatic-people",0).edit().clear().commit();assertEquals(revision,AutoPeople.revision(app));assertTrue(AutoPeople.needsGrouping(app))
    }
    @Test fun clearResultsAlsoRemovesNewDurableWorkPlans(){FaceStore(app).use{f->
        val id=seed(f);PeopleStore(f).rename(id,"Saved");SignatureRefinement.candidates(f,listOf(photo(1)));RecognitionState.checkpoint(f.writableDatabase,"joins",4,true)
        f.writableDatabase.execSQL("INSERT INTO recognition_contacts VALUES('old',3)");f.clear()
        for(table in listOf("recognition_refinement_plan","recognition_repairs","recognition_contacts"))assertEquals(0,android.database.DatabaseUtils.longForQuery(f.readableDatabase,"SELECT COUNT(*) FROM $table",null))
    }}
    @Test fun inaccessibleRetriesDoNotScheduleWorkAndDeferredErrorsAreNotDone(){FaceStore(app){1000}.use{f->
        f.save(photo(1),emptyList(),"decode");assertNotNull(f.nextRetryDelay(listOf(photo(1))));assertNull(f.nextRetryDelay(emptyList()))
        val counts=f.workCounts(listOf(photo(1)));assertEquals(0,counts.done);assertEquals(1,counts.failed);assertEquals(1,counts.pending)
    }}
    @Test fun detectorBusyDoesNotConsumePhotoAttempts(){FaceStore(app).use{f->
        assertThrows(DetectorUnavailable::class.java){FaceProcessing.run(f,listOf(photo(1)),{true},{throw DetectorUnavailable("busy")})}
        assertEquals(0,f.summary().errors);assertEquals(listOf(photo(1)),f.pending(listOf(photo(1)),false));assertNull(f.nextRetryDelay(listOf(photo(1))))
    }}
    @Test fun preEncodingFailuresUseTheSharedRetryLedger(){FaceStore(app).use{f->
        f.save(photo(1),listOf(face));f.signatureFailure(photo(1),IllegalStateException("decode")){true}
        assertEquals(1,f.signatureSummary().errors);assertNotNull(f.nextRetryDelay(listOf(photo(1))));assertTrue(f.pendingSignatures(listOf(photo(1)),false).isEmpty())
    }}
    @Test fun resumeIsPersistedEvenWhileBackgroundWorkerIsDraining(){
        AutoPeople.pauseForDay(app);FaceJobs.publish(FaceJobs.State(mode=FaceScanService.AUTO,pausing=true,background=true))
        val c=Robolectric.buildService(FaceScanService::class.java).create();c.get().onStartCommand(Intent(app,FaceScanService::class.java).setAction(FaceScanService.GROUP),0,1)
        assertTrue(AutoPeople.enabled(app));assertEquals(0,AutoPeople.pauseUntil(app));c.destroy()
    }
    class SlowQuery:AutoPeopleJob(){val entered=CountDownLatch(1);val release=CountDownLatch(1);override fun photos(signal:CancellationSignal):GalleryRepository.Result {entered.countDown();release.await(5,TimeUnit.SECONDS);return GalleryRepository.Result(emptyList(),0)}}
    @Test fun backgroundMediaQueryDoesNotHoldPeopleLock(){
        val c=Robolectric.buildService(SlowQuery::class.java).create();val job=c.get();val pool=Executors.newSingleThreadExecutor()
        try{assertTrue(job.onStartJob(params()));assertTrue(job.entered.await(5,TimeUnit.SECONDS));assertEquals(42,pool.submit<Int>{FaceWork.read{42}}.get(1,TimeUnit.SECONDS))}finally{job.release.countDown();finish();pool.shutdownNow();c.destroy()}
    }
    @Test fun repairCursorAdvancesAcrossSlices(){FaceStore(app).use{f->
        val p=PeopleStore(f);val ids=(1..7).map{seed(f,it)};ids.zipWithNext().forEach{(a,b)->p.link(a,b,"merge","auto","Test valid join")}
        var last=0L;repeat(3){var visits=0;p.repairJoins{++visits<=2};val checkpoint=RecognitionState.repair(f.readableDatabase,"joins");assertTrue(checkpoint.cursor>last);last=checkpoint.cursor}
        p.repairJoins{true};assertTrue(RecognitionState.repair(f.readableDatabase,"joins").complete)
    }}
    private fun capsule(id:Int,time:Long=120000)=GroupRules.Capsule(id.toLong(),mutableSetOf(id.toLong()),mutableSetOf("content://index/$id"),mutableSetOf("content://index/$id"),mutableListOf(GroupRules.Prototype(GroupRules.Member(GroupRules.Key("content://index/$id",0),face,time,id.toLong(),null,"known",1f,"",true),vector())))
    @Test fun comparisonFingerprintIncludesTimeAndPose(){val a=capsule(1);val b=capsule(1,121000);assertNotEquals(IdentityComparisonCache.fingerprint(a),IdentityComparisonCache.fingerprint(b));val ref=a.prototypes.single();a.prototypes[0]=ref.copy(member=ref.member.copy(face=face.copy(yaw=20f)));assertNotEquals(IdentityComparisonCache.fingerprint(a),IdentityComparisonCache.fingerprint(capsule(1)))}
    @Test fun unchangedPairEvidenceSurvivesAnotherGroupBeingAdded(){
        val groups=listOf(capsule(1),capsule(2));var computes=0;val first=IdentityComparisonCache(listOf(1,2),.68f);first.pairs(groups){true};first.get(1,2){computes++;IdentityComparisonCache.Evidence(.9f,.9f)}
        val next=IdentityComparisonCache(listOf(1,2,3),.68f);next.pairs(groups+capsule(3)){true};next.get(1,2){computes++;IdentityComparisonCache.Evidence(.9f,.9f)};assertEquals(1,computes)
    }
    @Test fun exactIndexMatchesExhaustiveCandidates(){
        val random=java.util.Random(17);val groups=(1..40).map{capsule(it).apply{val ref=prototypes[0];val v=FloatArray(128){i->if(i<6)random.nextFloat()else 0f};prototypes[0]=ref.copy(vector=FaceVectors.normalize(v))}}
        val indexed=FaceCandidateIndex(groups).pairs(.68f){true}!!.filter{(a,b)->FaceVectors.cosine(groups[(a-1).toInt()].prototypes[0].vector,groups[(b-1).toInt()].prototypes[0].vector)>=.68f}.toSet()
        val exhaustive=buildSet{for(i in groups.indices)for(j in i+1 until groups.size)if(FaceVectors.cosine(groups[i].prototypes[0].vector,groups[j].prototypes[0].vector)>=.68f)add(groups[i].id to groups[j].id)};assertEquals(exhaustive,indexed)
    }
    class CompletedForeground:FaceScanService(){var records=emptyList<PhotoRecord>();override fun accessiblePhotos(signal:CancellationSignal)=records}
    @Test fun foregroundAlsoSkipsMatchingForTwentyZeroFaceResults(){
        FaceStore(app).use{f->val id=seed(f);PeopleStore(f).rename(id,"Saved");RecognitionState.grouped(f.writableDatabase,f.evidenceRevision());(2..21).forEach{f.save(photo(it),emptyList())}}
        val phases=mutableListOf<String>();val observer:(FaceJobs.State)->Unit={phases+=it.message};FaceJobs.observe(observer)
        val c=Robolectric.buildService(CompletedForeground::class.java).create();val service=c.get();service.records=(1..21).map(::photo)
        try{service.onStartCommand(Intent(app,FaceScanService::class.java).setAction(FaceScanService.GROUP),0,1);val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(FaceJobs.state.busy && System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.yield()};assertFalse(FaceJobs.state.busy);assertFalse(phases.any{it.contains("saved matches") || it.contains("Sorting people") || it.contains("Comparing people")})}finally{FaceJobs.remove(observer);c.destroy()}
    }
    @Test fun contactTraversalContinuesAfterReopeningTheDatabase(){
        val portraits=(1..7).map{ContactRecognition.Photo(ContactNames.Contact("content://com.android.contacts/contacts/lookup/audit$it/$it","Name $it"),"same")};var inferred=0
        repeat(3){FaceStore(app).use{f->assertFalse(ContactRecognition.scan(f,portraits,{true},limit=2,read={byteArrayOf(1)},infer={inferred++;vector()}))}}
        FaceStore(app).use{f->assertTrue(ContactRecognition.scan(f,portraits,{true},limit=2,read={byteArrayOf(1)},infer={inferred++;vector()}))};assertEquals(7,inferred)
    }
    @Test fun contactCommitRechecksCancellationAfterInference(){FaceStore(app).use{f->
        val portrait=ContactRecognition.Photo(ContactNames.Contact("content://com.android.contacts/contacts/lookup/cancel/1","Name"),"same");var checks=0
        assertFalse(ContactRecognition.scan(f,listOf(portrait),{++checks<=2},read={byteArrayOf(1)},infer={vector()}));assertNull(ContactRecognition.cached(f,portrait.contact.lookup))
    }}
    @Test fun fullPeopleSnapshotSurvivesMixedVideoAccessWithoutPhotoIndex(){
        FaceStore(app).use{f->val id=seed(f);PeopleStore(f).rename(id,"Saved")}
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        PhotoIndex.clear(app)
        val companion=PeopleActivity.Companion
        val read=companion.javaClass.declaredMethods.single{it.name=="readDataInternal"}.apply{isAccessible=true}
        read.invoke(companion,app,listOf(photo(1)),CancellationSignal(),false,{true},true)
        val saved=companion.javaClass.declaredMethods.single{it.name=="saved"}.apply{isAccessible=true}
        assertNotNull(saved.invoke(companion,app));Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO);assertNotNull(saved.invoke(companion,app))
    }
}
