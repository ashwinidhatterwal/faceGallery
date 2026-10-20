package com.mosaic.gallery

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.os.Bundle
import android.os.CancellationSignal
import android.view.View
import android.widget.*
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

internal data class MediaPickerRequest(val mime:String,val types:List<String>,val multiple:Boolean){
    fun accepts(photo:PhotoRecord)=ClipDescription.compareMimeTypes(photo.mimeType,mime) &&
        (types.isEmpty() || types.any{ClipDescription.compareMimeTypes(photo.mimeType,it)})
    companion object{
        fun from(intent:Intent):MediaPickerRequest {
            val requested=intent.type?.lowercase()
            val mime=when(requested){"vnd.android.cursor.dir/image"->"image/*";"vnd.android.cursor.dir/video"->"video/*";null->if(intent.data?.pathSegments?.contains("video")==true)"video/*"else"image/*";else->requested}
            return MediaPickerRequest(mime,intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.map{it.lowercase()}.orEmpty(),intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false))
        }
    }
}

/** Standard gallery selection for other apps; returns only explicitly selected URI grants. */
class MediaPickerActivity:Activity(){
    private val worker=Executors.newSingleThreadExecutor()
    private lateinit var request:MediaPickerRequest
    private lateinit var adapter:PhotoGridAdapter
    private lateinit var status:TextView
    private lateinit var done:View
    private lateinit var permission:View
    private val selected=linkedSetOf<String>()
    private var photos=emptyList<PhotoRecord>()
    private var signal:CancellationSignal?=null
    private var active=false
    override fun onCreate(state:Bundle?){
        super.onCreate(state);setResult(RESULT_CANCELED)
        if(intent.action !in setOf(Intent.ACTION_PICK,Intent.ACTION_GET_CONTENT)){finish();return}
        request=MediaPickerRequest.from(intent)
        selected.addAll(state?.getStringArrayList("selected").orEmpty())
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(GalleryStyle.canvas(context))}
        val bar=GalleryStyle.bar(this)
        bar.addView(GalleryStyle.action(this,"back","Cancel",compact=true){finish()})
        bar.addView(GalleryStyle.text(this,if(request.multiple)"Select media"else if(request.mime.startsWith("video/"))"Select video"else"Select photo",24f),LinearLayout.LayoutParams(0,-2,1f))
        done=GalleryStyle.action(this,"select","Done",compact=true){complete()}.apply{visibility=if(request.multiple)View.VISIBLE else View.GONE};bar.addView(done);root.addView(bar)
        status=GalleryStyle.text(this,"",14f,GalleryStyle.muted(this)).apply{setPadding(24,12,24,12)};root.addView(status)
        permission=GalleryStyle.action(this,"photo","Allow media access"){requestPermissions(MediaAccess.permissions(),701)};root.addView(permission)
        adapter=PhotoGridAdapter(this,{photo->
            if(request.multiple){val key=photo.uri.toString();if(!selected.add(key))selected.remove(key);adapter.setSelection(selected.toSet());updateCount()}
            else {selected.clear();selected.add(photo.uri.toString());complete()}
        },{})
        adapter.selectionMode=request.multiple
        root.addView(RecyclerView(this).apply{layoutManager=GridLayoutManager(this@MediaPickerActivity,4);adapter=this@MediaPickerActivity.adapter;itemAnimator=null},LinearLayout.LayoutParams(-1,0,1f))
        Ui.insets(this,root);setContentView(root);Ui.back(this){finish()}
    }
    override fun onResume(){super.onResume();active=true;load()}
    private fun updateCount(){done.isEnabled=selected.isNotEmpty();done.alpha=if(selected.isEmpty()).4f else 1f;status.text=if(selected.isNotEmpty())"${selected.size} selected"else if(photos.isEmpty())"No matching media"else ""}
    private fun load(){
        signal?.cancel();permission.visibility=if(MediaAccess.allowed(this))View.GONE else View.VISIBLE
        if(!MediaAccess.allowed(this)){photos=emptyList();selected.clear();adapter.submitList(emptyList());status.text="Allow access to choose media";return}
        val query=CancellationSignal();signal=query;status.text="Loading…"
        worker.execute{
            val result=runCatching{GalleryData.load(this,query,true).photos.filter(request::accepts)}
            runOnUiThread{if(active && !query.isCanceled && !isDestroyed){
                result.onSuccess{photos=it;selected.retainAll(it.map{p->p.uri.toString()}.toSet());adapter.submitList(it);adapter.setSelection(selected.toSet());updateCount()}
                    .onFailure{status.text="Could not load media"}
            }}
        }
    }
    private fun complete(){
        val chosen=photos.filter{it.uri.toString() in selected};if(chosen.isEmpty())return
        val first=chosen.first();val clip=ClipData.newRawUri("Selected media",first.uri)
        chosen.drop(1).forEach{clip.addItem(ClipData.Item(it.uri))}
        setResult(RESULT_OK,Intent().setDataAndType(first.uri,if(chosen.map{it.mimeType}.distinct().size==1)first.mimeType else "*/*").apply{
            clipData=clip;addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        });finish()
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(code,permissions,results);if(code==701 && active)load()}
    override fun onSaveInstanceState(state:Bundle){state.putStringArrayList("selected",ArrayList(selected));super.onSaveInstanceState(state)}
    override fun onPause(){active=false;signal?.cancel();super.onPause()}
    override fun onDestroy(){signal?.cancel();if(::adapter.isInitialized)adapter.close();worker.shutdown();super.onDestroy()}
}
