package com.mosaic.gallery

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** Wait for the scan worker to finish its current operation before editing its graph. */
class PeopleEditGate(private val activity:Activity) {
    private val main=Handler(Looper.getMainLooper())
    private var generation=0
    private var autoListener:(()->Unit)?=null
    private var listener:((FaceJobs.State)->Unit)?=null
    fun run(action:()->Unit) {
        cancel()
        FaceWork.pauseForEdit()
        if(FaceWork.automatic){val token=generation;val callback:()->Unit={if(token==generation)run(action)};autoListener=callback;FaceWork.observe(callback);return}
        if(!FaceJobs.state.busy){action();return}
        val token=generation
        val observer:(FaceJobs.State)->Unit={state->
            if(!state.busy){
                listener?.let{FaceJobs.remove(it)};listener=null
                main.post{if(token==generation && !activity.isFinishing && !activity.isDestroyed)run(action)}
            }
        }
        listener=observer;FaceJobs.observe(observer)
        if(!FaceJobs.state.pausing){
            Toast.makeText(activity,"Pausing to edit…",Toast.LENGTH_SHORT).show()
            try{activity.startService(Intent(activity,FaceScanService::class.java).setAction(FaceScanService.EDIT_PAUSE))}
            catch(_:IllegalStateException){cancel();Toast.makeText(activity,"Could not pause. Try again.",Toast.LENGTH_SHORT).show()}
        }
    }
    fun cancel(){generation++;autoListener?.let{FaceWork.remove(it)};autoListener=null;listener?.let{FaceJobs.remove(it)};listener=null}
}
