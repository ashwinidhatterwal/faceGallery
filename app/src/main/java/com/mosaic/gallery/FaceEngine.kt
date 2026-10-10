package com.mosaic.gallery

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/** A single bundled detector and one bounded bitmap at a time. Called only on the scan worker. */
class FaceEngine(private val context:Context):java.io.Closeable{
    private val detector=FaceDetection.getClient(FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .setMinFaceSize(0.03f).build())
    fun detect(photo:PhotoRecord):List<FaceObservation>{
        val bitmap=PhotoImages.decode(context,photo.uri,1600,2_000_000)
        try{return detectBitmap(bitmap)}finally{bitmap.recycle()}
    }
    @Volatile private var closeRequested=false
    @Volatile private var inFlight=false
    fun detectBitmap(bitmap:android.graphics.Bitmap,requireSingle:Boolean=false,detectedCount:(Int)->Unit={}):List<FaceObservation> {
        check(nativeSlot.tryAcquire()){ "Detector still finishing a previous task" }
        val owned=try{bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888,false)?:error("Could not prepare detector input")}catch(e:Throwable){nativeSlot.release();throw e}
        inFlight=true
        val task=try{detector.process(InputImage.fromBitmap(owned,0))}catch(e:Throwable){inFlight=false;owned.recycle();nativeSlot.release();throw e}
        var timedOut=false
        try{val faces=Tasks.await(task,8,java.util.concurrent.TimeUnit.SECONDS);detectedCount(faces.size);return if(requireSingle && faces.size!=1)emptyList()else faces.mapNotNull{observation(bitmap,it)}}
        catch(e:java.util.concurrent.TimeoutException){timedOut=true;throw e}
        finally{
            if(timedOut || !task.isComplete)task.addOnCompleteListener(java.util.concurrent.Executor{it.run()}){owned.recycle();inFlight=false;nativeSlot.release();if(closeRequested)detector.close()}
            else{owned.recycle();inFlight=false;nativeSlot.release()}
        }
    }
    companion object {
        private val nativeSlot=java.util.concurrent.Semaphore(1)
        fun observation(bitmap:android.graphics.Bitmap,face:com.google.mlkit.vision.face.Face):FaceObservation? {
            val observation=FaceQuality.observation(bitmap,face.boundingBox,face.headEulerAngleY,face.headEulerAngleX,face.headEulerAngleZ)?:return null
            val types=intArrayOf(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE,com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE,
                com.google.mlkit.vision.face.FaceLandmark.NOSE_BASE,com.google.mlkit.vision.face.FaceLandmark.MOUTH_LEFT,com.google.mlkit.vision.face.FaceLandmark.MOUTH_RIGHT)
            val points=types.map{face.getLandmark(it)?.position}
            if(points.any{it==null})return observation
            val eyes=points.take(2).filterNotNull().sortedBy{it.x};val mouth=points.drop(3).filterNotNull().sortedBy{it.x}
            return observation.copy(landmarks=(eyes+listOf(points[2]!!)+mouth).flatMap{listOf(it.x/bitmap.width,it.y/bitmap.height)})
        }
    }
    override fun close(){closeRequested=true;if(!inFlight)detector.close()}
}
