package com.mosaic.gallery

import android.net.Uri
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.*
import java.util.Locale

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35])
class PeopleSearchTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val zone=ZoneId.of("Asia/Kolkata")
    private fun photo(id:Int,time:Long=LocalDate.of(2026,10,6).atStartOfDay(zone).toInstant().toEpochMilli(),name:String="$id.jpg",album:String="Camera")=PhotoRecord(id.toLong(),Uri.parse("content://search/$id"),name,time,400,300,album)
    private fun index()=PeopleSearch.Index(mapOf(photo(1).uri.toString() to setOf(1L,2L),photo(2).uri.toString() to setOf(1L)),mapOf(1L to "Ashwini",2L to "अंजली"),mapOf(1L to 1L,2L to 2L,9L to 1L))
    private fun find(p:List<PhotoRecord>,q:PeopleSearch.Query)=PeopleSearch.filter(p,index(),q,zone).map{it.id}
    @Before fun reset(){app.deleteDatabase("faces.db")}
    @After fun cleanup(){app.deleteDatabase("faces.db")}
    @Test fun namesFilenameAndFolderShareTokenSearch(){assertEquals(listOf(1L),find(listOf(photo(1,name="School.jpg"),photo(2)),PeopleSearch.Query("ASHWINI school camera")))}
    @Test fun hindiNamesPreserveVowelMarks(){assertEquals(listOf(1L),find(listOf(photo(1),photo(2)),PeopleSearch.Query("अंजली")));assertTrue(find(listOf(photo(1)),PeopleSearch.Query("अंजाला")).isEmpty())}
    @Test fun explicitPersonFollowsMergedLeaf(){assertEquals(listOf(1L,2L),find(listOf(photo(1),photo(2),photo(3)),PeopleSearch.Query(person=9)))}
    @Test fun namesCombineWithDatesAndPerson(){val d=LocalDate.of(2026,10,6);assertEquals(listOf(1L),find(listOf(photo(1),photo(2)),PeopleSearch.Query("अंजली",1,d,d)))}
    @Test fun inclusiveDateUsesLocalMidnight(){val d=LocalDate.of(2026,10,6);val start=d.atStartOfDay(zone).toInstant().toEpochMilli();val end=d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();assertEquals(listOf(2L,3L),find(listOf(photo(1,start-1),photo(2,start),photo(3,end-1),photo(4,end)),PeopleSearch.Query(from=d,through=d)))}
    @Test fun daylightSavingDaysAreNotFixed24Hours(){val z=ZoneId.of("America/New_York");val d=LocalDate.of(2026,11,1);val end=d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli();assertEquals(listOf(1L),PeopleSearch.filter(listOf(photo(1,end-1),photo(2,end)),index(),PeopleSearch.Query(from=d,through=d),z).map{it.id})}
    @Test fun unknownDateAllowedOnlyWithoutDateFilter(){assertEquals(listOf(1L),find(listOf(photo(1,0)),PeopleSearch.Query()));assertTrue(find(listOf(photo(1,0)),PeopleSearch.Query(from=LocalDate.of(2026,1,1))).isEmpty())}
    @Test(expected=IllegalArgumentException::class) fun reversedRangeRejected(){PeopleSearch.Query(from=LocalDate.of(2026,10,7),through=LocalDate.of(2026,10,6))}
    @Test fun noDuplicatePhotoAndKeepInputOrder(){assertEquals(listOf(2L,1L),find(listOf(photo(2),photo(1),photo(2)),PeopleSearch.Query(person=1)))}
    @Test fun unavailablePersonDoesNotFallBackToAllPhotos(){assertTrue(find(listOf(photo(1)),PeopleSearch.Query(person=999)).isEmpty())}
    @Test fun onlyProvidedAccessiblePhotosCanMatch(){assertEquals(listOf(2L),find(listOf(photo(2)),PeopleSearch.Query("Ashwini")))}
    @Test fun canceledSearchDoesNotPublishPartialResults(){var calls=0;assertTrue(PeopleSearch.filter(listOf(photo(1),photo(2)),index(),PeopleSearch.Query(),zone){++calls<2}.isEmpty())}
    @Test fun matchingIsIndependentOfTurkishLocale(){val old=Locale.getDefault();try{Locale.setDefault(Locale.forLanguageTag("tr-TR"));assertEquals(listOf(1L),find(listOf(photo(1)),PeopleSearch.Query("ASHWINI")))}finally{Locale.setDefault(old)}}
    @Test fun liveIndexTracksManualWeakAssignmentExclusionAndUndo(){FaceStore(app).use{faces->
        val p=photo(1);faces.save(p,listOf(FaceObservation(.1f,.1f,.4f,.5f,0f,0f,0f,100f,.2f,"Shadow")))
        val store=PeopleStore(faces);val key=GroupRules.Key(p.uri.toString(),0);store.correct(setOf(key),create=true);val root=store.components()[store.members().single().person]!!;store.rename(root,"अंजली")
        assertEquals(setOf(root),PeopleSearch.read(app).people[p.uri.toString()]);assertEquals("अंजली",PeopleSearch.read(app).labels[root]);assertTrue(store.capsules().single().prototypes.isEmpty())
        store.correct(setOf(key),exclude=true);assertTrue(PeopleSearch.read(app).people.isEmpty());store.undoCorrection();assertEquals(setOf(root),PeopleSearch.read(app).people[p.uri.toString()])
    }}
    @Test fun changedOrRemovedSourcesDoNotContributeNames(){FaceStore(app).use{faces->
        val p=photo(1);faces.save(p,listOf(FaceObservation(.1f,.1f,.4f,.5f,0f,0f,0f,100f,.2f,"Shadow")))
        PeopleStore(faces).correct(setOf(GroupRules.Key(p.uri.toString(),0)),create=true)
        assertEquals(1,PeopleSearch.read(app,listOf(p)).people.size)
        assertTrue(PeopleSearch.read(app,listOf(p.copy(modifiedMillis=999))).people.isEmpty())
        assertTrue(PeopleSearch.read(app,emptyList()).people.isEmpty())
        assertEquals(1,PeopleStore(faces).members().size) // Search is read-only.
    }}

}
