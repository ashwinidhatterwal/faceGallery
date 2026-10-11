package com.mosaic.gallery

import android.content.Context
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.*
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Recycled tag folders with four round previews, using accessible media only. */
class TagRail(context:Context,private val open:(String,View)->Unit):LinearLayout(context) {
    data class Folder(val key:String,val name:String,val count:Int,val covers:List<PhotoRecord>)
    companion object {
        fun folders(context:Context,photos:List<PhotoRecord>):List<Folder> {
            val tags=MediaTags.read(context);val groups=linkedMapOf<String,MutableList<PhotoRecord>>()
            photos.forEach{photo->tags.media[photo.uri.toString()].orEmpty().forEach{tag->groups.getOrPut(tag){mutableListOf()}.add(photo)}}
            return groups.map{(key,list)->Folder(MediaTags.PREFIX+key,tags.names[key].orEmpty(),list.size,list.take(4))}.sortedBy{it.name.lowercase(java.util.Locale.ROOT)}
        }
    }
    private val pool=Executors.newFixedThreadPool(2)
    private var closed=false;private var items=emptyList<Folder>()
    private val cache=object:android.util.LruCache<String,Bitmap>(4*1024*1024){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount}
    private val holders=mutableSetOf<Holder>()
    private val adapter=object:RecyclerView.Adapter<Holder>() {
        override fun getItemCount()=items.size
        override fun onCreateViewHolder(parent:ViewGroup,type:Int):Holder {
            val card=LinearLayout(context).apply{orientation=VERTICAL;gravity=Gravity.CENTER;setPadding(dp(8),dp(6),dp(8),dp(6));layoutParams=RecyclerView.LayoutParams(dp(112),dp(146))}
            val collage=GridLayout(context).apply{columnCount=2;rowCount=2}
            val images=List(4){ImageView(context).apply{scaleType=ImageView.ScaleType.CENTER_CROP;GalleryStyle.roundFace(this)}.also{collage.addView(it,GridLayout.LayoutParams().apply{width=dp(42);height=dp(42);setMargins(dp(2),dp(2),dp(2),dp(2))})}}
            card.addView(collage)
            val title=GalleryStyle.text(context,"",13f).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;gravity=Gravity.CENTER}
            val count=GalleryStyle.text(context,"",11f,GalleryStyle.muted(context)).apply{gravity=Gravity.CENTER}
            card.addView(title,LayoutParams(-1,-2).apply{topMargin=dp(4)});card.addView(count,LayoutParams(-1,-2))
            return Holder(card,images,title,count).also{holders+=it}
        }
        override fun onBindViewHolder(holder:Holder,position:Int){
            val item=items[position];holder.clear();holder.title.text=item.name;holder.count.text=item.count.toString()
            holder.itemView.contentDescription="${item.name}, ${item.count} items";holder.itemView.setOnClickListener{open(item.key,holder.itemView)}
            item.covers.forEachIndexed{slot,photo->
                val image=holder.images[slot];image.visibility=View.VISIBLE
                val key=PeopleData.access(context)+photo.uri+FaceStore.fingerprint(photo);val bitmap=cache.get(key);image.setImageBitmap(bitmap)
                if(bitmap==null){val signal=CancellationSignal();holder.signals+=signal;val token=holder.token
                    holder.jobs+=pool.submit{val result=runCatching{GalleryMedia.thumbnail(context,photo,128,signal)}.getOrNull()
                        image.post{if(!closed && holder.token==token && !signal.isCanceled && MediaAccess.allowed(context) && key==PeopleData.access(context)+photo.uri+FaceStore.fingerprint(photo) && result!=null){cache.put(key,result);image.setImageBitmap(result)}}}
                }
            }
        }
        override fun onViewRecycled(holder:Holder){holder.clear()}
    }
    private val list=RecyclerView(context).apply{layoutManager=LinearLayoutManager(context,RecyclerView.HORIZONTAL,false);adapter=this@TagRail.adapter;itemAnimator=null;clipToPadding=false;setPadding(dp(12),0,dp(12),0)}
    init{orientation=VERTICAL;addView(GalleryStyle.text(context,"Tags",16f).apply{setPadding(dp(24),dp(2),dp(24),dp(2))});addView(list,LayoutParams(-1,dp(146)));visibility=GONE}
    private class Holder(view:View,val images:List<ImageView>,val title:TextView,val count:TextView):RecyclerView.ViewHolder(view){
        var token=0;val jobs=mutableListOf<Future<*>>();val signals=mutableListOf<CancellationSignal>()
        fun clear(){token++;signals.forEach{it.cancel()};signals.clear();jobs.forEach{it.cancel(true)};jobs.clear();images.forEach{it.setImageDrawable(null);it.visibility=View.INVISIBLE}}
    }
    fun submit(photos:List<PhotoRecord>,show:Boolean){
        if(!show){visibility=GONE;return}
        val next=folders(context,photos)
        visibility=if(next.isEmpty())GONE else VISIBLE
        if(items==next)return
        val old=items;val diff=DiffUtil.calculateDiff(object:DiffUtil.Callback(){
            override fun getOldListSize()=old.size;override fun getNewListSize()=next.size
            override fun areItemsTheSame(a:Int,b:Int)=old[a].key==next[b].key
            override fun areContentsTheSame(a:Int,b:Int)=old[a]==next[b]
        });items=next;diff.dispatchUpdatesTo(adapter)
    }
    fun close(){closed=true;holders.forEach{it.clear()};pool.shutdownNow();cache.evictAll()}
    private fun dp(value:Int)=GalleryStyle.dp(context,value)
}
