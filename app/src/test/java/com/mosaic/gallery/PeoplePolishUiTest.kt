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
            val popup=ShadowPopupMenu.getLatestPopupMenu()
            assertSame(icon,ReflectionHelpers.getField<View>(popup,"mAnchor"))
            val titles=(0 until popup.menu.size()).map{popup.menu.getItem(it).title.toString()}
            assertEquals(listOf("Needs review","Possible duplicates","Settings"),titles)
        }finally{controller.pause().stop().destroy()}
    }
}
