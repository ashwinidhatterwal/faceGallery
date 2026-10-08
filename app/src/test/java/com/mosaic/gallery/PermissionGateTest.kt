package com.mosaic.gallery

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
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
    @After fun reset(){shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)}
    private fun root(activity:MainActivity)=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    @Test fun firstLaunchRequestsSystemPermissionWithoutGalleryScreen(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        val request=shadowOf(activity).lastRequestedPermission
        assertEquals(1001,request.requestCode)
        assertArrayEquals(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),request.requestedPermissions)
        assertEquals(View.INVISIBLE,root(activity).visibility)
        controller.pause().stop().destroy()
    }
    @Test fun denialClosesTheGallery(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        activity.onRequestPermissionsResult(1001,arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),intArrayOf(PackageManager.PERMISSION_DENIED))
        assertTrue(activity.isFinishing);controller.pause().stop().destroy()
    }
    @Test fun grantingPermissionRevealsTheGallery(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        activity.onRequestPermissionsResult(1001,arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),intArrayOf(PackageManager.PERMISSION_GRANTED))
        assertFalse(activity.isFinishing);assertEquals(View.VISIBLE,root(activity).visibility)
        controller.pause().stop().destroy()
    }
}
