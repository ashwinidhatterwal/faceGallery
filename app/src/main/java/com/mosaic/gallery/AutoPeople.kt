package com.mosaic.gallery

import android.Manifest
import android.app.job.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.provider.MediaStore
import java.util.concurrent.Executors

/** Small persisted jobs, with a content trigger and periodic discovery fallback. No foreground notification. */
object AutoPeople {
    const val BATCH=71;const val DISCOVER=72;const val WATCH=73;const val RESUME=74
    private var retained:List<PhotoRecord>?=null;private var retainedAccess="";private var retainedComplete=false
    fun retainIfChanged(c:Context,store:FaceStore,photos:List<PhotoRecord>,complete:Boolean){
        if(retained!=photos || retainedAccess!=PeopleData.access(c) || retainedComplete!=complete){store.retain(photos,complete);retained=photos;retainedAccess=PeopleData.access(c);retainedComplete=complete}
    }
    private fun prefs(c:Context)=c.getSharedPreferences("automatic-people",0)
    fun allowed(c:Context)=c.checkSelfPermission(if(Build.VERSION.SDK_INT>=33)Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED || (Build.VERSION.SDK_INT>=34 && c.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)==PackageManager.PERMISSION_GRANTED)
    fun started(c:Context)=prefs(c).let{it.getBoolean("user-started",it.getLong("pause-until",0)>0)}
    fun pauseUntil(c:Context)=prefs(c).getLong("pause-until",0)
    @Synchronized fun enabled(c:Context,now:Long=System.currentTimeMillis()):Boolean {
        if(!RecognitionConsent.allowed(c) || !started(c))return false
        val p=prefs(c);if(p.getBoolean("paused",false))return false
        val until=p.getLong("pause-until",0)
        if(until>now)return false
        if(until!=0L)p.edit().putBoolean("user-started",true).remove("pause-until").apply()
        return true
    }
    @Synchronized fun pauseForDay(c:Context){
        prefs(c).edit().putBoolean("paused",false).putBoolean("user-started",true).putLong("pause-until",System.currentTimeMillis()+24*60*60_000L).apply()
        val scheduler=c.getSystemService(JobScheduler::class.java)
        listOf(BATCH,DISCOVER,WATCH).forEach(scheduler::cancel);FaceWork.pauseForEdit();scheduleResume(c)
    }
    @Synchronized private fun scheduleResume(c:Context){
        val until=pauseUntil(c);if(until<=0 || prefs(c).getBoolean("paused",false))return
        val scheduler=c.getSystemService(JobScheduler::class.java)
        if(scheduler.getPendingJob(RESUME)?.extras?.getLong("until")==until)return
        runCatching{scheduler.schedule(JobInfo.Builder(RESUME,ComponentName(c,AutoPeopleJob::class.java)).setMinimumLatency((until-System.currentTimeMillis()).coerceAtLeast(0)).setPersisted(true).setExtras(PersistableBundle().apply{putLong("until",until)}).build())}
    }
    @Synchronized fun pause(c:Context){
        prefs(c).edit().putBoolean("paused",true).remove("pause-until").apply()
        val scheduler=c.getSystemService(JobScheduler::class.java)
        listOf(BATCH,DISCOVER,WATCH,RESUME).forEach(scheduler::cancel)
        FaceWork.pauseForEdit()
    }
    @Synchronized fun resume(c:Context){prefs(c).edit().putBoolean("user-started",true).putBoolean("paused",false).remove("pause-until").apply();c.getSystemService(JobScheduler::class.java).cancel(RESUME)}
    fun canStartVisible(c:Context)=allowed(c) && enabled(c) && !FaceJobs.state.busy && SystemClock.elapsedRealtime()>=FaceWork.editingUntil
    /** Read on a worker; completed photos/signatures are never submitted for inference again. */
    fun hasPending(c:Context,photos:List<PhotoRecord>):Boolean {
        val images=photos.filterNot{it.isVideo}
        if(images.isEmpty())return ContactRecognition.needsWork(c)
        return FaceStore(c).use{store->
            store.pending(images,false).isNotEmpty() || store.pendingSignatures(images,false).isNotEmpty() ||
                (needsGrouping(c) && store.signatureSummary().ready>0) || SignatureRefinement.candidates(store,images,1).isNotEmpty() || ContactRecognition.needsWork(c)
        }
    }
    @Synchronized fun dirty(c:Context){val p=prefs(c);p.edit().putLong("revision",p.getLong("revision",0)+1).apply()}
    fun revision(c:Context)=prefs(c).getLong("revision",0)
    fun needsGrouping(c:Context)=revision(c)!=prefs(c).getLong("grouped",-1) || prefs(c).getInt("grouping-policy",0)!=2
    @Synchronized fun grouped(c:Context,value:Long){if(revision(c)==value)prefs(c).edit().putLong("grouped",value).putInt("grouping-policy",2).apply()}
    @Synchronized fun ensure(context:Context,delay:Long=15_000){
        val c=context.applicationContext;if(!enabled(c)){val scheduler=c.getSystemService(JobScheduler::class.java);listOf(BATCH,DISCOVER,WATCH).forEach(scheduler::cancel);scheduleResume(c);return};if(!allowed(c))return
        c.getSystemService(JobScheduler::class.java).cancel(RESUME)
        runCatching{
            val scheduler=c.getSystemService(JobScheduler::class.java);val service=ComponentName(c,AutoPeopleJob::class.java)
            if(scheduler.getPendingJob(DISCOVER)==null || scheduler.getPendingJob(DISCOVER)?.isRequireDeviceIdle==true || scheduler.getPendingJob(DISCOVER)?.isRequireBatteryNotLow==true)scheduler.schedule(JobInfo.Builder(DISCOVER,service).setPeriodic(2*60*60_000L).setRequiresStorageNotLow(true).setPersisted(true).build())
            val watching=scheduler.getPendingJob(WATCH)
            if(watching==null || watching.isRequireBatteryNotLow || (ContactRecognition.available(c) && watching.triggerContentUris.orEmpty().none{it.uri==android.provider.ContactsContract.Contacts.CONTENT_URI}))watch(c)
            request(c,delay)
        }
    }
    @Synchronized fun watch(c:Context){if(!allowed(c) || !enabled(c))return;runCatching{val job=JobInfo.Builder(WATCH,ComponentName(c,AutoPeopleJob::class.java)).addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS));if(ContactRecognition.available(c))job.addTriggerContentUri(JobInfo.TriggerContentUri(android.provider.ContactsContract.Contacts.CONTENT_URI,JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS));c.getSystemService(JobScheduler::class.java).schedule(job.setTriggerContentUpdateDelay(5_000).setTriggerContentMaxDelay(30_000).setRequiresStorageNotLow(true).build())}}
    @Synchronized fun request(c:Context,delay:Long=60_000,replaceFinished:Boolean=false){
        if(!allowed(c) || !enabled(c) || FaceWork.automatic)return
        runCatching{val s=c.getSystemService(JobScheduler::class.java);if(replaceFinished || s.getPendingJob(BATCH)==null || s.getPendingJob(BATCH)?.isRequireBatteryNotLow==true)s.schedule(JobInfo.Builder(BATCH,ComponentName(c,AutoPeopleJob::class.java)).setMinimumLatency(delay).setRequiresStorageNotLow(true).setBackoffCriteria(5*60_000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL).setPersisted(true).build())}
    }
}
class PeopleBoot:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){if(intent.action in listOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED))AutoPeople.ensure(context,60_000)}}
open class AutoPeopleJob:JobService() {
    private val worker=Executors.newSingleThreadExecutor();private val main=Handler(Looper.getMainLooper())
    private data class Run(val params:JobParameters,val signal:CancellationSignal=CancellationSignal(),@Volatile var stopped:Boolean=false)
    private var current:Run?=null
    private fun canCommit()=current?.stopped==false && !FaceWork.stopAutomatic && (!FaceJobs.state.busy || FaceJobs.state.background) && AutoPeople.allowed(this) && AutoPeople.enabled(this)
    protected open fun photos(signal:CancellationSignal)=GalleryData.load(this,signal)
    protected open fun detect(store:FaceStore,pending:List<PhotoRecord>,keepGoing:()->Boolean){FaceEngine(applicationContext).use{engine->FaceProcessing.run(store,pending,keepGoing,engine::detect,canCommit=::canCommit)}}
    protected open fun signatures(store:FaceStore,pending:List<PhotoRecord>,keepGoing:()->Boolean){
        fun failed(photo:PhotoRecord,e:Throwable){if(canCommit())store.observations(photo.uri.toString()).forEachIndexed{i,f->if(f.authority!="Shadow" && store.needsSignature(photo.uri.toString(),i))store.saveSignature(photo.uri.toString(),i,null,"error",e.javaClass.simpleName)}}
        runCatching{SignatureEngine(applicationContext,1).use{engine->for(photo in pending){if(!keepGoing())break;runCatching{engine.process(store,photo,keepGoing,::canCommit,false)}.onFailure{failed(photo,it)}}}}
            .getOrThrow()
    }
    protected open fun refine(store:FaceStore,photo:PhotoRecord,keys:Set<GroupRules.Key>,keepGoing:()->Boolean){
        SignatureEngine(applicationContext,1).use{engine->engine.refine(store,photo,keys,keepGoing,::canCommit)}
    }
    override fun onStartJob(params:JobParameters):Boolean {
        if(params.jobId==AutoPeople.RESUME){getSystemService(JobScheduler::class.java).cancel(AutoPeople.RESUME);AutoPeople.ensure(this,0);return false}
        if(params.jobId==AutoPeople.WATCH){
            val authorities=params.triggeredContentAuthorities.orEmpty()
            if(authorities.isEmpty() || MediaStore.AUTHORITY in authorities)GalleryData.invalidate()
            if(authorities.isEmpty() || android.provider.ContactsContract.AUTHORITY in authorities)ContactRecognition.invalidate(this)
        }
        if(current!=null || !AutoPeople.allowed(this) || !AutoPeople.enabled(this) || !FaceWork.begin()){main.post{AutoPeople.request(this,5*60_000L,params.jobId==AutoPeople.BATCH);if(params.jobId==AutoPeople.WATCH)AutoPeople.watch(this)};return false}
        val run=Run(params);current=run
        FaceJobs.publish(FaceJobs.State(mode=FaceScanService.AUTO,background=true,message="Preparing photos…"))
        FaceNotifications.update(this,RecognitionProgress.Snapshot("Preparing photos",0,0))
        worker.execute{
            var more=false;var retryDelay:Long?=null
            val outcome=runCatching{FaceWork.write{
                val deadline=SystemClock.elapsedRealtime()+20_000
                fun keepGoing():Boolean {if(FaceWork.readRequested){more=true;return false};return !run.stopped && !FaceWork.stopAutomatic && (!FaceJobs.state.busy || FaceJobs.state.background) && AutoPeople.allowed(this) && AutoPeople.enabled(this) && SystemClock.elapsedRealtime()<deadline}
                if(!keepGoing()){more=true;return@write}
                val result=photos(run.signal);val photos=result.photos.filterNot{it.isVideo}
                FaceStore(this).use{store->
                    // A disconnected volume or selected-only access must not erase confirmed identities.
                    val complete=MediaAccess.fullPhotos(this) && result.unreadableVolumes==0
                    AutoPeople.retainIfChanged(this,store,photos,complete)
                    // Reserve a turn for portraits even while a large gallery is still catching up.
                    val contacts=ContactRecognition.runSafe(this,store,::keepGoing,run.signal,limit=2)
                    retryDelay=contacts.retryDelay
                    val rate=RecognitionProgress();var lastUpdate=0L
                    fun progress(){
                        val now=SystemClock.elapsedRealtime();if(now-lastUpdate<1000)return;lastUpdate=now
                        val pendingUris=(store.pending(photos,false)+store.pendingSignatures(photos,false)).map{it.uri}.toSet()
                        val snapshot=rate.sample("Photos checked",photos.size-pendingUris.size,photos.size)
                        main.post{if(current===run && !run.stopped && FaceJobs.state.background && AutoPeople.enabled(this)){FaceJobs.publish(FaceJobs.State(mode=FaceScanService.AUTO,background=true,message=snapshot.text(),progress=snapshot));FaceNotifications.update(this,snapshot)}}
                    }
                    progress()
                    val detectionDeadline=minOf(deadline-10_000,SystemClock.elapsedRealtime()+8_000)
                    val pending=store.pending(photos,false).take(8)
                    if(pending.isNotEmpty() && keepGoing()){detect(store,pending,{keepGoing() && SystemClock.elapsedRealtime()<detectionDeadline});progress()}
                    val signatureDeadline=deadline-2_000
                    val signatures=store.pendingSignatures(photos,false).take(12)
                    if(signatures.isNotEmpty() && keepGoing()){signatures(store,signatures,{keepGoing() && SystemClock.elapsedRealtime()<signatureDeadline});progress()}
                    // One original-photo retry only after normal recognition has caught up.
                    if(store.pending(photos,false).isEmpty() && store.pendingSignatures(photos,false).isEmpty() && keepGoing()){
                        val retry=SignatureRefinement.candidates(store,photos,1)
                        for((photo,keys) in retry){
                            if(!keepGoing())break
                            runCatching{refine(store,photo,keys,::keepGoing)}
                            if(keepGoing())keys.filter{store.needsRefinement(it)}.forEach{store.refinementFailed(it)}
                        }
                    }
                    if(AutoPeople.needsGrouping(this) && store.signatureSummary().ready>0 && keepGoing()){
                        PeopleGrouping.run(PeopleStore(store),::keepGoing,reuseComparisons=true,phase={label->
                            main.post{if(current===run && !run.stopped && FaceJobs.state.background && AutoPeople.enabled(this)){val snapshot=RecognitionProgress.Snapshot(label,0,0,"faces");FaceJobs.publish(FaceJobs.State(mode=FaceScanService.AUTO,background=true,message=label,progress=snapshot));FaceNotifications.update(this,snapshot)}}
                        })
                        if(keepGoing())AutoPeople.grouped(this,AutoPeople.revision(this))
                    }
                    retryDelay=listOfNotNull(retryDelay,store.nextRetryDelay()).minOrNull()
                    more=store.pending(photos,false).isNotEmpty() || store.pendingSignatures(photos,false).isNotEmpty() || (AutoPeople.needsGrouping(this) && store.signatureSummary().ready>0) || SignatureRefinement.candidates(store,photos,1).isNotEmpty()
                    more=more || contacts.more || ContactRecognition.needsWork(this)
                }
            }}
            main.post{
                if(FaceJobs.state.background)FaceJobs.publish(FaceJobs.State(message=if(more)"Recognition will continue"else "People are up to date"))
                FaceWork.end();if(current===run)current=null
                PeopleData.changed()
                if(AutoPeople.pauseUntil(this)>System.currentTimeMillis())FaceNotifications.paused(this)
                else if(more || outcome.isFailure)FaceNotifications.waiting(this,"Saved progress will continue automatically")
                else FaceNotifications.finished(this)
                if(!run.stopped)jobFinished(params,false)
                if(params.jobId==AutoPeople.WATCH)AutoPeople.watch(this)
                if(more || retryDelay!=null || outcome.isFailure || run.stopped)AutoPeople.request(this,if(outcome.isFailure)15*60_000L else if(more)1000L else retryDelay?:60_000L,params.jobId==AutoPeople.BATCH)
            }
        };return true
    }
    override fun onStopJob(params:JobParameters):Boolean {current?.takeIf{it.params===params}?.let{it.stopped=true;it.signal.cancel()};return AutoPeople.allowed(this) && AutoPeople.enabled(this)}
    override fun onDestroy(){current?.let{it.stopped=true;it.signal.cancel()};worker.shutdown();super.onDestroy()}
}
