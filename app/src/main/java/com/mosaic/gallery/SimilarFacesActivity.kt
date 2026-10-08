package com.mosaic.gallery

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.os.CancellationSignal
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Ranked candidates only: no threshold, automatic merge, names or identity labels. */
class SimilarFacesActivity:Activity(){
    private val worker=Executors.newSingleThreadExecutor()
    private var active=false
    private var epoch=0
    private var query:CancellationSignal?=null
    private lateinit var status:TextView
    private lateinit var adapter:Matches
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(GalleryStyle.canvas(context))}
        val bar=GalleryStyle.bar(this);bar.addView(GalleryStyle.action(this,"back","Back",compact=true){finish()});bar.addView(GalleryStyle.text(this,"Similar faces",24f));root.addView(bar)
        status=GalleryStyle.text(this,"Finding candidates…",14f,GalleryStyle.muted(this)).apply{setPadding(dp(20),dp(12),dp(20),dp(12))};root.addView(status)
        adapter=Matches();root.addView(RecyclerView(this).apply{layoutManager=LinearLayoutManager(this@SimilarFacesActivity);adapter=this@SimilarFacesActivity.adapter;itemAnimator=null},LinearLayout.LayoutParams(-1,0,1f))
        Ui.insets(this,root);setContentView(root);Ui.back(this){finish()}
    }
    override fun onResume(){
        super.onResume();active=true;val token=++epoch;val uri=intent.data?:run{finish();return};val ordinal=intent.getIntExtra("ordinal",-1)
        adapter.submit(emptyList());status.text="Finding candidates…";val signal=CancellationSignal();query=signal
        worker.execute{
            val result=runCatching{
                val photos=GalleryData.load(this,signal).photos;signal.throwIfCanceled()
                FaceStore(this).use{store->
                    val valid=photos.map{it.uri.toString()}.toSet()-store.pending(photos).map{it.uri.toString()}.toSet();val available=uri.toString() in valid && store.signature(uri.toString(),ordinal)!=null
                    val byUri=photos.filter{it.uri.toString() in valid}.associateBy{it.uri.toString()}
                    available to store.similar(uri.toString(),ordinal,eligible=valid,keepGoing={active && !signal.isCanceled}).mapNotNull{match->byUri[match.uri]?.let{match to it}}
                }
            }
            runOnUiThread{if(active && token==epoch && !isDestroyed)result.onSuccess{(ready,rows)->
                adapter.submit(rows);status.text=if(!ready)"This face is being recognised automatically. Weak or unalignable faces are skipped."else if(rows.isEmpty())"No candidates yet. New photos are recognised automatically."else "${rows.size} nearest candidates · Similarity is a score, not a confirmed identity. Tap a face to review the photo."
            }.onFailure{status.text="Could not compare accessible photos. Check photo permission and retry."}}
        }
    }
    override fun onPause(){active=false;epoch++;query?.cancel();adapter.submit(emptyList());super.onPause()}
    override fun onDestroy(){worker.shutdown();adapter.close();super.onDestroy()}
    private fun dp(value:Int)=GalleryStyle.dp(this,value)
    private inner class Matches:RecyclerView.Adapter<Matches.Holder>(){
        private val images=Executors.newSingleThreadExecutor()
        private var rows=emptyList<Pair<FaceVectors.Match,PhotoRecord>>()
        private var closed=false
        private val holders=mutableSetOf<Holder>()
        inner class Holder(val root:LinearLayout,val image:FaceCrop,val label:TextView):RecyclerView.ViewHolder(root){var job:Future<*>?=null;var token=0;var bitmap:Bitmap?=null
            fun clear(){token++;job?.cancel(true);job=null;image.show(null,null);bitmap?.recycle();bitmap=null}
        }
        fun submit(next:List<Pair<FaceVectors.Match,PhotoRecord>>){holders.forEach{it.clear()};rows=next;notifyDataSetChanged()}
        override fun getItemCount()=rows.size
        override fun onCreateViewHolder(parent:ViewGroup,viewType:Int):Holder{
            val root=LinearLayout(this@SimilarFacesActivity).apply{gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(8));layoutParams=RecyclerView.LayoutParams(-1,dp(104))}
            val image=FaceCrop(this@SimilarFacesActivity);root.addView(image,LinearLayout.LayoutParams(dp(80),dp(80)))
            val label=GalleryStyle.text(this@SimilarFacesActivity,"",15f).apply{setPadding(dp(16),0,0,0);maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END};root.addView(label,LinearLayout.LayoutParams(0,-2,1f))
            return Holder(root,image,label).also{holders+=it}
        }
        override fun onBindViewHolder(holder:Holder,position:Int){
            holder.clear();val (match,photo)=rows[position];val token=holder.token
            holder.label.text="${position+1}. ${photo.displayName}\nSimilarity ${String.format(java.util.Locale.getDefault(),"%.3f",match.similarity)} · ${if(match.face.authority=="Anchor")"Clear"else"Usable"}"
            holder.root.contentDescription=holder.label.text;holder.root.setOnClickListener{startActivity(Intent(this@SimilarFacesActivity,FaceReviewActivity::class.java).setData(photo.uri).putExtra("name",photo.displayName))}
            holder.job=images.submit{
                val bitmap=runCatching{PhotoImages.decode(this@SimilarFacesActivity,photo.uri,480,250_000)}.getOrNull()
                holder.image.post{if(closed || !active || holder.token!=token)bitmap?.recycle()else{holder.bitmap=bitmap;holder.image.show(bitmap,match.face)}}
            }
        }
        override fun onViewRecycled(holder:Holder){holder.clear()}
        fun close(){closed=true;holders.forEach{it.clear()};holders.clear();images.shutdownNow()}
    }
}

internal class FaceCrop(context:android.content.Context):android.view.View(context){
    companion object{
        private fun bounds(image:Bitmap,observation:FaceObservation,source:android.graphics.Rect){
            val side=maxOf((observation.right-observation.left)*image.width,(observation.bottom-observation.top)*image.height)*1.3f
            val cx=(observation.left+observation.right)*image.width/2;val cy=(observation.top+observation.bottom)*image.height/2
            source.set((cx-side/2).toInt().coerceIn(0,image.width-1),(cy-side/2).toInt().coerceIn(0,image.height-1),(cx+side/2).toInt().coerceIn(1,image.width),(cy+side/2).toInt().coerceIn(1,image.height))
        }
        fun thumbnail(image:Bitmap,face:FaceObservation):Bitmap{
            val crop=android.graphics.Rect();bounds(image,face,crop)
            return Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888).also{bitmap->
                android.graphics.Canvas(bitmap).drawBitmap(image,crop,android.graphics.Rect(0,0,192,192),android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            }
        }
    }
    private var bitmap:Bitmap?=null
    private var face:FaceObservation?=null
    override fun onMeasure(w:Int,h:Int){val side=MeasureSpec.getSize(w).coerceAtLeast(1);setMeasuredDimension(side,side)}
    private val source=android.graphics.Rect();private val destination=RectF();private val outline=android.graphics.Path();private val paint=android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    fun show(image:Bitmap?,observation:FaceObservation?){bitmap=image;face=observation;invalidate()}
    override fun onDraw(canvas:android.graphics.Canvas){
        val image=bitmap?:return;val observation=face?:return;if(image.isRecycled)return
        bounds(image,observation,source)
        destination.set(0f,0f,width.toFloat(),height.toFloat())
        val save=canvas.save();if(clipToOutline){outline.reset();outline.addOval(destination,android.graphics.Path.Direction.CW);canvas.clipPath(outline)}
        canvas.drawBitmap(image,source,destination,paint);canvas.restoreToCount(save)
    }
}
