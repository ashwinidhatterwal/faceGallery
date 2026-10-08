package com.mosaic.gallery

import android.Manifest
import android.app.job.*
import android.content.*
import android.net.Uri
import android.os.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class AutomaticPeopleTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val photo=PhotoRecord(1,Uri.parse("content://auto/1"),"one.jpg",120000,400,300,"Camera")
    private val face=FaceObservation(.1f,.1f,.4f,.5f,0f,0f,0f,120f,.95f,"Anchor")
    @Before fun reset(){FaceJobs.publish(FaceJobs.State());app.deleteDatabase("faces.db");app.getSharedPreferences("automatic-people",0).edit().clear().commit();app.getSystemService(JobScheduler::class.java).cancelAll();Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES);ShadowSystemClock.advanceBy(Duration.ofMinutes(2));ReflectionHelpers.setField(FaceWork,"editingUntil",0L)}
    @After fun end(){Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES);app.getSystemService(JobScheduler::class.java).cancelAll();app.deleteDatabase("faces.db")}
    @Test fun schedulingIsUniqueDurableAndNeedsNoNetwork(){
        repeat(3){AutoPeople.ensure(app)};val s=app.getSystemService(JobScheduler::class.java)
        assertEquals(3,s.allPendingJobs.size)
        val batch=s.getPendingJob(AutoPeople.BATCH)!!;assertTrue(batch.isPersisted);assertTrue(batch.isRequireBatteryNotLow);assertTrue(batch.isRequireStorageNotLow);assertEquals(JobInfo.NETWORK_TYPE_NONE,batch.networkType)
        val periodic=s.getPendingJob(AutoPeople.DISCOVER)!!;assertTrue(periodic.isPeriodic);assertTrue(periodic.isPersisted);assertFalse(periodic.isRequireDeviceIdle)
        assertNotNull(s.getPendingJob(AutoPeople.WATCH)!!.triggerContentUris)
    }
    @Test fun upgradeRemovesOldIdleOnlyDiscoveryJob(){
        val scheduler=app.getSystemService(JobScheduler::class.java)
        scheduler.schedule(JobInfo.Builder(AutoPeople.DISCOVER,ComponentName(app,AutoPeopleJob::class.java)).setPeriodic(2*60*60_000L).setRequiresDeviceIdle(true).setPersisted(true).build())
        AutoPeople.ensure(app)
        assertFalse(scheduler.getPendingJob(AutoPeople.DISCOVER)!!.isRequireDeviceIdle)
    }
    @Test fun pausePersistsAndBootDoesNotResumeItUntilAnExplicitScan(){
        AutoPeople.ensure(app);AutoPeople.pause(app)
        assertFalse(AutoPeople.enabled(app));assertFalse(AutoPeople.canStartVisible(app))
        AutoPeople.ensure(app);PeopleBoot().onReceive(app,Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(app.getSystemService(JobScheduler::class.java).allPendingJobs.isEmpty())
        assertTrue(app.getSharedPreferences("automatic-people",0).getBoolean("paused",false))
        AutoPeople.resume(app);AutoPeople.ensure(app)
        assertEquals(3,app.getSystemService(JobScheduler::class.java).allPendingJobs.size)
    }
    @Test fun pendingCheckUsesSavedDetectionAndSignatures(){
        assertTrue(AutoPeople.hasPending(app,listOf(photo)))
        FaceStore(app).use{store->store.save(photo,emptyList())}
        assertFalse(AutoPeople.hasPending(app,listOf(photo)))
        assertTrue(AutoPeople.hasPending(app,listOf(photo.copy(modifiedMillis=999))))
        FaceStore(app).use{store->store.save(photo,listOf(face))}
        assertTrue(AutoPeople.hasPending(app,listOf(photo)))
    }
    @Test fun galleryAutomaticallyLaunchesCompleteRecognitionWithoutOpeningPeople(){
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val screen=Robolectric.buildActivity(MainActivity::class.java).create().start().visible();val activity=screen.get()
        fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}
        field("allPhotos").set(activity,listOf(photo));field("active").setBoolean(activity,true)
        val start=MainActivity::class.java.getDeclaredMethod("startAutomaticRecognition").apply{isAccessible=true}
        start.invoke(activity)
        (field("io").get(activity) as java.util.concurrent.ExecutorService).submit{}.get(5,TimeUnit.SECONDS)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val command=Shadows.shadowOf(activity).nextStartedService!!
        assertEquals(FaceScanService::class.java.name,command.component!!.className);assertEquals(FaceScanService.AUTO,command.action)
        assertTrue(app.getSystemService(JobScheduler::class.java).getPendingJob(AutoPeople.BATCH)!!.isPersisted)
        FaceJobs.publish(FaceJobs.State(mode=FaceScanService.GROUP));start.invoke(activity)
        assertNull(Shadows.shadowOf(activity).nextStartedService)
        FaceJobs.publish(FaceJobs.State());screen.stop().destroy()
    }
    @Test @Config(sdk=[28]) fun savedIndexStartsRecognitionWhileGalleryValidationIsStillBlocked(){
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        FaceStore(app).use{it.summary()}
        app.getSharedPreferences("startup-access",0).edit().putBoolean("contacts-asked",true).commit()
        PhotoIndex.save(app,GalleryRepository.Result(listOf(photo),0));GalleryData.invalidate()
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media",object:ContentProvider(){
            override fun onCreate()=true
            override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):android.database.Cursor{
                entered.countDown();check(release.await(5,TimeUnit.SECONDS))
                return android.database.MatrixCursor(p!!).apply{addRow(arrayOf<Any>(1,"1.jpg",120000,120,400,300,"Camera",1000,"",1))}
            }
            override fun getType(uri:Uri)="image/jpeg"
            override fun insert(uri:Uri,v:ContentValues?):Uri?=null
            override fun delete(uri:Uri,s:String?,a:Array<out String>?)=0
            override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
        })
        val screen=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try{
            assertTrue(entered.await(5,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(FaceScanService.AUTO,Shadows.shadowOf(screen.get()).nextStartedService!!.action)
            assertEquals(1L,release.count) // Service started before the media query completed.
        }finally{release.countDown();screen.pause().stop().destroy();PhotoIndex.clear(app)}
    }
    @Test fun closingGalleryDuringPreflightNeverLaunchesAServiceFromBackground(){
        val screen=Robolectric.buildActivity(MainActivity::class.java).create().start().visible();val activity=screen.get()
        fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}
        field("allPhotos").set(activity,listOf(photo));field("active").setBoolean(activity,true)
        MainActivity::class.java.getDeclaredMethod("startAutomaticRecognition").apply{isAccessible=true}.invoke(activity)
        field("active").setBoolean(activity,false)
        (field("io").get(activity) as java.util.concurrent.ExecutorService).submit{}.get(5,TimeUnit.SECONDS)
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(Shadows.shadowOf(activity).nextStartedService)
        screen.stop().destroy()
    }
    @Test fun noWorkIsScheduledWithoutPhotoAccess(){Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES);AutoPeople.ensure(app);assertTrue(app.getSystemService(JobScheduler::class.java).allPendingJobs.isEmpty())}
    @Test fun heatAndBatteryPolicyHasNoChargingOverrideForHeat(){
        fun state(b:Int=70,c:Boolean=false,t:Int=320,h:Int=0,s:Boolean=false)=FaceHeat.State(b,c,t,h,s)
        assertTrue(FaceHeat.safe(state()));assertFalse(FaceHeat.safe(state(t=400)));assertFalse(FaceHeat.safe(state(h=2)));assertTrue(FaceHeat.safe(state(b=20)));assertFalse(FaceHeat.safe(state(b=19)));assertFalse(FaceHeat.safe(state(s=true)));assertFalse(FaceHeat.safe(state(c=true,t=400)));assertTrue(FaceHeat.safe(state(b=20,c=true)));assertFalse(FaceHeat.safe(state(b=19,c=true)));assertTrue(FaceHeat.safe(state(t=0)))
    }
    @Test fun failedUnchangedPhotosAreNotAutomaticallyRetriedForever(){FaceStore(app).use{f->f.save(photo,emptyList(),"bad image");assertEquals(listOf(photo),f.pending(listOf(photo)));assertTrue(f.pending(listOf(photo),false).isEmpty());assertEquals(1,f.pending(listOf(photo.copy(modifiedMillis=999)),false).size)}}
    @Test fun failedSignaturesRequireManualRetryOrOneRefinement(){FaceStore(app).use{f->f.save(photo,listOf(face));f.saveSignature(photo.uri.toString(),0,null,"error","decode");assertEquals(1,f.pendingSignatures(listOf(photo)).size);assertTrue(f.pendingSignatures(listOf(photo),false).isEmpty());val key=GroupRules.Key(photo.uri.toString(),0);assertTrue(f.needsRefinement(key));f.refined(key);assertFalse(f.needsRefinement(key))}}
    @Test fun restrictedOrIncompletePhotoListsPreserveSavedIdentities(){FaceStore(app).use{f->f.save(photo,listOf(face));f.saveSignature(photo.uri.toString(),0,FloatArray(128){if(it==0)1f else 0f});val p=PeopleStore(f);p.nameFace(GroupRules.Key(photo.uri.toString(),0),"Ankita");f.retain(emptyList(),false);assertEquals("Ankita",p.names().values.single());assertEquals(1,f.summary().faces);f.retain(listOf(photo.copy(modifiedMillis=999)),false);assertEquals(1,f.summary().faces);assertTrue(f.observations(photo.uri.toString()).isEmpty())}}
    @Test fun completionCannotClearNewerGroupingEvidence(){AutoPeople.dirty(app);val old=AutoPeople.revision(app);AutoPeople.dirty(app);AutoPeople.grouped(app,old);assertTrue(AutoPeople.needsGrouping(app));AutoPeople.grouped(app,AutoPeople.revision(app));assertFalse(AutoPeople.needsGrouping(app))}
    @Test fun upgradeReconsidersPreviouslyCompletedGroupingOnce(){
        val prefs=app.getSharedPreferences("automatic-people",0)
        prefs.edit().putLong("revision",8).putLong("grouped",8).commit()
        assertTrue(AutoPeople.needsGrouping(app));AutoPeople.grouped(app,8)
        assertFalse(AutoPeople.needsGrouping(app))
    }
    class Pipeline:AutoPeopleJob(){
        var refinements=0;var detections=0;var encodes=0;var records=emptyList<PhotoRecord>()
        val photo=PhotoRecord(1,Uri.parse("content://auto/1"),"one.jpg",120000,400,300,"Camera")
        override fun photos(signal:CancellationSignal)=GalleryRepository.Result(records.ifEmpty{listOf(photo)},0)
        override fun detect(store:FaceStore,pending:List<PhotoRecord>,keepGoing:()->Boolean){detections+=pending.size;FaceProcessing.run(store,pending,keepGoing,{listOf(FaceObservation(.1f,.1f,.4f,.5f,0f,0f,0f,120f,.95f,"Anchor"))})}
        override fun refine(store:FaceStore,photo:PhotoRecord,keys:Set<GroupRules.Key>,keepGoing:()->Boolean){
            refinements++
            keys.forEach{if(keepGoing()){store.saveSignature(it.uri,it.ordinal,FloatArray(128){i->if(i==0)1f else 0f});store.refined(it)}}
        }
        override fun signatures(store:FaceStore,pending:List<PhotoRecord>,keepGoing:()->Boolean){pending.forEach{p->if(keepGoing()){encodes++;store.saveSignature(p.uri.toString(),0,FloatArray(128){if(it==0)1f else 0f})}}}
    }
    private fun parameters():JobParameters=ReflectionHelpers.newInstance(JobParameters::class.java).also{ReflectionHelpers.setField(it,"jobId",AutoPeople.BATCH)}
    private fun finish(){val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(FaceWork.automatic && System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.yield()};assertFalse(FaceWork.automatic)}
    @Test fun pipelineFindsFacesBuildsSignaturesAndGroupsWithoutManualSteps(){
        val c=Robolectric.buildService(Pipeline::class.java).create();val job=c.get()
        assertTrue(job.onStartJob(parameters()));finish()
        FaceStore(app).use{f->assertEquals(1,f.summary().faces);assertEquals(1,f.signatureSummary().ready);assertEquals(1,PeopleStore(f).capsules().size);assertTrue(PeopleStore(f).established().isEmpty())}
        assertEquals(1,job.detections);assertEquals(1,job.encodes)
        assertTrue(job.onStartJob(parameters()));finish();assertEquals(1,job.detections);assertEquals(1,job.encodes);c.destroy()
    }
    @Test fun refinementJobsDoNotRematchEveryPhotoAndSaveFinalRevision(){
        val c=Robolectric.buildService(Pipeline::class.java).create();val job=c.get()
        job.records=(1..4).map{photo.copy(id=it.toLong(),uri=Uri.parse("content://auto/$it"))}
        FaceStore(app).use{f->
            job.records.forEach{p->f.save(p,listOf(face));f.saveSignature(p.uri.toString(),0,FloatArray(128){if(it==0)1f else 0f})}
            val people=PeopleStore(f)
            people.nameFace(GroupRules.Key(job.records.first().uri.toString(),0),"Person")
            people.members().filter{it.person==null}.forEach{people.record(it,GroupRules.Decision())}
        }
        repeat(2){
            assertTrue(job.onStartJob(parameters()));finish()
            FaceStore(app).use{f->assertEquals(3,PeopleStore(f).pending().size)}
            assertTrue(AutoPeople.needsGrouping(app))
        }
        assertTrue(job.onStartJob(parameters()));finish()
        assertEquals(3,job.refinements)
        FaceStore(app).use{f->assertTrue(PeopleStore(f).pending().isEmpty())}
        assertFalse(AutoPeople.needsGrouping(app))
        assertTrue(job.onStartJob(parameters()));finish()
        assertEquals(3,job.refinements);assertEquals(0,job.detections);assertEquals(0,job.encodes)
        c.destroy()
    }
    @Test fun anEditStopsAutomaticWorkAndWaitsForItsIdleSignal(){
        val screen=Robolectric.buildActivity(android.app.Activity::class.java).setup();val gate=PeopleEditGate(screen.get());var called=false
        assertTrue(FaceWork.begin());gate.run{called=true};assertTrue(FaceWork.stopAutomatic);assertFalse(called);assertNull(Shadows.shadowOf(screen.get()).nextStartedService)
        FaceWork.end();Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(called);gate.cancel();screen.pause().stop().destroy()
    }
    @Test fun cachedPeopleInvalidateForIdentityMediaAndPermissionChanges(){
        val cache=PeopleCache<String>();cache.put(app,PeopleData.version,"old");assertEquals("old",cache.get(app));PeopleData.changed();assertNull(cache.get(app))
        cache.put(app,PeopleData.version,"new");GalleryData.invalidate();assertNull(cache.get(app))
        cache.put(app,PeopleData.version,"new");Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES);assertNull(cache.get(app))
    }
    @Test fun batchesAreBoundedAndReplaceTheirCompletedSchedulerEntry(){
        AutoPeople.ensure(app)
        val c=Robolectric.buildService(Pipeline::class.java).create();val job=c.get()
        job.records=(1..20).map{PhotoRecord(it.toLong(),Uri.parse("content://auto/$it"),"$it.jpg",it*120000L,400,300,"Camera")}
        assertTrue(job.onStartJob(parameters()));finish();assertEquals(8,job.detections);assertEquals(8,job.encodes)
        val followup=app.getSystemService(JobScheduler::class.java).getPendingJob(AutoPeople.BATCH)!!
        assertEquals(60_000L,followup.minLatencyMillis);c.destroy()
    }
    @Test fun aWarmPhoneDefersBeforeOpeningPhotosWithoutNotifications(){
        Shadows.shadowOf(app.getSystemService(PowerManager::class.java)).setCurrentThermalStatus(2)
        val c=Robolectric.buildService(Pipeline::class.java).create();val job=c.get()
        assertTrue(job.onStartJob(parameters()));finish();assertEquals(0,job.detections);assertEquals(0,job.encodes)
        assertEquals(15*60_000L,app.getSystemService(JobScheduler::class.java).getPendingJob(AutoPeople.BATCH)!!.minLatencyMillis)
        assertTrue(app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.isEmpty());c.destroy()
    }

    @Test fun coolingResumesBackgroundPipelineWithoutRepeatingFinishedRecognition(){
        val power=Shadows.shadowOf(app.getSystemService(PowerManager::class.java))
        val c=Robolectric.buildService(Pipeline::class.java).create();val job=c.get()
        power.setCurrentThermalStatus(2)
        assertTrue(job.onStartJob(parameters()));finish();assertEquals(0,job.detections)
        power.setCurrentThermalStatus(0)
        assertTrue(job.onStartJob(parameters()));finish();assertEquals(1,job.detections);assertEquals(1,job.encodes)
        assertTrue(job.onStartJob(parameters()));finish();assertEquals(1,job.detections);assertEquals(1,job.encodes)
        c.destroy()
    }
    @Test fun selectedPhotoMemoryCacheIsPermissionScoped(){
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        val cache=PeopleCache<String>();cache.put(app,PeopleData.version,"selected")
        assertEquals("selected",cache.get(app))
        GalleryData.invalidate();assertNull(cache.get(app))
        cache.put(app,PeopleData.version,"current")
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertNull(cache.get(app))
    }

}
