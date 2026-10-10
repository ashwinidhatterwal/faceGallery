package com.mosaic.gallery

import android.net.Uri
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class SimpleIdentityTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val face=FaceObservation(.1f,.1f,.4f,.5f,0f,0f,0f,120f,.95f,"Anchor")
    private fun key(id:Int,ordinal:Int=0)=GroupRules.Key("content://simple/$id",ordinal)
    private fun add(f:FaceStore,id:Int,count:Int=1,cos:Float=1f){
        f.save(PhotoRecord(id.toLong(),Uri.parse(key(id).uri),"$id.jpg",id*120_000L,400,300,"Camera"),List(count){face})
        repeat(count){f.saveSignature(key(id).uri,it,FloatArray(128){i->if(i==0)cos else if(i==1)kotlin.math.sqrt(1-cos*cos)else 0f})}
    }
    private fun seed(p:PeopleStore,key:GroupRules.Key)=p.record(p.members().first{it.key==key},GroupRules.Decision(seed=true,status="known"))!!
    @Before fun reset(){app.deleteDatabase("faces.db");FaceJobs.publish(FaceJobs.State())}
    @After fun cleanup(){app.deleteDatabase("faces.db")}
    @Test fun automaticGroupsNeverEstablishPermanentNames(){FaceStore(app).use{f->
        add(f,1);add(f,2);val p=PeopleStore(f);PeopleGrouping.run(p,{true});assertTrue(p.established().isEmpty());assertTrue(p.names().isEmpty())
        val data=IdentitySuggestions.read(p,key(2),setOf(key(1).uri,key(2).uri))!!;assertNull(data.person);assertTrue(data.suggestions.isEmpty())
    }}
    @Test fun onlyNamedGroupsAreSuggestedEvenWhenTemporaryGroupScoresHigher(){FaceStore(app).use{f->
        add(f,1,cos=.75f);add(f,2);add(f,3);val p=PeopleStore(f);val named=seed(p,key(1));seed(p,key(2));p.rename(named,"Ankita")
        val data=IdentitySuggestions.read(p,key(3),(1..3).map{key(it).uri}.toSet())!!;assertEquals(listOf(named),data.suggestions.map{it.id});assertEquals("Ankita",data.suggestions.single().name)
    }}
    @Test fun namedFacesAreOpenedWithoutAnotherQuestion(){FaceStore(app).use{f->
        add(f,1);add(f,2);val p=PeopleStore(f);val a=seed(p,key(1));val b=seed(p,key(2));p.rename(a,"Ankita");p.rename(b,"Other")
        val data=IdentitySuggestions.read(p,key(1),setOf(key(1).uri,key(2).uri))!!;assertEquals(a,data.person);assertTrue(data.suggestions.isEmpty());assertNull(PhotoPeople.read(p,key(1).uri,setOf(key(1).uri,key(2).uri)).single().suggestion)
    }}
    @Test fun confirmationCombinesWholeTemporaryFolderAndSurvivesReopen(){var main=0L;FaceStore(app).use{f->
        (1..3).forEach{add(f,it)};val p=PeopleStore(f);main=seed(p,key(1));p.rename(main,"Ankita");p.correct(setOf(key(2),key(3)),create=true);p.confirmIdentity(key(2),main)
    };FaceStore(app).use{f->val p=PeopleStore(f);PeopleGrouping.run(p,{true});assertEquals(3,p.capsules().single().photos.size);for(i in 2..3){val d=IdentitySuggestions.read(p,key(i),(1..3).map{key(it).uri}.toSet())!!;assertEquals(p.components()[main],d.person);assertTrue(d.suggestions.isEmpty())}}}
    @Test fun samePhotoConflictRemainsAutomaticButCanBeExplicitlyConfirmed(){var main=0L;FaceStore(app).use{f->
        add(f,1,2);add(f,2);val p=PeopleStore(f);main=seed(p,key(1));val other=seed(p,key(1,1));p.record(p.members().first{it.key==key(2)},GroupRules.Decision(target=other,status="known"));p.rename(main,"Ankita")
        assertEquals(2,p.capsules().size);val d=IdentitySuggestions.read(p,key(1,1),setOf(key(1).uri,key(2).uri))!!;assertEquals(setOf(key(1).uri),d.suggestions.single().sharedPhotos)
        assertTrue(runCatching{p.confirmIdentity(key(1,1),main)}.isFailure);p.confirmIdentity(key(1,1),main,true);assertEquals(1,p.capsules().size);assertEquals(3,p.members().count{it.manual})
    };FaceStore(app).use{f->val p=PeopleStore(f);PeopleGrouping.run(p,{true});assertEquals(1,p.capsules().size);assertEquals("Ankita",p.label(main));assertEquals(2,p.capsules().single().photos.size)}}
    @Test fun explicitSeparationCannotBeOverriddenByRepeatConfirmation(){FaceStore(app).use{f->
        add(f,1,2);val p=PeopleStore(f);val a=seed(p,key(1));val b=seed(p,key(1,1));p.rename(a,"Ankita");p.reject(a,b)
        assertTrue(IdentitySuggestions.read(p,key(1,1),setOf(key(1).uri))!!.choices.isEmpty());assertTrue(runCatching{p.confirmIdentity(key(1,1),a,true)}.isFailure);assertEquals(2,p.capsules().size)
    }}
    @Test fun inferredCooccurrenceCanBeOverriddenWithoutDeletingSourceFaces(){FaceStore(app).use{f->
        add(f,1,2);add(f,2,2);val p=PeopleStore(f);val a=seed(p,key(1));val b=seed(p,key(1,1));p.record(p.members().first{it.key==key(2)},GroupRules.Decision(target=a,status="known"));p.record(p.members().first{it.key==key(2,1)},GroupRules.Decision(target=b,status="known"));p.recordCooccurrence();assertTrue(p.relations().any{it.active && it.type=="cannot"})
        p.confirmIdentity(key(1,1),a,true);assertEquals(1,p.capsules().size);assertEquals(4,p.members().size);assertFalse(p.relations().any{it.active && it.type=="cannot"})
    }}
    @Test fun repeatConfirmationCanBeReversed(){FaceStore(app).use{f->
        add(f,1,2);val p=PeopleStore(f);val a=seed(p,key(1));seed(p,key(1,1));p.confirmIdentity(key(1,1),a,true);p.unlink(p.relations().single{it.active && it.source=="user-repeat"}.id);PeopleGrouping.run(p,{true});assertEquals(2,p.capsules().size)
    }}
    @Test fun namingTemporaryFolderNamesEveryMemberWithoutSplitting(){FaceStore(app).use{f->
        add(f,1);add(f,2);val p=PeopleStore(f);p.correct(setOf(key(1),key(2)),create=true);p.nameFace(key(2),ContactNames.Choice("Ankita",null),true);assertEquals(1,p.capsules().size);assertEquals("Ankita",IdentitySuggestions.read(p,key(1),setOf(key(1).uri,key(2).uri))!!.name)
    }}
    @Test fun choosingSameContactExplicitlyCombinesRepeatedAppearances(){FaceStore(app).use{f->
        add(f,1,2);val p=PeopleStore(f);val a=seed(p,key(1));val b=seed(p,key(1,1));val choice=ContactNames.Choice("Ankita",ContactNames.Contact("content://com.android.contacts/contacts/lookup/ankita/1","Ankita"));p.updatePerson(a,choice);p.updatePerson(b,choice,true);assertEquals(1,p.capsules().size)
    }}
    @Test fun unnamedTargetIsNeverSuggestedFromPhotoSheet(){FaceStore(app).use{f->
        add(f,1);add(f,2);val p=PeopleStore(f);seed(p,key(1));assertNull(PhotoPeople.read(p,key(2).uri,setOf(key(1).uri,key(2).uri)).single().suggestion)
    }}
    @Test fun explicitEditCanChooseAnotherIdentityWithoutAutomaticQuestions(){FaceStore(app).use{f->
        add(f,1);add(f,2);val p=PeopleStore(f);val a=seed(p,key(1));val b=seed(p,key(2));p.rename(a,"Ankita");p.rename(b,"Other")
        val data=IdentitySuggestions.read(p,key(1),setOf(key(1).uri,key(2).uri),true)!!;assertNull(data.person);assertEquals(listOf(b),data.suggestions.map{it.id})
    }}
    @Test fun pickerUsesShortNamesAndOneTapConfirmsTheWholeFolder(){
        val data=FaceStore(app).use{f->(1..3).forEach{add(f,it)};val p=PeopleStore(f);val named=seed(p,key(1));p.rename(named,"Ankita");p.correct(setOf(key(2),key(3)),create=true);IdentitySuggestions.read(p,key(2),(1..3).map{key(it).uri}.toSet())!!}
        val controller=Robolectric.buildActivity(android.app.Activity::class.java).setup();val activity=controller.get();val names=PeopleNames(activity)
        val chooser=IdentityChooser(activity,names,{change,_->FaceStore(app).use{change(PeopleStore(it))}},{})
        IdentityChooser::class.java.getDeclaredMethod("render",GroupRules.Key::class.java,IdentitySuggestions.Result::class.java,android.graphics.Bitmap::class.java,List::class.java,Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(chooser,key(2),data,null,listOf(null),false)
        val dialog=IdentityChooser::class.java.getDeclaredField("dialog").apply{isAccessible=true}.get(chooser) as android.app.AlertDialog
        val root=dialog.findViewById<android.widget.FrameLayout>(android.R.id.custom).getChildAt(0) as android.widget.LinearLayout
        assertEquals("Who is this?",((root.getChildAt(0) as android.widget.LinearLayout).getChildAt(0) as android.widget.TextView).text.toString());(0 until root.childCount).map{root.getChildAt(it)}.first{it.contentDescription=="Assign to Ankita"}.performClick();FaceStore(app).use{f->assertEquals(3,PeopleStore(f).capsules().single().photos.size)}
        chooser.close();names.close();controller.destroy()
    }
    @Test fun photoCorrectionMovesOnlyTheChosenFaceAndRemainsUndoable(){
        FaceStore(app).use{f->(1..3).forEach{add(f,it)};val p=PeopleStore(f);val named=seed(p,key(1));p.rename(named,"Ankita");p.correct(setOf(key(2),key(3)),create=true)}
        val controller=Robolectric.buildActivity(android.app.Activity::class.java).setup();val activity=controller.get();val names=PeopleNames(activity)
        val chooser=IdentityChooser(activity,names,{change,_->FaceStore(app).use{change(PeopleStore(it))}},{})
        val choice=FaceStore(app).use{f->IdentitySuggestions.read(PeopleStore(f),key(2),(1..3).map{key(it).uri}.toSet(),true)!!.choices.single()}
        IdentityChooser::class.java.getDeclaredMethod("select",GroupRules.Key::class.java,IdentitySuggestions.Choice::class.java,Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(chooser,key(2),choice,true)
        FaceStore(app).use{f->val p=PeopleStore(f);assertEquals(listOf(1,2),p.capsules().map{it.photos.size}.sorted());assertEquals("Ankita",p.label(p.members().first{it.key==key(2)}.person!!));p.undoCorrection();assertEquals(listOf(1,2),p.capsules().map{it.photos.size}.sorted());assertNotEquals(p.components()[p.members().first{it.key==key(1)}.person],p.components()[p.members().first{it.key==key(2)}.person])}
        chooser.close();names.close();controller.destroy()
    }

    @Test fun correctingOneFaceCanChooseADifferentContactWithoutMergingContacts(){FaceStore(app).use{f->
        (1..3).forEach{add(f,it)};val p=PeopleStore(f);p.correct(setOf(key(1),key(2)),create=true)
        val a=p.members().first{it.key==key(1)}.person!!;val b=seed(p,key(3))
        for((id,name) in listOf(a to "Alice",b to "Beth"))p.updatePerson(id,ContactNames.Choice(name,ContactNames.Contact("content://com.android.contacts/contacts/lookup/person$id/$id",name)))
        val visible=(1..3).map{key(it).uri}.toSet()
        assertTrue(IdentitySuggestions.read(p,key(2),visible)!!.choices.isEmpty())
        assertEquals(b,IdentitySuggestions.read(p,key(2),visible,true)!!.choices.single().id)
        p.correct(setOf(key(2)),target=b);assertEquals(2,p.contacts().size);assertEquals("Alice",p.label(a));assertEquals("Beth",p.label(p.members().first{it.key==key(2)}.person!!))
    }}

}
