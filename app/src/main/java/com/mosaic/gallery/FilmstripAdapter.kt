package com.mosaic.gallery

import android.content.Context
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

class FilmstripAdapter(private val context:Context,private val click:(PhotoRecord)->Unit):RecyclerView.Adapter<FilmstripAdapter.Holder>() {
    private var photos=emptyList<PhotoRecord>();private var closed=false
    private val worker=java.util.concurrent.ThreadPoolExecutor(2,2,0L,java.util.concurrent.TimeUnit.MILLISECONDS,java.util.concurrent.LinkedBlockingQueue())
    private val cache=object:android.util.LruCache<String,android.graphics.Bitmap>(2*1024*1024){override fun sizeOf(key:String,value:android.graphics.Bitmap)=value.allocationByteCount}
    class Holder(val image:ImageView):RecyclerView.ViewHolder(image){var key="";var job:java.util.concurrent.Future<*>?=null}
    fun submit(list:List<PhotoRecord>){if(photos==list)return;photos=list;notifyDataSetChanged()}
    override fun getItemCount()=photos.size
    override fun onCreateViewHolder(parent:ViewGroup,type:Int)=Holder(PhotoGridAdapter.Cell(context).apply{scaleType=ImageView.ScaleType.CENTER_CROP;layoutParams=RecyclerView.LayoutParams(GalleryStyle.dp(context,48),GalleryStyle.dp(context,44)).apply{setMargins(GalleryStyle.dp(context,1),GalleryStyle.dp(context,2),GalleryStyle.dp(context,1),GalleryStyle.dp(context,2))}})
    override fun onBindViewHolder(holder:Holder,position:Int){
        val photo=photos[position];(holder.image as PhotoGridAdapter.Cell).video=photo.isVideo;holder.image.invalidate();val key=photo.uri.toString();holder.job?.cancel(true);worker.purge();holder.key=key
        holder.image.setBackgroundColor(GalleryStyle.canvas(context));holder.image.setPadding(0,0,0,0)
        holder.image.contentDescription=photo.displayName;holder.image.setOnClickListener{click(photo)};val cached=cache.get(key);holder.image.setImageBitmap(cached)
        if(cached==null)holder.job=worker.submit{val bitmap=runCatching{GalleryMedia.thumbnail(context,photo,120)}.getOrNull()
            if(bitmap!=null&&!Thread.currentThread().isInterrupted)holder.image.post{if(!closed&&holder.key==key){cache.put(key,bitmap);holder.image.setImageBitmap(bitmap)}}}
    }
    override fun onViewRecycled(holder:Holder){holder.key="";holder.job?.cancel(true);holder.image.setImageDrawable(null)}
    fun close(){closed=true;worker.shutdownNow();cache.evictAll()}
}
