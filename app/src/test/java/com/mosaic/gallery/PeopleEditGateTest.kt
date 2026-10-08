package com.mosaic.gallery

import android.app.Activity
import android.os.Looper
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PeopleEditGateTest {
    @Before fun reset(){FaceJobs.publish(FaceJobs.State())}
    @After fun cleanup(){FaceJobs.publish(FaceJobs.State())}
    @Test fun editingRequestsPauseAndWaitsForWorkerCompletion(){
        val screen=Robolectric.buildActivity(Activity::class.java).setup()
        val gate=PeopleEditGate(screen.get());var applied=0
        FaceJobs.publish(FaceJobs.State(mode=FaceScanService.GROUP))
        gate.run{applied++}
        assertEquals(FaceScanService.EDIT_PAUSE,Shadows.shadowOf(screen.get()).nextStartedService.action)
        assertEquals(0,applied)
        FaceJobs.publish(FaceJobs.state.copy(pausing=true))
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,applied)
        FaceJobs.publish(FaceJobs.State());Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,applied)
        FaceJobs.publish(FaceJobs.State());assertEquals(1,applied)
        gate.cancel();screen.pause().stop().destroy()
    }
    @Test fun dismissCancelsPendingEdit(){
        val screen=Robolectric.buildActivity(Activity::class.java).setup();val gate=PeopleEditGate(screen.get());var applied=false
        FaceJobs.publish(FaceJobs.State(mode=FaceScanService.SIGNATURES,pausing=true))
        gate.run{applied=true};gate.cancel()
        FaceJobs.publish(FaceJobs.State());Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(applied);screen.pause().stop().destroy()
    }
    @Test fun cancellationAlsoInvalidatesAlreadyPostedCompletion(){
        val screen=Robolectric.buildActivity(Activity::class.java).setup();val gate=PeopleEditGate(screen.get());var applied=false
        FaceJobs.publish(FaceJobs.State(mode=FaceScanService.GROUP,pausing=true))
        gate.run{applied=true};FaceJobs.publish(FaceJobs.State());gate.cancel()
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(applied)
        screen.pause().stop().destroy()
    }
    @Test fun idleEditsNeedNoServiceCommand(){
        val screen=Robolectric.buildActivity(Activity::class.java).setup();val gate=PeopleEditGate(screen.get());var applied=false
        gate.run{applied=true};assertTrue(applied);assertNull(Shadows.shadowOf(screen.get()).nextStartedService)
        gate.cancel();screen.pause().stop().destroy()
    }
}
