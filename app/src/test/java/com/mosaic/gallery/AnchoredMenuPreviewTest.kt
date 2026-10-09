package com.mosaic.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.LinearLayout
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnchoredMenuPreviewTest {
    @Test fun anchoredPanelFitsScreenAndRendersBothThemes(){
        for(theme in listOf("notnight","night")){
            RuntimeEnvironment.setQualifiers("w360dp-h780dp-$theme-xxhdpi")
            val c=Robolectric.buildActivity(android.app.Activity::class.java).setup();val a=c.get()
            val root=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(GalleryStyle.canvas(a))}
            val bar=GalleryStyle.bar(a);bar.addView(GalleryStyle.text(a,"Photos",30f),LinearLayout.LayoutParams(0,-2,1f));val anchor=GalleryStyle.action(a,"more","More",compact=true){};bar.addView(anchor);root.addView(bar);a.setContentView(root)
            root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2340,View.MeasureSpec.EXACTLY));root.layout(0,0,1080,2340)
            val d=GalleryMenu.show(a,"Photos",listOf(GalleryMenu.Action("personAdd","People"){},GalleryMenu.Action("redo","Refresh library"){},GalleryMenu.Action("settings","Settings"){}),anchor)
            val at=d.window!!.attributes;val panel=d.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            panel.measure(View.MeasureSpec.makeMeasureSpec(at.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2340-at.y,View.MeasureSpec.AT_MOST));panel.layout(0,0,at.width,panel.measuredHeight)
            assertTrue(at.x+panel.width<=1080);assertTrue(at.y+panel.height<=2340)
            val bitmap=Bitmap.createBitmap(1080,2340,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);root.draw(canvas);canvas.save();canvas.translate(at.x.toFloat(),at.y.toFloat());panel.draw(canvas);canvas.restore()
            val folder=File("build/ui-review").apply{mkdirs()};File(folder,"anchored-menu-$theme.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle();d.dismiss();c.pause().stop().destroy()
        }
    }
}
