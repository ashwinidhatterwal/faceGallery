package com.mosaic.gallery

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** One CPU interpreter per worker session; model is memory-mapped, buffers reused. No network/delegates. */
class FaceEncoder(context:Context,threads:Int=2):java.io.Closeable {
    private val model=context.assets.openFd("mobilefacenet.tflite").use{asset->FileInputStream(asset.fileDescriptor).use{it.channel.map(FileChannel.MapMode.READ_ONLY,asset.startOffset,asset.declaredLength)}}
    private val interpreter=Interpreter(model,Interpreter.Options().setNumThreads(threads))
    private val input=ByteBuffer.allocateDirect(112*112*3*4).order(ByteOrder.nativeOrder())
    private val pixels=IntArray(112*112)
    private val output=arrayOf(FloatArray(FaceVectors.DIMENSIONS))
    init {
        check(interpreter.inputTensorCount==1 && interpreter.outputTensorCount==1)
        check(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1,3,112,112)) && interpreter.getInputTensor(0).dataType()==DataType.FLOAT32)
        check(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1,128)))
    }
    fun encode(aligned:Bitmap):FloatArray {
        require(aligned.width==112 && aligned.height==112)
        aligned.getPixels(pixels,0,112,0,0,112,112);input.rewind()
        for(shift in intArrayOf(16,8,0))pixels.forEach{input.putFloat(((it shr shift) and 255)/255f)}
        input.rewind();interpreter.run(input,output);return FaceVectors.normalize(output[0])
    }
    override fun close(){interpreter.close()}
}

/** Commits after each face, so leaving/pause retains completed work and never commits interrupted work. */
object SignatureProcessing {
    fun run(store:FaceStore,photo:PhotoRecord,faces:List<FaceObservation>,keepGoing:()->Boolean,canCommit:()->Boolean=keepGoing,retryErrors:Boolean=true,encode:(FaceObservation)->FloatArray?){
        val uri=photo.uri.toString()
        faces.forEachIndexed{ordinal,face->
            if(!keepGoing())return
            if(face.authority=="Shadow" || !store.needsSignature(uri,ordinal) || (!retryErrors && !store.encodingDue(uri,ordinal)))return@forEachIndexed
            val result=runCatching{encode(face)}
            if(!canCommit())return
            result.onSuccess{vector->store.saveSignature(uri,ordinal,vector,if(vector==null)"skipped"else"done",if(vector==null)"Face could not be aligned reliably"else null)}
                .onFailure{store.saveSignature(uri,ordinal,null,"error",it.javaClass.simpleName)}
        }
    }
}

class SignatureEngine(private val context:Context,threads:Int=2):java.io.Closeable {
    private val encoder=FaceEncoder(context,threads)
    private var detector:FaceEngine?=null
    fun process(store:FaceStore,photo:PhotoRecord,keepGoing:()->Boolean,canCommit:()->Boolean=keepGoing,retryErrors:Boolean=true){
        val bitmap=PhotoImages.decode(context,photo.uri,1600,2_000_000)
        try {
            var faces=store.observations(photo.uri.toString())
            if(faces.withIndex().any{(ordinal,face)->face.authority!="Shadow" && face.landmarks.size!=10 && store.needsSignature(photo.uri.toString(),ordinal)}){
                val engine=detector?:FaceEngine(context).also{detector=it}
                val fresh=engine.detectBitmap(bitmap);if(!canCommit())return
                faces=FaceAlignment.recover(faces,fresh);store.updateLandmarks(photo.uri.toString(),faces)
            }
            SignatureProcessing.run(store,photo,faces,keepGoing,canCommit,retryErrors){face->FaceAlignment.crop(bitmap,face)?.let{aligned->try{encoder.encode(aligned)}finally{aligned.recycle()}}}
            store.restoreAssertions(photo.uri.toString())
        }finally{bitmap.recycle()}
    }
    fun refine(store:FaceStore,photo:PhotoRecord,keys:Set<GroupRules.Key>,keepGoing:()->Boolean,canCommit:()->Boolean=keepGoing):Int {
        val bitmap=PhotoImages.decode(context,photo.uri,2800,6_000_000)
        try{
            val stored=store.observations(photo.uri.toString())
            val engine=detector?:FaceEngine(context).also{detector=it}
            val fresh=engine.detectBitmap(bitmap);if(!canCommit())return 0
            // Match old boxes mutually before using a fresh landmark set; ordinals remain stable.
            val recovered=FaceAlignment.recover(stored.map{it.copy(landmarks=emptyList())},fresh).map{old->
                val match=fresh.singleOrNull{old.landmarks.size==10 && it.landmarks==old.landmarks}
                if(match==null)old else old.copy(score=match.score,authority=match.authority,sharpness=match.sharpness,yaw=match.yaw,pitch=match.pitch,roll=match.roll)
            }
            return SignatureRefinement.run(store,photo.uri.toString(),recovered,keys,keepGoing,canCommit){face->
                FaceAlignment.crop(bitmap,face)?.let{aligned->try{
                    // The bundled graph already sums original and flipped-face features.
                    encoder.encode(aligned)
                }finally{aligned.recycle()}}
            }
        }finally{bitmap.recycle()}
    }
    override fun close(){detector?.close();encoder.close()}
}
