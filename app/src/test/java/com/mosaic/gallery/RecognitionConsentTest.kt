package com.mosaic.gallery

import android.Manifest
import android.app.AlertDialog
import android.app.job.JobScheduler
import android.content.Intent
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RecognitionConsentTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun reset(){
        app.getSharedPreferences("recognition-consent",0).edit().clear().commit()
        app.getSharedPreferences("automatic-people",0).edit().clear().commit()
        app.getSystemService(JobScheduler::class.java).cancelAll()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_CONTACTS)
    }
    @Test fun existingAndroidPermissionsDoNotAuthorizeBackgroundUse(){
        AutoPeople.ensure(app);PeopleBoot().onReceive(app,Intent(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(AutoPeople.enabled(app));assertFalse(ContactRecognition.available(app))
        assertTrue(app.getSystemService(JobScheduler::class.java).allPendingJobs.isEmpty())
    }
    @Test fun disclosurePrecedesPermissionsAndCancellationIsNotConsent(){
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_CONTACTS)
        val c=Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNull(Shadows.shadowOf(c.get()).lastRequestedPermission)
        ShadowAlertDialog.getLatestAlertDialog().cancel()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(RecognitionConsent.allowed(app));assertFalse(RecognitionConsent.decided(app))
        assertTrue(c.get().isFinishing);c.pause().stop().destroy()
    }
    @Test fun enableRequestsAndroidAccessAfterConsent(){
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_CONTACTS)
        val c=Robolectric.buildActivity(MainActivity::class.java).setup()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(RecognitionConsent.allowed(app))
        assertTrue(Manifest.permission.READ_CONTACTS in Shadows.shadowOf(c.get()).lastRequestedPermission.requestedPermissions)
        assertNull(Shadows.shadowOf(c.get()).nextStartedService);c.pause().stop().destroy()
    }
    @Test fun browseOnlyDoesNotRequestContactsOrStartRecognition(){
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_CONTACTS)
        val c=Robolectric.buildActivity(MainActivity::class.java).setup()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(RecognitionConsent.decided(app));assertFalse(RecognitionConsent.allowed(app))
        assertFalse(Manifest.permission.READ_CONTACTS in Shadows.shadowOf(c.get()).lastRequestedPermission.requestedPermissions)
        AutoPeople.ensure(app);assertTrue(app.getSystemService(JobScheduler::class.java).allPendingJobs.isEmpty())
        c.pause().stop().destroy()
    }
    @Test fun upgradeConsentPreservesPermanentPause(){
        AutoPeople.pause(app);RecognitionConsent.accept(app)
        assertFalse(AutoPeople.enabled(app))
    }
}
