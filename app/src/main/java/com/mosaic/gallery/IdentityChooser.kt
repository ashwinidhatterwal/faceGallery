package com.mosaic.gallery

import android.app.*
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.view.Gravity
import android.widget.*
import java.util.concurrent.Executors

/** One shared picker for unnamed profiles and photo faces. Selecting a name confirms its folder. */
class IdentityChooser(private val activity:Activity,private val names:PeopleNames,
    private val apply:((PeopleStore)->Unit,String)->Unit,private val open:(Long)->Unit) {
    private val editGate=PeopleEditGate(activity)
    private val worker=Executors.newSingleThreadExecutor()
    private var dialog:AlertDialog?=null;private var extra:AlertDialog?=null
    private var epoch=0;@Volatile private var closed=false
    private var signal:CancellationSignal?=null
    private val images=mutableListOf<Bitmap>();private val crops=mutableListOf<FaceCrop?>()
    fun show(key:GroupRules.Key,allowChange:Boolean=false){
        dismiss();val token=epoch;val cancel=CancellationSignal();signal=cancel
        dialog=GalleryStyle.dialog(activity,GalleryStyle.panelRoot(activity).apply{
            addView(GalleryStyle.text(activity,"Who is this?",22f));addView(GalleryStyle.text(activity,"Choose a person or add a name.",14f,GalleryStyle.muted(activity)))
        }).apply{
            setOnDismissListener{if(dialog===this){dialog=null;epoch++;signal?.cancel();extra?.dismiss();clearImages()}};show()
        }
        worker.execute{
            val decoded=mutableListOf<Bitmap>()
            val result=runCatching{
                val photos=GalleryData.load(activity,cancel).photos;cancel.throwIfCanceled()
                val data=FaceStore(activity).use{faces->
                    val stale=faces.pending(photos).map{it.uri.toString()}.toSet()
                    IdentitySuggestions.read(PeopleStore(faces),key,photos.map{it.uri.toString()}.filter{it !in stale}.toSet(),allowChange)
                }?:error("Face is unavailable")
                Triple(data,null as Bitmap?,List<Bitmap?>(data.suggestions.size){null})
            }
            activity.runOnUiThread{
                if(closed || token!=epoch || cancel.isCanceled || dialog?.isShowing!=true){decoded.forEach{it.recycle()};return@runOnUiThread}
                result.onSuccess{(data,source,references)->
                    if(data.person!=null){dismiss();decoded.forEach{it.recycle()};open(data.person);return@onSuccess}
                    val loading=dialog;dialog=null;loading?.dismiss();images+=decoded
                    render(key,data,source,references,allowChange)
                    val shown=dialog
                    if(shown?.isShowing==true){
                        val targets=listOf(0 to (key.uri to data.member.face))+data.suggestions.mapIndexedNotNull{index,choice->choice.reference?.let{(index+1) to (it.member.key.uri to it.member.face)}}
                        worker.execute{
                            for((index,target) in targets){
                                if(closed || token!=epoch || cancel.isCanceled)break
                                val bitmap=runCatching{PhotoImages.decode(activity,Uri.parse(target.first),480,250_000)}.getOrNull()?:continue
                                activity.runOnUiThread{if(closed || token!=epoch || dialog!==shown || !shown.isShowing)bitmap.recycle()else{images+=bitmap;crops.getOrNull(index)?.show(bitmap,target.second)}}
                            }
                        }
                    }
                }.onFailure{decoded.forEach{it.recycle()};dismiss();Toast.makeText(activity,"Could not load this face",Toast.LENGTH_SHORT).show()}
            }
        }
    }
    private fun render(key:GroupRules.Key,data:IdentitySuggestions.Result,source:Bitmap?,references:List<Bitmap?>,changing:Boolean){
        val root=GalleryStyle.panelRoot(activity)
        val heading=GalleryStyle.bar(activity).apply{setBackgroundColor(android.graphics.Color.TRANSPARENT)}
        heading.addView(GalleryStyle.text(activity,"Who is this?",22f),LinearLayout.LayoutParams(0,-2,1f));heading.addView(GalleryStyle.action(activity,"close","Close",compact=true){dismiss()});root.addView(heading)
        root.addView(GalleryStyle.text(activity,if(changing)"Choose the correct person for this face."else"Tap a matching person, or add a name.",14f,GalleryStyle.muted(activity)),LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(16)})
        fun crop(bitmap:Bitmap?,face:FaceObservation)=FaceCrop(activity).apply{GalleryStyle.roundFace(this);show(bitmap,face);crops+=this}
        root.addView(crop(source,data.member.face),LinearLayout.LayoutParams(dp(80),dp(80)).apply{gravity=Gravity.CENTER_HORIZONTAL})
        if(data.suggestions.isNotEmpty())root.addView(GalleryStyle.text(activity,"Suggested names · tap to confirm",13f,GalleryStyle.muted(activity)))
        data.suggestions.forEachIndexed{index,choice->
            val line=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(12),dp(12),dp(12));background=android.graphics.drawable.GradientDrawable().apply{setColor(GalleryStyle.surface(activity));cornerRadius=dp(14).toFloat()};contentDescription="Assign to ${choice.name}";setOnClickListener{select(key,choice,changing)}}
            choice.reference?.let{line.addView(crop(references[index],it.member.face),LinearLayout.LayoutParams(dp(52),dp(52)))}
            if(choice.reference==null)crops+=null
            if(choice.contact!=null)line.addView(ImageView(activity).apply{setImageDrawable(GalleryStyle.icon(activity,"personAdd"));contentDescription="Phone contact"},LinearLayout.LayoutParams(dp(32),dp(32)))
            line.addView(GalleryStyle.text(activity,choice.name+if(choice.hintContact!=null)"?"else "",17f).apply{setPadding(dp(12),0,0,0)},LinearLayout.LayoutParams(0,-2,1f));line.addView(ImageView(activity).apply{setImageDrawable(GalleryStyle.icon(activity,"back"));rotation=180f},LinearLayout.LayoutParams(dp(20),dp(20)));choice.hintContact?.let{contact->line.addView(GalleryStyle.action(activity,"close","Not ${choice.name}",compact=true){dismiss();apply({it.rejectContactHint(key,contact.lookup)},"Suggestion removed")})};root.addView(line,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        }
        val actions=LinearLayout(activity)
        actions.addView(GalleryStyle.button(activity,"Name / contact",true){
            names.show("",preview=images.firstOrNull(),face=data.member.face,photoKey=key,singleFace=changing){choice->apply({if(changing)it.correct(setOf(key),create=true);it.nameFace(key,choice,true)},"Saved")};dismiss()
        },LinearLayout.LayoutParams(0,dp(48),1f))
        if(data.choices.isNotEmpty())actions.addView(GalleryStyle.button(activity,"Choose person"){chooseOther(key,data.choices,changing)},LinearLayout.LayoutParams(0,dp(48),1f).apply{leftMargin=dp(10)})
        root.addView(actions,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(16)})
        dialog=GalleryStyle.dialog(activity,root).apply{setOnDismissListener{if(dialog===this){dialog=null;clearImages()}}}
    }

    private fun chooseOther(key:GroupRules.Key,choices:List<IdentitySuggestions.Choice>,changing:Boolean){
        extra=AlertDialog.Builder(activity).setTitle("Choose person").setItems(choices.map{it.name}.toTypedArray()){_,index->select(key,choices[index],changing)}.setNegativeButton("Later",null).show()
    }
    private fun select(key:GroupRules.Key,choice:IdentitySuggestions.Choice,changing:Boolean){
        if(FaceJobs.state.busy || FaceWork.automatic){editGate.run{select(key,choice,changing)};return}
        choice.contact?.let{contact->dismiss();apply({if(changing)it.correct(setOf(key),create=true);it.nameFace(key,ContactNames.Choice(contact.name,contact))},"Saved");return}
        if(changing){dismiss();apply({it.correct(setOf(key),target=choice.id)},"Face corrected");return}
        if(choice.sharedPhotos.isEmpty()){dismiss();apply({it.confirmIdentity(key,choice.id)},"Merged");return}
        // The user explicitly confirms repetition; automatic matching still cannot bypass occupancy.
        extra?.dismiss()
        val token=epoch
        val loading=AlertDialog.Builder(activity).setTitle("Same person?").setMessage("").setNegativeButton("Cancel",null).show();extra=loading
        worker.execute{
            val uri=choice.sharedPhotos.sorted().first()
            val result=runCatching{
                val pair=FaceStore(activity).use{f->val store=PeopleStore(f);val rows=store.members();val roots=store.components(rows)
                    val source=rows.first{it.key==key}.person?.let{roots[it]?:it};val target=roots[choice.id]?:choice.id
                    val inPhoto=rows.filter{it.key.uri==uri}
                    val a=if(source==null)rows.first{it.key==key}else inPhoto.first{it.person?.let{p->roots[p]?:p}==source}
                    val b=inPhoto.first{it.person?.let{p->roots[p]?:p}==target};a.face to b.face
                }
                pair to PhotoImages.decode(activity,Uri.parse(uri),800,1_000_000)
            }
            activity.runOnUiThread{
                if(closed || token!=epoch || extra!==loading || !loading.isShowing){result.getOrNull()?.second?.recycle();return@runOnUiThread}
                extra=null;loading.dismiss()
                result.onSuccess{(pair,bitmap)->
                    images+=bitmap
                    val preview=LinearLayout(activity).apply{gravity=Gravity.CENTER;setPadding(dp(16),dp(12),dp(16),0)}
                    val previews=listOf(pair.first,pair.second).map{face->FaceCrop(activity).apply{GalleryStyle.roundFace(this);show(bitmap,face);crops+=this}}
                    previews.forEach{preview.addView(it,LinearLayout.LayoutParams(dp(88),dp(88)).apply{setMargins(dp(8),0,dp(8),0)})}
                    extra=AlertDialog.Builder(activity).setTitle("Both faces are ${choice.name}?").setView(preview)
                        .setMessage("${choice.sharedPhotos.size} shared photos · Merge both folders?")
                        .setNegativeButton("Cancel",null).setPositiveButton("Merge folders"){_,_->dismiss();apply({it.confirmIdentity(key,choice.id,true)},"Merged")}.create().apply{
                            setOnDismissListener{previews.forEach{it.show(null,null)};crops.removeAll(previews.toSet());images.remove(bitmap);if(!bitmap.isRecycled)bitmap.recycle();if(extra===this)extra=null};show()
                        }
                }.onFailure{Toast.makeText(activity,"Could not load shared faces. Try again.",Toast.LENGTH_SHORT).show()}
            }
        }
    }
    private fun clearImages(){crops.forEach{it?.show(null,null)};crops.clear();images.forEach{if(!it.isRecycled)it.recycle()};images.clear()}
    fun dismiss(){editGate.cancel();epoch++;signal?.cancel();extra?.dismiss();extra=null;dialog?.dismiss();dialog=null;clearImages()}
    fun close(){closed=true;dismiss();worker.shutdownNow()}
    private fun dp(value:Int)=GalleryStyle.dp(activity,value)
}
