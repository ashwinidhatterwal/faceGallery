package com.mosaic.gallery

import android.app.Activity
import android.graphics.*
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import java.util.concurrent.Executors

class FaceReviewActivity:Activity(){
    private val worker=Executors.newSingleThreadExecutor()
    private var bitmap:Bitmap?=null
    private var active=false
    private var epoch=0
    private lateinit var preview:FacePreview
    private lateinit var caption:android.widget.TextView
    override fun onCreate(state:Bundle?){
        super.onCreate(state);val uri=intent.data?:run{finish();return}
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(GalleryStyle.canvas(context))}
        val top=GalleryStyle.bar(this);top.addView(GalleryStyle.action(this,"back","Back",compact=true){finish()})
        top.addView(GalleryStyle.text(this,"Detected faces",22f),LinearLayout.LayoutParams(0,-2,1f))
        top.addView(GalleryStyle.action(this,"photo","Open photo",compact=true){startActivity(android.content.Intent(this,PhotoActivity::class.java).setData(uri).putExtra("name",intent.getStringExtra("name")))})
        top.addView(GalleryStyle.action(this,"info","Photo details",compact=true){PhotoDetails.show(this,PhotoRecord(0,uri,intent.getStringExtra("name").orEmpty(),0,0,0))});root.addView(top)
        preview=FacePreview(this).apply{onFace={ordinal,face->
            android.app.AlertDialog.Builder(this@FaceReviewActivity).setTitle("${face.authority} · ${(face.score*100).toInt()}/100 quality")
                .setMessage("Pose: ${face.yaw.toInt()}° yaw, ${face.pitch.toInt()}° pitch, ${face.roll.toInt()}° roll\nSharpness estimate: ${face.sharpness.toInt()}")
                .setNegativeButton("Close",null).setPositiveButton("Similar faces"){_,_->startActivity(android.content.Intent(this@FaceReviewActivity,SimilarFacesActivity::class.java).setData(uri).putExtra("ordinal",ordinal))}.show()
        }};root.addView(preview,LinearLayout.LayoutParams(-1,0,1f))
        caption=GalleryStyle.text(this,"Loading…",14f).apply{setPadding(20,12,20,16)};root.addView(caption)
        Ui.insets(this,root);setContentView(root);Ui.back(this){finish()}
    }
    override fun onResume(){
        super.onResume();active=true;val token=++epoch;val uri=intent.data?:return
        preview.show(null,emptyList());bitmap?.recycle();bitmap=null;caption.text="Loading…"
        worker.execute{
            val result=runCatching{val faces=FaceStore(this).use{it.observations(uri.toString())};PhotoImages.decode(this,uri,1600,2_000_000) to faces}
            runOnUiThread{
                if(isDestroyed || !active || token!=epoch){result.getOrNull()?.first?.recycle();return@runOnUiThread}
                result.onSuccess{(image,faces)->bitmap=image;preview.show(image,faces);caption.text="${faces.size} faces · Clear / Usable / Weak\nQuality is an estimate, not an identity match. Tap a box for details or similar faces."}
                    .onFailure{caption.text="Photo is unavailable or photo permission changed."}
            }
        }
    }
    override fun onPause(){active=false;preview.show(null,emptyList());bitmap?.recycle();bitmap=null;super.onPause()}
    override fun onDestroy(){worker.shutdown();bitmap?.recycle();bitmap=null;super.onDestroy()}
}

class FacePreview(context:android.content.Context):View(context){
    var onFace:((Int,FaceObservation)->Unit)?=null
    private var bitmap:Bitmap?=null
    private var faces=emptyList<FaceObservation>()
    private val image=RectF();private val box=RectF();private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    fun show(image:Bitmap?,observations:List<FaceObservation>){bitmap=image;faces=observations;invalidate()}
    private fun rect(face:FaceObservation){box.set(image.left+image.width()*face.left,image.top+image.height()*face.top,image.left+image.width()*face.right,image.top+image.height()*face.bottom)}
    override fun onDraw(canvas:Canvas){
        val photo=bitmap?:return;if(photo.isRecycled)return
        val scale=minOf((width-32*resources.displayMetrics.density).coerceAtLeast(1f)/photo.width,height.toFloat()/photo.height)
        val w=photo.width*scale;val h=photo.height*scale;image.set((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)
        paint.style=Paint.Style.FILL;canvas.drawBitmap(photo,null,image,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=2*resources.displayMetrics.density
        faces.forEach{face->rect(face);paint.color=when(face.authority){"Anchor"->Color.rgb(95,202,137);"Support"->GalleryStyle.accent(context);else->GalleryStyle.muted(context)};canvas.drawRoundRect(box,4f,4f,paint)}
    }
    override fun onTouchEvent(event:android.view.MotionEvent):Boolean{
        if(event.actionMasked==android.view.MotionEvent.ACTION_UP){performClick();val index=faces.indexOfFirst{rect(it);box.contains(event.x,event.y)};if(index>=0)onFace?.invoke(index,faces[index])}
        return true
    }
    override fun performClick():Boolean{super.performClick();return true}
}
