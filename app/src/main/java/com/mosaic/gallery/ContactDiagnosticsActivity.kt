package com.mosaic.gallery

import android.app.Activity
import android.os.Bundle
import android.text.*
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import java.util.concurrent.Executors

class ContactDiagnosticsActivity:Activity() {
    private val worker=Executors.newSingleThreadExecutor()
    @Volatile private var closed=false
    private var recheckSignal:android.os.CancellationSignal?=null
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(GalleryStyle.canvas(context))}
        val header=GalleryStyle.bar(this);header.addView(GalleryStyle.action(this,"back","Back",compact=true){finish()});header.addView(GalleryStyle.text(this,"Contact diagnostics",24f),LinearLayout.LayoutParams(0,-2,1f));root.addView(header)
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(GalleryStyle.dp(context,16),GalleryStyle.dp(context,12),GalleryStyle.dp(context,16),GalleryStyle.dp(context,8))}
        val status=GalleryStyle.text(this,"Checking saved results…",15f,GalleryStyle.muted(this));body.addView(status)
        body.addView(GalleryStyle.text(this,"Saved evidence. Tap a contact to recheck its photo. Similarity is not a percentage. Export the recognition report from People to share results without contact names or photos.",13f,GalleryStyle.muted(this)))
        val search=EditText(this).apply{hint="Find contact or report ID";setSingleLine();setTextColor(GalleryStyle.textColor(context));setHintTextColor(GalleryStyle.muted(context))};body.addView(search)
        root.addView(body)
        val list=ListView(this).apply{divider=null;setPadding(GalleryStyle.dp(context,16),0,GalleryStyle.dp(context,16),0)}
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f));Ui.insets(this,root);setContentView(root);Ui.back(this){finish()}
        var records=emptyList<JSONObject>()
        fun render() {
            val query=search.text.toString().trim()
            val filtered=records.filter{it.optString("name").contains(query,true) || it.getString("contact_tag").contains(query,true)}
            list.adapter=object:ArrayAdapter<JSONObject>(this,0,filtered) {
                override fun getView(position:Int,recycled:View?,parent:ViewGroup):View {
                    val entry=getItem(position)!!
                    val panel=(recycled as? LinearLayout)?:GalleryStyle.panelRoot(this@ContactDiagnosticsActivity).apply{
                        addView(GalleryStyle.text(this@ContactDiagnosticsActivity,"",18f));addView(GalleryStyle.text(this@ContactDiagnosticsActivity,"",14f,GalleryStyle.muted(context)));addView(GalleryStyle.text(this@ContactDiagnosticsActivity,"",12f,GalleryStyle.muted(context)))
                    }
                    (panel.getChildAt(0) as TextView).text=entry.optString("name","Contact ${entry.getString("contact_tag")}")
                    (panel.getChildAt(1) as TextView).text=ContactDiagnostics.explanation(entry)
                    (panel.getChildAt(2) as TextView).text=buildString{
                        append("Report ID: ${entry.getString("contact_tag")}")
                        if(entry.optBoolean("portrait_changed_since_scan"))append("\nPhoto changed since saved scan")
                        if(entry.has("provider_has_photo") && !entry.getBoolean("provider_has_photo"))append("\nNo photo exposed by Android contacts")
                        if(entry.getJSONArray("linked_group_ids").length()>0)append("\nAlready linked to a gallery group")
                    }
                    return panel
                }
            }
        }
        search.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){render()};override fun afterTextChanged(s:Editable?){} })
        fun load() {
            worker.execute {
                val result=runCatching{FaceStore(this).use{f->PeopleStore(f).snapshot{ContactRecognition.diagnostics(this,f,names=true)}}}
                runOnUiThread {
                    if(closed || isFinishing || isDestroyed)return@runOnUiThread
                    result.onSuccess { report->
                        val entries=report.getJSONArray("portrait_entries");records=(0 until entries.length()).map{entries.getJSONObject(it)}.sortedBy{it.optString("name").lowercase(java.util.Locale.ROOT)}
                        status.text="${records.size} contacts · stage: ${report.optString("last_stage").replace('_',' ')}"
                        if(records.isEmpty())status.text="No records. Check recognition consent and contacts permission."
                        render()
                    }.onFailure{status.text="Could not read contact diagnostics. Check permission and try again."}
                }
            }
        }
        list.setOnItemClickListener{parent,_,position,_->
            val entry=parent.getItemAtPosition(position) as JSONObject
            val panel=GalleryStyle.panelRoot(this)
            panel.addView(GalleryStyle.text(this,entry.optString("name"),20f))
            panel.addView(ScrollView(this).apply{addView(GalleryStyle.text(this@ContactDiagnosticsActivity,entry.toString(2),13f))},LinearLayout.LayoutParams(-1,resources.displayMetrics.heightPixels/2))
            val message=GalleryStyle.text(this,ContactDiagnostics.explanation(entry),14f,GalleryStyle.muted(this));panel.addView(message)
            val button=GalleryStyle.button(this,"Recheck photo"){}
            panel.addView(button)
            val dialog=GalleryStyle.dialog(this,panel)
            var cancel:android.os.CancellationSignal?=null
            dialog.setOnDismissListener{cancel?.cancel()}
            button.isEnabled=entry.optBoolean("provider_has_photo") && ContactRecognition.permitted(this)
            button.setOnClickListener {
                if(recheckSignal!=null)return@setOnClickListener
                val signal=android.os.CancellationSignal();cancel=signal;recheckSignal=signal
                button.isEnabled=false;message.text="Checking this photo…"
                worker.execute {
                    val heat=FaceHeat(applicationContext)
                    val result=runCatching {
                        FaceWork.write {
                            fun active()=!closed && !signal.isCanceled && !FaceJobs.state.busy && heat.canRun()
                            check(active()){"Try again when recognition is idle and the phone is cool with enough battery"}
                            FaceStore(applicationContext).use{ContactRecognition.recheck(applicationContext,it,entry.getString("contact_tag"),::active,signal)}
                        }
                    }
                    runOnUiThread {
                        recheckSignal=null
                        if(closed || isFinishing || isDestroyed || signal.isCanceled)return@runOnUiThread
                        if(result.getOrNull()==true){dialog.dismiss();load()}
                        else {button.isEnabled=true;message.text=if(result.isFailure)"Could not recheck. Try when recognition is idle, the phone is cool and contacts permission is allowed. Saved results are preserved."else "Recheck paused. Saved results are preserved."}
                    }
                }
            }
        }
        load()
    }
    override fun onDestroy(){closed=true;recheckSignal?.cancel();worker.shutdownNow();super.onDestroy()}
}
