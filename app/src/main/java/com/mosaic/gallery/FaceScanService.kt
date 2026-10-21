package com.mosaic.gallery

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import java.util.concurrent.Executors

/** Process-wide progress only; durable results stay in FaceStore. No Activity references while hidden. */
object FaceJobs {
    data class State(val mode:String?=null,val pausing:Boolean=false,val message:String="",val faces:FaceStore.Summary?=null,val signatures:FaceStore.Signatures?=null,val progress:RecognitionProgress.Snapshot?=null,val background:Boolean=false){val busy get()=mode!=null}
    @Volatile var state=State();private set
    private val observers=mutableSetOf<(State)->Unit>()
    fun observe(observer:(State)->Unit){observers+=observer;observer(state)}
    fun remove(observer:(State)->Unit){observers-=observer}
    fun publish(next:State){state=next;observers.toList().forEach{it(next)}}
}

/** User-started local-file processing outlives screens. Explicit pause/finish releases CPU resources. */
open class FaceScanService:Service(){
    companion object {
        const val AUTO="automatic";const val EDIT_PAUSE="edit_pause";const val DETECT="detect";const val SIGNATURES="signatures";const val GROUP="group";const val PAUSE="pause";const val PAUSE_DAY="pause_day"
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
    private var automaticSession=false;private var editingPause=false
    @Volatile private var pauseReason="Paused. Completed results are saved."
    private val timeout=Runnable{pause("Paused at the session time limit. Saved progress will resume automatically.");stopForeground(STOP_FOREGROUND_REMOVE);releaseWake();stopSelf()}
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action in listOf(PAUSE,PAUSE_DAY,EDIT_PAUSE)){
            editingPause=intent?.action==EDIT_PAUSE
            if(intent?.action==PAUSE)AutoPeople.pauseForDay(this)
            if(intent?.action==PAUSE_DAY)AutoPeople.pauseForDay(this)
            if(!started && FaceJobs.state.background)FaceJobs.publish(FaceJobs.state.copy(pausing=true,message="Pausing…"))
            if(started)pause(if(intent?.action==PAUSE_DAY)"Paused for 24 hours. Saved progress will resume tomorrow."else"Paused. Completed results are saved.")else {if(AutoPeople.pauseUntil(this)>System.currentTimeMillis())FaceNotifications.paused(this);stopSelf()}
            return START_NOT_STICKY
        }
        val requested=intent?.action
        if(requested !in listOf(DETECT,SIGNATURES,GROUP,AUTO)){stopSelf();return START_NOT_STICKY}
        if(!RecognitionConsent.allowed(this)){stopSelf();return START_NOT_STICKY}
        if(requested!=AUTO)AutoPeople.resume(this)
        if(started || FaceJobs.state.busy){AutoPeople.ensure(this,0);if(!started)stopSelf();return START_NOT_STICKY}
        if(requested==AUTO && (!AutoPeople.allowed(this) || !AutoPeople.enabled(this))){stopSelf();return START_NOT_STICKY}
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
        val result=GalleryData.load(this,signal,!automaticSession);completeAccess=MediaAccess.fullPhotos(this) && result.unreadableVolumes==0;return result.photos.filterNot{it.isVideo}
    }
    private fun process(){
        var faces:FaceStore.Summary?=null;var signatures:FaceStore.Signatures?=null;var issues=0;var deferred=false
        val photoRate=RecognitionProgress();val groupRate=RecognitionProgress();var lastUpdate=0L;var lastStage=""
        val outcome=runCatching{
            val photos=accessiblePhotos(query);query.throwIfCanceled()
            FaceStore(this).use{store->
                FaceWork.write{store.retain(photos,completeAccess)}
                fun report(progress:RecognitionProgress.Snapshot,force:Boolean=false){
                    val now=SystemClock.elapsedRealtime()
                    if(!force && progress.stage==lastStage && now-lastUpdate<1000 && progress.done!=progress.total)return
                    lastUpdate=now;lastStage=progress.stage
                    val summary=FaceWork.read{store.summary()};val vectors=FaceWork.read{store.signatureSummary()}
                    main.post{if(!destroyed && continuing && AutoPeople.enabled(this)){
                        FaceJobs.publish(FaceJobs.State(mode,message=progress.text(),faces=summary,signatures=vectors,progress=progress))
                        FaceNotifications.update(this,progress)
                    }}
                }
                fun regroup(budget:Long):Boolean {
                    if(!FaceWork.read{RecognitionState.needed(store.readableDatabase) && store.signatureSummary().ready>0})return true
                    var interrupted=false;val deadline=SystemClock.elapsedRealtime()+budget
                    FaceWork.write{
                        fun ready():Boolean {val okay=keepGoing() && !FaceWork.readRequested && SystemClock.elapsedRealtime()<deadline;if(!okay)interrupted=true;return okay}
                        PeopleGrouping.run(PeopleStore(store),::ready,reuseComparisons=true,
                            phase={report(RecognitionProgress.Snapshot(it,0,0,"faces"),true)},
                            progress={count,total->report(groupRate.sample("Sorting people",count,total,"faces"))})
                        if(!interrupted && keepGoing())RecognitionState.grouped(store.writableDatabase,store.evidenceRevision())
                    }
                    main.post{PeopleData.changed()}
                    return !interrupted && keepGoing()
                }
                if(mode==GROUP){
                    val pending=FaceWork.read{(store.pending(photos,false)+store.pendingSignatures(photos,false)).distinctBy{it.uri}}
                    report(photoRate.sample("Recognising photos",0,pending.size),true)
                    var detector:FaceEngine?=null;var encoder:SignatureEngine?=null
                    try{
                        for((index,photo) in pending.withIndex()){
                            if(!keepGoing())break
                            if(FaceWork.read{store.pending(listOf(photo),false).isNotEmpty()}){
                                val engine=detector?:FaceEngine(applicationContext).also{detector=it}
                                FaceProcessing.run(store,listOf(photo),::keepGoing,engine::detect)
                            }
                            if(keepGoing() && FaceWork.read{store.pendingSignatures(listOf(photo),false).isNotEmpty()}){
                                val engine=encoder?:SignatureEngine(applicationContext).also{encoder=it}
                                runCatching{engine.process(store,photo,::keepGoing,retryErrors=false)}.onFailure{store.signatureFailure(photo,it,::keepGoing)}
                            }
                            report(photoRate.sample("Recognising photos",index+1,pending.size))
                            if((index+1)%12==0 && keepGoing())regroup(2500)
                        }
                    }finally{detector?.close();encoder?.close()}
                }else{
                    val pending=FaceWork.read{if(mode==DETECT)store.pending(photos)else store.pendingSignatures(photos)}
                    val label=if(mode==DETECT)"Finding faces"else "Recognising photos"
                    report(photoRate.sample(label,0,pending.size),true)
                    if(pending.isNotEmpty()){
                        if(mode==DETECT)FaceEngine(applicationContext).use{engine->
                            for((index,photo) in pending.withIndex()){
                                if(!keepGoing())break
                                FaceProcessing.run(store,listOf(photo),::keepGoing,engine::detect)
                                report(photoRate.sample(label,index+1,pending.size))
                            }
                        }else SignatureEngine(applicationContext).use{engine->
                            for((index,photo) in pending.withIndex()){
                                if(!keepGoing())break
                                runCatching{engine.process(store,photo,::keepGoing)}.onFailure{store.signatureFailure(photo,it,::keepGoing)}
                                report(photoRate.sample(label,index+1,pending.size))
                            }
                        }
                    }
                }
                if(mode in listOf(GROUP,SIGNATURES) && keepGoing()){
                    // Yield the writer between grouping slices so People can read current results.
                    while(keepGoing() && !regroup(10_000)){}
                    val retry=FaceWork.read{SignatureRefinement.candidates(store,photos,Int.MAX_VALUE)}.entries.toList()
                    if(retry.isNotEmpty() && keepGoing())SignatureEngine(applicationContext).use{engine->
                        val rate=RecognitionProgress();report(rate.sample("Improving matches",0,retry.size),true)
                        for((index,entry) in retry.withIndex()){
                            if(!keepGoing())break
                            val (photo,keys)=entry
                            runCatching{engine.refine(store,photo,keys,::keepGoing)}.onFailure{if(it is DetectorUnavailable)throw it}
                            FaceWork.write{if(keepGoing())keys.filter{store.needsRefinement(it)}.forEach{store.refinementFailed(it)}}
                            report(rate.sample("Improving matches",index+1,retry.size))
                        }
                    }
                    if(keepGoing())while(keepGoing() && !regroup(10_000)){}
                    if(keepGoing()){val contact=ContactRecognition.runSafe(this,store,::keepGoing,query,limit=2);deferred=contact.more || contact.retryDelay!=null}
                }
                faces=FaceWork.read{store.summary()};signatures=FaceWork.read{store.signatureSummary()}
                val counts=FaceWork.read{store.workCounts(photos)};issues=counts.failed;deferred=deferred || store.nextRetryDelay(photos)!=null || FaceWork.read{store.pending(photos,false).isNotEmpty() || store.pendingSignatures(photos,false).isNotEmpty()}
            }
        }
        PeopleData.changed();AutoPeople.ensure(this,1000)
        val message=if(!continuing)pauseReason else outcome.fold({if(deferred)"Waiting to retry unfinished photos."else if(issues>0)"Checked with $issues photos needing attention."else"Recognition finished."},{"Recognition stopped. Saved progress is kept; tap Resume to retry."})
        main.post{finishJob(message,faces,signatures,issues)}
    }
    private fun keepGoing():Boolean {
        if(!continuing || !AutoPeople.enabled(this))return false
        val full=checkSelfPermission(if(Build.VERSION.SDK_INT>=33)android.Manifest.permission.READ_MEDIA_IMAGES else android.Manifest.permission.READ_EXTERNAL_STORAGE)==android.content.pm.PackageManager.PERMISSION_GRANTED
        val partial=Build.VERSION.SDK_INT>=34 && checkSelfPermission(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)==android.content.pm.PackageManager.PERMISSION_GRANTED
        if(!full && !partial){pauseReason="Photo access changed. Reopen the gallery and grant access to resume.";continuing=false;return false}
        return true
    }
    private fun pause(reason:String="Paused. Completed results are saved."){
        pauseReason=reason;continuing=false;query.cancel()
        FaceJobs.publish(FaceJobs.state.copy(pausing=true,message="Pausing…"))
    }
    private fun notification(message:String,count:Int,total:Int):Notification =
        FaceNotifications.running(this,RecognitionProgress.Snapshot(message,count,total))
    private fun finishJob(message:String,faces:FaceStore.Summary?=null,signatures:FaceStore.Signatures?=null,issues:Int=0){
        continuing=false;main.removeCallbacks(timeout);releaseWake();if(!editingPause && AutoPeople.enabled(this))FaceWork.clearCooldown()
        FaceJobs.publish(FaceJobs.State(message=message,faces=faces,signatures=signatures))
        stopForeground(STOP_FOREGROUND_REMOVE)
        if(AutoPeople.pauseUntil(this)>System.currentTimeMillis())FaceNotifications.paused(this)
        else if(message=="Recognition finished." || message.startsWith("Checked with"))FaceNotifications.finished(this,issues)
        else if(AutoPeople.enabled(this))FaceNotifications.waiting(this,message)
        stopSelf()
    }
    private fun releaseWake(){wake?.let{if(it.isHeld)it.release()};wake=null}
    override fun onTimeout(startId:Int,fgsType:Int){pause("Android paused the scan at its time limit. Saved progress will resume automatically.");releaseWake();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    override fun onDestroy(){
        destroyed=true;continuing=false;query.cancel();main.removeCallbacks(timeout);releaseWake();worker.shutdown()
        // Native inference finishes safely. Its current uncommitted item remains pending.
        super.onDestroy()
    }
}
