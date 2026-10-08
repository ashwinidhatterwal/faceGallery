package com.mosaic.gallery

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28])
class PermissionGateTest {
    @Before fun setup(){RuntimeEnvironment.getApplication().getSharedPreferences("startup-access",0).edit().clear().commit();shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.READ_CONTACTS,Manifest.permission.READ_EXTERNAL_STORAGE)}
    @After fun reset(){shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)}
    private fun root(activity:MainActivity)=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    @Test fun firstLaunchRequestsSystemPermissionWithoutGalleryScreen(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        val request=shadowOf(activity).lastRequestedPermission
        assertEquals(1001,request.requestCode)
        assertArrayEquals(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.READ_CONTACTS),request.requestedPermissions)
        assertEquals(View.INVISIBLE,root(activity).visibility)
        controller.pause().stop().destroy()
    }
    @Test fun updateWithPhotoAccessAsksForContactsOnly(){
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        val c=Robolectric.buildActivity(MainActivity::class.java).setup()
        assertArrayEquals(arrayOf(Manifest.permission.READ_CONTACTS),shadowOf(c.get()).lastRequestedPermission.requestedPermissions)
        c.pause().stop().destroy()
    }
    @Test fun contactsDenialKeepsGalleryAndDoesNotAskAgain(){
        val app=RuntimeEnvironment.getApplication();shadowOf(app).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        val c=Robolectric.buildActivity(MainActivity::class.java).setup()
        c.get().onRequestPermissionsResult(1001,arrayOf(Manifest.permission.READ_CONTACTS),intArrayOf(PackageManager.PERMISSION_DENIED))
        assertFalse(c.get().isFinishing)
        c.pause().stop().destroy()
        val next=Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNull(shadowOf(next.get()).lastRequestedPermission)
        next.pause().stop().destroy()
    }
    @Test @Config(sdk=[35]) fun modernAndroidAsksForPhotosAndContacts(){
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        val c=Robolectric.buildActivity(MainActivity::class.java).setup()
        assertArrayEquals(arrayOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,Manifest.permission.READ_CONTACTS),shadowOf(c.get()).lastRequestedPermission.requestedPermissions)
        c.pause().stop().destroy()
    }
    @Test @Config(sdk=[35]) fun selectedPhotoAccessDoesNotAskForFullStorageAgain(){
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        val c=Robolectric.buildActivity(MainActivity::class.java).setup()
        assertArrayEquals(arrayOf(Manifest.permission.READ_CONTACTS),shadowOf(c.get()).lastRequestedPermission.requestedPermissions)
        c.pause().stop().destroy()
    }
    @Test fun denialClosesTheGallery(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        activity.onRequestPermissionsResult(1001,arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),intArrayOf(PackageManager.PERMISSION_DENIED))
        assertTrue(activity.isFinishing);controller.pause().stop().destroy()
    }
    @Test fun grantingBothPermissionsSchedulesContactsAndPhotosInBackground(){
        val app=RuntimeEnvironment.getApplication()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(app).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.READ_CONTACTS)
        c.get().onRequestPermissionsResult(1001,arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.READ_CONTACTS),intArrayOf(PackageManager.PERMISSION_GRANTED,PackageManager.PERMISSION_GRANTED))
        val scheduler=app.getSystemService(android.app.job.JobScheduler::class.java)
        assertTrue(scheduler.getPendingJob(AutoPeople.BATCH)!!.isPersisted)
        assertTrue(scheduler.getPendingJob(AutoPeople.WATCH)!!.triggerContentUris!!.any{it.uri==android.provider.ContactsContract.Contacts.CONTENT_URI})
        c.pause().stop().destroy()
    }
    @Test fun grantingPermissionRevealsTheGallery(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        activity.onRequestPermissionsResult(1001,arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),intArrayOf(PackageManager.PERMISSION_GRANTED))
        assertFalse(activity.isFinishing);assertEquals(View.VISIBLE,root(activity).visibility)
        controller.pause().stop().destroy()
    }
}
