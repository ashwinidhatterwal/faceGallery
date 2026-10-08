package com.mosaic.gallery

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets

object Ui {
    @Suppress("DEPRECATION")
    fun blendSystemBars(activity:Activity){
        activity.window.statusBarColor=android.graphics.Color.TRANSPARENT
        activity.window.navigationBarColor=android.graphics.Color.TRANSPARENT
        if(Build.VERSION.SDK_INT>=29){
            activity.window.isNavigationBarContrastEnforced=false
            activity.window.isStatusBarContrastEnforced=false
        }
    }
    fun back(activity: Activity, action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33) activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { action() }
    }
    fun insets(activity: Activity, root: View, padding: Int = 0) {
        blendSystemBars(activity)
        root.setPadding(padding, padding, padding, 0)
        if (Build.VERSION.SDK_INT >= 30) {
            activity.window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, bars.bottom)
                insets
            }
        }else{
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                if(activity.resources.getBoolean(R.bool.gallery_light_bars))View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
            root.setOnApplyWindowInsetsListener{view,insets->
                @Suppress("DEPRECATION")
                view.setPadding(padding+insets.stableInsetLeft,padding+insets.stableInsetTop,padding+insets.stableInsetRight,insets.stableInsetBottom);insets
            }
        }
    }
}
