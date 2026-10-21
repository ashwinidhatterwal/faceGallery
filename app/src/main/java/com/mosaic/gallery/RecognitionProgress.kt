package com.mosaic.gallery

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.text.NumberFormat

/** Estimates only the current step, from work completed during this run. */
class RecognitionProgress(private val clock:()->Long=SystemClock::elapsedRealtime){
    data class Snapshot(val stage:String,val done:Int,val total:Int,val unit:String="photos",val remainingMillis:Long?=null){
        val percent get()=if(total>0)(done.toLong()*100/total).toInt().coerceIn(0,100)else 0
        fun text():String {
            val numbers=NumberFormat.getIntegerInstance()
            val count=if(total>0)" · ${numbers.format(done)} of ${numbers.format(total)} $unit"else ""
            val eta=remainingMillis?.let{if(it<60_000)"Less than a minute left in this step"else "About ${(it+59_999)/60_000} min left in this step"}
            return stage+count+(eta?.let{"\n$it"}?:"")
        }
    }
    private var stage="";private var total=-1;private var began=0L;private var initial=0
    fun sample(label:String,done:Int,total:Int,unit:String="photos"):Snapshot {
        val now=clock();val count=done.coerceIn(0,total.coerceAtLeast(0))
        if(label!=stage || total!=this.total || count<initial){stage=label;this.total=total;began=now;initial=count}
        val completed=count-initial;val elapsed=(now-began).coerceAtLeast(0)
        val estimate=if(completed>0 && elapsed>=1000 && count<total)elapsed.toDouble()/completed*(total-count)else null
        return Snapshot(label,count,total,unit,estimate?.toLong())
    }
}

internal object FaceNotifications {
    const val ID=41;const val CHANNEL="face_processing"
    private fun manager(c:Context)=c.getSystemService(NotificationManager::class.java)
    fun channel(c:Context){manager(c).createNotificationChannel(NotificationChannel(CHANNEL,"People recognition",NotificationManager.IMPORTANCE_LOW).apply{description="Recognition progress and pause controls"})}
    private fun open(c:Context)=PendingIntent.getActivity(c,0,Intent(c,PeopleActivity::class.java).putExtra("search",true),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun resume(c:Context)=PendingIntent.getForegroundService(c,2,Intent(c,FaceScanService::class.java).setAction(FaceScanService.GROUP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun running(c:Context,progress:RecognitionProgress.Snapshot):Notification {
        channel(c)
        val pause=PendingIntent.getService(c,1,Intent(c,FaceScanService::class.java).setAction(FaceScanService.PAUSE_DAY),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_photo).setContentTitle("Organising people")
            .setContentText(progress.text()).setStyle(Notification.BigTextStyle().bigText(progress.text())).setContentIntent(open(c))
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(progress.total,progress.done,progress.total<=0)
            .addAction(Notification.Action.Builder(null,"Pause for 24 hours",pause).build()).build()
    }
    fun update(c:Context,progress:RecognitionProgress.Snapshot){manager(c).notify(ID,running(c,progress))}
    fun paused(c:Context){
        channel(c);val time=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(AutoPeople.pauseUntil(c)))
        manager(c).notify(ID,Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_photo).setContentTitle("Recognition paused")
            .setContentText("Resumes $time").setContentIntent(open(c)).setOnlyAlertOnce(true).setShowWhen(false)
            .addAction(Notification.Action.Builder(null,"Resume now",resume(c)).build()).build())
    }
    /** A future scheduled retry is idle: it must not look like active recognition. */
    fun idle(c:Context){
        if(FaceJobs.state.busy && !FaceJobs.state.background)return
        manager(c).cancel(ID)
    }
}
