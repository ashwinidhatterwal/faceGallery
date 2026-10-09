package com.mosaic.gallery

import android.Manifest
import android.app.job.*
import android.content.Intent
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class TimedPauseTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val scheduler get()=app.getSystemService(JobScheduler::class.java)
    @Before fun setup(){app.getSharedPreferences("automatic-people",0).edit().clear().commit();scheduler.cancelAll();Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES);FaceJobs.publish(FaceJobs.State())}
    @After fun end(){scheduler.cancelAll();app.getSharedPreferences("automatic-people",0).edit().clear().commit()}
    @Test fun notificationPauseCreatesOneDurableNextDayWake(){
        AutoPeople.ensure(app);AutoPeople.pauseForDay(app)
        assertFalse(AutoPeople.enabled(app));assertEquals(listOf(AutoPeople.RESUME),scheduler.allPendingJobs.map{it.id})
        val timer=scheduler.getPendingJob(AutoPeople.RESUME)!!;assertTrue(timer.isPersisted);assertTrue(kotlin.math.abs(86400000L-timer.minLatencyMillis)<=1000L)
        repeat(3){AutoPeople.ensure(app)};assertEquals(1,scheduler.allPendingJobs.size)
        assertFalse(app.getSharedPreferences("automatic-people",0).getBoolean("paused",false))
    }
    @Test fun restartRestoresRemainingPauseInsteadOfASecondDay(){
        AutoPeople.pauseForDay(app);val until=AutoPeople.pauseUntil(app);scheduler.cancelAll()
        PeopleBoot().onReceive(app,Intent(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(AutoPeople.enabled(app));assertEquals(until,scheduler.getPendingJob(AutoPeople.RESUME)!!.extras.getLong("until"))
    }
    @Test fun expirySchedulesRecognitionWithoutOpeningGallery(){
        AutoPeople.pauseForDay(app);app.getSharedPreferences("automatic-people",0).edit().putLong("pause-until",System.currentTimeMillis()-1).commit()
        val c=Robolectric.buildService(AutoPeopleJob::class.java).create()
        val params=ReflectionHelpers.newInstance(JobParameters::class.java).also{ReflectionHelpers.setField(it,"jobId",AutoPeople.RESUME)}
        assertFalse(c.get().onStartJob(params));assertTrue(AutoPeople.enabled(app));assertNotNull(scheduler.getPendingJob(AutoPeople.BATCH));assertNull(scheduler.getPendingJob(AutoPeople.RESUME));c.destroy()
    }
    @Test fun permanentSettingsPauseOverridesNextDayWake(){AutoPeople.pauseForDay(app);AutoPeople.pause(app);AutoPeople.ensure(app);assertTrue(scheduler.allPendingJobs.isEmpty());assertFalse(AutoPeople.enabled(app,System.currentTimeMillis()+2*86400000L))}
    @Test fun explicitResumeCancelsTimedWake(){AutoPeople.pauseForDay(app);AutoPeople.resume(app);assertTrue(AutoPeople.enabled(app));assertNull(scheduler.getPendingJob(AutoPeople.RESUME))}
    @Test fun notificationIntentUsesTimedPause(){val c=Robolectric.buildService(FaceScanService::class.java).create();c.get().onStartCommand(Intent(app,FaceScanService::class.java).setAction(FaceScanService.PAUSE_DAY),0,1);assertFalse(AutoPeople.enabled(app));assertNotNull(scheduler.getPendingJob(AutoPeople.RESUME));c.destroy()}
}
