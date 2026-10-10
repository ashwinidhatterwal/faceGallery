package com.mosaic.gallery

import android.app.Activity
import android.app.AlertDialog
import android.os.CancellationSignal
import android.os.SystemClock
import android.widget.LinearLayout
import java.util.concurrent.Executors

/** Visible, cancellable contact-only sweep. Shares caches/lock; never toggles automatic settings. */
class ContactPhotoSync(private val activity:Activity) {
    private val worker=Executors.newSingleThreadExecutor()
    private var dialog:AlertDialog?=null
    private var signal:CancellationSignal?=null
    @Volatile private var closed=false
    fun show(){
        if(closed || dialog?.isShowing==true)return
        if(!ContactRecognition.permitted(activity)){
            android.widget.Toast.makeText(activity,"Allow recognition and contacts in Privacy first",android.widget.Toast.LENGTH_LONG).show();return
        }
        val cancel=CancellationSignal();signal=cancel
        val root=GalleryStyle.panelRoot(activity)
        root.addView(GalleryStyle.text(activity,"Sync contact photos",22f))
        val status=GalleryStyle.text(activity,"Checking contact photos…",15f,GalleryStyle.muted(activity));root.addView(status)
        val button=GalleryStyle.button(activity,"Cancel"){dialog?.dismiss()}
        root.addView(button,LinearLayout.LayoutParams(-1,GalleryStyle.dp(activity,48)))
        val shown=GalleryStyle.dialog(activity,root);dialog=shown
        shown.setOnDismissListener{cancel.cancel();if(dialog===shown)dialog=null}
        worker.execute{
            val heat=FaceHeat(activity.applicationContext)
            var more=true;var retry:Long?=null;var unavailable=false
            val result=runCatching{
                while(more && !cancel.isCanceled && !closed){
                    if(!heat.canRun() || FaceJobs.state.busy){unavailable=true;break}
                    val batch=FaceWork.write{
                        val deadline=SystemClock.elapsedRealtime()+20_000
                        fun active()=!cancel.isCanceled && !closed && !FaceJobs.state.busy && heat.canRun() && SystemClock.elapsedRealtime()<deadline
                        if(!active())null else FaceStore(activity.applicationContext).use{ContactRecognition.runSafe(activity.applicationContext,it,::active,cancel,limit=4,manual=true)}
                    }
                    if(batch==null){unavailable=true;break}
                    more=batch.more;retry=batch.retryDelay
                }
            }
            activity.runOnUiThread{
                if(closed || cancel.isCanceled || dialog!==shown)return@runOnUiThread
                status.text=when{
                    result.isFailure->"Could not finish. Your saved recognition is preserved."
                    unavailable->"Sync paused. Try again when recognition is idle and the phone is cool with enough battery."
                    retry!=null->"Available photos checked. Some contacts are temporarily unavailable; automatic syncing can retry later."
                    else->"Contact photos synced. Clear matches are linked; similar faces have suggested names."
                }
                button.text="Done"
            }
        }
    }
    fun dismiss(){signal?.cancel();dialog?.dismiss()}
    fun close(){closed=true;signal?.cancel();dialog?.dismiss();worker.shutdownNow()}
}
