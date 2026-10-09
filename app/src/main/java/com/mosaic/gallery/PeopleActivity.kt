package com.mosaic.gallery

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.CancellationSignal
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Initial review surface. All photo opening/details continue through the existing gallery viewer. */
class PeopleActivity:Activity(){
    private val worker=Executors.newSingleThreadExecutor()
    @Volatile private var active=false;private var epoch=0;private var loaded=false;private var refreshing=false
    private var query:CancellationSignal?=null
    private var glide:GlideSelection?=null
    private lateinit var selectionBar:LinearLayout
    private val selected=mutableSetOf<GroupRules.Key>();private var assignKeys=emptySet<GroupRules.Key>();private var canUndo=false;private var recognition=""
    private lateinit var caption:TextView;private lateinit var cards:Cards
    private var groupId:Long?=null
    private var review=false;private var duplicates=false;private var mergeInto:Long?=null;private var refreshAgain=false
    private lateinit var namesEditor:PeopleNames
    private lateinit var identityChooser:IdentityChooser
    private var established=emptySet<Long>()
    private val editGate=PeopleEditGate(this)
    private lateinit var grid:RecyclerView;private lateinit var fullPhotos:PhotoGridAdapter
    private var folder=false;private var searchMode=false;private var profileQuery="";private var profiles=emptyList<Card>()
    private var returnUri:String?=null
    private var recognitionReport=""
    private var folderPhotos=emptyList<PhotoRecord>()
    private var titleView:TextView?=null
    private var contactLinks=emptyMap<Long,ContactNames.Contact>()
    private var graph=emptyMap<Long,Long>();private var rows=emptyList<GroupRules.Member>();private var relations=emptyList<PeopleStore.Relation>()
    private var labels=emptyMap<Long,String>();private var lastBusy=false
    private val observer:(FaceJobs.State)->Unit={state->if(active){
        controls(state);if(state.busy){caption.text=state.message.ifBlank{"Processing…"};lastBusy=true}else if(lastBusy){lastBusy=false;refresh()}
    }}
    override fun onCreate(state:Bundle?){
        super.onCreate(state);groupId=intent.getLongExtra("person",-1).takeIf{it>=0};review=intent.getBooleanExtra("review",false);duplicates=intent.getBooleanExtra("duplicates",false);mergeInto=intent.getLongExtra("mergeInto",-1).takeIf{it>=0}
        val uris=intent.getStringArrayExtra("faceUris").orEmpty();val ordinals=intent.getIntArrayExtra("faceOrdinals")?:intArrayOf();if(uris.size==ordinals.size)assignKeys=uris.indices.map{GroupRules.Key(uris[it],ordinals[it])}.toSet()
        namesEditor=PeopleNames(this);identityChooser=IdentityChooser(this,namesEditor,{change,message->mutate(change,message)}){id->openPerson(id)};searchMode=intent.getBooleanExtra("search",false);profileQuery=state?.getString("profileQuery").orEmpty();folder=groupId!=null && !intent.getBooleanExtra("reviewFaces",false)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(GalleryStyle.canvas(context))}
        val bar=GalleryStyle.bar(this);bar.addView(GalleryStyle.action(this,"back","Back",compact=true){if(selected.isNotEmpty())clearSelection()else finish()})
        titleView=GalleryStyle.text(this,if(review)"Needs review"else"People",28f);bar.addView(titleView,LinearLayout.LayoutParams(0,-2,1f))
        val more=GalleryStyle.action(this,"more","People options",compact=true){};more.setOnClickListener{viewMenu(it)};bar.addView(more);root.addView(bar)
        if(searchMode){root.addView(EditText(this).apply{hint="Search people";setSingleLine();setTextColor(GalleryStyle.textColor(context));setHintTextColor(GalleryStyle.muted(context));setPadding(dp(20),0,dp(20),0);setText(profileQuery);addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun afterTextChanged(s:android.text.Editable?){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){profileQuery=s.toString();if(loaded)showProfiles()}})},LinearLayout.LayoutParams(-1,dp(48)))}
        caption=GalleryStyle.text(this,"",14f,GalleryStyle.muted(this)).apply{setPadding(dp(20),dp(8),dp(20),dp(12))};root.addView(caption)
        cards=Cards();fullPhotos=PhotoGridAdapter(this,::openPhoto,{PhotoDetails.show(this,it)})
        grid=RecyclerView(this).apply{layoutManager=GridLayoutManager(this@PeopleActivity,if(folder)4 else if(duplicates)2 else 3).apply{if(folder)spanSizeLookup=object:GridLayoutManager.SpanSizeLookup(){override fun getSpanSize(position:Int)=if(fullPhotos.isHeader(position))4 else 1}};adapter=if(folder)fullPhotos else cards;itemAnimator=null
            if(!folder){glide=GlideSelection(this,{selected.isNotEmpty() && !FaceJobs.state.busy},{cards.photoKey(it)},{cards.selectedKeys()}){next->selected.clear();selected.addAll(cards.keys(next));if(selected.isEmpty())clearSelection()else{cards.markSelection();controls()}};addOnItemTouchListener(glide!!)}}
        root.addView(grid,LinearLayout.LayoutParams(-1,0,1f))
        selectionBar=GalleryStyle.bar(this).apply{visibility=View.GONE}
        listOf(Triple("album","Assign",{chooseAssign()}),Triple("personAdd","New person",{confirmCorrection("create")}),Triple("close","Not this person",{confirmCorrection("exclude")}),Triple("back","Cancel",{clearSelection()})).forEach{(icon,label,click)->selectionBar.addView(GalleryStyle.action(this,icon,label,action=click),LinearLayout.LayoutParams(0,-2,1f))}
        root.addView(selectionBar)
        if(searchMode){val navigation=GalleryStyle.bar(this);listOf("Photos" to "photo","Albums" to "album","Search" to "search").forEach{(label,icon)->GalleryStyle.add(navigation,GalleryStyle.action(this,icon,label,selected=label=="Search"){if(label!="Search")startActivity(Intent(this,MainActivity::class.java).putExtra("browsePage",label).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))})};root.addView(navigation)}
        setEnterSharedElementCallback(object:SharedElementCallback(){override fun onMapSharedElements(names:MutableList<String>,elements:MutableMap<String,View>){val uri=returnUri?:return;elements.clear();fullPhotos.thumbnailView(uri)?.let{it.transitionName="mosaic-photo";elements["mosaic-photo"]=it}?:names.clear()}})
        Ui.insets(this,root);setContentView(root);if(intent.getBooleanExtra("recognitionOptions",false))root.post{advanced()};Ui.back(this){if(selected.isNotEmpty())clearSelection()else finish()}
    }
    override fun onResume(){super.onResume();active=true;FaceJobs.observe(observer);GalleryData.resumed(this);PeopleData.observe(dataObserver);AutoPeople.ensure(this);refresh()}
    private fun controls(state:FaceJobs.State=FaceJobs.state){selectionBar.visibility=if(selected.isNotEmpty() && !state.busy)View.VISIBLE else View.GONE;if(selected.isNotEmpty())titleView?.text="${selected.size} selected"}
    companion object{
        private val cache=PeopleCache<ViewData>();private val duplicateCache=PeopleCache<ViewData>()
        private val warming=java.util.concurrent.atomic.AtomicBoolean();private val warmWorker=Executors.newSingleThreadExecutor()
        fun warm(context:android.content.Context,photos:List<PhotoRecord>){
            val app=context.applicationContext
            if(GalleryData.peek(app)?.photos!=photos)return
            if(cache.get(app)!=null || !AutoPeople.allowed(app) || warming.get())return
            if(!warming.compareAndSet(false,true))return
            val revision=PeopleData.version;val media=GalleryData.version
            warmWorker.execute{try{
                val signal=CancellationSignal();val data=readData(app,photos,signal,false){AutoPeople.allowed(app) && media==GalleryData.version}
                if(media==GalleryData.version)cache.put(app,revision,data,media)
                val covers=data.rows.filter{it.status=="known" && it.person!=null}.groupBy{data.graph[it.person]?:it.person!!}
                    .filterKeys{it !in data.provisional}.values.sortedByDescending{it.map{row->row.key.uri}.distinct().size}.take(9).map{it.maxBy{row->row.face.score}}
                for(cover in covers){
                    if(media!=GalleryData.version || revision!=PeopleData.version || !AutoPeople.allowed(app))break
                    val photo=data.photos[cover.key.uri]?:continue
                    runCatching{FaceThumbnails.load(app,photo,cover.face)}
                }
            }catch(_:Exception){}finally{warming.set(false)}}
        }
        private fun readData(context:android.content.Context,photos:List<PhotoRecord>,signal:CancellationSignal,duplicates:Boolean,keepGoing:()->Boolean):ViewData {
            val photoMap=photos.associateBy{it.uri.toString()}
            return FaceStore(context).use{faceStore->val store=PeopleStore(faceStore);store.syncContacts(context,signal)
                faceStore.snapshot{
                    signal.throwIfCanceled();val valid=photos.map{it.uri.toString()}.toSet()-faceStore.pending(photos).map{it.uri.toString()}.toSet()
                    val members=store.members().filter{it.key.uri in valid};val roots=store.components(members);val labels=store.labels(roots);val capsules=store.capsules(members,roots);val contacts=store.contacts(roots)
                    val provisional=IdentityEvidence.provisional(capsules,members,store.names().keys+contacts.keys)
                    ViewData(members,roots,labels,store.relations(),photoMap,if(duplicates)DuplicateReview.find(capsules,store.relations(),policy=store.policy(),keepGoing=keepGoing)else emptyList(),store.canUndo(),store.policy(),faceStore.summary(),faceStore.signatureSummary(),contacts,store.established(roots),provisional,"")
                }
            }
        }
    }
    private val dataObserver:()->Unit={if(active)refresh()}
    private fun refresh(){
        if(!active)return
        val saved=(if(duplicates)duplicateCache else cache).get(this)
        if(saved!=null){renderData(saved);return}
        if(!loaded)(if(duplicates)duplicateCache else cache).preview(this)?.let{renderData(it)}
        if(refreshing){refreshAgain=true;return};refreshing=true;controls();val token=++epoch;val signal=CancellationSignal();query=signal
        worker.execute{
            val result=runCatching{
                val photos=GalleryData.load(this,signal).photos;signal.throwIfCanceled()
                val revision=PeopleData.version;val media=GalleryData.version
                readData(this,photos,signal,duplicates,{active && !signal.isCanceled}).also{(if(duplicates)duplicateCache else cache).put(this,revision,it,media)}
            }
            runOnUiThread{if(active && token==epoch && !isDestroyed){refreshing=false;result.onSuccess{renderData(it)}.onFailure{caption.text="Could not read accessible face results. Check photo permission."};if(refreshAgain){refreshAgain=false;refresh()}}}
        }
    }
    private fun renderData(data:ViewData){
                rows=data.rows;graph=data.graph;labels=data.labels;contactLinks=data.contacts;established=data.established;relations=data.relations;canUndo=data.canUndo;loaded=true;selected.retainAll(rows.map{it.key}.toSet())
                recognition="${data.faces.done} of ${data.photos.size} photos scanned · ${data.faces.faces} faces\n${data.signatures.ready} signatures ready · ${rows.count{it.status=="known"}} assigned faces\n${data.policy.positives} confirmed-match samples · ${data.policy.negatives} different-person samples\nAutomatic grouping uses quality, independent references and competing-person margins. Explicit feedback adjusts bounded thresholds. Separations and conflicts are preserved."
                val allGroups=rows.filter{it.status=="known" && it.person!=null}.groupBy{graph[it.person]?:it.person!!}
                recognition+="\n${data.provisional.size} pending profiles · agreement gate ${data.policy.agreement}"
                val groups=if(review || groupId!=null || duplicates)allGroups else if(assignKeys.isNotEmpty() || mergeInto!=null)allGroups.filterKeys{it in established} else allGroups.filterKeys{it !in data.provisional}
                if(groupId!=null)groupId=graph[groupId]?:groupId
                selected.retainAll(rows.filter{if(review)it.status!="known"else groupId!=null && graph[it.person]==groupId}.map{it.key}.toSet())
                val nextItems=when{
                    duplicates->data.suggestions.mapNotNull{pair->val a=groups[pair.a]?.maxByOrNull{it.face.score};val b=groups[pair.b]?.maxByOrNull{it.face.score};if(a==null || b==null)null else Card("pair:${pair.a}:${pair.b}",a,data.photos[a.key.uri],"${labels[pair.a]} + ${labels[pair.b]}",pair.reason,pair.a,b,data.photos[b.key.uri],pair.b)}
                    review->rows.filter{it.status!="known" || (graph[it.person]?:it.person) in data.provisional}.map{member->Card("${member.key.uri}:${member.key.ordinal}",member,data.photos[member.key.uri],if(member.status=="tentative")"Tentative"else"Unknown",member.reason,null)}
                    groupId!=null->groups[groupId].orEmpty().map{member->Card("${member.key.uri}:${member.key.ordinal}",member,data.photos[member.key.uri],GalleryDates.label(member.time),if(member.face.authority=="Anchor")"Clear"else"Usable",null)}
                    else->groups.entries.filter{it.key!=(graph[mergeInto]?:mergeInto)}.sortedByDescending{it.value.map{m->m.key.uri}.distinct().size}.map{(id,members)->val cover=members.maxBy{it.face.score};Card("person:$id",cover,data.photos[cover.key.uri],labels[id]?.takeIf{id in established}?:"Person $id","${members.map{it.key.uri}.distinct().size} photos"+(if(contactLinks[id]!=null)" · Linked contact"else""),id)}
                }
                val oldOrder=profiles.mapIndexed{index,card->card.key to index}.toMap()
                val items=if(!review && groupId==null && !duplicates && oldOrder.isNotEmpty())nextItems.sortedBy{oldOrder[it.key]?:Int.MAX_VALUE}else nextItems
                profiles=items
                if(folder){val uris=groups[groupId].orEmpty().map{it.key.uri}.toSet();val next=data.photos.values.filter{it.uri.toString() in uris};fullPhotos.submitList(next,invalidateThumbnails=next!=folderPhotos);folderPhotos=next}else if(searchMode)showProfiles()else cards.submit(items)
                titleView?.text=if(duplicates)"Possible duplicates"else if(assignKeys.isNotEmpty())"Assign selected faces"else if(mergeInto!=null)"Choose same person"else if(review)"Needs review"else groupId?.let{labels[it]}?:"People"
                caption.text=if(FaceJobs.state.busy)FaceJobs.state.message else if(duplicates)if(items.isEmpty())"No duplicate suggestions. You can still merge from a person’s options."else "Tap either face to inspect its group. Tap the labels to confirm Same person or Different people."else if(assignKeys.isNotEmpty())if(intent.getBooleanExtra("wholeGroup",false))"Choose the person to combine this face’s whole folder with."else "Choose the person for ${assignKeys.size} selected faces. Clear faces can improve their references; weak faces stay out of automatic references."else if(mergeInto!=null)"Choose a group containing the same person. Your confirmation joins their references; the join can be undone."else if(items.isEmpty())"No ${if(review)"unassigned faces"else"groups"} yet. Photos are recognised automatically in the background."else if(groupId!=null)if(folder)"${groups[groupId].orEmpty().map{it.key.uri}.distinct().size} photos"else"${items.size} faces · Long-press to select faces for correction."else if(review)"${items.size} faces · Tap to identify."else "${groups.size} people · Tap an unnamed face to identify."
                if(searchMode)caption.text=if(items.isEmpty() && data.provisional.isNotEmpty())"Learning people from more photos · ${data.provisional.size} pending profiles"else if(items.isEmpty())"People will appear as photos are recognised."else"Tap a profile for their photos. ${data.provisional.size} profiles awaiting more evidence."
                controls()

    }
    private data class ViewData(val rows:List<GroupRules.Member>,val graph:Map<Long,Long>,val labels:Map<Long,String>,val relations:List<PeopleStore.Relation>,val photos:Map<String,PhotoRecord>,val suggestions:List<DuplicateReview.Suggestion>,val canUndo:Boolean,val policy:PeopleCalibration.Policy,val faces:FaceStore.Summary,val signatures:FaceStore.Signatures,val contacts:Map<Long,ContactNames.Contact>,val established:Set<Long>,val provisional:Set<Long>,val report:String)
    private data class Card(val key:String,val member:GroupRules.Member,val photo:PhotoRecord?,val title:String,val subtitle:String,val person:Long?,val second:GroupRules.Member?=null,val secondPhoto:PhotoRecord?=null,val other:Long?=null)
    private fun viewMenu(anchor:View){
        val actions=mutableListOf<GalleryMenu.Action>()
        if(folder){
            actions+=GalleryMenu.Action("personAdd","Name or contact"){renamePerson(groupId!!)}
            actions+=GalleryMenu.Action("personAdd","Combine with another person"){chooseMerge(groupId!!)}
            actions+=GalleryMenu.Action("select","Correct faces"){startActivity(Intent(this,PeopleActivity::class.java).putExtra("person",groupId!!).putExtra("reviewFaces",true))}
            contactLinks[groupId]?.let{contact->actions+=GalleryMenu.Action("info","View contact"){ContactNames.open(this,contact)}}
        }else{
            if(!review)actions+=GalleryMenu.Action("personAdd","Identify people"){startActivity(Intent(this,PeopleActivity::class.java).putExtra("review",true))}
            if(!duplicates)actions+=GalleryMenu.Action("select","Review similar people"){startActivity(Intent(this,PeopleActivity::class.java).putExtra("duplicates",true))}
        }
        if(canUndo)actions+=GalleryMenu.Action("undo","Undo last correction"){mutate{it.undoCorrection()}}
        actions+=GalleryMenu.Action("settings","Settings"){settings()}
        GalleryMenu.show(this,"People options",actions,anchor)
    }
    private fun settings(){startActivity(Intent(this,SettingsActivity::class.java))}
    private fun advanced(){
        GalleryMenu.show(this,"Recognition options",listOf(
            GalleryMenu.Action("info","Recognition status"){GalleryStyle.dialog(this,GalleryStyle.panelRoot(this).apply{addView(GalleryStyle.text(this@PeopleActivity,"Recognition status",22f));addView(GalleryStyle.text(this@PeopleActivity,if(FaceJobs.state.busy)FaceJobs.state.message+"\n"+recognition else recognition,15f))})},
            GalleryMenu.Action("adjust","Recognition tools"){startActivity(Intent(this,FaceScanActivity::class.java))},
            GalleryMenu.Action("share","Export recognition report"){exportReport()},
            GalleryMenu.Action("personAdd","Review combined people"){showJoins()},
            GalleryMenu.Action("delete","Reset people"){AlertDialog.Builder(this).setTitle("Reset people?").setMessage("Remove saved names and face corrections?").setNegativeButton("Cancel",null).setPositiveButton("Reset"){_,_->mutate{it.reset()}}.show()}
        ))
    }
    private fun exportReport(){
        worker.execute{
            val result=runCatching{
                val photos=GalleryData.load(this,CancellationSignal()).photos
                FaceStore(this).use{f->f.snapshot{
                    val store=PeopleStore(f)
                    val valid=photos.map{it.uri.toString()}.toSet()-f.pending(photos).map{it.uri.toString()}.toSet()
                    val rows=store.members().filter{it.key.uri in valid};val roots=store.components(rows);val groups=store.capsules(rows,roots)
                    RecognitionMetrics.report(store,rows,roots,groups,IdentityEvidence.provisional(groups,rows,store.established(roots)))
                }}
            }
            runOnUiThread{if(active)result.onSuccess{
                recognitionReport=it
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"mosaic-recognition.json"),4102)
            }.onFailure{Toast.makeText(this,"Could not prepare report",Toast.LENGTH_SHORT).show()}}
        }
    }
    private fun showJoins(){
        if(FaceJobs.state.busy)return
        val edges=relations.filter{it.active && it.type!="cannot" && graph[it.a]==groupId && graph[it.b]==groupId}
        if(edges.isEmpty()){AlertDialog.Builder(this).setMessage("This group has no active joins.").setPositiveButton("Close",null).show();return}
        AlertDialog.Builder(this).setTitle("Reversible joins").setItems(edges.map{"${it.a} ↔ ${it.b} · ${it.reason}"}.toTypedArray()){_,index->val edge=edges[index];AlertDialog.Builder(this).setTitle("Keep these groups separate?").setMessage("Undo this join and prevent automatic rejoining. Other valid joins remain.").setNegativeButton("Cancel",null).setPositiveButton("Separate"){_,_->mutate{it.unlink(edge.id)}}.show()}.show()
    }
    private fun mutate(change:(PeopleStore)->Unit)=mutate(change,"Saved")
    private fun mutate(change:(PeopleStore)->Unit,message:String){
        if(FaceJobs.state.busy || FaceWork.automatic){editGate.run{mutate(change,message)};return};loaded=false;controls()
        worker.execute{val result=runCatching{FaceWork.write{FaceStore(this).use{change(PeopleStore(it))};PeopleData.changed();AutoPeople.dirty(this)}}
            runOnUiThread{if(active){result.onSuccess{Toast.makeText(this,message,Toast.LENGTH_SHORT).show();selected.clear();if(assignKeys.isNotEmpty())finish()else refresh();AutoPeople.request(this)}.onFailure{loaded=true;controls();AlertDialog.Builder(this).setTitle("Could not apply correction").setMessage(it.message?:"Please try again.").setPositiveButton("Close",null).show()}}}}
    }
    private fun chooseMerge(id:Long){if(!FaceJobs.state.busy)startActivity(Intent(this,PeopleActivity::class.java).putExtra("mergeInto",id))}
    private fun confirmPair(a:Long,b:Long,canReject:Boolean=false){
        if(FaceJobs.state.busy)return
        val dialog=AlertDialog.Builder(this).setTitle("${labels[a]?:"Person $a"} + ${labels[b]?:"Person $b"}")
            .setMessage("Merge both folders?")
            .setNegativeButton("Cancel",null).setPositiveButton("Same person"){_,_->mutate{it.merge(a,b,true)}}
        if(canReject)dialog.setNeutralButton("Different people"){_,_->mutate{it.reject(a,b)}}
        dialog.show()
    }
    private fun toggle(key:GroupRules.Key){
        if(FaceJobs.state.busy)return;if(!selected.add(key))selected.remove(key);cards.markSelection();controls()
        if(selected.isEmpty())titleView?.text=if(review)"Needs review"else groupId?.let{labels[it]}?:"People"
    }
    private fun clearSelection(){selected.clear();cards.markSelection();controls();titleView?.text=if(review)"Needs review"else groupId?.let{labels[it]}?:"People"}
    private fun chooseAssign(){if(selected.isEmpty() || FaceJobs.state.busy)return;val keys=selected.toList();startActivity(Intent(this,PeopleActivity::class.java).putExtra("faceUris",keys.map{it.uri}.toTypedArray()).putExtra("faceOrdinals",keys.map{it.ordinal}.toIntArray()))}
    private fun confirmCorrection(mode:String,target:Long?=null){
        if(FaceJobs.state.busy)return;val keys=if(assignKeys.isNotEmpty())assignKeys else selected.toSet();if(keys.isEmpty())return
        val title=when(mode){"create"->"Create a separate person?";"exclude"->"These faces are not this person?";else->"Assign to ${labels[target]?:"this person"}?"}
        AlertDialog.Builder(this).setTitle(title).setMessage(if(intent.getBooleanExtra("wholeGroup",false) && mode=="assign")"Merge this folder?"else"${keys.size} faces").setNegativeButton("Cancel",null).setPositiveButton("Apply"){_,_->mutate{if(mode=="assign" && target!=null && keys.size==1 && intent.getBooleanExtra("wholeGroup",false))it.confirmIdentity(keys.single(),target)else it.correct(keys,target,mode=="create",mode=="exclude")}}.show()
    }
    private fun showProfiles(){cards.submit(profiles.filter{profileQuery.isBlank() || it.title.contains(profileQuery.trim(),true)})}
    private fun renamePerson(id:Long){editGate.run{namesEditor.show(if(id in established)labels[id].orEmpty()else "",contactLinks[id]){choice->mutate{it.updatePerson(id,choice,true)}}}}
    private fun openPerson(id:Long){startActivity(Intent(this,PeopleActivity::class.java).putExtra("person",id))}
    private fun identify(card:Card){if(card.person in established)openPerson(card.person!!)else identityChooser.show(card.member.key)}
    private fun openPhoto(photo:PhotoRecord){
        returnUri=null;val intent=Intent(this,PhotoActivity::class.java).setData(photo.uri).putExtra("name",photo.displayName).putExtra("peopleSearch",true).putExtra("searchPerson",groupId?:-1L)
        val tile=fullPhotos.thumbnailView(photo.uri.toString());if(tile!=null){tile.transitionName="mosaic-photo";intent.putExtra("transition",true);startActivity(intent,ActivityOptions.makeSceneTransitionAnimation(this,tile,"mosaic-photo").toBundle())}else startActivity(intent)
    }
    override fun onActivityReenter(resultCode:Int,data:Intent?){super.onActivityReenter(resultCode,data);returnUri=data?.data?.toString();val position=returnUri?.let{fullPhotos.positionOf(it)}?:-1;if(position<0)return;postponeEnterTransition();if(fullPhotos.thumbnailView(returnUri!!)==null)grid.scrollToPosition(position);grid.viewTreeObserver.addOnPreDrawListener(object:ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{grid.viewTreeObserver.removeOnPreDrawListener(this);startPostponedEnterTransition();return true}})}
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==4102 && resultCode==RESULT_OK){val uri=data?.data?:return;val report=recognitionReport
            worker.execute{val result=runCatching{contentResolver.openOutputStream(uri,"wt")?.use{it.write(report.toByteArray(Charsets.UTF_8))}?:error("Could not open report")};runOnUiThread{if(!isDestroyed)Toast.makeText(this,if(result.isSuccess)"Recognition report saved"else"Could not save report",Toast.LENGTH_SHORT).show()}}
        }
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);namesEditor.permissionResult(requestCode)}
    override fun onSaveInstanceState(state:Bundle){state.putString("profileQuery",profileQuery);super.onSaveInstanceState(state)}
    override fun onPause(){editGate.cancel();if(!namesEditor.requestingPermission){identityChooser.dismiss();namesEditor.dismiss()};glide?.finish();active=false;epoch++;refreshing=false;refreshAgain=false;FaceJobs.remove(observer);PeopleData.remove(dataObserver);query?.cancel();super.onPause()}
    override fun onDestroy(){glide?.detach();worker.shutdown();cards.close();fullPhotos.close();identityChooser.close();namesEditor.close();super.onDestroy()}
    private fun dp(value:Int)=GalleryStyle.dp(this,value)
    private inner class Cards:RecyclerView.Adapter<Cards.Holder>(){
        private val images=Executors.newSingleThreadExecutor();private var items=emptyList<Card>();private var closed=false;private val holders=mutableSetOf<Holder>()
        inner class Holder(val root:LinearLayout,val image:FaceCrop,val second:FaceCrop,val title:TextView,val subtitle:TextView):RecyclerView.ViewHolder(root){var bitmap:Bitmap?=null;var bitmap2:Bitmap?=null;var job:Future<*>?=null;var token=0;var sourceKey=""
            fun clear(){sourceKey="";token++;job?.cancel(true);image.show(null,null);bitmap=null;second.show(null,null);bitmap2=null}
        }
        init{setHasStableIds(true)}
        override fun getItemId(position:Int):Long=items[position].let{c->when{c.person!=null && c.other==null->c.person;else->c.key.fold(1125899906842597L){a,b->31*a+b.code}}}
        fun submit(next:List<Card>){
            if(items==next)return
            val old=items;val diff=androidx.recyclerview.widget.DiffUtil.calculateDiff(object:androidx.recyclerview.widget.DiffUtil.Callback(){
                override fun getOldListSize()=old.size;override fun getNewListSize()=next.size
                override fun areItemsTheSame(a:Int,b:Int)=old[a].key==next[b].key
                override fun areContentsTheSame(a:Int,b:Int)=old[a]==next[b]
            });items=next;diff.dispatchUpdatesTo(this)
        }
        fun photoKey(position:Int)=items.getOrNull(position)?.takeIf{it.person==null}?.key
        fun selectedKeys()=items.filter{it.member.key in selected}.map{it.key}.toSet()
        fun keys(strings:Set<String>)=items.filter{it.key in strings}.map{it.member.key}.toSet()
        fun markSelection(){holders.forEach{holder->val position=holder.bindingAdapterPosition;if(position in items.indices)mark(holder,items[position])}}
        private fun mark(holder:Holder,card:Card){val checked=card.person==null && card.member.key in selected;holder.root.setBackgroundColor(if(checked)GalleryStyle.panel(this@PeopleActivity)else android.graphics.Color.TRANSPARENT);holder.title.text=(if(checked)"✓ "else "")+card.title;holder.root.isSelected=checked}
        override fun getItemCount()=items.size
        override fun onCreateViewHolder(parent:ViewGroup,type:Int):Holder{
            val root=LinearLayout(this@PeopleActivity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(6),dp(8),dp(6),dp(8));layoutParams=RecyclerView.LayoutParams(-1,-2)}
            val pair=LinearLayout(this@PeopleActivity)
            fun crop()=FaceCrop(this@PeopleActivity).apply{background=GradientDrawable().apply{setColor(GalleryStyle.panel(context));shape=GradientDrawable.OVAL};clipToOutline=true}
            val image=crop();val second=crop();pair.addView(image,LinearLayout.LayoutParams(0,-2,1f));pair.addView(second,LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(3)});root.addView(pair)
            val titleView=GalleryStyle.text(this@PeopleActivity,"",15f);val subtitleView=GalleryStyle.text(this@PeopleActivity,"",12f,GalleryStyle.muted(this@PeopleActivity)).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END};root.addView(titleView);root.addView(subtitleView)
            return Holder(root,image,second,titleView,subtitleView).also{holders+=it}
        }
        override fun onBindViewHolder(holder:Holder,position:Int){
            val card=items[position];val imageKey="${card.photo?.let{FaceStore.fingerprint(it)}}:${card.member.key}:${card.member.face}:${card.secondPhoto?.let{FaceStore.fingerprint(it)}}:${card.second?.key}:${card.second?.face}";val decode=holder.sourceKey!=imageKey;if(decode){holder.clear();holder.sourceKey=imageKey};holder.second.visibility=if(card.second==null)View.GONE else View.VISIBLE;holder.title.text=card.title;holder.subtitle.text=card.subtitle;holder.title.gravity=Gravity.CENTER;holder.subtitle.gravity=Gravity.CENTER;
            holder.title.setOnClickListener{holder.root.performClick()}
            holder.title.setOnLongClickListener{if(card.person!=null && card.other==null)renamePerson(card.person);true};holder.root.contentDescription="${card.title}, ${card.subtitle}";mark(holder,card)
            holder.root.setOnClickListener{if(card.person==null && selected.isNotEmpty())toggle(card.member.key)else if(assignKeys.isNotEmpty() && card.person!=null)confirmCorrection("assign",card.person)else if(card.other!=null && card.person!=null)confirmPair(card.person,card.other,true)else if(mergeInto!=null && card.person!=null)confirmPair(mergeInto!!,card.person)else identify(card)}
            holder.image.setOnClickListener(if(card.other!=null)View.OnClickListener{startActivity(Intent(this@PeopleActivity,PeopleActivity::class.java).putExtra("person",card.person!!))}else null);holder.image.isClickable=card.other!=null
            holder.second.setOnClickListener{card.other?.let{startActivity(Intent(this@PeopleActivity,PeopleActivity::class.java).putExtra("person",it))}}
            holder.root.setOnLongClickListener{if(card.person==null){toggle(card.member.key);true}else if(card.other==null){renamePerson(card.person);true}else false};if(!decode)return;val token=holder.token;val photo=card.photo?:return
            holder.bitmap=FaceThumbnails.get(this@PeopleActivity,photo,card.member.face)
            holder.bitmap2=card.secondPhoto?.let{second->card.second?.let{FaceThumbnails.get(this@PeopleActivity,second,it.face)}}
            holder.image.show(holder.bitmap,FaceThumbnails.full(card.member.face));holder.second.show(holder.bitmap2,card.second?.face?.let(FaceThumbnails::full))
            if(holder.bitmap!=null && (card.second==null || holder.bitmap2!=null))return
            holder.job=images.submit{
                val bitmap=runCatching{FaceThumbnails.load(this@PeopleActivity,photo,card.member.face)}.getOrNull()
                val bitmap2=card.secondPhoto?.let{second->card.second?.let{row->runCatching{FaceThumbnails.load(this@PeopleActivity,second,row.face)}.getOrNull()}}
                holder.image.post{if(!closed && token==holder.token && AutoPeople.allowed(this@PeopleActivity)){holder.bitmap=bitmap;holder.bitmap2=bitmap2;holder.image.show(bitmap,FaceThumbnails.full(card.member.face));holder.second.show(bitmap2,card.second?.face?.let(FaceThumbnails::full))}}
            }
        }
        override fun onViewRecycled(holder:Holder){holder.clear()}
        fun close(){closed=true;holders.forEach{it.clear()};holders.clear();images.shutdownNow()}
    }
}
