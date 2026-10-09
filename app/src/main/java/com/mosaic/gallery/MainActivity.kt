package com.mosaic.gallery

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.View
import android.widget.*
import java.util.concurrent.Executors
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager

class MainActivity : Activity() {
    private enum class Access { NONE, PARTIAL, FULL }
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var adapter: PhotoGridAdapter
    private lateinit var status: TextView
    private lateinit var message: TextView
    private lateinit var action: Button
    private lateinit var select: View
    private lateinit var heading: TextView
    private lateinit var headerActions: LinearLayout
    private lateinit var navigation: LinearLayout
    private var page = "Photos"
    private var photoColumns=4
    private lateinit var selectionBar: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var grid: RecyclerView
    private lateinit var gridHost:FrameLayout
    private var parentGrid:RecyclerView?=null
    private var parentAdapter:PhotoGridAdapter?=null
    private var folderAnimation:android.animation.Animator?=null
    private var glide:GlideSelection?=null
    private lateinit var deletion: PhotoDeletion
    private var allPhotos = emptyList<PhotoRecord>()
    private var visible = emptyList<PhotoRecord>()
    private val selected = mutableSetOf<String>()
    private var selecting = false
    private var album = ""
    private var folderOpen=false
    private var albumLayout:Parcelable?=null
    private var folderOrigin=android.graphics.Rect()
    private var unreadableVolumes = 0
    private var signal: CancellationSignal? = null
    private var revision = 0
    private var scrollPosition = 0
    private var checkingAutomatic=false
    private var active = false
    private var observed = false
    private var needsRefresh = true
    private var loadedAccess:Access? = null
    private var loadedScope=""
    private var lastFavorites = emptySet<String>()
    private var permissionInFlight=false
    private lateinit var galleryRoot:View
    private var returnUri:String?=null
    private val refresh = Runnable { refreshGallery() }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { needsRefresh=true; handler.removeCallbacks(refresh); if(active)handler.postDelayed(refresh, 350) }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        photoColumns=(state?.getInt("photoColumns")?:getSharedPreferences("gallery-layout",0).getInt("photoColumns",4)).coerceIn(2,8)
        permissionInFlight=state?.getBoolean("permissionInFlight")?:false
        scrollPosition = state?.getInt("scrollPosition") ?: 0
        album = state?.getString("album").orEmpty()
        folderOpen=state?.getBoolean("folderOpen")?:false
        @Suppress("DEPRECATION")
        albumLayout=state?.getParcelable("albumLayout")
        page = state?.getString("page")?.takeIf{it=="Photos" || it=="Albums"} ?: "Photos"
        selecting = state?.getBoolean("selecting") ?: false
        selected.addAll(state?.getStringArrayList("selected") ?: emptyList())
        deletion = PhotoDeletion(this) { count ->
            if (count > 0) { selecting = false; selected.clear() }
            updateSelection(); refreshGallery()
        }
        deletion.restore(state)
        adapter=createAdapter()
        galleryRoot=buildUi();galleryRoot.visibility=if(currentAccess()==Access.NONE)View.INVISIBLE else View.VISIBLE
        setContentView(galleryRoot)
        setEnterSharedElementCallback(object:android.app.SharedElementCallback(){
            override fun onMapSharedElements(names:MutableList<String>,sharedElements:MutableMap<String,View>){
                val uri=returnUri?:return;val target=adapter.thumbnailView(uri)
                sharedElements.clear();if(target!=null){target.transitionName="mosaic-photo";sharedElements["mosaic-photo"]=target}else names.clear()
            }
        })
        Ui.back(this, ::handleBack)
    }
    private fun createAdapter()=PhotoGridAdapter(this, { photo ->
            if(openingAlbum)return@PhotoGridAdapter
            if (selecting) toggle(photo) else {
                returnUri=null
                val intent=Intent(this,PhotoActivity::class.java).setData(photo.uri)
                    .putExtra("name",photo.displayName).putExtra("album",album).putExtra("mimeType",photo.mimeType)
                val thumbnail=if(photo.isVideo)null else adapter.thumbnailView(photo.uri.toString())
                if(thumbnail!=null){thumbnail.transitionName="mosaic-photo";intent.putExtra("transition",true)
                    startActivity(intent,android.app.ActivityOptions.makeSceneTransitionAnimation(this,thumbnail,"mosaic-photo").toBundle())
                }else startActivity(intent)
            }
        }, { photo -> selecting = true; toggle(photo) }, { chosen,cover -> openAlbum(chosen,cover) })
    override fun onActivityReenter(resultCode:Int,data:Intent?){
        super.onActivityReenter(resultCode,data);returnUri=data?.data?.toString()
        val uri=returnUri?:return;val position=adapter.positionOf(uri);if(position<0)return
        postponeEnterTransition()
        if(adapter.thumbnailView(uri)==null)grid.scrollToPosition(position)
        grid.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener{
            override fun onPreDraw():Boolean{grid.viewTreeObserver.removeOnPreDrawListener(this);startPostponedEnterTransition();return true}
        })
    }
    override fun onResume() {
        super.onResume();if(isFinishing)return;active=true;GalleryData.resumed(this)
        if(requestStartupAccess())return
        galleryRoot.visibility=View.VISIBLE
        AutoPeople.ensure(this,0)
        // A first installation has no index to inspect. Establish the service before a long media query.
        if(!getDatabasePath("faces.db").exists() && FaceHeat(this).canRun())launchAutomaticRecognition()
        if(needsRefresh || loadedAccess!=currentAccess() || loadedScope!=MediaAccess.scope(this) || GalleryData.peek(this)==null)refreshGallery()
        else {
            val favorites=GalleryStyle.favorites(this)
            if(favorites!=lastFavorites && (page=="Albums" || album=="@favorites")){
                val state=grid.layoutManager?.onSaveInstanceState();render();grid.layoutManager?.onRestoreInstanceState(state)
            }
            lastFavorites=favorites
        }
        startAutomaticRecognition()
        deletion.resume()
    }
    override fun onPause() {
        if (adapter.itemCount > 0) scrollPosition = (grid.layoutManager as? GridLayoutManager)?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
        (grid as? PinchPhotoGrid)?.cancelResize()
        active = false; revision++; signal?.cancel(); handler.removeCallbacks(refresh)
        super.onPause()
    }
    // Keep the bounded thumbnail cache alive through the shared-element transition.
    // Explicit refresh and onDestroy release it.
    override fun onSaveInstanceState(state: Bundle) {
        state.putInt("photoColumns",photoColumns)
        state.putBoolean("permissionInFlight",permissionInFlight)
        state.putBoolean("folderOpen",folderOpen);state.putParcelable("albumLayout",albumLayout)
        state.putInt("scrollPosition", if (adapter.itemCount > 0) (grid.layoutManager as? GridLayoutManager)?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0 else scrollPosition)
        state.putString("page", page); state.putString("album", album); state.putBoolean("selecting", selecting)
        state.putStringArrayList("selected", ArrayList(selected)); deletion.save(state); super.onSaveInstanceState(state)
    }
    override fun onDestroy() { if(observed)contentResolver.unregisterContentObserver(observer); folderAnimation?.cancel();glide?.detach();parentAdapter?.close();deletion.close(); adapter.close(); io.shutdownNow(); super.onDestroy() }
    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(GalleryStyle.canvas(this@MainActivity)) }
        val header = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(24),dp(22),dp(12),dp(14)) }
        heading = GalleryStyle.text(this, "Photos", 36f).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
        header.addView(heading, LinearLayout.LayoutParams(0,-2,1f))
        headerActions=LinearLayout(this);header.addView(headerActions);root.addView(header)
        status=GalleryStyle.text(this,"",12f,GalleryStyle.muted(this@MainActivity)).apply{setPadding(dp(24),0,dp(24),dp(6))};root.addView(status)
        message=GalleryStyle.text(this,"",15f,GalleryStyle.muted(this@MainActivity)).apply{setPadding(dp(24),dp(12),dp(24),dp(12))};root.addView(message)
        action=Button(this);root.addView(action)
        progress=ProgressBar(this).apply{visibility=View.GONE};root.addView(progress,LinearLayout.LayoutParams(-1,dp(40)))
        grid=newGrid(adapter);gridHost=FrameLayout(this);gridHost.addView(grid,FrameLayout.LayoutParams(-1,-1))
        root.addView(gridHost,LinearLayout.LayoutParams(-1,0,1f));attachGlide()
        val divider=View(this).apply{setBackgroundColor(GalleryStyle.dividerColor(this@MainActivity))};root.addView(divider,LinearLayout.LayoutParams(-1,dp(1)))
        selectionBar=GalleryStyle.bar(this)
        GalleryStyle.add(selectionBar,GalleryStyle.action(this,"share","Share"){shareSelected()})
        GalleryStyle.add(selectionBar,GalleryStyle.action(this,"delete","Delete"){deletion.delete(allPhotos.filter{it.uri.toString() in selected}.map{it.uri})})
        val selectionMore=GalleryStyle.action(this,"more","Selection options"){}
        selectionMore.setOnClickListener{anchor->
            val actions=mutableListOf<GalleryMenu.Action>()
            if(selected.size==1)actions+=GalleryMenu.Action("info","Details"){allPhotos.firstOrNull{it.uri.toString() in selected}?.let{PhotoDetails.show(this,it)}}
            actions+=GalleryMenu.Action("select","Select all"){selected.addAll(visible.map{it.uri.toString()});updateSelection()}
            actions+=GalleryMenu.Action("close","Clear selection"){selected.clear();updateSelection()}
            GalleryMenu.show(this,"Selection",actions,anchor)
        };GalleryStyle.add(selectionBar,selectionMore);root.addView(selectionBar)
        navigation=GalleryStyle.bar(this);root.addView(navigation)
        Ui.insets(this,root);updateSelection();return root
    }
    private fun newGrid(data:PhotoGridAdapter)=PinchPhotoGrid(this,{page=="Photos" && !folderOpen && !selecting && !openingAlbum}){columns->
        photoColumns=columns;getSharedPreferences("gallery-layout",0).edit().putInt("photoColumns",columns).apply()
    }.apply{adapter=data;setBackgroundColor(GalleryStyle.canvas(this@MainActivity));clipToPadding=false;itemAnimator=null}
    private fun attachGlide(){
        glide?.detach()
        glide=GlideSelection(grid,{selecting},{adapter.photoKey(it)},{selected.toSet()}){keys->
            selected.clear();selected.addAll(keys);heading.text="${selected.size} selected";adapter.setSelection(keys)
        }.also{grid.addOnItemTouchListener(it)}
    }
    private var openingAlbum=false
    private fun openAlbum(chosen:String,cover:View){
        if(openingAlbum)return
        openingAlbum=true;glide?.detach();albumLayout=grid.layoutManager?.onSaveInstanceState()
        val image=(cover as? android.view.ViewGroup)?.getChildAt(0)?:cover
        folderOrigin=FolderTransition.bounds(gridHost,image)
        parentGrid=grid;parentAdapter=adapter
        adapter=createAdapter();grid=newGrid(adapter);gridHost.addView(grid,FrameLayout.LayoutParams(-1,-1));attachGlide()
        album=chosen;folderOpen=true;page="Albums";scrollPosition=0;render()
        folderAnimation=FolderTransition.play(gridHost,grid,folderOrigin,true){openingAlbum=false}
    }
    private fun closeAlbum(){
        if(openingAlbum)return
        openingAlbum=true;glide?.detach()
        val closingGrid=grid;val closingAdapter=adapter;val chosen=album
        if(parentGrid!=null){grid=parentGrid!!;adapter=parentAdapter!!}
        else{adapter=createAdapter();grid=newGrid(adapter);gridHost.addView(grid,0,FrameLayout.LayoutParams(-1,-1))}
        parentGrid=null;parentAdapter=null;folderOpen=false;album=""
        if(grid.layoutManager==null){render();grid.layoutManager?.onRestoreInstanceState(albumLayout)}else{adapter.submitAlbums(allPhotos);updateSelection()}
        attachGlide()
        val target=adapter.albumView(chosen)?.let{FolderTransition.bounds(gridHost,it)}?:folderOrigin
        folderAnimation=FolderTransition.play(gridHost,closingGrid,target,false){gridHost.removeView(closingGrid);closingAdapter.close();openingAlbum=false}
    }
    private fun switchPage(value:String){
        if(openingAlbum)return
        if(value=="Search"){PeopleActivity.warm(this,allPhotos);startActivity(Intent(this,PeopleActivity::class.java).putExtra("search",true));return}
        if(parentGrid!=null){gridHost.removeView(parentGrid);parentAdapter?.close();parentGrid=null;parentAdapter=null}
        grid.animate().cancel();grid.alpha=1f;grid.scaleX=1f;grid.scaleY=1f;openingAlbum=false
        page=value;album="";folderOpen=false;selecting=false;selected.clear();scrollPosition=0;render()
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra("requestMedia",false)){intent.removeExtra("requestMedia");requestPhotoAccess();return};intent.getStringExtra("browsePage")?.takeIf{it=="Photos" || it=="Albums"}?.let{switchPage(it)}}
    private fun render(){
        (grid as? PinchPhotoGrid)?.cancelResize()
        val columns=if(page=="Albums" && !folderOpen)3 else if(page=="Photos")photoColumns else 4
        val data=adapter
        val layout=GridLayoutManager(this,columns)
        layout.spanSizeLookup=object:GridLayoutManager.SpanSizeLookup(){override fun getSpanSize(position:Int)=if(data.isHeader(position))layout.spanCount else 1}
        grid.layoutManager=layout
        if(page=="Albums" && !folderOpen)adapter.submitAlbums(allPhotos)else showAlbum()
        updateSelection()
    }
    private fun overflow(anchor:View){
        GalleryMenu.show(this,if(folderOpen)album.ifBlank{"Library"}else page,listOf(
            GalleryMenu.Action("personAdd","People"){startActivity(Intent(this,PeopleActivity::class.java))},
            GalleryMenu.Action("redo","Refresh library"){adapter.retryThumbnails();refreshGallery()},
            GalleryMenu.Action("settings","Settings"){startActivity(Intent(this,SettingsActivity::class.java))}
        ),anchor)
    }
    private fun toggle(photo: PhotoRecord) {
        val key = photo.uri.toString(); if (!selected.add(key)) selected.remove(key); updateSelection()
    }
    private fun updateSelection() {
        heading.text=if(selecting)"${selected.size} selected"else if(album=="@favorites")"Favorites"else if(album=="@other")"Other"else if(folderOpen)album.ifBlank{"All"}else page
        heading.textSize=if(selecting)24f else 36f
        headerActions.removeAllViews()
        if(selecting){
            headerActions.addView(GalleryStyle.action(this,"select","Select all",compact=true){selected.addAll(visible.map{it.uri.toString()});updateSelection()})
            headerActions.addView(GalleryStyle.action(this,"close","Exit selection",compact=true){selecting=false;selected.clear();updateSelection()})
        }else{
            if(folderOpen)headerActions.addView(GalleryStyle.action(this,"back","Back to albums",compact=true){closeAlbum()})
            headerActions.addView(GalleryStyle.action(this,"search","Search",compact=true){switchPage("Search")})
            select=GalleryStyle.action(this,"select","Select photos",compact=true){if(page=="Albums" && !folderOpen){page="Photos";render()};selecting=true;updateSelection()}
            headerActions.addView(select)
            val more=GalleryStyle.action(this,"more","More",compact=true){}
            more.setOnClickListener{overflow(it)};headerActions.addView(more)
        }
        selectionBar.visibility=if(selecting)View.VISIBLE else View.GONE
        navigation.visibility=if(selecting)View.GONE else View.VISIBLE
        navigation.removeAllViews()
        listOf("Photos" to "photo","Albums" to "album","Search" to "search").forEach{(label,icon)->
            GalleryStyle.add(navigation,GalleryStyle.action(this,icon,label,page==label){switchPage(label)})
        }
        adapter.selectionMode=selecting;adapter.setSelection(selected.toSet());updateCount()
    }
    private fun updateCount(){
        status.text=if(unreadableVolumes>0)"Some storage is unavailable"else ""
        status.visibility=if(!selecting && unreadableVolumes>0)View.VISIBLE else View.GONE
    }
    private fun showAlbum(clearCache:Boolean=false){
        val favorites=GalleryStyle.favorites(this)
        visible=allPhotos.filter{album.isEmpty()||if(album=="@favorites")it.uri.toString() in favorites else if(album=="@other")it.album.isBlank() else it.album==album}
        adapter.submitList(visible,clearCache);adapter.setSelection(selected.toSet())
        if(visible.isNotEmpty())grid.scrollToPosition(scrollPosition.coerceIn(0,adapter.itemCount-1))
        message.visibility=if(visible.isEmpty()&&page!="Albums")View.VISIBLE else View.GONE
        message.text="No accessible photos or videos"
        updateCount()
    }
    private fun startAutomaticRecognition(){
        if(checkingAutomatic || !active || allPhotos.isEmpty() || !AutoPeople.canStartVisible(this))return
        checkingAutomatic=true
        val photos=allPhotos;val access=currentAccess()
        io.execute{
            val pending=runCatching{FaceHeat(this).canRun() && AutoPeople.hasPending(this,photos)}.getOrDefault(false)
            handler.post{
                checkingAutomatic=false
                // The query may finish after the user leaves. Only a visible activity may launch this service.
                if(pending && currentAccess()==access)launchAutomaticRecognition()
            }
        }
    }
    private fun launchAutomaticRecognition(){
        if(!active || permissionInFlight || isDestroyed || isFinishing || !AutoPeople.canStartVisible(this))return
        AutoPeople.ensure(this,0) // Durable recovery remains scheduled if the process is killed.
        runCatching{startForegroundService(Intent(this,FaceScanService::class.java).setAction(FaceScanService.AUTO))}
            .onFailure{AutoPeople.request(this,0)}
            .onSuccess{
                val preferences=getSharedPreferences("face-notifications",0)
                if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("asked",false)){
                    preferences.edit().putBoolean("asked",true).apply()
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),3101)
                }
            }
    }
    private fun refreshGallery() {
        if (!active) return
        needsRefresh=true
        val savedLayout=grid.layoutManager?.onSaveInstanceState()
        val access = currentAccess();val scope=MediaAccess.scope(this); signal?.cancel(); val token = ++revision
        if (adapter.itemCount > 0) scrollPosition = (grid.layoutManager as? GridLayoutManager)?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
        progress.visibility = View.GONE; message.visibility = View.GONE; action.visibility = View.GONE
        grid.visibility = if (access == Access.NONE) View.GONE else View.VISIBLE

        if (access == Access.NONE) { finish(); return }
        if (!observed) observed = runCatching { contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer) }.isSuccess
        if (access == Access.PARTIAL) {
            action.text = "Change selected media"; action.setOnClickListener { requestPhotoAccess() }; action.visibility = View.VISIBLE
        }
        if(loadedAccess!=null && loadedAccess!=access){
            allPhotos=emptyList();visible=emptyList();selected.clear();loadedAccess=null;adapter.submitList(emptyList())
        }
        if(loadedAccess==null){progress.visibility = View.VISIBLE; status.text = "Loading local media…"}
        val query = CancellationSignal(); signal = query
        io.execute {
            // Publish the saved index first; validation continues on this same worker.
            if(loadedAccess==null){
                val cached=PhotoIndex.read(this)
                val pending=cached!=null && AutoPeople.canStartVisible(this) && runCatching{FaceHeat(this).canRun() && AutoPeople.hasPending(this,cached.photos)}.getOrDefault(false)
                if(cached!=null)runOnUiThread{
                    if(active && !query.isCanceled && token==revision && !isDestroyed && currentAccess()==access && MediaAccess.scope(this)==scope){
                        allPhotos=cached.photos;unreadableVolumes=cached.unreadableVolumes;loadedAccess=access;loadedScope=scope;progress.visibility=View.GONE
                        render();grid.layoutManager?.onRestoreInstanceState(savedLayout)
                        // Start from the saved index before a slow MediaStore validation can delay the handoff.
                        if(pending)launchAutomaticRecognition()
                    }
                }
            }
            val result = runCatching { GalleryData.load(this,query,true) }
            runOnUiThread {
                if (!active || query.isCanceled || token != revision || isDestroyed || (currentAccess()!=access || MediaAccess.scope(this)!=scope)) return@runOnUiThread
                progress.visibility = View.GONE
                result.onSuccess {
                    val changed=allPhotos!=it.photos || loadedAccess!=access
                    allPhotos = it.photos; unreadableVolumes = it.unreadableVolumes
                    loadedAccess=access;loadedScope=scope;needsRefresh=false;lastFavorites=GalleryStyle.favorites(this);AutoPeople.ensure(this);startAutomaticRecognition();PeopleActivity.warm(this,it.photos)
                    selected.retainAll(allPhotos.map { photo -> photo.uri.toString() }.toSet())
                    if(changed || grid.layoutManager==null){render();grid.layoutManager?.onRestoreInstanceState(savedLayout)}else updateCount()
                }.onFailure {
                    if(loadedAccess!=access){allPhotos = emptyList(); visible = emptyList();adapter.submitList(emptyList())}
                    status.text = "Could not load media"; message.text = "Check photo permissions and connected storage, then retry."
                    message.visibility = View.VISIBLE; action.text = "Retry"; action.setOnClickListener { refreshGallery() }; action.visibility = View.VISIBLE
                }
            }
        }
    }
    private fun shareSelected() {
        val uris = ArrayList(allPhotos.filter { it.uri.toString() in selected }.map { it.uri })
        if (uris.isEmpty()) return
        // Huge lists can exceed Android's intent transaction limit; ask for a smaller selection.
        if (uris.size > 200) { Toast.makeText(this, "Share up to 200 photos at a time.", Toast.LENGTH_LONG).show(); return }
        runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = when{allPhotos.filter{it.uri.toString() in selected}.all{it.isVideo}->"video/*";allPhotos.filter{it.uri.toString() in selected}.none{it.isVideo}->"image/*";else->"*/*"}; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newUri(contentResolver, "Photos", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share selected photos")) }.onFailure { Toast.makeText(this, "Could not share the selected photos.", Toast.LENGTH_SHORT).show() }
    }
    private fun readPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    private fun currentAccess() = when {
        MediaAccess.fullPhotos(this) || MediaAccess.fullVideos(this) -> Access.FULL
        Build.VERSION.SDK_INT >= 34 && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED -> Access.PARTIAL
        else -> Access.NONE
    }
    /** Ask once on first launch/update; a declined optional permission stays optional. */
    private fun requestStartupAccess():Boolean {
        if(permissionInFlight)return currentAccess()==Access.NONE
        if(intent.getBooleanExtra("requestMedia",false)){intent.removeExtra("requestMedia");requestPhotoAccess();return currentAccess()==Access.NONE}
        val prefs=getSharedPreferences("startup-access",0)
        val contacts=ContactRecognition.enabled(this) && !ContactRecognition.allowed(this) && !prefs.getBoolean("contacts-asked",false)
        val videos=Build.VERSION.SDK_INT>=33 && !MediaAccess.fullVideos(this) && prefs.getInt("media-request-policy",0)<2
        if(currentAccess()!=Access.NONE && !contacts && !videos)return false
        val permissions=mutableListOf<String>()
        if(currentAccess()==Access.NONE){
            galleryRoot.visibility=View.INVISIBLE
            permissions.addAll(MediaAccess.permissions())
        }else if(videos){
            permissions.addAll(MediaAccess.permissions())
        }
        if(Manifest.permission.READ_MEDIA_VIDEO in permissions)prefs.edit().putBoolean("videos-asked",true).putInt("media-request-policy",2).apply()
        if(contacts){permissions.add(Manifest.permission.READ_CONTACTS);prefs.edit().putBoolean("contacts-asked",true).apply()}
        permissionInFlight=true
        requestPermissions(permissions.toTypedArray(),1001)
        return currentAccess()==Access.NONE
    }
    private fun requestPhotoAccess() {
        if(permissionInFlight)return
        permissionInFlight=true
        requestPermissions(MediaAccess.permissions(),1001)
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (deletion.onPermissionsResult(code, results)) return
        if (code == 1001) {
            permissionInFlight=false
            if(currentAccess()==Access.NONE){finish();return}
            if(ContactRecognition.allowed(this))ContactRecognition.invalidate(this)
            AutoPeople.ensure(this,1_000)
            galleryRoot.visibility=View.VISIBLE;needsRefresh=true;refreshGallery()
        }
    }
    @Deprecated("Legacy activity results")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) { super.onActivityResult(code, result, data); deletion.onActivityResult(code, result) }
    // API 33+ uses the OnBackInvokedDispatcher callback registered through Ui.back.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("Legacy Android back navigation")
    override fun onBackPressed() { handleBack() }
    private fun handleBack() {
        if (deletion.inProgress) return
        if (selecting) { selecting = false; selected.clear(); updateSelection() } else if(folderOpen)closeAlbum()else finish()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
