package com.mosaic.gallery

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.PopupMenu
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPopupMenu
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PeoplePolishUiTest {
    private fun find(view:View):View? {
        if(view.contentDescription=="People options")return view
        if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let{return it}
        return null
    }
    @Test fun normalPeopleMenuIsAnchoredToItsIconAndHasNoPipelineControls(){
        val controller=Robolectric.buildActivity(PeopleActivity::class.java).setup()
        try{
            val icon=find(controller.get().window.decorView)!!;icon.performClick()
            val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog()
            fun labels(view:View):List<String> =(if(view is android.widget.TextView)listOf(view.text.toString())else emptyList())+if(view is ViewGroup)(0 until view.childCount).flatMap{labels(view.getChildAt(it))}else emptyList()
            assertEquals(android.view.Gravity.TOP or android.view.Gravity.LEFT,dialog.window!!.attributes.gravity)
            val titles=labels(dialog.window!!.decorView)
            assertTrue(titles.containsAll(listOf("Identify people","Review similar people","Settings")))
            assertFalse(titles.contains("Recognition tools"));assertFalse(titles.contains("Reset people"))
            fun settings(view:View):View?{if(view.contentDescription=="Settings")return view;if(view is ViewGroup)for(i in 0 until view.childCount)settings(view.getChildAt(i))?.let{return it};return null}
            settings(dialog.window!!.decorView)!!.performClick()
            assertEquals(SettingsActivity::class.java.name,Shadows.shadowOf(controller.get()).nextStartedActivity.component!!.className)
        }finally{controller.pause().stop().destroy()}
    }
}
