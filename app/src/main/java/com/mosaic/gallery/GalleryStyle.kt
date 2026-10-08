package com.mosaic.gallery

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.*

object GalleryStyle {
    fun button(context:Context,label:String,primary:Boolean=false,action:()->Unit)=Button(context).apply{
        text=label;isAllCaps=false;textSize=15f;minimumHeight=dp(context,48);minWidth=0
        setTextColor(if(primary)canvas(context) else textColor(context));contentDescription=label
        background=GradientDrawable().apply{setColor(if(primary)accent(context)else surface(context));cornerRadius=dp(context,14).toFloat()}
        setOnClickListener{action()}
    }
    fun dialog(activity:android.app.Activity,root:View)=android.app.AlertDialog.Builder(activity).setView(root).create().apply{
        show();window?.apply{setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));setLayout(minOf(activity.resources.displayMetrics.widthPixels-dp(activity,32),dp(activity,560)),-2)}
    }
    fun panelRoot(context:Context)=LinearLayout(context).apply{
        orientation=LinearLayout.VERTICAL;setPadding(dp(context,20),dp(context,20),dp(context,20),dp(context,20))
        background=GradientDrawable().apply{setColor(panel(context));cornerRadius=dp(context,24).toFloat()}
    }
    fun roundFace(view:View){view.background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(panel(view.context))};view.clipToOutline=true}
    fun accent(context:Context)=context.getColor(R.color.gallery_accent)
    fun surface(context:Context)=context.getColor(R.color.gallery_surface)
    fun canvas(context:Context)=context.getColor(R.color.gallery_canvas)
    fun muted(context:Context)=context.getColor(R.color.gallery_muted)
    fun textColor(context:Context)=context.getColor(R.color.gallery_text)
    fun iconColor(context:Context)=context.getColor(R.color.gallery_icon)
    fun panel(context:Context)=context.getColor(R.color.gallery_panel)
    fun dividerColor(context:Context)=context.getColor(R.color.gallery_divider)
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun text(context: Context, value: String, size: Float = 14f, color: Int = textColor(context)) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
    }
    fun action(context: Context, icon: String, label: String, selected: Boolean = false, compact: Boolean = false, action: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            val color = if (selected) accent(context) else iconColor(context)
            background = RippleDrawable(ColorStateList.valueOf(if(context.resources.getBoolean(R.bool.gallery_light_bars))0x14000000 else 0x33ffffff), null,
                GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(context, 18).toFloat() })
            minimumWidth = dp(context, 48); minimumHeight = dp(context, if (compact) 48 else 58)
            addView(ImageView(context).apply { setImageDrawable(GalleryStyle.icon(context,icon,color)) },
                LinearLayout.LayoutParams(dp(context, 24), dp(context, 24)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            if (!compact) addView(text(context, label, 12f, color).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; maxLines = 1 },
                LinearLayout.LayoutParams(-1, dp(context, 17)).apply { topMargin = dp(context, 3) })
            contentDescription = label; isFocusable = true; setOnClickListener { action() }
        }
    fun bar(context: Context): LinearLayout = LinearLayout(context).apply {
        gravity = Gravity.CENTER; setBackgroundColor(canvas(context))
        setPadding(dp(context, 12), dp(context, 2), dp(context, 12), dp(context, 2))
    }
    fun divider(context:Context)=View(context).apply{setBackgroundColor(dividerColor(context));layoutParams=LinearLayout.LayoutParams(-1,dp(context,1).coerceAtLeast(1))}
    fun add(bar: LinearLayout, view: View) { bar.addView(view, LinearLayout.LayoutParams(0, -2, 1f)) }
    fun icon(context:Context,name:String,tint:Int=iconColor(context)):Drawable {
        val resource=when(name){
            "back" -> R.drawable.ic_back
            "close" -> R.drawable.ic_close
            "check" -> R.drawable.ic_check
            "select" -> R.drawable.ic_select
            "search" -> R.drawable.ic_search
            "more" -> R.drawable.ic_more
            "down" -> R.drawable.ic_down
            "info" -> R.drawable.ic_info
            "heart" -> R.drawable.ic_heart
            "heartFilled" -> R.drawable.ic_heartfilled
            "delete" -> R.drawable.ic_delete
            "share" -> R.drawable.ic_share
            "edit" -> R.drawable.ic_edit
            "album" -> R.drawable.ic_album
            "personAdd" -> R.drawable.ic_personadd
            "crop" -> R.drawable.ic_crop
            "rotate" -> R.drawable.ic_rotate
            "undo" -> R.drawable.ic_undo
            "redo" -> R.drawable.ic_redo
            "adjust" -> R.drawable.ic_adjust
            "filters" -> R.drawable.ic_filters
            "photo" -> R.drawable.ic_photo
            "custom" -> R.drawable.ic_custom
            "ratioSquare" -> R.drawable.ic_ratiosquare
            "ratioPortrait" -> R.drawable.ic_ratioportrait
            "ratioLandscape" -> R.drawable.ic_ratiolandscape
            "original" -> R.drawable.ic_original
            else -> R.drawable.ic_photo
        }
        return context.getDrawable(resource)!!.mutate().apply{setTint(tint)}
    }
    fun favorites(context: Context): Set<String> = context.getSharedPreferences("gallery", Context.MODE_PRIVATE)
        .getStringSet("favorites", emptySet())!!.toSet()
    fun favorite(context: Context, uri: String): Boolean {
        val set = favorites(context).toMutableSet(); val added = set.add(uri); if (!added) set.remove(uri)
        context.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().putStringSet("favorites", set).apply()
        return added
    }
}

