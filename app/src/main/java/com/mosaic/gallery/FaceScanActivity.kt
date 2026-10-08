package com.mosaic.gallery

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.CancellationSignal
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/** Scan controls observe the independent service; leaving this screen only detaches the UI. */
class FaceScanActivity:Activity(){
    private val worker=Executors.newSingleThreadExecutor()
    @Volatile private var active=false
    @Volatile private var epoch=0
    private var query:CancellationSignal?=null
    private var photos=emptyList<PhotoRecord>()
    private lateinit var status:TextView
    private lateinit var action:Button
    private lateinit var clear:Button
    private lateinit var signatures:Button
    private var signatureSummary=FaceStore.Signatures(0,0,0)
    private lateinit var adapter:PhotoGridAdapter
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(GalleryStyle.canvas(context))}
        val header=GalleryStyle.bar(this)
        header.addView(GalleryStyle.action(this,"back","Back",compact=true){finish()})
        header.addView(GalleryStyle.text(this,"Faces",30f),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(GalleryStyle.action(this,"album","People",compact=true){startActivity(Intent(this,PeopleActivity::class.java))})
        root.addView(header)
        root.addView(GalleryStyle.text(this,"Recognition and grouping start automatically when you open the gallery, and continue with the screen locked. Pause from the notification; start a scan here to resume.",14f,GalleryStyle.muted(this)).apply{setPadding(dp(20),dp(8),dp(20),dp(8))})
        status=GalleryStyle.text(this,"Loading scan index…",14f).apply{setPadding(dp(20),dp(8),dp(20),dp(12))};root.addView(status)
        val buttons=LinearLayout(this).apply{gravity=Gravity.CENTER;setPadding(dp(12),0,dp(12),dp(8))}
        action=Button(this).apply{text="Scan changes";isEnabled=false;setOnClickListener{if(FaceJobs.state.busy)pauseScan()else startScan(if(AutoPeople.enabled(this@FaceScanActivity))FaceScanService.DETECT else FaceScanService.GROUP)}}
        clear=Button(this).apply{text="Clear results";isEnabled=false;setOnClickListener{AlertDialog.Builder(this@FaceScanActivity).setTitle("Clear face results?").setMessage("Photos remain unchanged. The next scan will analyse them again.").setNegativeButton("Cancel",null).setPositiveButton("Clear"){_,_->reset()}.show()}}
        signatures=Button(this).apply{text="Build signatures";isEnabled=false;setOnClickListener{if(FaceJobs.state.busy)pauseScan()else startScan(FaceScanService.SIGNATURES)}}
        root.addView(signatures,LinearLayout.LayoutParams(-1,dp(48)).apply{setMargins(dp(12),0,dp(12),dp(8))})
        buttons.addView(action,LinearLayout.LayoutParams(0,dp(48),1f));buttons.addView(clear,LinearLayout.LayoutParams(0,dp(48),1f));root.addView(buttons)
        adapter=PhotoGridAdapter(this,{photo->startActivity(Intent(this,FaceReviewActivity::class.java).setData(photo.uri).putExtra("name",photo.displayName))},{})
        root.addView(RecyclerView(this).apply{layoutManager=GridLayoutManager(this@FaceScanActivity,4).apply{spanSizeLookup=object:GridLayoutManager.SpanSizeLookup(){override fun getSpanSize(position:Int)=if(this@FaceScanActivity.adapter.isHeader(position))4 else 1}};adapter=this@FaceScanActivity.adapter;itemAnimator=null},LinearLayout.LayoutParams(-1,0,1f))
        Ui.insets(this,root);setContentView(root);Ui.back(this){finish()}
    }
    override fun onResume(){
        super.onResume();active=true;loaded=false;photos=emptyList();shown=emptyList();adapter.submitList(emptyList(),false);FaceJobs.observe(observer);status.text="Loading scan index…";val token=++epoch;action.text="Scan changes";action.isEnabled=false;clear.isEnabled=false;signatures.isEnabled=false;signatures.text="Build signatures"
        val signal=CancellationSignal();query=signal
        worker.execute{
            val result=runCatching{
                val list=GalleryData.load(this,signal).photos;signal.throwIfCanceled()
                FaceStore(this).use{store->Triple(list,store.summary(),store.signatureSummary())}
            }
            runOnUiThread{if(valid(token))result.onSuccess{(list,summary,vectors)->photos=list;signatureSummary=vectors;loaded=true;show(summary,FaceJobs.state.message);controls()}.onFailure{status.text="Could not read accessible photos. Check photo permission and reopen this screen."}}
        }
    }
    private var loaded=false
    private var latestSummary:FaceStore.Summary?=null
    private val observer:(FaceJobs.State)->Unit={state->
        if(active){
            state.signatures?.let{signatureSummary=it}
            if(loaded)(state.faces?:latestSummary)?.let{show(it,state.message)}
            controls(state)
        }
    }
    private fun controls(state:FaceJobs.State=FaceJobs.state){
        action.text=if(state.busy && state.mode!=FaceScanService.SIGNATURES)if(state.pausing)"Pausing…"else if(state.mode==FaceScanService.GROUP)"Pause recognition"else"Pause scan"else if(!AutoPeople.enabled(this))"Resume recognition"else"Scan changes"
        signatures.text=if(state.mode==FaceScanService.SIGNATURES)if(state.pausing)"Pausing…"else"Pause signatures"else"Build signatures"
        action.isEnabled=loaded && !state.pausing && (!state.busy || state.mode!=FaceScanService.SIGNATURES)
        signatures.isEnabled=loaded && !state.pausing && (!state.busy || state.mode==FaceScanService.SIGNATURES)
        clear.isEnabled=loaded && !state.busy
    }
    private fun startScan(mode:String){
        if(FaceJobs.state.busy)return
        if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED && !getSharedPreferences("face-notifications",0).getBoolean("asked",false)){
            getSharedPreferences("face-notifications",0).edit().putBoolean("asked",true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),3101)
        }
        action.isEnabled=false;signatures.isEnabled=false;clear.isEnabled=false
        runCatching{startForegroundService(Intent(this,FaceScanService::class.java).setAction(mode))}.onFailure{status.text="Could not start background processing. Try again while this screen is open.";controls()}
    }
    private fun pauseScan(){startService(Intent(this,FaceScanService::class.java).setAction(FaceScanService.PAUSE))}
    private fun show(summary:FaceStore.Summary,progress:String=""){
        latestSummary=summary
        status.text="${summary.done} of ${photos.size} photos analysed · ${summary.faces} faces\nClear ${summary.counts["Anchor"]?:0} · Usable ${summary.counts["Support"]?:0} · Weak ${summary.counts["Shadow"]?:0}"+
            "\n${signatureSummary.ready} signatures ready · ${signatureSummary.skipped} unaligned · ${signatureSummary.errors} to retry"+
            (if(summary.errors>0)"\n${summary.errors} photos could not be analysed; the next scan retries them."else "")+(if(progress.isNotEmpty())"\n$progress"else "")
        val results=photos.filter{it.uri.toString() in summary.facePhotos}
        if(results!=shown){shown=results;adapter.submitList(results,false)}
    }
    private var shown=emptyList<PhotoRecord>()
    private fun reset(){
        if(FaceJobs.state.busy)return
        val token=epoch;loaded=false;controls()
        worker.execute{FaceWork.pauseForEdit();FaceWork.write{FaceStore(this).use{store->store.clear();val summary=store.summary();val vectors=store.signatureSummary();runOnUiThread{if(valid(token)){signatureSummary=vectors;loaded=true;show(summary,FaceJobs.state.message);controls()}}}}}
    }
    private fun valid(token:Int)=active && token==epoch && !isDestroyed
    private fun dp(value:Int)=GalleryStyle.dp(this,value)
    override fun onPause(){active=false;epoch++;FaceJobs.remove(observer);query?.cancel();super.onPause()}
    override fun onDestroy(){FaceJobs.remove(observer);worker.shutdown();adapter.close();super.onDestroy()}
}
