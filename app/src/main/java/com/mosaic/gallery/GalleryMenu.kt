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
        gravity=Gravity.CENTER_VERTICAL;minimumHeight=GalleryStyle.dp(activity,48)
        setPadding(GalleryStyle.dp(activity,12),GalleryStyle.dp(activity,4),GalleryStyle.dp(activity,12),GalleryStyle.dp(activity,4))
        background=GalleryStyle.action(activity,action.icon,action.label,compact=true){}.background
        addView(ImageView(activity).apply{setImageDrawable(GalleryStyle.icon(activity,action.icon))},LinearLayout.LayoutParams(GalleryStyle.dp(activity,20),GalleryStyle.dp(activity,20)))
        addView(GalleryStyle.text(activity,action.label,15f),LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=GalleryStyle.dp(activity,12)})
        isFocusable=true;contentDescription=action.label;setOnClickListener{action.run()}
    }
    fun show(activity:Activity,title:String,actions:List<Action>,anchor:View?=null):Dialog {
        val dialog=Dialog(activity)
        val root=GalleryStyle.panelRoot(activity).apply{setPadding(GalleryStyle.dp(activity,8),GalleryStyle.dp(activity,8),GalleryStyle.dp(activity,8),GalleryStyle.dp(activity,8));accessibilityPaneTitle=title;background=GradientDrawable().apply{setColor(GalleryStyle.surface(activity));cornerRadius=GalleryStyle.dp(activity,16).toFloat()}}
        val header=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        header.addView(GalleryStyle.text(activity,title,22f),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(GalleryStyle.action(activity,"close","Close menu",compact=true){dialog.dismiss()});if(anchor==null)root.addView(header)
        val items=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
        actions.forEach{a->items.addView(row(activity,a.copy(run={dialog.dismiss();a.run()})))}
        root.addView(ScrollView(activity).apply{addView(items);isFillViewport=false},LinearLayout.LayoutParams(-1,-2))
        dialog.setContentView(root);dialog.setCanceledOnTouchOutside(true);dialog.show()
        dialog.window?.apply{
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if(anchor==null){setLayout(activity.resources.displayMetrics.widthPixels-GalleryStyle.dp(activity,24),-2);setGravity(Gravity.BOTTOM);setWindowAnimations(R.style.GalleryMenuAnimation);attributes=attributes.apply{y=GalleryStyle.dp(activity,12);dimAmount=.32f}}
            else {
                val frame=android.graphics.Rect();anchor.getWindowVisibleDisplayFrame(frame)
                if(frame.isEmpty)frame.set(0,0,activity.resources.displayMetrics.widthPixels,activity.resources.displayMetrics.heightPixels)
                val margin=GalleryStyle.dp(activity,8);val width=minOf(GalleryStyle.dp(activity,252),frame.width()-margin*2).coerceAtLeast(1)
                root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec((frame.height()-margin*2).coerceAtLeast(1),View.MeasureSpec.AT_MOST))
                val at=IntArray(2);anchor.getLocationOnScreen(at)
                val height=root.measuredHeight;val below=at[1]+anchor.height+margin-frame.top
                val above=at[1]-height-margin-frame.top
                setLayout(width,-2);setGravity(Gravity.TOP or Gravity.LEFT)
                setWindowAnimations(R.style.GalleryAnchoredMenuAnimation)
                attributes=attributes.apply{x=(at[0]+anchor.width-width-frame.left).coerceIn(margin,maxOf(margin,frame.width()-width-margin));y=(if(below+height<=frame.height()-margin)below else above).coerceIn(margin,maxOf(margin,frame.height()-height-margin));dimAmount=.16f}
            }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        return dialog
    }
}
