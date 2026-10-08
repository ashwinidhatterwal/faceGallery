package com.mosaic.gallery

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.CancellationSignal
import android.view.*
import android.widget.*
import java.util.concurrent.Executors

object PhotoPeople {
    data class Face(val member:GroupRules.Member,val person:Long?,val name:String,val suggestion:GroupRules.Candidate?,val suggestedName:String?,val contact:ContactNames.Contact?=null,val identity:Long?=person)
    fun read(store:PeopleStore,uri:String,accessible:Set<String>,suggestions:Boolean=true):List<Face> =store.snapshot{
        val all=store.members();val current=all.filter{it.key.uri==uri && it.key.uri in accessible}.sortedBy{it.key.ordinal}
        if(current.isEmpty())return@snapshot emptyList()
        val roots=store.components(all);val labels=store.labels(roots);val contacts=store.contacts(roots)
        val established=store.established(roots)
        val needsSuggestions=suggestions && established.isNotEmpty() && current.any{row->row.person?.let{roots[it]?:it} !in established}
        val groups=if(!needsSuggestions)emptyList()else store.capsules(all,roots).map{group->GroupRules.Capsule(group.id,group.leaves,group.photos,group.anchors,group.prototypes.filter{it.member.key.uri in accessible}.toMutableList())}.filter{it.photos.any{p->p in accessible}}
        val cutoff=store.policy().review;val negatives=store.relations().filter{it.active && it.type=="cannot" && it.source=="user"}
        current.map { row->
            val person=row.person?.takeIf{row.status=="known"}?.let{roots[it]?:it};val source=groups.firstOrNull{it.id==person}
            val vector=if(needsSuggestions && person !in established && row.ready)store.vector(row.key)else null
            val match=groups.filter{candidate->
                person !in established && candidate.id in established && candidate.id!=person && (source==null || (
                    !negatives.any{(it.a in source.leaves && it.b in candidate.leaves)||(it.b in source.leaves && it.a in candidate.leaves)} &&
                    listOfNotNull(contacts[person]?.lookup,contacts[candidate.id]?.lookup).distinct().size<=1))
            }.mapNotNull{candidate->
                val evidence=buildList{vector?.let{GroupRules.rank(it,candidate)?.let(::add)};source?.prototypes?.forEach{GroupRules.rank(it.vector,candidate)?.let(::add)}}
                evidence.maxByOrNull{it.score}
            }.filter{it.score>=cutoff}.maxWithOrNull(compareBy<GroupRules.Candidate>{it.score}.thenBy{if(contacts[it.capsule.id]!=null)1 else 0}.thenBy{it.capsule.photos.size})
            Face(row,person?.takeIf{it in established},person?.takeIf{it in established}?.let{labels[it]}.orEmpty(),match,match?.let{labels[it.capsule.id]?:"Person ${it.capsule.id}"},contacts[person],person)
        }
    }
}
/** Refresh committed identities using the already-decoded photo, without a global regroup. */
class PhotoPeopleSheet(private val activity:Activity,private val names:PeopleNames,private val host:FrameLayout?=null,private val onClosed:()->Unit={}) {
    private data class Cached(val faces:List<PhotoPeople.Face>,val accessible:Set<String>,val faceVersion:Long)
    companion object{private val cache=PeopleCache<Map<String,Cached>>()}
    private var opened=false;private var ownsImage=false;private var prefetchSignal:CancellationSignal?=null
    @Volatile private var prepared=""
    val isShowing get()=opened
    fun prepare(record:PhotoRecord){
        if(closed || opened)return
        val uri=record.uri.toString();if(cache.get(activity)?.get(uri)?.faceVersion==PeopleData.faceVersion(uri))return
        val stamp="$uri:${PeopleData.version}:${PeopleData.faceVersion(uri)}:${GalleryData.version}"
        if(prepared==stamp)return
        prepared=stamp;prefetchSignal?.cancel();val cancel=CancellationSignal();prefetchSignal=cancel
        worker.execute{runCatching{read(record,cancel,false)}.onFailure{if(prepared==stamp)prepared=""}}
    }
    private data class Loaded(val cached:Cached,val revision:Long,val media:Long,val bitmap:Bitmap?)
    private fun read(record:PhotoRecord,cancel:CancellationSignal,decode:Boolean):Loaded {
        cancel.throwIfCanceled();val uri=record.uri.toString()
        var revision=PeopleData.version;var media=GalleryData.version
        var cached=cache.get(activity)?.get(uri)?.takeIf{it.faceVersion==PeopleData.faceVersion(uri)}
        if(cached==null){
            val photos=GalleryData.load(activity,cancel).photos;cancel.throwIfCanceled()
            revision=PeopleData.version;media=GalleryData.version;val faceVersion=PeopleData.faceVersion(uri)
            cached=FaceStore(activity).use{f->
                val valid=photos.map{it.uri.toString()}.toSet()-f.pending(photos).map{it.uri.toString()}.toSet()
                Cached(PhotoPeople.read(PeopleStore(f),uri,valid),valid,faceVersion)
            }
            cancel.throwIfCanceled()
            if(faceVersion==PeopleData.faceVersion(uri) && media==GalleryData.version){
                val next=cache.get(activity).orEmpty().toMutableMap();next[uri]=cached
                while(next.size>12)next.remove(next.keys.first());cache.put(activity,revision,next,media)
            }
        }
        val bitmap=if(decode && cached.faces.isNotEmpty())runCatching{PhotoImages.decode(activity,record.uri,800,1_000_000)}.getOrNull()else null
        return Loaded(cached,revision,media,bitmap)
    }
    private val editGate=PeopleEditGate(activity)
    private val worker=Executors.newSingleThreadExecutor();private var dialog:Dialog?=null
    private val chooser=IdentityChooser(activity,names,{action,message->change(action,message)}){person->dismiss();activity.startActivity(Intent(activity,PeopleActivity::class.java).putExtra("person",person))}
    private var image:Bitmap?=null;private val crops=mutableListOf<FaceCrop>();private var epoch=0;@Volatile private var closed=false
    private var signal:CancellationSignal?=null;private var photo:PhotoRecord?=null;private var accessible=emptySet<String>()
    private var writing=false;private var dataRevision=-1L;private var faceRevision=-1L;private var displayed=emptyList<PhotoPeople.Face>()
    private lateinit var caption:TextView;private lateinit var row:LinearLayout
    private val dataObserver:()->Unit={if(opened && !writing && (dataRevision!=PeopleData.version || faceRevision!=PeopleData.faceVersion(photo?.uri.toString())))load()}
    fun show(record:PhotoRecord,preview:Bitmap?=null) {
        dismiss();opened=true;displayed=emptyList();photo=record
        if(preview!=null && !preview.isRecycled){image=preview;ownsImage=false}
        val root=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(12));background=GradientDrawable().apply{setColor(GalleryStyle.panel(activity));cornerRadius=dp(22).toFloat()}}
        val bar=GalleryStyle.bar(activity).apply{setBackgroundColor(android.graphics.Color.TRANSPARENT)}
        bar.addView(GalleryStyle.text(activity,"People in this photo",20f),LinearLayout.LayoutParams(0,-2,1f));bar.addView(GalleryStyle.action(activity,"close","Close",compact=true){dismiss()});root.addView(bar)
        caption=GalleryStyle.text(activity,"",13f,GalleryStyle.muted(activity));root.addView(caption)
        row=LinearLayout(activity).apply{setPadding(0,dp(12),0,0)}
        root.addView(HorizontalScrollView(activity).apply{isHorizontalScrollBarEnabled=false;addView(row)},LinearLayout.LayoutParams(-1,dp(136)))
        if(host!=null){host.removeAllViews();host.addView(root,FrameLayout.LayoutParams(-1,-1));host.visibility=View.VISIBLE}
        else dialog=Dialog(activity).apply{setContentView(root);setOnDismissListener{if(dialog===this){dialog=null;dismiss()}};show();window?.apply{setBackgroundDrawableResource(android.R.color.transparent);setGravity(Gravity.BOTTOM);setLayout(-1,-2);navigationBarColor=GalleryStyle.panel(activity);attributes=attributes.apply{dimAmount=.3f}}}

        PeopleData.observe(dataObserver);PeopleData.observeFaces(dataObserver);AutoPeople.ensure(activity)
        val cached=cache.get(activity)?.get(record.uri.toString())?.takeIf{it.faceVersion==PeopleData.faceVersion(record.uri.toString())}
        if(cached!=null){accessible=cached.accessible;dataRevision=PeopleData.version;faceRevision=cached.faceVersion;render(cached.faces)}
        if(cached==null || image==null)load()
    }
    private fun load() {
        val record=photo?:return;val token=++epoch;signal?.cancel();val cancel=CancellationSignal();signal=cancel;val decode=image==null
        worker.execute {
            val result=runCatching {read(record,cancel,decode)}
            activity.runOnUiThread {
                if(closed || token!=epoch || cancel.isCanceled || !opened){result.getOrNull()?.bitmap?.recycle();return@runOnUiThread}
                result.onSuccess{loaded->
                    val data=loaded.cached;accessible=data.accessible
                    loaded.bitmap?.let{if(image==null){image=it;ownsImage=true}else it.recycle()}
                    dataRevision=loaded.revision;faceRevision=data.faceVersion;render(data.faces)
                    if(loaded.revision!=PeopleData.version || loaded.media!=GalleryData.version || data.faceVersion!=PeopleData.faceVersion(record.uri.toString()))load()
                }
                    .onFailure{caption.text="Could not load faces. Try again."}
            }
        }
    }
    private fun render(faces:List<PhotoPeople.Face>) {
        displayed=faces
        crops.forEach{it.show(null,null)};crops.clear();row.removeAllViews()
        caption.text=if(faces.isEmpty())if(photo?.uri.toString() in accessible)"No faces found."else""else"Tap a face. Hold to correct."
        faces.forEach{face->
            val cell=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(6),0,dp(6),0)}
            val crop=FaceCrop(activity).apply{GalleryStyle.roundFace(this);show(image,face.member.face)};crops+=crop;cell.addView(crop,LinearLayout.LayoutParams(dp(82),dp(82)))
            val label=GalleryStyle.text(activity,if(face.person!=null)face.name else face.suggestedName?.let{"$it?"}?:face.identity?.let{"Person $it"}?:"Name / contact",14f).apply{gravity=Gravity.CENTER;maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;minimumHeight=dp(40)};cell.addView(label,LinearLayout.LayoutParams(dp(108),dp(40)))
            fun edit(){editGate.run{names.show(face.name,face.contact,preview=image,face=face.member.face){choice->change({it.nameFace(face.member.key,choice,true)},"Saved")}}}
            fun select(){val group=face.identity
                if(group!=null && face.member.status=="known"){dismiss();activity.startActivity(Intent(activity,PeopleActivity::class.java).putExtra("person",group))}else chooser.show(face.member.key)}
            label.setOnClickListener{select()};cell.setOnClickListener{select()}
            crop.contentDescription=if(face.person!=null)"Open ${face.name} photos"else"Identify this face"
            fun options(){AlertDialog.Builder(activity).setItems(arrayOf("Edit name and contact","Choose person","Not this person","Undo automatic folder join")){_,which->when(which){
                0->edit()
                1->chooser.show(face.member.key,true)
                2->change({it.correct(setOf(face.member.key),create=true)},"Face separated")
                3->AlertDialog.Builder(activity).setTitle("Undo automatic folder join?")
                    .setMessage("Separate the most recent automatic folder join containing this face and keep those folders apart. Other joins and saved names remain. You can repeat this if needed.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Separate folders"){_,_->change({it.undoAutomaticJoin(face.member.key)},"Folders separated")}.show()
            }}.show()}
            cell.setOnLongClickListener{options();true};label.setOnLongClickListener{options();true};row.addView(cell)
        }
    }
    private fun change(action:(PeopleStore)->Unit,message:String) {
        if(FaceJobs.state.busy || FaceWork.automatic){editGate.run{change(action,message)};return}
        val token=epoch;val record=photo?:return;val previous=displayed;writing=true
        worker.execute{
            val result=runCatching{FaceWork.write{FaceStore(activity).use{f->val store=PeopleStore(f);action(store)
                val next=PhotoPeople.read(store,record.uri.toString(),accessible,false)
                val roots=store.components();val labels=store.labels(roots);val established=store.established(roots);val contacts=store.contacts(roots)
                next.map{face->
                    val old=previous.firstOrNull{it.member.key==face.member.key}
                    val candidate=old?.suggestion;val target=candidate?.capsule?.id?.let{roots[it]?:it}
                    val sourceLeaves=roots.filterValues{it==face.identity}.keys;val targetLeaves=roots.filterValues{it==target}.keys
                    if(face.person==null && old?.identity==face.identity && old?.member?.status==face.member.status && candidate!=null && target in established &&
                        !store.blocked(sourceLeaves,targetLeaves) && listOfNotNull(contacts[face.identity]?.lookup,contacts[target]?.lookup).distinct().size<=1)
                        face.copy(suggestion=candidate,suggestedName=labels[target])else face
                }}.also{PeopleData.changed();AutoPeople.dirty(activity)}}}
            activity.runOnUiThread{writing=false;AutoPeople.request(activity)
                if(!closed && token==epoch && opened){result.onSuccess{dataRevision=PeopleData.version;faceRevision=PeopleData.faceVersion(record.uri.toString());render(it);Toast.makeText(activity,message,Toast.LENGTH_SHORT).show();load()}.onFailure{Toast.makeText(activity,it.message?:"Could not update face",Toast.LENGTH_LONG).show()}}
            }
        }
    }
    fun updatePreview(bitmap:Bitmap?){
        if(!opened || bitmap==null || bitmap.isRecycled || image===bitmap)return
        clearImages();image=bitmap;ownsImage=false;render(displayed)
    }
    private fun clearImages(){crops.forEach{it.show(null,null)};crops.clear();if(ownsImage)image?.recycle();image=null;ownsImage=false}
    fun dismiss(){
        val wasOpen=opened;opened=false;PeopleData.remove(dataObserver);PeopleData.removeFaces(dataObserver);editGate.cancel();chooser.dismiss()
        val old=dialog;dialog=null;old?.dismiss();epoch++;signal?.cancel();clearImages()
        if(wasOpen)onClosed()
    }
    fun close(){closed=true;prefetchSignal?.cancel();dismiss();chooser.close();worker.shutdownNow()}
    private fun dp(value:Int)=GalleryStyle.dp(activity,value)
}
