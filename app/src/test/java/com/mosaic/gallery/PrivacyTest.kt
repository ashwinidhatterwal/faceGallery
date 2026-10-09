package com.mosaic.gallery

import android.provider.Settings
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35])
class PrivacyTest {
    @org.junit.Before fun consent(){RecognitionConsent.accept(org.robolectric.RuntimeEnvironment.getApplication())}
    private fun texts(view:View):List<TextView> = (if(view is TextView)listOf(view) else emptyList()) +
        if(view is ViewGroup)(0 until view.childCount).flatMap{texts(view.getChildAt(it))}else emptyList()
    @Test fun policyIsReadableOfflineAndStorageActionTargetsThisApp(){
        val controller=Robolectric.buildActivity(PrivacyActivity::class.java).setup()
        val activity=controller.get();val labels=texts(activity.window.decorView)
        val policy=labels.single{it.text.toString().contains("Face Gallery Privacy Policy")}.text.toString()
        assertTrue(policy.contains("Contacts access is optional"));assertTrue(policy.contains("technical SDK metrics"))
        assertTrue(policy.contains("Background recognition first"));assertTrue(policy.contains("does not contain photo pixels"))
        (labels.single{it.text.toString()=="Manage permissions and app storage"}.parent as View).performClick()
        val command=Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,command.action)
        assertEquals("package:com.mosaic.gallery",command.data.toString())
        controller.pause().stop().destroy()
    }
    @Test fun quietRecognitionCanBePausedPersistentlyAndResumed(){
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.READ_MEDIA_IMAGES)
        app.getSharedPreferences("automatic-people",0).edit().clear().commit()
        FaceJobs.publish(FaceJobs.State());AutoPeople.ensure(app)
        val first=Robolectric.buildActivity(PrivacyActivity::class.java).setup()
        val toggle=texts(first.get().window.decorView).filterIsInstance<android.widget.CheckBox>().single{it.text=="Background recognition"}
        assertTrue(toggle.isChecked);toggle.performClick()
        assertFalse(AutoPeople.enabled(app))
        assertTrue(app.getSystemService(android.app.job.JobScheduler::class.java).allPendingJobs.isEmpty())
        first.pause().stop().destroy()
        val second=Robolectric.buildActivity(PrivacyActivity::class.java).setup()
        val restored=texts(second.get().window.decorView).filterIsInstance<android.widget.CheckBox>().single{it.text=="Background recognition"}
        assertFalse(restored.isChecked);restored.performClick()
        assertTrue(AutoPeople.enabled(app))
        assertNotNull(app.getSystemService(android.app.job.JobScheduler::class.java).getPendingJob(AutoPeople.BATCH))
        second.pause().stop().destroy()
    }
}
