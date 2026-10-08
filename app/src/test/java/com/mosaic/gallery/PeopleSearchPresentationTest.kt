package com.mosaic.gallery

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PeopleSearchPresentationTest {
    private fun query(activity:PeopleSearchActivity)=PeopleSearchActivity::class.java.getDeclaredMethod("query").apply{isAccessible=true}.invoke(activity) as PeopleSearch.Query
    private fun set(activity:PeopleSearchActivity,name:String,value:Any){PeopleSearchActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.set(activity,value)}
    @Test fun personShortcutAndDraftQueryStartCorrectly(){
        val controller=Robolectric.buildActivity(PeopleSearchActivity::class.java,Intent(RuntimeEnvironment.getApplication(),PeopleSearchActivity::class.java).putExtra("person",7L).putExtra("searchText","अंजली"))
        val activity=controller.create().get();assertEquals(PeopleSearch.Query("अंजली",7),query(activity));controller.destroy()
    }
    @Test fun criteriaSurviveRecreation(){
        val state=Bundle().apply{putLong("person",8);putString("query","School");putString("from","2026-10-01");putString("through","2026-10-06")}
        val controller=Robolectric.buildActivity(PeopleSearchActivity::class.java).create(state);assertEquals(PeopleSearch.Query("School",8,LocalDate.of(2026,10,1),LocalDate.of(2026,10,6)),query(controller.get()));controller.destroy()
    }
    @Test fun openingDisplayedPhotoUsesDisplayedCriteriaBeforeDebounce(){
        val controller=Robolectric.buildActivity(PeopleSearchActivity::class.java).create();val activity=controller.get()
        val shown=PeopleSearch.Query("Old name",4,LocalDate.of(2026,9,1),LocalDate.of(2026,10,6));set(activity,"shownQuery",shown)
        val field=PeopleSearchActivity::class.java.getDeclaredField("text").apply{isAccessible=true};(field.get(activity) as EditText).setText("New draft")
        val photo=PhotoRecord(1,Uri.parse("content://search/1"),"1.jpg",0,400,300)
        PeopleSearchActivity::class.java.getDeclaredMethod("open",PhotoRecord::class.java).apply{isAccessible=true}.invoke(activity,photo)
        val intent=Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(PhotoActivity::class.java.name,intent.component!!.className);assertTrue(intent.getBooleanExtra("peopleSearch",false));assertEquals("Old name",intent.getStringExtra("searchText"));assertEquals(4L,intent.getLongExtra("searchPerson",-1));assertEquals("2026-09-01",intent.getStringExtra("searchFrom"));assertEquals("2026-10-06",intent.getStringExtra("searchThrough"));assertEquals(photo.uri,intent.data)
        controller.destroy()
    }
}
