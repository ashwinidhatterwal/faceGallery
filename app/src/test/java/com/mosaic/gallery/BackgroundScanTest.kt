package com.mosaic.gallery

import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.CancellationSignal
import android.os.Looper
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35])
class BackgroundScanTest {
    class ControlledService:FaceScanService(){
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val returned=CountDownLatch(1)
        @Volatile var calls=0
        override fun accessiblePhotos(signal:CancellationSignal):List<PhotoRecord>{calls++;entered.countDown();release.await(5,TimeUnit.SECONDS);returned.countDown();return emptyList()}
    }
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun reset(){FaceJobs.publish(FaceJobs.State());app.deleteDatabase("faces.db")}
    @After fun cleanup(){FaceJobs.publish(FaceJobs.State());app.deleteDatabase("faces.db")}
    private fun waitUntilIdle(){
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(FaceJobs.state.busy && System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.yield()}
        assertFalse(FaceJobs.state.busy)
    }
    @Test fun automaticSessionContinuesAfterClosingGalleryAndKeepsRecoveryScheduled(){
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.READ_MEDIA_IMAGES)
        app.getSharedPreferences("automatic-people",0).edit().clear().commit()
        val controller=Robolectric.buildService(ControlledService::class.java).create();val service=controller.get()
        service.onStartCommand(Intent(app,FaceScanService::class.java).setAction(FaceScanService.AUTO),0,1)
        assertTrue(service.entered.await(5,TimeUnit.SECONDS));assertEquals(FaceScanService.GROUP,FaceJobs.state.mode)
        val screen=Robolectric.buildActivity(android.app.Activity::class.java).setup();screen.pause().stop().destroy()
        assertTrue(FaceJobs.state.busy)
        assertTrue(org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock().isHeld)
        assertTrue(app.getSystemService(android.app.job.JobScheduler::class.java).getPendingJob(AutoPeople.BATCH)!!.isPersisted)
        assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.isNotEmpty())
        val notification=app.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertEquals("Working quietly in the background",notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT))
        assertFalse(notification.extras.containsKey(android.app.Notification.EXTRA_PROGRESS))
        assertEquals("Pause for 24 hours",notification.actions.single().title)
        service.release.countDown();waitUntilIdle();controller.destroy()
    }
    @Test fun automaticSessionHonorsSavedPauseAndEditingPauseIsTemporary(){
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.READ_MEDIA_IMAGES)
        AutoPeople.pause(app)
        val controller=Robolectric.buildService(ControlledService::class.java).create();val service=controller.get()
        service.onStartCommand(Intent(app,FaceScanService::class.java).setAction(FaceScanService.AUTO),0,1)
        assertFalse(FaceJobs.state.busy);assertEquals(0,service.calls)
        service.onStartCommand(Intent(app,FaceScanService::class.java).setAction(FaceScanService.GROUP),0,2)
        assertTrue(service.entered.await(5,TimeUnit.SECONDS));assertTrue(AutoPeople.enabled(app))
        service.onStartCommand(Intent(app,FaceScanService::class.java).setAction(FaceScanService.EDIT_PAUSE),0,3)
        assertTrue(AutoPeople.enabled(app));assertTrue(FaceJobs.state.pausing)
        service.release.countDown();waitUntilIdle();controller.destroy()
    }
    @Test fun screenPauseAndLockDoNotCancelServiceWork(){
        val controller=Robolectric.buildService(ControlledService::class.java).create();val service=controller.get()
        service.onStartCommand(Intent(app,FaceScanService::class.java).setAction(FaceScanService.DETECT),0,2)
        assertTrue(service.entered.await(5,TimeUnit.SECONDS));assertTrue(FaceJobs.state.busy)
        val wake=org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock()
        assertTrue(wake.isHeld)
        val screen=Robolectric.buildActivity(FaceScanActivity::class.java).create().start().resume();screen.pause().stop()
        assertTrue(FaceJobs.state.busy);assertFalse(FaceJobs.state.pausing);assertTrue(wake.isHeld)
        assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.isNotEmpty())
        service.release.countDown();waitUntilIdle();assertFalse(wake.isHeld);screen.destroy();controller.destroy()
    }
    @Test fun pauseFromNotificationStopsAndReleasesResources(){
        val controller=Robolectric.buildService(ControlledService::class.java).create();val service=controller.get()
        service.onStartCommand(Intent(app,FaceScanService::class.java).setAction(FaceScanService.SIGNATURES),0,1);assertTrue(service.entered.await(5,TimeUnit.SECONDS))
        val notification=app.getSystemService(NotificationManager::class.java).activeNotifications.first().notification
        assertEquals("Pause for 24 hours",notification.actions[0].title)
        notification.actions[0].actionIntent.send()
        val command=Shadows.shadowOf(app).nextStartedService;assertEquals(FaceScanService.PAUSE_DAY,command.action)
        service.onStartCommand(command,0,2);assertTrue(FaceJobs.state.pausing)
        val wake=org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock()
        service.release.countDown();waitUntilIdle();assertFalse(wake.isHeld);assertTrue(FaceJobs.state.message.startsWith("Paused"));assertFalse(AutoPeople.enabled(app));assertEquals(listOf(AutoPeople.RESUME),app.getSystemService(android.app.job.JobScheduler::class.java).allPendingJobs.map{it.id});controller.destroy()
    }
    @Test fun duplicateStartDoesNotCreateAnotherScan(){
        val controller=Robolectric.buildService(ControlledService::class.java).create();val service=controller.get()
        service.onStartCommand(Intent(app,FaceScanService::class.java).setAction(FaceScanService.DETECT),0,1);assertTrue(service.entered.await(5,TimeUnit.SECONDS))
        service.onStartCommand(Intent(app,FaceScanService::class.java).setAction(FaceScanService.SIGNATURES),0,2)
        assertEquals(FaceScanService.DETECT,FaceJobs.state.mode);assertEquals(1,service.calls)
        service.release.countDown();waitUntilIdle();controller.destroy()
    }
    @Test fun timeoutStopsPromptlyWithoutHoldingWakeLock(){
        val controller=Robolectric.buildService(ControlledService::class.java).create();val service=controller.get()
        service.onStartCommand(Intent(app,FaceScanService::class.java).setAction(FaceScanService.DETECT),0,1);assertTrue(service.entered.await(5,TimeUnit.SECONDS))
        val wake=org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock()
        service.onTimeout(1,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);assertFalse(wake.isHeld);assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
        service.release.countDown();waitUntilIdle();controller.destroy()
    }
    @Test fun detachedScreenObserversAreNotRetained(){
        var updates=0;val listener:(FaceJobs.State)->Unit={updates++}
        FaceJobs.observe(listener);FaceJobs.publish(FaceJobs.State(mode=FaceScanService.DETECT));FaceJobs.remove(listener)
        FaceJobs.publish(FaceJobs.State(mode=FaceScanService.SIGNATURES));assertEquals(2,updates)
    }
    @Test fun missingCommandNeverStartsProcessing(){
        val controller=Robolectric.buildService(ControlledService::class.java).create();val service=controller.get()
        assertEquals(android.app.Service.START_NOT_STICKY,service.onStartCommand(null,0,1));assertFalse(FaceJobs.state.busy);assertEquals(0,service.calls);controller.destroy()
    }
    @Test fun legacyForegroundTypeUsesSupportedApi(){assertEquals(0,FaceScanService.typeFor(28));assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,FaceScanService.typeFor(29));assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,FaceScanService.typeFor(35))}
}
