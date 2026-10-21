package com.mosaic.gallery

import android.Manifest
import android.app.NotificationManager
import android.app.job.*
import android.content.Intent
import android.net.Uri
import android.os.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RecognitionIdleTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val scheduler get()=app.getSystemService(JobScheduler::class.java)
    private val notifications get()=app.getSystemService(NotificationManager::class.java)
    private fun photo()=PhotoRecord(1,Uri.parse("content://idle/1"),"one.jpg",120000,400,300,"Camera")
    @Before fun setup(){
        app.deleteDatabase("faces.db");RecognitionConsent.accept(app);FaceJobs.publish(FaceJobs.State())
        app.getSharedPreferences("automatic-people",0).edit().clear().commit();AutoPeople.resume(app)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)
        scheduler.cancelAll();notifications.cancelAll()
    }
    @After fun cleanup(){scheduler.cancelAll();notifications.cancelAll();app.deleteDatabase("faces.db")}
    private fun params()=ReflectionHelpers.newInstance(JobParameters::class.java).also{ReflectionHelpers.setField(it,"jobId",AutoPeople.BATCH)}
    private fun finish(){val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while((FaceWork.automatic || FaceJobs.state.busy) && System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.yield()};assertFalse(FaceWork.automatic);assertFalse(FaceJobs.state.busy)}
    open class Empty:AutoPeopleJob(){override fun photos(signal:CancellationSignal)=GalleryRepository.Result(emptyList(),0)}
    class Unavailable:AutoPeopleJob(){
        var record:PhotoRecord?=null;var calls=0
        override fun photos(signal:CancellationSignal)=GalleryRepository.Result(listOf(record!!),0)
        override fun detect(store:FaceStore,pending:List<PhotoRecord>,keepGoing:()->Boolean){calls++;pending.forEach{store.save(it,emptyList(),"decode")}}
    }
    class ForegroundEmpty:FaceScanService(){override fun accessiblePhotos(signal:CancellationSignal)=emptyList<PhotoRecord>()}
    @Test fun completedBackgroundRunRemovesStaleBatchAndNotificationButKeepsEventWatcher(){
        AutoPeople.ensure(app,0)
        val c=Robolectric.buildService(Empty::class.java).create()
        try{assertTrue(c.get().onStartJob(params()));finish();assertNull(scheduler.getPendingJob(AutoPeople.BATCH));assertNull(scheduler.getPendingJob(AutoPeople.DISCOVER));assertNotNull(scheduler.getPendingJob(AutoPeople.WATCH));assertTrue(notifications.activeNotifications.isEmpty())}finally{c.destroy()}
    }
    @Test fun unavailablePhotoSleepsWithoutNotificationAndStopsAfterBoundedRetries(){
        AutoPeople.ensure(app,0)
        val c=Robolectric.buildService(Unavailable::class.java).create();val job=c.get();job.record=photo()
        try{
            repeat(3){attempt->
                if(attempt>0)FaceStore(app).use{it.writableDatabase.execSQL("UPDATE operation_retries SET next_time=0")}
                assertTrue(job.onStartJob(params()));finish();assertTrue(notifications.activeNotifications.isEmpty())
                if(attempt<2){val retry=scheduler.getPendingJob(AutoPeople.BATCH)!!;assertTrue(retry.minLatencyMillis>=60_000);assertFalse(retry.isPeriodic)}
            }
            assertEquals(3,job.calls);assertNull(scheduler.getPendingJob(AutoPeople.BATCH));assertNull(scheduler.getPendingJob(AutoPeople.DISCOVER))
            assertTrue(job.onStartJob(params()));finish();assertEquals(3,job.calls);assertNull(scheduler.getPendingJob(AutoPeople.BATCH))
        }finally{c.destroy()}
    }
    class FailedQuery:AutoPeopleJob(){override fun photos(signal:CancellationSignal):GalleryRepository.Result=throw IllegalStateException("provider unavailable")}
    @Test fun runLevelFailuresAlsoStopAfterThreeAttempts(){
        AutoPeople.ensure(app,0);val c=Robolectric.buildService(FailedQuery::class.java).create()
        try{repeat(3){i->assertTrue(c.get().onStartJob(params()));finish();assertTrue(notifications.activeNotifications.isEmpty());if(i<2)assertNotNull(scheduler.getPendingJob(AutoPeople.BATCH))};assertNull(scheduler.getPendingJob(AutoPeople.BATCH))}finally{c.destroy()}
    }
    @Test fun obsoleteRefinementRetryDoesNotKeepSchedulingWakeups(){FaceStore(app).use{f->
        val photo=photo();val face=FaceObservation(.1f,.1f,.4f,.5f,0f,0f,0f,120f,.95f,"Anchor")
        f.save(photo,listOf(face));f.saveSignature(photo.uri.toString(),0,FloatArray(128){if(it==0)1f else 0f})
        val people=PeopleStore(f);val id=people.record(people.members().single(),GroupRules.Decision(seed=true,status="known"))!!
        f.refinementFailed(GroupRules.Key(photo.uri.toString(),0));people.rename(id,"Saved person")
        assertTrue(SignatureRefinement.candidates(f,listOf(photo),ignoreBackoff=true).isEmpty())
        assertNull(f.nextRetryDelay(listOf(photo)))
    }}
    @Test fun completedForegroundRunCancelsStartupBatchAndReleasesWakeLock(){
        val c=Robolectric.buildService(ForegroundEmpty::class.java).create()
        try{c.get().onStartCommand(Intent(app,FaceScanService::class.java).setAction(FaceScanService.GROUP),0,1);finish();assertNull(scheduler.getPendingJob(AutoPeople.BATCH));assertTrue(notifications.activeNotifications.isEmpty());assertFalse(org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock().isHeld);assertTrue(Shadows.shadowOf(c.get()).isStoppedBySelf)}finally{c.destroy()}
    }
    @Test fun photoEventDuringAnActiveSliceGetsOneFollowupInsteadOfBeingLost(){
        assertTrue(FaceWork.begin())
        try{AutoPeople.request(app,0);assertNull(scheduler.getPendingJob(AutoPeople.BATCH))}finally{FaceWork.end()}
        AutoPeople.settle(app,null);assertEquals(1000L,scheduler.getPendingJob(AutoPeople.BATCH)!!.minLatencyMillis)
        AutoPeople.settle(app,null);assertNull(scheduler.getPendingJob(AutoPeople.BATCH))
    }
    @Test fun idleCleanupNeverRemovesAnActiveForegroundProgressNotification(){
        FaceJobs.publish(FaceJobs.State(mode=FaceScanService.GROUP))
        FaceNotifications.update(app,RecognitionProgress.Snapshot("Photos checked",1,2));FaceNotifications.idle(app)
        assertEquals(1,notifications.activeNotifications.size)
        FaceJobs.publish(FaceJobs.State());FaceNotifications.idle(app);assertTrue(notifications.activeNotifications.isEmpty())
    }
}
