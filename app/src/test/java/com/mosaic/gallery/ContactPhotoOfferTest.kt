package com.mosaic.gallery

import android.Manifest
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ContactPhotoOfferTest {
    private val face=FaceObservation(.8f,.1f,1f,.5f,0f,0f,0f,100f,.9f,"Anchor")
    private val contact=ContactNames.Contact("content://com.android.contacts/contacts/lookup/test/1","Alice")
    @Test fun cropRemainsSquareAtImageEdgeAndDoesNotRecycleSource(){
        val source=Bitmap.createBitmap(1200,800,Bitmap.Config.ARGB_8888)
        val crop=ContactPhotoOffer.crop(source,face)
        assertEquals(crop.width,crop.height);assertTrue(crop.width<=512);assertFalse(source.isRecycled)
        crop.recycle();source.recycle()
    }
    @Test fun smallSourceIsNotUpscaled(){val source=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);val crop=ContactPhotoOffer.crop(source,face);assertTrue(crop.width<=100);source.recycle();crop.recycle()}
    @Test fun blurryOrSidewaysFacesAreNotChosen(){assertTrue(ContactPhotoOffer.eligible(face));assertFalse(ContactPhotoOffer.eligible(face.copy(sharpness=2f)));assertFalse(ContactPhotoOffer.eligible(face.copy(yaw=65f)));assertFalse(ContactPhotoOffer.eligible(face.copy(score=.2f)))}
    @Test fun existingPhotoAndMissingContactAreNeverEligible(){
        val controller=Robolectric.buildActivity(android.app.Activity::class.java).setup();val activity=controller.get()
        Shadows.shadowOf(activity).grantPermissions(Manifest.permission.READ_CONTACTS)
        val provider=object:ContentProvider(){
            var exists=true;var photo:Long?=7L
            override fun onCreate()=true
            override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):Cursor=MatrixCursor(arrayOf("_id","photo_id")).apply{if(exists)addRow(arrayOf(1L,photo))}
            override fun getType(uri:Uri):String?=null
            override fun insert(uri:Uri,v:ContentValues?):Uri?=error("Must not write")
            override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=error("Must not write")
            override fun delete(uri:Uri,s:String?,a:Array<out String>?)=error("Must not write")
        }
        ShadowContentResolver.registerProviderInternal("com.android.contacts",provider)
        val offer=ContactPhotoOffer(activity)
        val method=ContactPhotoOffer::class.java.getDeclaredMethod("emptyId",ContactNames.Contact::class.java).apply{isAccessible=true}
        assertNull(method.invoke(offer,contact));provider.photo=null;assertEquals(1L,method.invoke(offer,contact));provider.exists=false;assertNull(method.invoke(offer,contact))
        assertNull(method.invoke(offer,contact.copy(lookup="https://example.com")))
        offer.close();controller.destroy()
    }
    @Test fun deniedWritePermissionDoesNotWriteOrRequestAgain(){
        val controller=Robolectric.buildActivity(android.app.Activity::class.java).setup();val offer=ContactPhotoOffer(controller.get())
        assertFalse(offer.permissionResult(123));assertTrue(offer.permissionResult(ContactPhotoOffer.PERMISSION));assertFalse(offer.requestingPermission)
        offer.close();controller.destroy()
    }
}
