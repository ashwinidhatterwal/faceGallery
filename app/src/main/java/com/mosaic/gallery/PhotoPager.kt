package com.mosaic.gallery

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Native scrolling owns drag, velocity, interruption and snapping. No frame-driven bitmap work. */
class PhotoPager(context: Context) : RecyclerView(context) {
    var onSelected: (PhotoRecord) -> Unit = {}
    var onImageReady: (Boolean) -> Unit = {}
    var onPhotoTap: () -> Unit = {}
    var onSwipeUp:()->Unit={}
    private var swipingUp=false
    private var photos = emptyList<PhotoRecord>()
    private var selected = 0
    private var running = false
    private var videoControls=true
    private val videoStates=linkedMapOf<String,GalleryVideoView.State>()
    private var closed = false
    var deferImageUpgrades=false
    var onDismissProgress:(Float)->Unit={}
    var onDismissReleased:()->Unit={}
    private var controlsGesture=false
    private var downX=0f;private var downY=0f
    private var dismissImage:ZoomPhotoView?=null
    private var directionLocked=false
    private var dismissBlocked=false
    private val touchSlop=android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val dismissing get()=dismissImage!=null
    private val worker = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceIn(8L * 1024 * 1024, 32L * 1024 * 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val bound = mutableSetOf<Holder>()
    private val snap = PagerSnapHelper()
    private val pages = Pages()
    private val layout = object : LinearLayoutManager(context, HORIZONTAL, false) {
        override fun calculateExtraLayoutSpace(state: State, extra: IntArray) {
            // Keep both neighboring pages ready before a finger moves.
            extra[0] = width; extra[1] = width
        }
    }
    private class Holder(val image: ZoomPhotoView,val root:FrameLayout) : ViewHolder(root) {
        var video:GalleryVideoView?=null
        var key = ""
        var job: Future<*>? = null
        var cancellation: CancellationSignal? = null
        var pending: Bitmap? = null
        var failed = false
        fun cancel() { job?.cancel(true); cancellation?.cancel(); job = null }
    }
    init {
        layoutManager = layout; adapter = pages; itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        clipChildren=false;clipToPadding=false
        setScrollingTouchSlop(TOUCH_SLOP_DEFAULT)
        setItemViewCacheSize(3)
        snap.attachToRecyclerView(this)
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrollStateChanged(view: RecyclerView, state: Int) {
                if(state!=SCROLL_STATE_IDLE)stopVideos()
                if (state == SCROLL_STATE_IDLE) post {
                    // Let SnapHelper start any final correction before changing images/chrome.
                    if (closed || scrollState != SCROLL_STATE_IDLE) return@post
                    updateSelected()
                    if(!deferImageUpgrades && !dismissing)attached().forEach { holder ->
                        holder.pending?.let { holder.image.upgrade(it); holder.pending = null }
                    }
                    updateVideos();reportImage()
                }
            }
        })
    }
    override fun onInterceptTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN){val holder=findViewHolderForAdapterPosition(selected) as? Holder;controlsGesture=holder?.video?.let{it.controlsHit(event.x-holder.root.left-it.left,event.y-holder.root.top-it.top)}==true}
        if(controlsGesture)return false
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{downX=event.x;downY=event.y;directionLocked=false;dismissBlocked=false;swipingUp=false}
            MotionEvent.ACTION_POINTER_DOWN->dismissBlocked=true
            MotionEvent.ACTION_MOVE->{
                val dx=event.x-downX;val dy=event.y-downY
                if(!directionLocked && !dismissBlocked && event.pointerCount==1 && scrollState==SCROLL_STATE_IDLE){
                    if(dy < -touchSlop && -dy>kotlin.math.abs(dx)*1.2f && currentImage()?.canDismiss==true){directionLocked=true;swipingUp=true;parent?.requestDisallowInterceptTouchEvent(true);return true}
                    if(dy>touchSlop && dy>kotlin.math.abs(dx)*1.2f && currentImage()?.canDismiss==true){
                        directionLocked=true;dismissImage=currentImage();parent?.requestDisallowInterceptTouchEvent(true)
                        dragDismiss(dx,dy);return true
                    }
                    if(kotlin.math.abs(dx)>touchSlop || dy< -touchSlop)directionLocked=true
                }
            }
        }
        return super.onInterceptTouchEvent(event)
    }
    private fun dragDismiss(dx:Float,dy:Float){
        val distance=dy.coerceAtLeast(0f)
        val progress=(distance/(height*0.7f).coerceAtLeast(1f)).coerceIn(0f,1f)
        dismissImage?.apply{translationX=dx;translationY=distance;scaleX=1f-progress*0.35f;scaleY=scaleX}
        onDismissProgress(progress)
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(swipingUp){
            when(event.actionMasked){
                MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_CANCEL->{swipingUp=false;parent?.requestDisallowInterceptTouchEvent(false)}
                MotionEvent.ACTION_UP->{swipingUp=false;parent?.requestDisallowInterceptTouchEvent(false);if(downY-event.y>touchSlop*2)onSwipeUp()}
            };return true
        }
        val image=dismissImage ?: return super.onTouchEvent(event)
        when(event.actionMasked){
            MotionEvent.ACTION_MOVE->dragDismiss(event.x-downX,event.y-downY)
            MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_CANCEL->cancelDismiss(image)
            MotionEvent.ACTION_UP->{
                parent?.requestDisallowInterceptTouchEvent(false)
                if(event.y-downY>touchSlop){onDismissReleased();dismissImage=null}else cancelDismiss(image)
            }
        }
        return true
    }
    private fun cancelDismiss(image:ZoomPhotoView){
        dismissImage=null;parent?.requestDisallowInterceptTouchEvent(false)
        image.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        onDismissProgress(0f)
    }
    private fun attached() = (0 until childCount).mapNotNull { getChildAt(it)?.let { view -> getChildViewHolder(view) as? Holder } }
    fun currentImage(): ZoomPhotoView? = (findViewHolderForAdapterPosition(selected) as? Holder)?.image
    fun submit(list: List<PhotoRecord>, uri: String) {
        val position = list.indexOfFirst { it.uri.toString() == uri }.coerceAtLeast(0)
        if (photos.map { it.uri } == list.map { it.uri }) { photos = list; return }
        stopScroll(); attached().forEach { it.cancel() }; worker.purge()
        photos = list; selected = position; pages.notifyDataSetChanged()
        layout.scrollToPositionWithOffset(position, 0)
        photos.getOrNull(selected)?.let(onSelected);post{updateVideos()}
    }
    fun goTo(uri: String, animate: Boolean = true) {
        val position = photos.indexOfFirst { it.uri.toString() == uri }
        if (position < 0 || position == selected) return
        if (animate && kotlin.math.abs(position - selected) == 1) smoothScrollToPosition(position)
        else { stopScroll(); selected = position; layout.scrollToPositionWithOffset(position, 0); photos[position].let(onSelected); post { currentImage()?.resetToFit();updateVideos();reportImage() } }
    }
    fun step(delta: Int) { photos.getOrNull(selected + delta)?.let { goTo(it.uri.toString()) } }
    private fun updateSelected() {
        val view = snap.findSnapView(layout) ?: return
        val position = getChildAdapterPosition(view)
        if (position == NO_POSITION || position == selected) return
        stopVideos();selected = position;currentImage()?.resetToFit();photos.getOrNull(position)?.let(onSelected)
    }
    private fun reportImage() {
        val holder = findViewHolderForAdapterPosition(selected) as? Holder ?: return
        if (holder.image.drawable != null || holder.failed) onImageReady(holder.image.drawable != null)
    }
    fun finishOpening(){
        deferImageUpgrades=false
        if(scrollState==SCROLL_STATE_IDLE)attached().forEach{holder->holder.pending?.let{holder.image.upgrade(it);holder.pending=null}}
        currentImage()?.resetToFit()
    }
    fun settleForClose(): ZoomPhotoView? {
        stopScroll(); updateSelected()
        layout.scrollToPositionWithOffset(selected, 0)
        return currentImage()
    }
    fun resume() {
        if (closed) return
        running = true;post{updateVideos()}
        attached().filter { it.image.drawable == null }.forEach { holder ->
            val position = holder.bindingAdapterPosition
            photos.getOrNull(position)?.let { load(holder, it) }
        }
    }
    fun pause(retainImage: Boolean) {
        running = false; stopScroll();stopVideos()
        bound.forEach { it.cancel(); it.pending = null; if (!retainImage) it.image.show(null) }
        worker.queue.clear(); worker.purge()
        if (!retainImage) { recycledViewPool.clear(); cache.evictAll() }
    }
    fun close() { closed = true; pause(false); worker.shutdownNow(); adapter = null; bound.clear() }
    private fun load(holder: Holder, photo: PhotoRecord) {
        holder.cancel(); worker.purge(); holder.failed = false
        val key = photo.uri.toString(); holder.key = key
        cache.get(key)?.let { holder.image.show(it); holder.image.post { if(holder.bindingAdapterPosition==selected)reportImage() }; return }
        holder.image.show(null)
        if (!running || closed) return
        val cancellation = CancellationSignal(); holder.cancellation = cancellation
        holder.job = worker.submit {
            // System previews are fast and preserve a visible page while the full image prepares.
            val preview = runCatching {
                if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(photo.uri, Size(640, 640), cancellation)
                else GalleryMedia.thumbnail(context,photo,640,cancellation)
            }.getOrNull()
            if (cancellation.isCanceled || Thread.currentThread().isInterrupted) return@submit
            preview?.prepareToDraw()
            deliver(holder, key, cancellation, preview, false)
            if(photo.isVideo){deliver(holder,key,cancellation,preview,true);return@submit}
            val full = runCatching {
                PhotoImages.decode(context, photo.uri, 2048, (cache.maxSize().toLong() / 24).coerceIn(350_000L, 2_000_000L)).apply { prepareToDraw() }
            }.getOrNull()
            if (cancellation.isCanceled || Thread.currentThread().isInterrupted) return@submit
            deliver(holder, key, cancellation, full ?: preview, true)
        }
    }
    private fun deliver(holder: Holder, key: String, cancellation: CancellationSignal, bitmap: Bitmap?, complete: Boolean) {
        post {
            if (!running || closed || cancellation.isCanceled || holder.key != key) return@post
            if (complete && bitmap != null) cache.put(key, bitmap)
            holder.failed = complete && bitmap == null
            // Never replace the texture/matrix of a moving page. Existing pages stay intact.
            if (bitmap != null) {
                if (scrollState == SCROLL_STATE_IDLE && !dismissing && (!deferImageUpgrades || holder.image.drawable==null)) holder.image.upgrade(bitmap)
                else holder.pending = bitmap
            }
            if (scrollState == SCROLL_STATE_IDLE && holder.bindingAdapterPosition == selected) reportImage()
        }
    }
    private fun rememberVideo(holder:Holder){
        val state=holder.video?.state()?.takeIf{it.uri.isNotEmpty()}?:return
        videoStates[state.uri]=state;if(videoStates.size>32)videoStates.remove(videoStates.keys.first())
    }
    private fun stopVideos(){bound.forEach{rememberVideo(it);it.video?.stopPlayback()}}
    private fun updateVideos(){
        bound.forEach{holder->
            val isSelected=running && !closed && scrollState==SCROLL_STATE_IDLE && holder.bindingAdapterPosition==selected && photos.getOrNull(selected)?.isVideo==true && holder.itemView.parent===this
            if(isSelected){holder.video?.activate();holder.video?.controlsVisible(videoControls)}else if(holder.video?.visibility==View.VISIBLE){rememberVideo(holder);holder.video?.stopPlayback()}
        }
    }
    fun showVideoControls(visible:Boolean){videoControls=visible;bound.forEach{it.video?.controlsVisible(visible)}}
    fun videoState():GalleryVideoView.State?{val holder=findViewHolderForAdapterPosition(selected) as? Holder;return if(photos.getOrNull(selected)?.isVideo==true)holder?.video?.state()else null}
    fun restoreVideoState(state:GalleryVideoView.State){videoStates[state.uri]=state}
    private inner class Pages : Adapter<Holder>() {
        override fun getItemCount() = photos.size
        override fun onCreateViewHolder(parent:ViewGroup,type:Int):Holder {
            val root=FrameLayout(context).apply{layoutParams=LayoutParams(-1,-1)}
            val image=ZoomPhotoView(context).apply{onTap={onPhotoTap()}}
            root.addView(image,FrameLayout.LayoutParams(-1,-1))
            return Holder(image,root)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            bound.add(holder)
            holder.image.contentDescription = photos[position].displayName
            val photo=photos[position]
            if(photo.isVideo){
                if(holder.video==null){holder.video=GalleryVideoView(context).apply{visibility=View.GONE;onTap={onPhotoTap()}};holder.root.addView(holder.video,FrameLayout.LayoutParams(-1,-1))}
                holder.video?.bind(photo,videoStates[photo.uri.toString()])
            }else holder.video?.stopPlayback()
            load(holder,photo)
            post{updateVideos()}
        }
        override fun onViewAttachedToWindow(holder: Holder) {
            post{updateVideos()}
            if(scrollState==SCROLL_STATE_IDLE && !deferImageUpgrades) holder.pending?.let{holder.image.upgrade(it);holder.pending=null}
            if(running && holder.image.drawable==null && holder.pending==null && holder.job?.isDone!=false)
                photos.getOrNull(holder.bindingAdapterPosition)?.let { load(holder,it) }
        }
        override fun onViewDetachedFromWindow(holder:Holder){rememberVideo(holder);holder.video?.stopPlayback()}
        override fun onViewRecycled(holder: Holder) {
            rememberVideo(holder);holder.video?.stopPlayback();bound.remove(holder);holder.cancel(); holder.key = ""; holder.pending = null; holder.image.show(null); worker.purge()
        }
    }
}
