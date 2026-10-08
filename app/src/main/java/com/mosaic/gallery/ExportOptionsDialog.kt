package com.mosaic.gallery

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import java.util.concurrent.Executors

/** Measures compressed output off-thread; the same pipeline is used by Save copy. */
class ExportOptionsDialog(private val activity:Activity,percent:Int,quality:Int,private val render:()->Bitmap,private val confirmed:(Int,Int)->Unit):Dialog(activity){
    private val worker=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    @Volatile private var closed=false
    private var rendered:Bitmap?=null // Owned exclusively by worker.
    private var generation=0
    private var resolution=percent
    private var compression=quality
    private val size:TextView
    private val dimensions:TextView
    private val qualityBar:SeekBar
    private val note:TextView
    private val measure=Runnable{estimate()}
    init{
        val body=LinearLayout(activity).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),dp(12))
            background=GradientDrawable().apply{setColor(GalleryStyle.panel(activity));cornerRadius=dp(22).toFloat()}
        }
        val top=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        top.addView(GalleryStyle.action(activity,"back","Cancel",compact=true){dismiss()})
        top.addView(Space(activity),LinearLayout.LayoutParams(0,1,1f))
        top.addView(GalleryStyle.action(activity,"check","Apply export settings",selected=true,compact=true){confirmed(resolution,compression);dismiss()}.apply{
            minimumWidth=dp(64)
            background=GradientDrawable().apply{setColor(GalleryStyle.accent(activity));cornerRadius=dp(22).toFloat()}
            (getChildAt(0) as ImageView).setImageDrawable(GalleryStyle.icon(activity,"check",GalleryStyle.canvas(activity)))
        })
        body.addView(top)
        fun row(label:String):TextView{
            val row=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(10),0,dp(8))}
            row.addView(GalleryStyle.text(activity,label,16f),LinearLayout.LayoutParams(0,-2,1f))
            val value=GalleryStyle.text(activity,"…",14f,GalleryStyle.muted(activity));row.addView(value);body.addView(row);return value
        }
        size=row("Image size");body.addView(GalleryStyle.divider(activity));dimensions=row("Resolution")
        fun slider(value:Int,minimum:Int,maximum:Int,changed:(Int)->Unit):SeekBar{
            val bar=SeekBar(activity).apply{
                min=minimum;max=maximum;progress=value
                progressTintList=android.content.res.ColorStateList.valueOf(GalleryStyle.accent(activity))
                thumbTintList=progressTintList
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                    override fun onProgressChanged(b:SeekBar,p:Int,user:Boolean){if(user){changed(p);generation++;size.text="Calculating…";handler.removeCallbacks(measure);handler.postDelayed(measure,160)}}
                    override fun onStartTrackingTouch(b:SeekBar){}
                    override fun onStopTrackingTouch(b:SeekBar){handler.removeCallbacks(measure);estimate()}
                })
            };body.addView(bar,LinearLayout.LayoutParams(-1,dp(48)));return bar
        }
        slider(resolution,20,100){resolution=it}
        val labels=LinearLayout(activity)
        listOf("20%","40%","60%","80%","100%").forEach{labels.addView(GalleryStyle.text(activity,it,13f,GalleryStyle.muted(activity)).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,dp(20),1f))};body.addView(labels)
        body.addView(GalleryStyle.divider(activity));row("Image quality").visibility=android.view.View.GONE
        qualityBar=slider(compression,10,100){compression=it}
        val ends=LinearLayout(activity)
        ends.addView(GalleryStyle.text(activity,"Lowest",13f,GalleryStyle.muted(activity)),LinearLayout.LayoutParams(0,dp(22),1f))
        ends.addView(GalleryStyle.text(activity,"Highest",13f,GalleryStyle.muted(activity)).apply{gravity=Gravity.END},LinearLayout.LayoutParams(0,dp(22),1f));body.addView(ends)
        note=GalleryStyle.text(activity,"Estimated saved copy; 100% is the available export resolution.",12f,GalleryStyle.muted(activity));body.addView(note)
        setContentView(ScrollView(activity).apply{addView(body);isVerticalScrollBarEnabled=false})
        window?.apply{setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));setGravity(Gravity.TOP);setDimAmount(0.4f)}
        setOnDismissListener{
            closed=true;handler.removeCallbacks(measure)
            worker.execute{rendered?.recycle();rendered=null};worker.shutdown()
        }
        worker.execute{
            val result=runCatching{render()}
            if(closed){result.getOrNull()?.recycle();return@execute}
            rendered=result.getOrNull()
            activity.runOnUiThread{
                if(closed)return@runOnUiThread
                val bitmap=rendered
                if(bitmap==null){size.text="Unavailable";note.text="Could not prepare an export estimate."}
                else{qualityBar.isEnabled=!bitmap.hasAlpha();if(bitmap.hasAlpha())note.text="Lossless PNG preserves transparency; resolution still changes file size.";estimate()}
            }
        }
    }
    override fun show(){super.show();window?.setLayout(-1,-2)}
    private fun estimate(){
        if(closed)return
        val token=generation;val percent=resolution;val quality=compression
        worker.execute{
            val base=rendered ?: return@execute
            if(closed)return@execute
            val resized=PhotoImages.resized(base,percent)
            val result=runCatching{PhotoImages.encodedSize(resized,quality)}
            val width=resized.width;val height=resized.height
            if(resized!==base)resized.recycle()
            activity.runOnUiThread{if(!closed && token==generation){dimensions.text="$width × $height";size.text=result.fold({String.format(java.util.Locale.getDefault(),"About %.3f MB",it/1_000_000.0)},{"Unavailable"})}}
        }
    }
    private fun dp(value:Int)=GalleryStyle.dp(activity,value)
}
