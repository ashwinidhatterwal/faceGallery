package com.mosaic.gallery

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.*

object GalleryDates {
    fun label(time: Long): String {
        val date=Calendar.getInstance().apply { timeInMillis=time }; val now=Calendar.getInstance()
        if(date.get(Calendar.YEAR)==now.get(Calendar.YEAR) && date.get(Calendar.DAY_OF_YEAR)==now.get(Calendar.DAY_OF_YEAR)) return "Today"
        now.add(Calendar.DAY_OF_YEAR,-1)
        if(date.get(Calendar.YEAR)==now.get(Calendar.YEAR) && date.get(Calendar.DAY_OF_YEAR)==now.get(Calendar.DAY_OF_YEAR)) return "Yesterday"
        return SimpleDateFormat(if(date.get(Calendar.YEAR)==Calendar.getInstance().get(Calendar.YEAR)) "MMMM d" else "MMMM d, yyyy",Locale.getDefault()).format(Date(time))
    }
}

class PhotoGridAdapter(private val context: Context, private val click: (PhotoRecord)->Unit,
    private val longClick: (PhotoRecord)->Unit, private val albumClick:(String,View)->Unit = {_,_->}) : RecyclerView.Adapter<PhotoGridAdapter.Holder>() {
    private data class Item(val photo:PhotoRecord?=null,val title:String="",val count:Int=0,val album:String?=null)
    private var items=emptyList<Item>()
    private var labelsDay=Long.MIN_VALUE
    private var labelsScope=""
    private var selected=emptySet<String>()
    var selectionMode=false
    private var closed=false
    private val workers=ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,LinkedBlockingQueue())
    private val cells=mutableSetOf<Cell>()
    private val cache=object:LruCache<String,Bitmap>((Runtime.getRuntime().maxMemory()/16).coerceIn(2L*1024*1024,16L*1024*1024).toInt()) {
        override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
    }
    // Native Activity/theme: AppCompat is only a transitive detector dependency.
    @android.annotation.SuppressLint("AppCompatCustomView")
    class Cell(context:Context):ImageView(context) {
        var albumKey:String?=null;var key:String?=null;var sourceKey:String?=null;var job:Future<*>?=null;var signal:CancellationSignal?=null;var revision=0
        var checked=false;var selectable=false;var failed=false;var video=false
        private val playIcon=GalleryStyle.icon(context,"play",Color.WHITE)
        private val checkIcon=GalleryStyle.icon(context,"select",GalleryStyle.accent(context))
        private val emptyIcon=GalleryStyle.icon(context,"ratioSquare",Color.WHITE)
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onMeasure(w:Int,h:Int){val side=View.MeasureSpec.getSize(w).coerceAtLeast(1);setMeasuredDimension(side,side)}
        override fun onDraw(canvas:Canvas){
            super.onDraw(canvas);val d=resources.displayMetrics.density
            if(failed){paint.color=GalleryStyle.muted(context);paint.textSize=11*d;paint.textAlign=Paint.Align.CENTER;canvas.drawText("No preview",width/2f,height/2f,paint)}
            if(video){
                paint.color=0x66000000;canvas.drawCircle(18*d,height-18*d,12*d,paint)
                playIcon.setBounds((9*d).toInt(),(height-27*d).toInt(),(27*d).toInt(),(height-9*d).toInt());playIcon.draw(canvas)
            }
            if(selectable){
                if(checked){paint.color=0x44000000;canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)}
                val left=width-25*d;val top=7*d
                val icon=if(checked)checkIcon else emptyIcon
                icon.setBounds(left.toInt(),top.toInt(),(left+18*d).toInt(),(top+18*d).toInt());icon.draw(canvas)
                paint.style=Paint.Style.FILL
            }
        }
        fun cancel(){transitionName=null;revision++;signal?.cancel();job?.cancel(true);signal=null;job=null;key=null;sourceKey=null;setImageDrawable(null)}
    }
    class Holder(val root:View,val image:Cell?=null,val title:TextView?=null,val count:TextView?=null):RecyclerView.ViewHolder(root)
    fun positionOf(uri:String)=items.indexOfFirst{it.album==null&&it.photo?.uri.toString()==uri}
    fun thumbnailView(uri:String):View?=cells.firstOrNull{it.key==uri && it.albumKey==null && it.isAttachedToWindow}
    fun albumView(album:String):View?=cells.firstOrNull{it.albumKey==album && it.isAttachedToWindow}
    fun isHeader(position:Int)=items[position].photo==null && items[position].album==null
    fun submitList(photos:List<PhotoRecord>,invalidateThumbnails:Boolean=true){
        if(invalidateThumbnails){
            val sources=photos.associateBy{it.uri.toString()}
            cells.forEach{cell->val photo=sources[cell.key];if(photo==null || cell.sourceKey!=imageKey(photo))cell.cancel()}
            workers.purge()
        }
        val day=java.time.LocalDate.now().toEpochDay()
        val scope=java.util.TimeZone.getDefault().id+java.util.Locale.getDefault().toLanguageTag()
        if(!invalidateThumbnails && labelsDay==day && labelsScope==scope && items.none{it.album!=null} && items.mapNotNull{it.photo}==photos)return
        val list=ArrayList<Item>();var last=""
        photos.forEach{val label=GalleryDates.label(it.dateTakenMillis);if(label!=last){list+=Item(title=label);last=label};list+=Item(photo=it)}
        if(items==list && !invalidateThumbnails){labelsDay=day;labelsScope=scope;return}
        labelsDay=day;labelsScope=scope;items=list;notifyDataSetChanged()
    }
    fun submitAlbums(photos:List<PhotoRecord>){
        val favorites=GalleryStyle.favorites(context);val groups=photos.groupBy{it.album}
        val tags=MediaTags.read(context)
        val tagged=mutableMapOf<String,MutableList<PhotoRecord>>()
        photos.forEach{photo->tags.media[photo.uri.toString()].orEmpty().forEach{key->tagged.getOrPut(key){mutableListOf()}.add(photo)}}
        val tagAlbums=tagged.entries.sortedBy{tags.names[it.key].orEmpty().lowercase()}.map{(key,members)->Item(members.first(),tags.names[key].orEmpty(),members.size,MediaTags.PREFIX+key)}
        val next=listOf(Item(photos.firstOrNull(),"All",photos.size,""),
            Item(photos.firstOrNull{it.uri.toString() in favorites},"Favorites",photos.count{it.uri.toString() in favorites},"@favorites"))+
            groups.entries.sortedWith(compareByDescending<Map.Entry<String,List<PhotoRecord>>>{it.key.equals("Camera",true)}.thenBy{it.key})
                .map{Item(it.value.firstOrNull(),it.key.ifBlank{"Other"},it.value.size,it.key.ifBlank{"@other"})}+tagAlbums
        if(next==items)return
        items=next;notifyDataSetChanged()
    }
    fun retryThumbnails(){cells.forEach{it.cancel()};cache.evictAll();workers.purge();notifyDataSetChanged()}
    private var shownSelectionMode=false
    fun scrollLabel(position:Int)=items.getOrNull(position)?.let{it.photo?.let{photo->GalleryDates.label(photo.dateTakenMillis)}?:it.title}.orEmpty()
    fun photoKey(position:Int)=items.getOrNull(position)?.takeIf{it.album==null}?.photo?.uri?.toString()
    fun setSelection(keys:Set<String>){
        if(selected==keys && shownSelectionMode==selectionMode)return
        val previous=selected;selected=keys
        if(shownSelectionMode!=selectionMode){shownSelectionMode=selectionMode;notifyItemRangeChanged(0,itemCount,"selection")}
        else items.forEachIndexed{index,item->val key=item.photo?.uri.toString();if((key in previous)!=(key in keys))notifyItemChanged(index,"selection")}
    }
    override fun onBindViewHolder(holder:Holder,position:Int,payloads:MutableList<Any>){
        if(payloads.isEmpty()){onBindViewHolder(holder,position);return}
        holder.image?.apply{checked=photoKey(position) in selected;selectable=selectionMode && items[position].album==null;invalidate()}
    }
    override fun getItemCount()=items.size
    override fun getItemViewType(position:Int)=if(isHeader(position))0 else if(items[position].album!=null)2 else 1
    override fun onCreateViewHolder(parent:ViewGroup,type:Int):Holder {
        if(type==0)return Holder(GalleryStyle.text(context,"",18f).apply{setPadding(GalleryStyle.dp(context,24),GalleryStyle.dp(context,22),0,GalleryStyle.dp(context,18));layoutParams=RecyclerView.LayoutParams(-1,-2)})
        val image=Cell(context).apply{scaleType=ImageView.ScaleType.CENTER_CROP;setBackgroundColor(GalleryStyle.panel(context));cells.add(this)}
        if(type==1){image.layoutParams=RecyclerView.LayoutParams(-1,-2).apply { val gap=GalleryStyle.dp(context,1).coerceAtLeast(1);setMargins(0,0,gap,gap) };return Holder(image,image)}
        image.background=GradientDrawable().apply{setColor(GalleryStyle.panel(context));cornerRadius=GalleryStyle.dp(context,16).toFloat()};image.clipToOutline=true
        val name=GalleryStyle.text(context,"",16f).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,GalleryStyle.dp(context,7),0,0)}
        val count=GalleryStyle.text(context,"",13f,GalleryStyle.muted(context))
        val root=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(GalleryStyle.dp(context,8),GalleryStyle.dp(context,12),GalleryStyle.dp(context,8),GalleryStyle.dp(context,12));addView(image,LinearLayout.LayoutParams(-1,-2));addView(name);addView(count);layoutParams=RecyclerView.LayoutParams(-1,-2)}
        return Holder(root,image,name,count)
    }
    private fun imageKey(photo:PhotoRecord)=PeopleData.access(context)+":"+(if(PhotoIndex.allowed(context))"full"else GalleryData.version.toString())+":"+photo.uri+":"+FaceStore.fingerprint(photo)
    override fun onBindViewHolder(holder:Holder,position:Int){
        val item=items[position]
        if(isHeader(position)){(holder.root as TextView).text=item.title;return}
        val cell=holder.image!!;cell.albumKey=item.album;val photo=item.photo;cell.video=photo?.isVideo==true
        holder.title?.text=item.title;holder.count?.text=item.count.toString()
        cell.checked=photo?.uri.toString() in selected;cell.selectable=selectionMode&&item.album==null;cell.invalidate()
        holder.root.contentDescription=if(item.album!=null)"${item.title}, ${item.count} items" else photo?.displayName
        holder.root.setOnClickListener{if(item.album!=null)albumClick(item.album,holder.root)else photo?.let(click)}
        holder.root.setOnLongClickListener{if(item.album==null&&photo!=null){longClick(photo);true}else false}
        val key=photo?.uri?.toString()
        val sourceKey=photo?.let(::imageKey)
        if(key!=null&&key==cell.key && sourceKey==cell.sourceKey)return
        cell.cancel();workers.purge();cell.key=key;cell.sourceKey=sourceKey;cell.failed=false
        if(photo==null){cell.setImageDrawable(GalleryStyle.icon(context,"photo",GalleryStyle.muted(context)));return}
        val cached=cache.get(sourceKey!!);cell.setImageBitmap(cached)
        if(cached!=null||closed)return
        val signal=CancellationSignal();cell.signal=signal;val token=cell.revision
        cell.job=workers.submit{
            val result=runCatching{
                GalleryMedia.thumbnail(context,photo,320,signal)
            }
            cell.post{if(!closed&&cell.revision==token&&!signal.isCanceled){result.onSuccess{if(sourceKey==imageKey(photo) && MediaAccess.allowed(context)){cache.put(sourceKey!!,it);cell.setImageBitmap(it)}}.onFailure{cell.failed=true;cell.invalidate()}}}
        }
    }
    override fun onViewRecycled(holder:Holder){holder.image?.albumKey=null;holder.image?.cancel();workers.purge()}
    fun close(){closed=true;cells.forEach{it.cancel()};cells.clear();workers.shutdownNow();cache.evictAll()}
}
