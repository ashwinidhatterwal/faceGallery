package com.mosaic.gallery

import android.app.Notification
import android.app.job.JobScheduler
import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RecognitionFlowTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Test fun etaUsesOnlyMeasuredWorkAndResetsForANewStep(){
        var now=0L;val rate=RecognitionProgress{now}
        assertNull(rate.sample("Recognising photos",40,100).remainingMillis)
        now=10_000
        val next=rate.sample("Recognising photos",50,100)
        assertEquals(50_000L,next.remainingMillis);assertEquals(50,next.percent)
        assertTrue(next.text().contains("50 of 100 photos"));assertTrue(next.text().contains("left in this step"))
        assertNull(rate.sample("Sorting people",0,200,"faces").remainingMillis)
        now=12_000;assertEquals(18_000L,rate.sample("Sorting people",20,200,"faces").remainingMillis)
        assertNull(rate.sample("Sorting people",200,200,"faces").remainingMillis)
    }
    @Test fun notificationContainsRealCountsProgressAndPause(){
        val notice=FaceNotifications.running(app,RecognitionProgress.Snapshot("Recognising photos",240,1200,remainingMillis=300_000))
        assertEquals(240,notice.extras.getInt(Notification.EXTRA_PROGRESS));assertEquals(1200,notice.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertFalse(notice.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertTrue(notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("240 of 1,200"))
        assertEquals("Pause for 24 hours",notice.actions.single().title)
    }
    @Test fun permissionConsentAloneDoesNotStartRecognition(){
        RecognitionConsent.accept(app);app.getSharedPreferences("automatic-people",0).edit().clear().commit()
        val scheduler=app.getSystemService(JobScheduler::class.java);scheduler.cancelAll();AutoPeople.ensure(app)
        assertFalse(AutoPeople.enabled(app));assertTrue(scheduler.allPendingJobs.isEmpty())
        AutoPeople.resume(app);assertTrue(AutoPeople.enabled(app))
    }
    @Test fun closingFolderStaysTransparentUntilItsOwnerRemovesIt(){
        val c=Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val host=FrameLayout(c.get());val content=View(c.get());host.addView(content);c.get().setContentView(host)
        host.layout(0,0,400,700);content.layout(0,0,400,700);var finished=false
        FolderTransition.play(host,content,Rect(0,0,100,100),false){finished=true;host.removeView(content)}
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(finished);assertEquals(0f,content.alpha);assertNull(content.parent);c.pause().stop().destroy()
    }
    @Test fun aFolderStartsInvisibleUntilItsFirstLaidOutFrame(){
        val c=Robolectric.buildActivity(MainActivity::class.java).create()
        val activity=c.get();val cover=View(activity)
        activity.javaClass.getDeclaredMethod("openAlbum",String::class.java,View::class.java).apply{isAccessible=true}.invoke(activity,"Camera",cover)
        val grid=activity.javaClass.getDeclaredField("grid").apply{isAccessible=true}.get(activity) as View
        assertEquals(0f,grid.alpha);assertNotNull(grid.background);c.destroy()
    }
}
