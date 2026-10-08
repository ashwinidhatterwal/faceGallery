package com.mosaic.gallery

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FacePhaseTest{
    private val app get()=RuntimeEnvironment.getApplication()
    private val photo=PhotoRecord(1,Uri.parse("content://faces/1"),"one.jpg",100,400,300,"Camera",1000,"/storage/one.jpg",100)
    private val face=FaceObservation(.1f,.2f,.4f,.6f,0f,0f,0f,120f,.9f,"Anchor")
    @Before fun reset(){app.deleteDatabase("faces.db")}
    @After fun finish(){app.deleteDatabase("faces.db")}
    @Test fun faceObservationsSurviveDatabaseReopen(){
        FaceStore(app).use{it.save(photo,listOf(face))};FaceStore(app).use{assertEquals(listOf(face),it.observations(photo.uri.toString()));assertEquals(1,it.summary().faces)}
    }
    @Test fun successfulNoFacePhotoIsNotScannedAgain(){FaceStore(app).use{it.save(photo,emptyList());assertTrue(it.pending(listOf(photo)).isEmpty());assertEquals(1,it.summary().done)}}
    @Test fun inPlaceEditWithSameSizeIsDetectedByModificationTime(){FaceStore(app).use{it.save(photo,listOf(face));assertEquals(listOf(photo.copy(modifiedMillis=200)),it.pending(listOf(photo.copy(modifiedMillis=200))))}}
    @Test fun modelVersionChangesInvalidateCompletedWork(){FaceStore(app).use{it.save(photo,listOf(face));it.writableDatabase.execSQL("UPDATE photos SET model='old'");assertEquals(listOf(photo),it.pending(listOf(photo)))}}
    @Test fun repeatedScanReplacesObservationsWithoutDuplicates(){FaceStore(app).use{it.save(photo,listOf(face,face));it.save(photo,listOf(face));assertEquals(1,it.summary().faces);assertEquals(1,it.summary().done)}}
    @Test fun failedPhotosRemainRetryable(){FaceStore(app).use{it.save(photo,emptyList(),"Decode failure");assertEquals(1,it.summary().errors);assertEquals(listOf(photo),it.pending(listOf(photo)));it.save(photo,emptyList());assertEquals(0,it.summary().errors)}}
    @Test fun removedAccessDeletesItsObservationsByCascade(){FaceStore(app).use{it.save(photo,listOf(face));it.retain(emptyList());assertEquals(0,it.summary().faces);assertEquals(0,it.summary().done)}}
    @Test fun changedPhotosDoNotDisplayStaleBoxes(){FaceStore(app).use{it.save(photo,listOf(face));it.retain(listOf(photo.copy(modifiedMillis=200)));assertTrue(it.observations(photo.uri.toString()).isEmpty())}}
    @Test fun clearRemovesScanStateAndFaces(){FaceStore(app).use{it.save(photo,listOf(face));it.clear();assertEquals(0,it.summary().faces);assertEquals(listOf(photo),it.pending(listOf(photo)))}}
    @Test fun schedulerStopsBetweenPhotosAndResumesOnlyPendingWork(){FaceStore(app).use{store->
        val second=photo.copy(id=2,uri=Uri.parse("content://faces/2"));val list=listOf(photo,second);var running=true;var calls=0
        FaceProcessing.run(store,store.pending(list),{running},{calls++;listOf(face)}){running=false}
        assertEquals(1,calls);assertEquals(listOf(second),store.pending(list))
        FaceProcessing.run(store,store.pending(list),{true},{calls++;emptyList()});assertEquals(2,calls);assertTrue(store.pending(list).isEmpty())
    }}
    @Test fun interruptedInferenceIsNotMarkedComplete(){FaceStore(app).use{store->
        var running=true;FaceProcessing.run(store,listOf(photo),{running},{running=false;listOf(face)})
        assertEquals(listOf(photo),store.pending(listOf(photo)));assertEquals(0,store.summary().faces)
    }}
    @Test fun pausedSchedulerDoesNotInvokeDetector(){FaceStore(app).use{store->
        var calls=0;FaceProcessing.run(store,listOf(photo),{false},{calls++;emptyList()});assertEquals(0,calls)
    }}
    @Test fun schedulerRecordsFailureAndContinuesToNextPhoto(){FaceStore(app).use{store->
        val second=photo.copy(id=2,uri=Uri.parse("content://faces/2"));FaceProcessing.run(store,listOf(photo,second),{true},{if(it.id==1L)error("decode")else listOf(face)})
        assertEquals(1,store.summary().errors);assertEquals(1,store.summary().faces);assertEquals(listOf(photo),store.pending(listOf(photo,second)))
    }}
    @Test fun highQualityFrontalFaceIsAnAnchor(){assertEquals("Anchor",FaceQuality.score(180,120f,0f,0f,0f).second)}
    @Test fun moderateProfileIsSupport(){assertEquals("Support",FaceQuality.score(90,20f,40f,0f,0f).second)}
    @Test fun TinyBlurredAndSevereProfileFacesAreShadows(){
        assertEquals("Shadow",FaceQuality.score(30,100f,0f,0f,0f).second);assertEquals("Shadow",FaceQuality.score(180,0f,0f,0f,0f).second);assertEquals("Shadow",FaceQuality.score(180,100f,70f,0f,0f).second)
    }
    @Test fun flatFaceCropHasLowSharpnessAndClampedCoordinates(){
        val bitmap=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.GRAY)
        val observation=FaceQuality.observation(bitmap,Rect(-10,-20,200,210),0f,0f,0f)!!
        assertEquals(0f,observation.left,0f);assertEquals(1f,observation.bottom,0f);assertEquals(0f,observation.sharpness,0f);assertEquals("Shadow",observation.authority);bitmap.recycle()
    }
    @Test fun fullyOutsideBoxIsDiscarded(){val bitmap=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);assertNull(FaceQuality.observation(bitmap,Rect(110,110,140,140),0f,0f,0f));bitmap.recycle()}
}
