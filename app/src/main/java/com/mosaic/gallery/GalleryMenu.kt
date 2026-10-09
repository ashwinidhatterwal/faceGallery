package com.mosaic.gallery

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** One theme-aware action panel for gallery, people and viewer options. */
object GalleryMenu {
    data class Action(val icon:String,val label:String,val run:()->Unit)
    fun row(activity:Activity,action:Action):LinearLayout = LinearLayout(activity).apply{
        gravity=Gravity.CENTER_VERTICAL;minimumHeight=GalleryStyle.dp(activity,56)
        setPadding(GalleryStyle.dp(activity,12),GalleryStyle.dp(activity,8),GalleryStyle.dp(activity,12),GalleryStyle.dp(activity,8))
        background=GalleryStyle.action(activity,action.icon,action.label,compact=true){}.background
        addView(ImageView(activity).apply{setImageDrawable(GalleryStyle.icon(activity,action.icon))},LinearLayout.LayoutParams(GalleryStyle.dp(activity,24),GalleryStyle.dp(activity,24)))
        addView(GalleryStyle.text(activity,action.label,16f),LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=GalleryStyle.dp(activity,18)})
        isFocusable=true;contentDescription=action.label;setOnClickListener{action.run()}
    }
    fun show(activity:Activity,title:String,actions:List<Action>):Dialog {
        val dialog=Dialog(activity)
        val root=GalleryStyle.panelRoot(activity).apply{background=GradientDrawable().apply{setColor(GalleryStyle.surface(activity));cornerRadius=GalleryStyle.dp(activity,24).toFloat()}}
        val header=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        header.addView(GalleryStyle.text(activity,title,22f),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(GalleryStyle.action(activity,"close","Close menu",compact=true){dialog.dismiss()});root.addView(header)
        val items=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
        actions.forEach{a->items.addView(row(activity,a.copy(run={dialog.dismiss();a.run()})))}
        root.addView(ScrollView(activity).apply{addView(items);isFillViewport=false},LinearLayout.LayoutParams(-1,-2))
        dialog.setContentView(root);dialog.setCanceledOnTouchOutside(true);dialog.show()
        dialog.window?.apply{setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));setLayout(activity.resources.displayMetrics.widthPixels-GalleryStyle.dp(activity,24),-2);setGravity(Gravity.BOTTOM);setWindowAnimations(R.style.GalleryMenuAnimation);attributes=attributes.apply{y=GalleryStyle.dp(activity,12);dimAmount=.32f};addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)}
        return dialog
    }
}
