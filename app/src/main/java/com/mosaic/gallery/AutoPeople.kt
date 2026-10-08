package com.mosaic.gallery

import android.Manifest
import android.app.job.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.provider.MediaStore
import java.util.concurrent.Executors

/** Small persisted jobs, with a content trigger and idle discovery fallback. No foreground notification. */
object AutoPeople {
    const val BATCH=71;const val DISCOVER=72;const val WATCH=73
    private var retained:List<PhotoRecord>?=null;private var retainedAccess="";private var retainedComplete=false
    fun retainIfChanged(c:Context,store:FaceStore,photos:List<PhotoRecord>,complete:Boolean){
        if(retained!=photos || retainedAccess!=PeopleData.access(c) || retainedComplete!=complete){store.retain(photos,complete);retained=photos;retainedAccess=PeopleData.access(c);retainedComplete=complete}
    }
    private fun prefs(c:Context)=c.getSharedPreferences("automatic-people",0)
    fun allowed(c:Context)=c.checkSelfPermission(if(Build.VERSION.SDK_INT>=33)Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED || (Build.VERSION.SDK_INT>=34 && c.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)==PackageManager.PERMISSION_GRANTED)
    fun enabled(c:Context)=!prefs(c).getBoolean("paused",false)
    fun pause(c:Context){
        prefs(c).edit().putBoolean("paused",true).apply()
        val scheduler=c.getSystemService(JobScheduler::class.java)
        listOf(BATCH,DISCOVER,WATCH).forEach(scheduler::cancel)
        FaceWork.pauseForEdit()
    }
    fun resume(c:Context){prefs(c).edit().putBoolean("paused",false).apply()}
    fun canStartVisible(c:Context)=allowed(c) && enabled(c) && !FaceJobs.state.busy && SystemClock.elapsedRealtime()>=FaceWork.editingUntil
    /** Read on a worker; completed photos/signatures are never submitted for inference again. */
    fun hasPending(c:Context,photos:List<PhotoRecord>):Boolean {
        if(photos.isEmpty())return false
        return FaceStore(c).use{store->
            store.pending(photos,false).isNotEmpty() || store.pendingSignatures(photos,false).isNotEmpty() ||
                (needsGrouping(c) && store.signatureSummary().ready>0) || SignatureRefinement.candidates(store,photos,1).isNotEmpty()
        }
    }
    @Synchronized fun dirty(c:Context){val p=prefs(c);p.edit().putLong("revision",p.getLong("revision",0)+1).apply()}
    fun revision(c:Context)=prefs(c).getLong("revision",0)
    fun needsGrouping(c:Context)=revision(c)!=prefs(c).getLong("grouped",-1) || prefs(c).getInt("grouping-policy",0)!=2
    @Synchronized fun grouped(c:Context,value:Long){if(revision(c)==value)prefs(c).edit().putLong("grouped",value).putInt("grouping-policy",2).apply()}
    fun ensure(context:Context,delay:Long=15_000){
        val c=context.applicationContext;if(!allowed(c) || !enabled(c))return
        runCatching{
            val scheduler=c.getSystemService(JobScheduler::class.java);val service=ComponentName(c,AutoPeopleJob::class.java)
            if(scheduler.getPendingJob(DISCOVER)==null)scheduler.schedule(JobInfo.Builder(DISCOVER,service).setPeriodic(2*60*60_000L).setRequiresDeviceIdle(true).setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).setPersisted(true).build())
            if(scheduler.getPendingJob(WATCH)==null)watch(c)
            request(c,delay)
        }
    }
    fun watch(c:Context){if(!allowed(c) || !enabled(c))return;runCatching{c.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(WATCH,ComponentName(c,AutoPeopleJob::class.java)).addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS)).setTriggerContentUpdateDelay(5_000).setTriggerContentMaxDelay(30_000).setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())}}
    fun request(c:Context,delay:Long=60_000,replaceFinished:Boolean=false){
        if(!allowed(c) || !enabled(c) || FaceWork.automatic)return
        runCatching{val s=c.getSystemService(JobScheduler::class.java);if(replaceFinished || s.getPendingJob(BATCH)==null)s.schedule(JobInfo.Builder(BATCH,ComponentName(c,AutoPeopleJob::class.java)).setMinimumLatency(delay).setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).setBackoffCriteria(5*60_000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL).setPersisted(true).build())}
    }
}
/** Conservative battery/thermal gates also work on Android 9 via battery temperature. */
class FaceHeat(private val context:Context) {
    data class State(val battery:Int,val charging:Boolean,val temperature:Int,val thermal:Int,val saver:Boolean)
    companion object{fun safe(s:State)=!s.saver && (s.charging || s.battery<0 || s.battery>=20) && (s.temperature<=0 || s.temperature<400) && s.thermal<2}
    private var checked=Long.MIN_VALUE;private var usable=true
    fun canRun():Boolean {
        val now=SystemClock.elapsedRealtime();if(checked!=Long.MIN_VALUE && now-checked<1_000)return usable;checked=now
        val p=context.getSystemService(PowerManager::class.java)
        val b=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level=b?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)?:-1;val scale=b?.getIntExtra(BatteryManager.EXTRA_SCALE,100)?:100
        usable=safe(State(if(level<0 || scale<=0)-1 else level*100/scale,(b?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)?:0)!=0,b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0)?:0,if(Build.VERSION.SDK_INT>=29)p.currentThermalStatus else 0,p.isPowerSaveMode))
        return usable
    }
}
class PeopleBoot:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){if(intent.action in listOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED))AutoPeople.ensure(context,60_000)}}
open class AutoPeopleJob:JobService() {
    private val worker=Executors.newSingleThreadExecutor();private val main=Handler(Looper.getMainLooper())
    private data class Run(val params:JobParameters,val signal:CancellationSignal=CancellationSignal(),@Volatile var stopped:Boolean=false)
    private var current:Run?=null
    private fun canCommit()=current?.stopped==false && !FaceWork.stopAutomatic && !FaceJobs.state.busy && AutoPeople.allowed(this) && AutoPeople.enabled(this)
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
        if(params.jobId==AutoPeople.WATCH)GalleryData.invalidate()
        if(current!=null || !AutoPeople.allowed(this) || !AutoPeople.enabled(this) || !FaceWork.begin()){main.post{AutoPeople.request(this,5*60_000L,params.jobId==AutoPeople.BATCH);if(params.jobId==AutoPeople.WATCH)AutoPeople.watch(this)};return false}
        val run=Run(params);current=run
        worker.execute{
            var more=false;var cool=false;var retryDelay:Long?=null
            val outcome=runCatching{FaceWork.write{
                val heat=FaceHeat(this);val deadline=SystemClock.elapsedRealtime()+20_000
                fun keepGoing():Boolean {val okay=heat.canRun();if(!okay)cool=true;return !run.stopped && !FaceWork.stopAutomatic && !FaceJobs.state.busy && AutoPeople.allowed(this) && AutoPeople.enabled(this) && SystemClock.elapsedRealtime()<deadline && okay}
                if(!keepGoing()){more=true;return@write}
                val result=photos(run.signal);val photos=result.photos
                FaceStore(this).use{store->
                    // A disconnected volume or selected-only access must not erase confirmed identities.
                    val complete=PhotoIndex.allowed(this) && result.unreadableVolumes==0
                    AutoPeople.retainIfChanged(this,store,photos,complete)
                    val detectionDeadline=minOf(deadline-10_000,SystemClock.elapsedRealtime()+8_000)
                    val pending=store.pending(photos,false).take(8)
                    if(pending.isNotEmpty() && keepGoing())detect(store,pending,{keepGoing() && SystemClock.elapsedRealtime()<detectionDeadline})
                    val signatureDeadline=deadline-2_000
                    val signatures=store.pendingSignatures(photos,false).take(12)
                    if(signatures.isNotEmpty() && keepGoing())signatures(store,signatures,{keepGoing() && SystemClock.elapsedRealtime()<signatureDeadline})
                    // One original-photo retry only after normal recognition has caught up.
                    if(store.pending(photos,false).isEmpty() && store.pendingSignatures(photos,false).isEmpty() && keepGoing()){
                        val retry=SignatureRefinement.candidates(store,photos,1)
                        for((photo,keys) in retry){
                            if(!keepGoing())break
                            runCatching{refine(store,photo,keys,::keepGoing)}
                            if(keepGoing())keys.filter{store.needsRefinement(it)}.forEach{store.refinementFailed(it)}
                        }
                    }
                    if(AutoPeople.needsGrouping(this) && store.signatureSummary().ready>0 && keepGoing() && SignatureRefinement.candidates(store,photos,1).isEmpty()){
                        PeopleGrouping.run(PeopleStore(store),::keepGoing,reuseComparisons=true)
                        if(keepGoing())AutoPeople.grouped(this,AutoPeople.revision(this))
                    }
                    retryDelay=store.nextRetryDelay()
                    more=store.pending(photos,false).isNotEmpty() || store.pendingSignatures(photos,false).isNotEmpty() || (AutoPeople.needsGrouping(this) && store.signatureSummary().ready>0) || SignatureRefinement.candidates(store,photos,1).isNotEmpty()
                }
            }}
            main.post{
                FaceWork.end();if(current===run)current=null
                if(!run.stopped)jobFinished(params,false)
                if(params.jobId==AutoPeople.WATCH)AutoPeople.watch(this)
                if(more || retryDelay!=null || outcome.isFailure || run.stopped)AutoPeople.request(this,if(cool || outcome.isFailure)15*60_000L else if(more)60_000L else retryDelay?:60_000L,params.jobId==AutoPeople.BATCH)
            }
        };return true
    }
    override fun onStopJob(params:JobParameters):Boolean {current?.takeIf{it.params===params}?.let{it.stopped=true;it.signal.cancel()};return AutoPeople.allowed(this) && AutoPeople.enabled(this)}
    override fun onDestroy(){current?.let{it.stopped=true;it.signal.cancel()};worker.shutdown();super.onDestroy()}
}
