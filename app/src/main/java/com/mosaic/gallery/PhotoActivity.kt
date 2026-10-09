package com.mosaic.gallery

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.View
import android.widget.*
import java.util.Date
import java.util.concurrent.Executors

class PhotoActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var active = false
    private var current: PhotoRecord? = null
    private var photos = emptyList<PhotoRecord>()
    private var album = ""
    private lateinit var pager: PhotoPager
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var deletion: PhotoDeletion
    private var querySignal: CancellationSignal? = null
    private lateinit var top: LinearLayout
    private lateinit var bottom: LinearLayout
    private lateinit var favorite: LinearLayout
    private lateinit var editAction:LinearLayout
    private lateinit var film: PhotoRail
    private lateinit var filmAdapter: FilmstripAdapter
    private lateinit var chrome:LinearLayout
    private var controlsVisible=true
    private var closing=false
    private lateinit var peopleNames:PeopleNames
    private lateinit var peopleSheet:PhotoPeopleSheet
    private lateinit var peopleHost:FrameLayout
    private var peopleFraction=0f;private var peopleAnimator:android.animation.ValueAnimator?=null
    private var viewerRoot:FrameLayout?=null
    private fun showPeople(){current?.let{record->
        if(record.isVideo || peopleSheet.isShowing)return
        controlsVisible=true;chrome.visibility=View.VISIBLE;chrome.alpha=1f;viewerBars(true)
        peopleSheet.show(record,(pager.currentImage()?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap)
        animatePeople(true)
    }}
    private fun animatePeople(open:Boolean){
        peopleAnimator?.cancel();film.visibility=View.INVISIBLE;bottom.visibility=View.INVISIBLE
        peopleAnimator=android.animation.ValueAnimator.ofFloat(peopleFraction,if(open)1f else 0f).apply{
            duration=260;interpolator=android.view.animation.PathInterpolator(.2f,0f,0f,1f)
            addUpdateListener{peopleFraction=it.animatedValue as Float;layoutPeople()}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(animation:android.animation.Animator){if(!open){peopleHost.visibility=View.GONE;film.visibility=View.VISIBLE;bottom.visibility=View.VISIBLE}}});start()
        }
    }
    private fun layoutPeople(){
        val root=viewerRoot?:return;if(!::pager.isInitialized || root.height==0)return
        val insetTop=status.bottom.coerceAtLeast(top.bottom);val closedBottom=(root.height-film.top+GalleryStyle.dp(this,1)).coerceAtLeast(0)
        val available=(root.height-insetTop-chrome.paddingBottom).coerceAtLeast(0)
        val middle=insetTop+available/2;val openBottom=root.height-middle
        val margins=pager.layoutParams as FrameLayout.LayoutParams
        val nextBottom=(closedBottom+(openBottom-closedBottom)*peopleFraction).toInt()
        if(margins.topMargin!=insetTop || margins.bottomMargin!=nextBottom){margins.topMargin=insetTop;margins.bottomMargin=nextBottom;pager.layoutParams=margins}
        val panel=peopleHost.layoutParams as FrameLayout.LayoutParams
        if(panel.topMargin!=middle || panel.height!=available/2){panel.topMargin=middle;panel.height=available/2;peopleHost.layoutParams=panel}
        peopleHost.translationY=(1f-peopleFraction)*available/2
    }


    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        peopleNames=PeopleNames(this);peopleHost=FrameLayout(this).apply{visibility=View.GONE};peopleSheet=PhotoPeopleSheet(this,peopleNames,peopleHost){if(::film.isInitialized)animatePeople(false)}
        if(state==null && intent.getBooleanExtra("transition",false)){
            postponeEnterTransition()
            window.sharedElementEnterTransition=android.transition.TransitionSet().apply{
                addTransition(android.transition.ChangeBounds());addTransition(android.transition.ChangeTransform());addTransition(android.transition.ChangeImageTransform())
                duration=280;interpolator=android.view.animation.DecelerateInterpolator()
            }
            window.sharedElementEnterTransition.addListener(object:android.transition.Transition.TransitionListener{
                override fun onTransitionStart(t:android.transition.Transition){}
                override fun onTransitionEnd(t:android.transition.Transition){pager.finishOpening();t.removeListener(this)}
                override fun onTransitionCancel(t:android.transition.Transition){pager.finishOpening();t.removeListener(this)}
                override fun onTransitionPause(t:android.transition.Transition){}
                override fun onTransitionResume(t:android.transition.Transition){}
            })
            window.enterTransition=android.transition.Fade().apply{duration=180;excludeTarget("mosaic-photo",true)}
        }
        window.sharedElementReturnTransition=android.transition.TransitionSet().apply{addTransition(android.transition.ChangeBounds());addTransition(android.transition.ChangeTransform());addTransition(android.transition.ChangeImageTransform());duration=250;interpolator=android.view.animation.DecelerateInterpolator()}
        val uri = state?.getString("uri")?.let(Uri::parse) ?: intent.data ?: run { finish(); return }
        current = PhotoRecord(0, uri, intent.getStringExtra("name").orEmpty(), 0, 0, 0,mimeType=state?.getString("mimeType")?:intent.getStringExtra("mimeType")?:"image/*")
        album = intent.getStringExtra("album").orEmpty()
        deletion = PhotoDeletion(this) { count -> if (count > 0) finish() }
        deletion.restore(state)
        val root = FrameLayout(this).apply { setBackgroundColor(GalleryStyle.canvas(this@PhotoActivity)) }
        chrome=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        top=LinearLayout(this).apply{gravity=android.view.Gravity.CENTER_VERTICAL;minimumHeight=GalleryStyle.dp(context,60);setPadding(GalleryStyle.dp(context,10),GalleryStyle.dp(context,4),GalleryStyle.dp(context,10),GalleryStyle.dp(context,4))}
        top.setBackgroundColor(GalleryStyle.canvas(this@PhotoActivity))
        top.addView(GalleryStyle.action(this,"back","Back",compact=true){closePhoto()})
        title=GalleryStyle.text(this,"",21f).apply{maxLines=2;setPadding(12,0,0,0)}
        top.addView(title,LinearLayout.LayoutParams(0,-2,1f))
        favorite=GalleryStyle.action(this,"heart","Favorite",compact=true){toggleFavorite()};top.addView(favorite)
        val more=GalleryStyle.action(this,"more","Media options",compact=true){};more.setOnClickListener{mediaMenu(it)};top.addView(more);chrome.addView(top)
        status=GalleryStyle.text(this,"",12f,GalleryStyle.muted(this@PhotoActivity)).apply{setPadding(24,0,24,4)};chrome.addView(status)
        pager=PhotoPager(this).apply {
            deferImageUpgrades=state==null && intent.getBooleanExtra("transition",false)
            onPhotoTap={toggleControls()}
            onSwipeUp={showPeople()}
            onDismissProgress={progress->chrome.animate().cancel();chrome.alpha=if(controlsVisible)1f-progress.coerceAtMost(0.95f)else 0f}
            onDismissReleased={closePhoto()}
            onSelected={photo->if(peopleSheet.isShowing)peopleSheet.dismiss();current=photo;if(!photo.isVideo)peopleSheet.prepare(photo);editAction.visibility=if(photo.isVideo)View.GONE else View.VISIBLE;status.text="";updateTitle();updateNavigation()}
            onImageReady={available->peopleSheet.updatePreview((pager.currentImage()?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap);current?.takeIf{!it.isVideo}?.let{peopleSheet.prepare(it)};status.text=if(available || current?.isVideo==true)""else"Media unavailable";startPostponedEnterTransition()}
        }
        state?.getString("videoUri")?.let{pager.restoreVideoState(GalleryVideoView.State(it,state.getLong("videoPosition"),state.getBoolean("videoPlaying",true),state.getBoolean("videoMuted",false)))}
        root.addView(pager,FrameLayout.LayoutParams(-1,-1))
        chrome.addView(Space(this),LinearLayout.LayoutParams(-1,0,1f))
        filmAdapter=FilmstripAdapter(this){photo->pager.goTo(photo.uri.toString())}
        film=PhotoRail(this).apply{
            adapter=filmAdapter
            onCentered={position->photos.getOrNull(position)?.let{pager.goTo(it.uri.toString(),animate=false)}}
        };chrome.addView(film,LinearLayout.LayoutParams(-1,GalleryStyle.dp(this,50)))
        bottom=GalleryStyle.bar(this).apply{setBackgroundColor(GalleryStyle.canvas(this@PhotoActivity))}
        GalleryStyle.add(bottom,GalleryStyle.action(this,"share","Share"){share()})
        editAction=GalleryStyle.action(this,"edit","Edit"){current?.takeIf{!it.isVideo}?.let{startActivity(Intent(this,PhotoEditorActivity::class.java).setData(it.uri).putExtra("name",it.displayName))}};GalleryStyle.add(bottom,editAction)
        GalleryStyle.add(bottom,GalleryStyle.action(this,"delete","Delete"){current?.let{deletion.delete(listOf(it.uri))}})
        chrome.addView(bottom);root.addView(chrome,FrameLayout.LayoutParams(-1,-1))
        viewerRoot=root;root.addView(peopleHost,FrameLayout.LayoutParams(-1,0))
        root.viewTreeObserver.addOnGlobalLayoutListener{layoutPeople()}

        Ui.insets(this,chrome)
        if(Build.VERSION.SDK_INT<30)chrome.setOnApplyWindowInsetsListener{view,insets->
            @Suppress("DEPRECATION")
            view.setPadding(maxOf(insets.stableInsetLeft,insets.systemWindowInsetLeft),maxOf(insets.stableInsetTop,insets.systemWindowInsetTop),maxOf(insets.stableInsetRight,insets.systemWindowInsetRight),maxOf(insets.stableInsetBottom,insets.systemWindowInsetBottom));insets
        }
        setContentView(root);Ui.blendSystemBars(this);viewerBars(true)
        val sharedCallback=object:android.app.SharedElementCallback(){
            override fun onMapSharedElements(names:MutableList<String>,elements:MutableMap<String,View>){
                elements.clear();pager.currentImage()?.let{it.transitionName="mosaic-photo";elements["mosaic-photo"]=it}
            }
        }
        setEnterSharedElementCallback(sharedCallback);setExitSharedElementCallback(sharedCallback)
        Ui.back(this){closePhoto()}
        current?.takeIf{it.isVideo}?.let{photos=listOf(it);filmAdapter.submit(photos);pager.submit(photos,it.uri.toString())}
    }
    private fun toggleControls(){
        if(peopleSheet.isShowing){peopleSheet.dismiss();return}
        controlsVisible=!controlsVisible
        chrome.animate().cancel()
        if(controlsVisible)chrome.visibility=View.VISIBLE
        chrome.animate().alpha(if(controlsVisible)1f else 0f).setDuration(160)
            .withEndAction{chrome.visibility=if(controlsVisible)View.VISIBLE else View.INVISIBLE}.start()
        pager.showVideoControls(controlsVisible);viewerBars(controlsVisible)
    }
    @Suppress("DEPRECATION")
    private fun viewerBars(visible:Boolean){
        if(Build.VERSION.SDK_INT>=30){
            window.insetsController?.apply{
                systemBarsBehavior=android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if(visible)show(android.view.WindowInsets.Type.systemBars())else hide(android.view.WindowInsets.Type.systemBars())
            }
        }else{
            val light=if(resources.getBoolean(R.bool.gallery_light_bars))View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
            val layout=View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or light
            window.decorView.systemUiVisibility=if(visible)layout else layout or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }
    private fun toggleFavorite(){current?.let{GalleryStyle.favorite(this,it.uri.toString());updateFavorite()}}
    private fun updateFavorite(){
        val selected=current?.uri.toString() in GalleryStyle.favorites(this)
        favorite.removeAllViews();favorite.addView(ImageView(this).apply{setImageDrawable(GalleryStyle.icon(context,if(selected)"heartFilled"else"heart",if(selected)GalleryStyle.accent(this@PhotoActivity) else GalleryStyle.iconColor(this@PhotoActivity)))},LinearLayout.LayoutParams(GalleryStyle.dp(this,26),GalleryStyle.dp(this,26)))
        favorite.contentDescription=if(selected)"Remove favorite"else"Add favorite"
    }
    override fun onResume() {
        super.onResume(); active = true
        deletion.resume()
        pager.resume();status.text=""
        val signal = CancellationSignal(); querySignal = signal
        worker.execute {
            val favorites=GalleryStyle.favorites(this)
            val result = runCatching { val available=GalleryData.load(this,signal).photos
                val filtered=if(intent.getBooleanExtra("peopleSearch",false))PeopleSearch.filter(available,PeopleSearch.cachedRead(this,available),PeopleSearch.Query(intent.getStringExtra("searchText").orEmpty(),intent.getLongExtra("searchPerson",-1).takeIf{it>=0},intent.getStringExtra("searchFrom")?.let(java.time.LocalDate::parse),intent.getStringExtra("searchThrough")?.let(java.time.LocalDate::parse)),keepGoing={!signal.isCanceled}) else available.filter { (!intent.getBooleanExtra("cameraOnly",false) || CameraMedia.contains(it)) && (album.isEmpty() || if(album=="@favorites")it.uri.toString() in favorites else if(album=="@other")it.album.isBlank() else it.album == album) && (intent.getStringExtra("query").orEmpty().let{q -> q.isEmpty() || it.displayName.contains(q,true) || it.album.contains(q,true)}) };available to filtered }.getOrNull()
            runOnUiThread {
                if (!active || signal.isCanceled || isDestroyed) return@runOnUiThread
                val list=result?.second
                if(intent.getBooleanExtra("peopleSearch",false) && list?.any{it.uri==current?.uri}!=true){finish();return@runOnUiThread}
                val previousPhotos=photos
                photos = list?.takeIf{it.isNotEmpty()} ?: listOfNotNull(current)
                val record = photos.find { it.uri == current?.uri }
                if (record != null) { current = record; updateTitle();if(!record.isVideo)peopleSheet.prepare(record) }
                if(photos!=previousPhotos){
                    filmAdapter.submit(photos)
                    pager.submit(photos,current?.uri.toString().orEmpty());film.focus(photos.indexOfFirst{it.uri==current?.uri},animate=false)
                }
            }
        }
    }
    private fun updateTitle(){
        current?.let{photo->
            title.text=if(photo.dateTakenMillis>0){
                val date=GalleryDates.label(photo.dateTakenMillis)
                android.text.SpannableString(date+"\n"+java.text.SimpleDateFormat("h:mm a",java.util.Locale.getDefault()).format(Date(photo.dateTakenMillis))).apply{
                    setSpan(android.text.style.RelativeSizeSpan(0.68f),date.length+1,length,0)
                    setSpan(android.text.style.ForegroundColorSpan(GalleryStyle.muted(this@PhotoActivity)),date.length+1,length,0)
                }
            }else photo.displayName
        }
        updateFavorite()
    }
    private fun navigate(delta:Int){pager.step(delta)}
    private fun closePhoto(){
        if(deletion.inProgress||closing)return
        if(peopleSheet.isShowing){peopleSheet.dismiss();return}
        closing=true
        val image=pager.settleForClose();viewerBars(true)
        image?.transitionName="mosaic-photo"
        setResult(RESULT_OK,Intent().setData(current?.uri))
        if(intent.getBooleanExtra("transition",false)&&image?.drawable!=null)finishAfterTransition()else finish()
    }
    private fun updateNavigation(){
        val index=photos.indexOfFirst{it.uri==current?.uri}
        if(index>=0)film.focus(index)
    }
    private fun share() {
        val uri = current?.uri ?: return
        runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = contentResolver.getType(uri) ?: if(current?.isVideo==true)"video/*"else"image/*"; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, "Photo", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share media")) }.onFailure { Toast.makeText(this, "Could not share this item.", Toast.LENGTH_SHORT).show() }
    }
    private fun mediaMenu(anchor:View){
        val actions=mutableListOf(GalleryMenu.Action("info","Details"){info()})
        if(current?.isVideo!=true){actions+=GalleryMenu.Action("personAdd","People in this photo"){showPeople()};actions+=GalleryMenu.Action("rotate","Rotate photo"){pager.currentImage()?.rotateQuarterTurn()}}
        actions+=GalleryMenu.Action("settings","Settings"){startActivity(Intent(this,SettingsActivity::class.java))}
        GalleryMenu.show(this,if(current?.isVideo==true)"Video options"else"Photo options",actions,anchor)
    }
    private fun info(){current?.let{PhotoDetails.show(this,it)}}
    override fun onPause() {
        active=false;querySignal?.cancel();if(!peopleNames.requestingPermission){peopleSheet.dismiss();peopleNames.dismiss()}
        pager.pause(retainImage=closing)
        super.onPause()
    }
    override fun onSaveInstanceState(state: Bundle) { state.putString("uri", current?.uri.toString());state.putString("mimeType",current?.mimeType);pager.videoState()?.let{state.putString("videoUri",it.uri);state.putLong("videoPosition",it.position);state.putBoolean("videoPlaying",it.playing);state.putBoolean("videoMuted",it.muted)}; deletion.save(state); super.onSaveInstanceState(state) }
    @Deprecated("Legacy activity results")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) { super.onActivityResult(code, result, data); deletion.onActivityResult(code, result) }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results); peopleNames.permissionResult(code); deletion.onPermissionsResult(code, results)
    }
    // API 33+ uses the OnBackInvokedDispatcher callback registered through Ui.back.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("Legacy Android back navigation")
    override fun onBackPressed() { closePhoto() }
    override fun onDestroy() { peopleSheet.close();peopleAnimator?.cancel();peopleNames.close(); pager.close();deletion.close(); filmAdapter.close(); worker.shutdownNow(); super.onDestroy() }
}
