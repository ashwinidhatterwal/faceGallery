package com.mosaic.gallery

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Matrix
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
class FaceSignatureTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val photo=PhotoRecord(1,Uri.parse("content://faces/1"),"one.jpg",100,400,300,"Camera",1000,"/storage/one.jpg",100)
    private val face=FaceObservation(.1f,.2f,.4f,.6f,0f,0f,0f,120f,.9f,"Anchor")
    private fun vector(index:Int)=FloatArray(128){if(it==index)1f else 0f}
    @Before fun reset(){app.deleteDatabase("faces.db")}
    @After fun cleanup(){app.deleteDatabase("faces.db")}
    @Test fun fullPrecisionVectorIsCompactNormalizedAndRoundTrips(){
        val input=FloatArray(128){it.toFloat()-40};val blob=FaceVectors.pack(input);assertEquals(512,blob.size)
        val unit=FaceVectors.unpack(blob);assertEquals(1f,FaceVectors.cosine(unit,unit),.00001f);assertArrayEquals(FaceVectors.normalize(input),unit,.000001f)
    }
    @Test fun invalidVectorsAreRejected(){
        for(input in listOf(FloatArray(127),FloatArray(128),FloatArray(128){Float.NaN},FloatArray(128){Float.POSITIVE_INFINITY}))assertTrue(runCatching{FaceVectors.normalize(input)}.isFailure)
        assertTrue(runCatching{FaceVectors.unpack(ByteArray(511))}.isFailure)
    }
    @Test fun streamingSearchRanksAndLimitsAndExcludesCooccurringFaces(){
        val search=FaceVectors.Search(vector(0),"source",2)
        search.offer("source",0,face,vector(0));search.offer("weak",0,face.copy(authority="Shadow"),vector(0))
        search.offer("orthogonal",0,face,vector(1));search.offer("close",0,face,FloatArray(128){if(it==0).8f else if(it==1).6f else 0f});search.offer("best",0,face,vector(0))
        assertEquals(listOf("best","close"),search.results().map{it.uri})
    }
    @Test fun prototypeCombinesQualityWeightsAndNormalizes(){
        val prototype=FaceVectors.prototype(listOf(vector(0) to 3f,vector(1) to 1f));assertEquals(.9486833f,prototype[0],.00001f);assertEquals(.3162278f,prototype[1],.00001f)
        assertTrue(runCatching{FaceVectors.prototype(listOf(vector(0) to -1f))}.isFailure)
    }
    @Test fun alignmentRecoversTranslationScaleAndRotation(){
        val source=FaceAlignment.template.chunked(2).flatMap{(x,y)->listOf((2*x-y+80)/400,(x+2*y+50)/400)}
        val transform=FaceAlignment.transform(source,400,400)!!;val matrix=Matrix().apply{setValues(transform)}
        val pixels=source.map{it*400}.toFloatArray();matrix.mapPoints(pixels)
        assertArrayEquals(FaceAlignment.template.toFloatArray(),pixels,.001f)
    }
    @Test fun missingDegenerateOrImpossibleLandmarksAreRejected(){
        assertNull(FaceAlignment.transform(emptyList(),200,200));assertNull(FaceAlignment.transform(List(10){.5f},200,200))
        val points=FaceAlignment.template.map{it/200}.toMutableList();points[0]=Float.NaN;assertNull(FaceAlignment.transform(points,200,200))
        points[0]=1f;assertNull(FaceAlignment.transform(points,200,200))
    }
    @Test fun alignedCropIs112PixelsAndTruncatedFacesAreSkipped(){
        val bitmap=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888)
        val aligned=FaceAlignment.crop(bitmap,face.copy(landmarks=FaceAlignment.template.map{it/200}))!!
        assertEquals(112,aligned.width);assertEquals(112,aligned.height);aligned.recycle()
        val edge=FaceAlignment.template.mapIndexed{i,v->(v-if(i%2==0)30 else 0)/200}
        assertNull(FaceAlignment.crop(bitmap,face.copy(landmarks=edge)));bitmap.recycle()
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun tightPortraitCanAlignWithoutChangingGalleryCrop(){
        val bitmap=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.RED)}
        val tight=face.copy(landmarks=listOf(.25f,.30f,.75f,.30f,.50f,.55f,.32f,.75f,.68f,.75f))
        assertNull(FaceAlignment.crop(bitmap,tight))
        val aligned=FaceAlignment.portraitCrop(bitmap,tight)!!;assertEquals(112,aligned.width);assertEquals(112,aligned.height)
        assertEquals(android.graphics.Color.RED,aligned.getPixel(0,0));assertEquals(android.graphics.Color.RED,aligned.getPixel(111,111));aligned.recycle();bitmap.recycle()
    }
    @Test fun portraitAlignmentStillRejectsInvalidLandmarksAndExcessiveTruncation(){
        val bitmap=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888)
        assertNull(FaceAlignment.portraitCrop(bitmap,face.copy(landmarks=emptyList())))
        assertNull(FaceAlignment.portraitCrop(bitmap,face.copy(landmarks=List(10){.5f})))
        assertNull(FaceAlignment.portraitCrop(bitmap,face.copy(landmarks=listOf(.1f,.2f,.9f,.2f,.5f,.55f,.25f,.9f,.75f,.9f))))
        bitmap.recycle()
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun completePortraitUsesIdenticalPixelsToGalleryAlignment(){
        val bitmap=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888)
        for(y in 0 until 200)for(x in 0 until 200)bitmap.setPixel(x,y,android.graphics.Color.rgb(x,y,0))
        val f=face.copy(landmarks=FaceAlignment.template.map{it/200});val gallery=FaceAlignment.crop(bitmap,f)!!;val portrait=FaceAlignment.portraitCrop(bitmap,f)!!
        assertTrue(gallery.sameAs(portrait));gallery.recycle();portrait.recycle();bitmap.recycle()
    }
    @Test fun oldFacesReceiveOnlyMutualMatchingLandmarks(){
        val second=face.copy(left=.6f,right=.9f);val fresh=face.copy(landmarks=List(10){.3f})
        val result=FaceAlignment.recover(listOf(face,second),listOf(fresh));assertEquals(fresh.landmarks,result[0].landmarks);assertTrue(result[1].landmarks.isEmpty())
        assertTrue(FaceAlignment.recover(listOf(face),listOf(second.copy(landmarks=fresh.landmarks)))[0].landmarks.isEmpty())
    }
    @Test fun ambiguousLandmarkRecoveryDoesNotGuess(){
        val fresh=face.copy(landmarks=List(10){.3f})
        assertTrue(FaceAlignment.recover(listOf(face,face),listOf(fresh)).all{it.landmarks.isEmpty()})
        assertTrue(FaceAlignment.recover(listOf(face),listOf(fresh,fresh)).all{it.landmarks.isEmpty()})
    }
    @Test fun pausedSignatureWorkerDoesNotInvokeEncoder(){FaceStore(app).use{store->
        store.save(photo,listOf(face));var calls=0
        SignatureProcessing.run(store,photo,listOf(face),{false}){calls++;vector(0)}
        assertEquals(0,calls);assertEquals(0,store.signatureSummary().ready)
    }}
    @Test fun landmarksAndSignaturesSurviveDatabaseReopen(){
        val observation=face.copy(landmarks=FaceAlignment.template.map{it/200})
        FaceStore(app).use{it.save(photo,listOf(observation));it.saveSignature(photo.uri.toString(),0,vector(0))}
        FaceStore(app).use{assertEquals(observation,it.observations(photo.uri.toString())[0]);assertArrayEquals(vector(0),it.signature(photo.uri.toString(),0),0f);assertTrue(it.pendingSignatures(listOf(photo)).isEmpty())}
    }
    @Test fun phase2DatabaseMigratesWithoutLosingCompletedPhotos(){
        val path=app.getDatabasePath("faces.db");path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path,null).use{db->
            db.execSQL("CREATE TABLE photos(uri TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,model TEXT NOT NULL,status TEXT NOT NULL,error TEXT)")
            db.execSQL("CREATE TABLE faces(uri TEXT NOT NULL REFERENCES photos(uri) ON DELETE CASCADE,ordinal INTEGER NOT NULL,l REAL,t REAL,r REAL,b REAL,yaw REAL,pitch REAL,roll REAL,sharpness REAL,score REAL,authority TEXT,PRIMARY KEY(uri,ordinal))")
            db.execSQL("INSERT INTO photos VALUES(?,?,?,'done',NULL)",arrayOf(photo.uri.toString(),FaceStore.fingerprint(photo),FaceStore.MODEL))
            db.execSQL("INSERT INTO faces VALUES(?,0,.1,.2,.4,.6,0,0,0,120,.9,'Anchor')",arrayOf(photo.uri.toString()));db.version=1
        }
        FaceStore(app).use{assertEquals(1,it.summary().faces);assertTrue(it.pending(listOf(photo)).isEmpty());assertEquals(listOf(photo),it.pendingSignatures(listOf(photo)));it.saveSignature(photo.uri.toString(),0,vector(0));assertEquals(1,it.signatureSummary().ready)}
    }
    @Test fun revokedAccessAndEditedPhotosDeleteTheirSignatures(){
        FaceStore(app).use{store->
            store.save(photo,listOf(face));store.saveSignature(photo.uri.toString(),0,vector(0));store.retain(listOf(photo.copy(modifiedMillis=200)));assertEquals(0,store.signatureSummary().ready)
            store.save(photo,listOf(face));store.saveSignature(photo.uri.toString(),0,vector(0));store.retain(emptyList());assertEquals(0,store.signatureSummary().ready)
        }
    }
    @Test fun staleEncoderResultsAreIgnoredAndBecomePending(){FaceStore(app).use{store->
        store.save(photo,listOf(face));store.saveSignature(photo.uri.toString(),0,vector(0));store.writableDatabase.execSQL("UPDATE embeddings SET model='old'")
        assertNull(store.signature(photo.uri.toString(),0));assertEquals(0,store.signatureSummary().ready);assertEquals(listOf(photo),store.pendingSignatures(listOf(photo)))
    }}
    @Test fun errorsRetryButUnalignableAndWeakFacesDoNot(){FaceStore(app).use{store->
        store.save(photo,listOf(face,face.copy(authority="Shadow")));store.saveSignature(photo.uri.toString(),0,null,"error","Decode")
        assertEquals(listOf(photo),store.pendingSignatures(listOf(photo)));store.saveSignature(photo.uri.toString(),0,null,"skipped","No landmarks")
        assertTrue(store.pendingSignatures(listOf(photo)).isEmpty());assertEquals(1,store.signatureSummary().skipped)
    }}
    @Test fun pausingCommitsOnlyFinishedFacesAndResumesPending(){FaceStore(app).use{store->
        store.save(photo,listOf(face,face));var running=true;var calls=0
        SignatureProcessing.run(store,photo,store.observations(photo.uri.toString()),{running}){calls++;if(calls==2)running=false;vector(0)}
        assertEquals(1,store.signatureSummary().ready);assertTrue(store.needsSignature(photo.uri.toString(),1))
        SignatureProcessing.run(store,photo,store.observations(photo.uri.toString()),{true}){calls++;vector(0)}
        assertEquals(3,calls);assertEquals(2,store.signatureSummary().ready)
    }}
    @Test fun encoderFailureContinuesAndRemainsRetryable(){FaceStore(app).use{store->
        store.save(photo,listOf(face,face));var calls=0
        SignatureProcessing.run(store,photo,store.observations(photo.uri.toString()),{true}){if(calls++==0)error("inference")else vector(0)}
        assertEquals(1,store.signatureSummary().errors);assertEquals(1,store.signatureSummary().ready);assertTrue(store.needsSignature(photo.uri.toString(),0))
    }}
    @Test fun databaseSearchUsesCurrentVectorsAndExcludesSamePhoto(){FaceStore(app).use{store->
        val second=photo.copy(uri=Uri.parse("content://faces/2"));store.save(photo,listOf(face,face));store.save(second,listOf(face))
        store.saveSignature(photo.uri.toString(),0,vector(0));store.saveSignature(photo.uri.toString(),1,vector(0));store.saveSignature(second.uri.toString(),0,vector(0))
        assertEquals(listOf(second.uri.toString()),store.similar(photo.uri.toString(),0).map{it.uri})
        store.clear();assertEquals(0,store.signatureSummary().ready)
    }}
}
