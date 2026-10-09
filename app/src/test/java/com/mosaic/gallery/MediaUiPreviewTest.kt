package com.mosaic.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Render the real settings and action views for visual review in both themes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaUiPreviewTest {
    private fun save(view:View,name:String,height:Int=2340){
        view.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,1080,height)
        val bitmap=Bitmap.createBitmap(1080,height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        val folder=File("build/ui-review").apply{mkdirs()};File(folder,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun settingsAndMenuHaveUsableThemeLayouts(){
        for(theme in listOf("notnight","night")){
            RuntimeEnvironment.setQualifiers("w360dp-h780dp-$theme-xxhdpi")
            val c=Robolectric.buildActivity(SettingsActivity::class.java).setup()
            val body=c.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            save(body,"settings-$theme.png")
            fun find(v:View):View?{if(v.contentDescription=="Recognition and contacts")return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null}
            val row=find(body)!!;assertTrue(row.height>=GalleryStyle.dp(c.get(),48));row.performClick()
            assertEquals(PrivacyActivity::class.java.name,Shadows.shadowOf(c.get()).nextStartedActivity.component!!.className)
            val d=GalleryMenu.show(c.get(),"Gallery",listOf(GalleryMenu.Action("personAdd","People"){},GalleryMenu.Action("redo","Refresh library"){},GalleryMenu.Action("settings","Settings"){}))
            val panel=d.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            save(panel,"menu-$theme.png",840);d.dismiss();c.pause().stop().destroy()
        }
    }
}
