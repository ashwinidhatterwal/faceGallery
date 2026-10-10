package com.mosaic.gallery

import android.app.*
import android.content.Intent
import android.database.ContentObserver
import android.os.*
import android.provider.MediaStore
import android.text.*
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.*
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** A small search surface sharing the gallery's grid, cache, viewer and date sections. */
class PeopleSearchActivity:Activity() {
    companion object{private val cache=PeopleCache<Pair<List<PhotoRecord>,PeopleSearch.Index>>() }
    private val dataObserver:()->Unit={if(active)load()}
    private val worker=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    @Volatile private var active=false;@Volatile private var epoch=0;private var signal:CancellationSignal?=null;private var filtering:Future<*>?=null
    private var photos=emptyList<PhotoRecord>();private var index=PeopleSearch.Index(emptyMap(),emptyMap(),emptyMap())
    private var shownQuery=PeopleSearch.Query();private var thumbnailsDirty=false;private var ready=false;private var person:Long?=null;private var from:LocalDate?=null;private var through:LocalDate?=null
    private lateinit var text:EditText;private lateinit var caption:TextView;private lateinit var filters:LinearLayout
    private lateinit var grid:RecyclerView;private lateinit var adapter:PhotoGridAdapter
    private var selected=mutableSetOf<String>();private var selecting=false;private var shown=emptyList<PhotoRecord>();private lateinit var selectionBar:LinearLayout;private lateinit var deletion:PhotoDeletion;private var tagRevision=-1L
    private var returnUri:String?=null;private var observed=false;private var loadEpoch=0
    private val searchTask=Runnable{filter()};private val reload=Runnable{load()}
    private val observer=object:ContentObserver(handler){override fun onChange(selfChange:Boolean){handler.removeCallbacks(reload);if(active)handler.postDelayed(reload,350)}}
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        deletion=PhotoDeletion(this){if(it>0){GalleryData.invalidate();selected.clear();selecting=false;load()}};deletion.restore(state)
        selecting=state?.getBoolean("selecting")?:false;selected.addAll(state?.getStringArrayList("selected").orEmpty())
        person=(state?.getLong("person",-1)?:intent.getLongExtra("person",-1)).takeIf{it>=0}
        from=state?.getString("from")?.let(LocalDate::parse);through=state?.getString("through")?.let(LocalDate::parse)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(GalleryStyle.canvas(context))}
        val bar=GalleryStyle.bar(this);bar.addView(GalleryStyle.action(this,"back","Back",compact=true){finish()})
        bar.addView(GalleryStyle.text(this,"Search photos",26f),LinearLayout.LayoutParams(0,-2,1f))
        bar.addView(GalleryStyle.action(this,"personAdd","People",compact=true){startActivity(Intent(this,PeopleActivity::class.java).putExtra("search",true))})
        bar.addView(GalleryStyle.action(this,"close","Clear filters",compact=true){person=null;from=null;through=null;text.setText("");updateFilters();schedule()});root.addView(bar)
        text=EditText(this).apply{hint="People, tags, dates or places";setSingleLine();setTextColor(GalleryStyle.textColor(context));setHintTextColor(GalleryStyle.muted(context));setPadding(dp(20),0,dp(20),0);setText(state?.getString("query")?:intent.getStringExtra("searchText").orEmpty())}
        text.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun afterTextChanged(s:Editable?){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){schedule()}})
        root.addView(text,LinearLayout.LayoutParams(-1,dp(48)))
        filters=GalleryStyle.bar(this);root.addView(filters);updateFilters()
        caption=GalleryStyle.text(this,"",13f,GalleryStyle.muted(this)).apply{setPadding(dp(20),dp(8),dp(20),dp(10))};root.addView(caption)
        adapter=PhotoGridAdapter(this,{if(selecting)toggle(it)else open(it)},{selecting=true;toggle(it)})
        grid=RecyclerView(this).apply{itemAnimator=null;adapter=this@PeopleSearchActivity.adapter;layoutManager=GridLayoutManager(this@PeopleSearchActivity,4).apply{spanSizeLookup=object:GridLayoutManager.SpanSizeLookup(){override fun getSpanSize(position:Int)=if(this@PeopleSearchActivity.adapter.isHeader(position))4 else 1}}}
        root.addView(grid,LinearLayout.LayoutParams(-1,0,1f))
        selectionBar=GalleryStyle.bar(this)
        GalleryStyle.add(selectionBar,GalleryStyle.action(this,"share","Share"){MediaSharing.share(this,shown.filter{it.uri.toString() in selected})})
        GalleryStyle.add(selectionBar,GalleryStyle.action(this,"delete","Delete"){deletion.delete(shown.filter{it.uri.toString() in selected}.map{it.uri})})
        val more=GalleryStyle.action(this,"more","Selection options"){}
        more.setOnClickListener{anchor->GalleryMenu.show(this,"Selection",listOf(
            GalleryMenu.Action("tag","Add tags"){TagEditor.show(this,selected.toSet()){tagRevision=-1;load()}},
            GalleryMenu.Action("select","Select all"){selected.addAll(shown.map{it.uri.toString()});selection()},
            GalleryMenu.Action("close","Exit selection"){selecting=false;selected.clear();selection()}),anchor)}
        GalleryStyle.add(selectionBar,more);root.addView(selectionBar);selection()
        Ui.insets(this,root);setContentView(root);Ui.back(this){if(selecting){selecting=false;selected.clear();selection()}else finish()}
        setEnterSharedElementCallback(object:SharedElementCallback(){override fun onMapSharedElements(names:MutableList<String>,elements:MutableMap<String,View>){val uri=returnUri?:return;elements.clear();adapter.thumbnailView(uri)?.let{it.transitionName="mosaic-photo";elements["mosaic-photo"]=it}?:names.clear()}})
    }
    private fun toggle(photo:PhotoRecord){val key=photo.uri.toString();if(!selected.add(key))selected.remove(key);selection()}
    private fun selection(){adapter.selectionMode=selecting;adapter.setSelection(selected.toSet());selectionBar.visibility=if(selecting)View.VISIBLE else View.GONE}
    private fun query()=PeopleSearch.Query(text.text.toString(),person,from,through)
    private fun updateFilters() {
        filters.removeAllViews()
        fun button(label:String,click:()->Unit)=Button(this).apply{text=label;isAllCaps=false;maxLines=1;textSize=12f;minWidth=0;minimumWidth=0;setPadding(dp(6),0,dp(6),0);setOnClickListener{click()}}
        filters.addView(button(person?.let{index.labels[index.roots[it]?:it]}?:if(person!=null)"Selected person"else"Any person",::choosePerson),LinearLayout.LayoutParams(0,dp(48),1f))
        filters.addView(button(from?.toString()?:"From date"){chooseDate(true)},LinearLayout.LayoutParams(0,dp(48),1f))
        filters.addView(button(through?.toString()?:"To date"){chooseDate(false)},LinearLayout.LayoutParams(0,dp(48),1f))
    }
    private fun choosePerson() {
        if(!ready)return
        val matches=index.labels.entries.sortedBy{it.value.lowercase(java.util.Locale.ROOT)}
        AlertDialog.Builder(this).setTitle("Choose person").setItems((listOf("Any person")+matches.map{it.value}).toTypedArray()){_,which->person=if(which==0)null else matches[which-1].key;updateFilters();schedule()}.setNegativeButton("Cancel",null).show()
    }
    private fun chooseDate(start:Boolean) {
        val date=(if(start)from else through)?:LocalDate.now()
        val picker=DatePickerDialog(this,{_,year,month,day->
            val next=LocalDate.of(year,month+1,day)
            if(start && through?.let{next>it}==true || !start && from?.let{next<it}==true)Toast.makeText(this,"Start date must be before the end date",Toast.LENGTH_SHORT).show()
            else{if(start)from=next else through=next;updateFilters();schedule()}
        },date.year,date.monthValue-1,date.dayOfMonth)
        picker.setButton(AlertDialog.BUTTON_NEUTRAL,"Clear"){_,_->if(start)from=null else through=null;updateFilters();schedule()};picker.show()
    }
    override fun onResume(){super.onResume();active=true;GalleryData.resumed(this);PeopleData.observe(dataObserver);deletion.resume();load()}
    private fun load() {
        if(!active)return
        cache.get(this)?.takeIf{tagRevision==MediaTags.version}?.let{(p,i)->photos=p;index=i;ready=true;updateFilters();filter();return}
        if(!ready)caption.text=""
        cache.preview(this)?.let{(p,i)->photos=p;index=i;ready=true;updateFilters()
            val q=query();if(q==PeopleSearch.Query()){shownQuery=q;adapter.submitList(p);caption.text=if(p.isEmpty())""else"${p.size} photos"}else filter()
        }
        signal?.cancel();filtering?.cancel(false);handler.removeCallbacks(searchTask);val cancel=CancellationSignal();signal=cancel;val token=++loadEpoch;epoch++
        worker.execute {
            val result=runCatching{val photos=GalleryData.load(this,cancel).photos;cancel.throwIfCanceled();val revision=PeopleData.version;var complete=true;val index=runCatching{PeopleSearch.cachedRead(this,photos)}.getOrElse{complete=false;PeopleReadDiagnostics.record(this,it);val tags=MediaTags.read(this);PeopleSearch.Index(emptyMap(),emptyMap(),emptyMap(),tags.media.mapValues{(uri,_)->tags.labels(uri)},tags.places)};cancel.throwIfCanceled();(photos to index).also{if(complete)cache.put(this,revision,it)}}
            runOnUiThread{if(active && token==loadEpoch && !cancel.isCanceled){result.onSuccess{(p,i)->thumbnailsDirty=thumbnailsDirty || photos!=p;photos=p;index=i;ready=true;tagRevision=MediaTags.version;updateFilters();filter()}.onFailure{if(!MediaAccess.allowed(this)){photos=emptyList();shown=emptyList();selected.clear();adapter.submitList(emptyList());selection();caption.text="Allow media access to search your library."}}}}
        }
    }
    private fun schedule(){handler.removeCallbacks(searchTask);if(active && ready)handler.postDelayed(searchTask,150)}
    private fun filter() {
        if(!active || !ready)return
        filtering?.cancel(false);val token=++epoch;val q=query();val p=photos;val i=index
        filtering=worker.submit {val next=PeopleSearch.filter(p,i,q,keepGoing={active && token==epoch});runOnUiThread{if(active && token==epoch){shownQuery=q;shown=next;selected.retainAll(next.map{it.uri.toString()}.toSet());selection();adapter.submitList(next,invalidateThumbnails=thumbnailsDirty);thumbnailsDirty=false;caption.text=if(next.isEmpty())"No matching accessible photos. Try another person or date."else "${next.size} photos"}}}
    }
    private fun open(photo:PhotoRecord) {
        returnUri=null
        val q=shownQuery;val intent=Intent(this,PhotoActivity::class.java).setData(photo.uri).putExtra("name",photo.displayName)
            .putExtra("groupPlaylist",GroupPhotoPlaylist.remember(this,shown)).putExtra("mimeType",photo.mimeType).putExtra("peopleSearch",true).putExtra("searchText",q.text).putExtra("searchPerson",q.person?:-1L).putExtra("searchFrom",q.from?.toString()).putExtra("searchThrough",q.through?.toString())
        val tile=if(photo.isVideo)null else adapter.thumbnailView(photo.uri.toString())
        if(tile!=null){tile.transitionName="mosaic-photo";intent.putExtra("transition",true);startActivity(intent,ActivityOptions.makeSceneTransitionAnimation(this,tile,"mosaic-photo").toBundle())}else startActivity(intent)
    }
    override fun onActivityReenter(resultCode:Int,data:Intent?) {
        super.onActivityReenter(resultCode,data);returnUri=data?.data?.toString();val uri=returnUri?:return;val position=adapter.positionOf(uri);if(position<0)return
        postponeEnterTransition();if(adapter.thumbnailView(uri)==null)grid.scrollToPosition(position)
        grid.viewTreeObserver.addOnPreDrawListener(object:ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{grid.viewTreeObserver.removeOnPreDrawListener(this);startPostponedEnterTransition();return true}})
    }
    override fun onPause(){PeopleData.remove(dataObserver);active=false;epoch++;loadEpoch++;signal?.cancel();filtering?.cancel(false);handler.removeCallbacks(searchTask);handler.removeCallbacks(reload);if(observed){contentResolver.unregisterContentObserver(observer);observed=false};super.onPause()}
    override fun onSaveInstanceState(state:Bundle){state.putBoolean("selecting",selecting);state.putStringArrayList("selected",ArrayList(selected));deletion.save(state);state.putLong("person",person?:-1L);state.putString("query",text.text.toString());state.putString("from",from?.toString());state.putString("through",through?.toString());super.onSaveInstanceState(state)}
    @Deprecated("Legacy activity results")
    override fun onActivityResult(code:Int,result:Int,data:Intent?){super.onActivityResult(code,result,data);deletion.onActivityResult(code,result)}
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(code,permissions,results);deletion.onPermissionsResult(code,results)}
    override fun onDestroy(){deletion.close();worker.shutdownNow();adapter.close();super.onDestroy()}
    private fun dp(value:Int)=GalleryStyle.dp(this,value)
}
