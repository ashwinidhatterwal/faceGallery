package com.mosaic.gallery

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import java.util.concurrent.Executors

/** Process-wide progress only; durable results stay in FaceStore. No Activity references while hidden. */
object FaceJobs {
    data class State(val mode:String?=null,val pausing:Boolean=false,val message:String="",val faces:FaceStore.Summary?=null,val signatures:FaceStore.Signatures?=null){val busy get()=mode!=null}
    var state=State();private set
    private val observers=mutableSetOf<(State)->Unit>()
    fun observe(observer:(State)->Unit){observers+=observer;observer(state)}
    fun remove(observer:(State)->Unit){observers-=observer}
    fun publish(next:State){state=next;observers.toList().forEach{it(next)}}
}

/** User-started local-file processing outlives screens. Explicit pause/finish releases CPU resources. */
open class FaceScanService:Service(){
    companion object {
        const val AUTO="automatic";const val EDIT_PAUSE="edit_pause";const val DETECT="detect";const val SIGNATURES="signatures";const val GROUP="group";const val PAUSE="pause"
        private const val CHANNEL="face_processing";private const val NOTIFICATION=41
        // Stop before Android's six-hour foreground-service allowance; never keep an unbounded lock.
        private const val SESSION_MS=5*60*60*1000L+55*60*1000L
        internal fun typeFor(sdk:Int)=if(sdk>=29)ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
    }
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private var wake:PowerManager.WakeLock?=null
    private val query=CancellationSignal()
    @Volatile private var continuing=false
    private var started=false
    private var destroyed=false
    private var mode=DETECT
    private var automaticSession=false
    @Volatile private var pauseReason="Paused. Completed results are saved."
    private val timeout=Runnable{pause("Paused at the session time limit. Saved progress will resume automatically.");stopForeground(STOP_FOREGROUND_REMOVE);releaseWake();stopSelf()}
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action in listOf(PAUSE,EDIT_PAUSE)){
            if(intent?.action==PAUSE)AutoPeople.pause(this)
            if(started)pause()else stopSelf()
            return START_NOT_STICKY
        }
        val requested=intent?.action
        if(requested !in listOf(DETECT,SIGNATURES,GROUP,AUTO)){stopSelf();return START_NOT_STICKY}
        if(started)return START_NOT_STICKY
        if(FaceJobs.state.busy){stopSelf();return START_NOT_STICKY}
        if(requested==AUTO && (!AutoPeople.allowed(this) || !AutoPeople.enabled(this))){stopSelf();return START_NOT_STICKY}
        if(requested!=AUTO)AutoPeople.resume(this)
        AutoPeople.ensure(this,0)
        FaceWork.pauseForEdit();started=true;automaticSession=requested==AUTO;mode=if(automaticSession)GROUP else requested!!;continuing=true
        FaceJobs.publish(FaceJobs.State(mode=mode,message="Preparing accessible photos…"))
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Face processing",NotificationManager.IMPORTANCE_LOW).apply{description="Silent status and pause control for photo analysis"})
            val notification=notification("Preparing accessible photos…",0,0)
            if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification,typeFor(Build.VERSION.SDK_INT))else startForeground(NOTIFICATION,notification)
            wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Mosaic:FaceProcessing").apply{setReferenceCounted(false);acquire(SESSION_MS)}
            main.postDelayed(timeout,SESSION_MS)
            worker.execute{process()}
        }catch(error:Exception){finishJob("Could not start processing: ${error.javaClass.simpleName}. Try again while the app is open.")}
        return START_NOT_STICKY
    }
    private var completeAccess=false
    protected open fun accessiblePhotos(signal:CancellationSignal):List<PhotoRecord> {
        val result=GalleryData.load(this,signal,!automaticSession);completeAccess=PhotoIndex.allowed(this) && result.unreadableVolumes==0;return result.photos
    }
    private fun process(){
        var faces:FaceStore.Summary?=null;var signatures:FaceStore.Signatures?=null;var refinementDeferred=false
        val outcome=runCatching{FaceWork.write{
            val photos=accessiblePhotos(query);query.throwIfCanceled()
            FaceStore(this).use{store->
                store.retain(photos,completeAccess)
                fun stage(message:String){main.post{if(!destroyed && continuing){FaceJobs.publish(FaceJobs.state.copy(message=message));if(!automaticSession)getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification(message,0,0))}}}
                if(mode==GROUP){
                    val missing=store.pending(photos,false)
                    if(missing.isNotEmpty() && keepGoing()){stage("Finding faces…");FaceEngine(applicationContext).use{engine->FaceProcessing.run(store,missing,::keepGoing,engine::detect)}}
                    val unsigned=store.pendingSignatures(photos,false)
                    if(unsigned.isNotEmpty() && keepGoing()){stage("Recognising faces…");SignatureEngine(applicationContext).use{engine->for(photo in unsigned){if(!keepGoing())break;runCatching{engine.process(store,photo,::keepGoing,retryErrors=false)}}}}
                }
                val pending=when(mode){DETECT->store.pending(photos);SIGNATURES->store.pendingSignatures(photos);else->emptyList()}
                var lastUpdate=0L;var grouping=mode==GROUP
                fun progress(count:Int,total:Int=pending.size){
                    val now=SystemClock.elapsedRealtime()
                    if(now-lastUpdate<1000 && count!=total)return
                    lastUpdate=now;val summary=store.summary();val vectors=store.signatureSummary()
                    main.post{if(!destroyed && continuing){val message=if(grouping)if(count<total)"Matching faces · $count / $total"else"Comparing people…"else"Finding faces · $count / $total";FaceJobs.publish(FaceJobs.State(mode,message=message,faces=summary,signatures=vectors));if(!automaticSession)getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification(message,count,total))}}
                }
                progress(0)
                if(mode==GROUP)PeopleGrouping.run(PeopleStore(store),::keepGoing,reuseComparisons=true,phase=::stage){count,total->progress(count,total)}
                else if(pending.isNotEmpty()){
                    if(mode==DETECT)FaceEngine(applicationContext).use{engine->FaceProcessing.run(store,pending,::keepGoing,engine::detect,progress={progress(it)})}
                    else SignatureEngine(applicationContext).use{engine->
                        for((index,photo) in pending.withIndex()){
                            if(!keepGoing())break
                            runCatching{engine.process(store,photo,::keepGoing)}.onFailure{error->
                                if(keepGoing())store.observations(photo.uri.toString()).forEachIndexed{i,face->if(face.authority!="Shadow" && store.needsSignature(photo.uri.toString(),i))store.saveSignature(photo.uri.toString(),i,null,"error",error.javaClass.simpleName)}
                            }
                            progress(index+1)
                        }
                    }
                }
                if(mode==SIGNATURES && keepGoing()){grouping=true;PeopleGrouping.run(PeopleStore(store),::keepGoing,reuseComparisons=true,phase=::stage){count,total->progress(count,total)}}
                if(mode in listOf(GROUP,SIGNATURES) && keepGoing()){
                    // Snapshot once: failed/no-progress photos cannot re-enter this session.
                    val retry=SignatureRefinement.candidates(store,photos,Int.MAX_VALUE)
                    if(retry.isNotEmpty())SignatureEngine(applicationContext).use{engine->
                        stage("Refining difficult faces…")
                        RefinementSweep.run(retry.entries.toList(),::keepGoing,refine={entry->
                            val (photo,keys)=entry
                            val updated=runCatching{engine.refine(store,photo,keys,::keepGoing)}.getOrElse{
                                if(keepGoing())keys.forEach{store.refinementFailed(it)}
                                0
                            }
                            // Even an encoder returning zero must consume a retry or enter backoff.
                            if(keepGoing())keys.filter{store.needsRefinement(it)}.forEach{store.refinementFailed(it)}
                            updated
                        },regroup={PeopleGrouping.run(PeopleStore(store),::keepGoing,reuseComparisons=true,phase=::stage){count,total->progress(count,total)}})
                    }
                }
                if(mode in listOf(GROUP,SIGNATURES) && keepGoing() && store.pending(photos,false).isEmpty() && store.pendingSignatures(photos,false).isEmpty())AutoPeople.grouped(this,AutoPeople.revision(this))
                faces=store.summary();signatures=store.signatureSummary()
            }
        }
        }
        PeopleData.changed();AutoPeople.ensure(this,60_000)
        val message=if(!continuing)pauseReason else outcome.fold({"${when(mode){DETECT->"Face scan";GROUP->"People grouping";else->"Signature processing"}} finished."},{"Processing stopped: ${it.javaClass.simpleName}. Completed results are saved; tap to retry."})
        main.post{finishJob(message+(if(continuing && refinementDeferred)" Face retries deferred while the phone is warm."else""),faces,signatures)}
    }
    private val heat=FaceHeat(this)
    private fun keepGoing():Boolean {
        if(!continuing || !AutoPeople.enabled(this))return false
        if(!heat.canRun()){pauseReason="Paused for battery or temperature. Recognition will continue automatically.";continuing=false;return false}
        val full=checkSelfPermission(if(Build.VERSION.SDK_INT>=33)android.Manifest.permission.READ_MEDIA_IMAGES else android.Manifest.permission.READ_EXTERNAL_STORAGE)==android.content.pm.PackageManager.PERMISSION_GRANTED
        val partial=Build.VERSION.SDK_INT>=34 && checkSelfPermission(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)==android.content.pm.PackageManager.PERMISSION_GRANTED
        if(!full && !partial){pauseReason="Photo access changed. Reopen the gallery and grant access to resume.";continuing=false;return false}
        return true
    }
    private fun pause(reason:String="Paused. Completed results are saved."){
        pauseReason=reason;continuing=false;query.cancel()
        FaceJobs.publish(FaceJobs.state.copy(pausing=true,message="Pausing…"))
    }
    private fun notification(message:String,count:Int,total:Int):Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,FaceScanActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause=PendingIntent.getService(this,1,Intent(this,FaceScanService::class.java).setAction(PAUSE),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_photo).setContentTitle(if(automaticSession)"Recognising people"else when(mode){DETECT->"Scanning faces";GROUP->"Grouping people";else->"Building face signatures"})
            .setContentText(if(automaticSession)"Working quietly in the background"else message).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS)
            .apply{if(!automaticSession)setProgress(total,count,total==0)}.addAction(Notification.Action.Builder(null,"Pause",pause).build()).build()
    }
    private fun finishJob(message:String,faces:FaceStore.Summary?=null,signatures:FaceStore.Signatures?=null){
        continuing=false;main.removeCallbacks(timeout);releaseWake()
        FaceJobs.publish(FaceJobs.State(message=message,faces=faces,signatures=signatures))
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    private fun releaseWake(){wake?.let{if(it.isHeld)it.release()};wake=null}
    override fun onTimeout(startId:Int,fgsType:Int){pause("Android paused the scan at its time limit. Saved progress will resume automatically.");releaseWake();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    override fun onDestroy(){
        destroyed=true;continuing=false;query.cancel();main.removeCallbacks(timeout);releaseWake();worker.shutdown()
        // Native inference finishes safely. Its current uncommitted item remains pending.
        super.onDestroy()
    }
}
