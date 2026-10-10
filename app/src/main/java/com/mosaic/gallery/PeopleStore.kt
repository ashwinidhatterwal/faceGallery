package com.mosaic.gallery

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** Logical leaf identities + reversible edges; source faces/vectors are never overwritten by a merge. */
class PeopleStore(private val faces:FaceStore) {
    companion object {
        fun create(db:SQLiteDatabase){
            db.execSQL("CREATE TABLE people(id INTEGER PRIMARY KEY AUTOINCREMENT,label TEXT NOT NULL DEFAULT '',contact_lookup TEXT,contact_name TEXT,name_rank INTEGER NOT NULL DEFAULT 0,contact_auto_blocked INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE membership(uri TEXT NOT NULL,ordinal INTEGER NOT NULL,person INTEGER REFERENCES people(id),suggested INTEGER REFERENCES people(id),status TEXT NOT NULL,score REAL NOT NULL,reason TEXT NOT NULL,model TEXT NOT NULL,manual INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(uri,ordinal),FOREIGN KEY(uri,ordinal) REFERENCES faces(uri,ordinal) ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX people_members ON membership(person)")
            db.execSQL("CREATE TABLE relations(id INTEGER PRIMARY KEY AUTOINCREMENT,a INTEGER NOT NULL REFERENCES people(id) ON DELETE CASCADE,b INTEGER NOT NULL REFERENCES people(id) ON DELETE CASCADE,type TEXT NOT NULL,source TEXT NOT NULL,active INTEGER NOT NULL,reason TEXT NOT NULL,batch INTEGER)")
            db.execSQL("CREATE INDEX relation_pair ON relations(a,b,type,active)");PeopleCorrections.create(db)
        }
    }
    private val db get()=faces.writableDatabase
    fun contactReferences()=faces.contactReferences()
    data class Relation(val id:Long,val a:Long,val b:Long,val type:String,val source:String,val active:Boolean,val reason:String)
    fun relations():List<Relation> = buildList{db.rawQuery("SELECT id,a,b,type,source,active,reason FROM relations ORDER BY id",null).use{while(it.moveToNext())add(Relation(it.getLong(0),it.getLong(1),it.getLong(2),it.getString(3),it.getString(4),it.getInt(5)!=0,it.getString(6)))}}
    fun members():List<GroupRules.Member> = buildList {
        val sql="SELECT f.uri,f.ordinal,f.l,f.t,f.r,f.b,f.yaw,f.pitch,f.roll,f.sharpness,f.score,f.authority,p.fingerprint,m.person,m.suggested,m.status,m.score,m.reason,m.model,EXISTS(SELECT 1 FROM embeddings e WHERE e.uri=f.uri AND e.ordinal=f.ordinal AND e.model=? AND e.status='done'),m.manual FROM faces f JOIN photos p ON p.uri=f.uri LEFT JOIN membership m ON m.uri=f.uri AND m.ordinal=f.ordinal WHERE p.status='done' ORDER BY f.score DESC,f.uri,f.ordinal"
        db.rawQuery(sql,arrayOf(FaceVectors.MODEL)).use{c->while(c.moveToNext()){
            val current=c.getString(18)==GroupRules.VERSION;val excluded=c.getString(15)=="excluded"
            val face=FaceObservation(c.getFloat(2),c.getFloat(3),c.getFloat(4),c.getFloat(5),c.getFloat(6),c.getFloat(7),c.getFloat(8),c.getFloat(9),c.getFloat(10),c.getString(11))
            val ready=c.getInt(19)!=0;val manual=c.getInt(20)!=0
            add(GroupRules.Member(GroupRules.Key(c.getString(0),c.getInt(1)),face,c.getString(12).substringAfterLast(':').toLongOrNull()?:0,
                if(current && (ready || manual) && !c.isNull(13))c.getLong(13)else null,if(current && ready && !c.isNull(14))c.getLong(14)else null,
                if(excluded)"excluded"else if(current && (ready || manual))c.getString(15)?:"unknown"else"unknown",if(current)c.getFloat(16)else 0f,
                if(current || excluded)c.getString(17).orEmpty()else if(face.authority=="Shadow")"Weak face"else"Awaiting signatures or grouping",ready,manual))
        }}
    }
    fun correct(keys:Set<GroupRules.Key>,target:Long?=null,create:Boolean=false,exclude:Boolean=false)=PeopleCorrections(faces).correct(keys,target,create,exclude)
    fun undoCorrection()=PeopleCorrections(faces).undo()
    fun canUndo()=PeopleCorrections(faces).canUndo()
    fun policy()=PeopleCorrections(faces).policy()
    fun pending()=members().filter{it.ready && it.face.authority!="Shadow" && it.status !in setOf("known","excluded")}.sortedWith(compareBy<GroupRules.Member>{it.face.authority!="Anchor"}.thenByDescending{it.face.score}.thenBy{it.key.uri}.thenBy{it.key.ordinal})
    fun <T> snapshot(read:()->T):T=faces.snapshot(read)
    fun comparisonCheckpoint()=faces.comparisonCheckpoint()
    fun vector(key:GroupRules.Key)=faces.signature(key.uri,key.ordinal)
    private fun transaction(action:()->Unit){db.beginTransaction();try{action();db.setTransactionSuccessful()}finally{db.endTransaction()}}
    fun record(member:GroupRules.Member,decision:GroupRules.Decision):Long? {
        var person=decision.target
        transaction{
            if(member.face.authority=="Anchor" && (decision.seed || decision.target!=null)){
                person=db.insertOrThrow("people",null,ContentValues().apply{put("label","")})
                if(decision.target!=null)link(person!!,decision.target,"attach","auto",decision.reason)
            }
            db.insertWithOnConflict("membership",null,ContentValues().apply{
                put("uri",member.key.uri);put("ordinal",member.key.ordinal);put("person",person);put("suggested",decision.suggested);put("status",decision.status);put("score",decision.score);put("reason",decision.reason);put("model",GroupRules.VERSION)
            },SQLiteDatabase.CONFLICT_REPLACE).also{check(it!=-1L){"Face assignment could not be saved"}}
        }
        return person
    }
    fun link(a:Long,b:Long,type:String,source:String,reason:String){
        if(a==b)return;val low=minOf(a,b);val high=maxOf(a,b)
        val exists=db.rawQuery("SELECT 1 FROM relations WHERE a=? AND b=? AND type=? AND active=1",arrayOf(low.toString(),high.toString(),type)).use{it.moveToFirst()}
        if(!exists)db.insertOrThrow("relations",null,ContentValues().apply{put("a",low);put("b",high);put("type",type);put("source",source);put("active",1);put("reason",reason)})
    }
    fun blocked(a:Set<Long>,b:Set<Long>):Boolean = relations().any{it.active && it.type=="cannot" && ((it.a in a && it.b in b)||(it.b in a && it.a in b))}
    /** Deterministic union with co-occurrence and explicit negative vetoes, including cycle repair. */
    fun components(rows:List<GroupRules.Member> = members(),repair:Boolean=false,skipRelation:Long?=null):Map<Long,Long> {
        val parent=mutableMapOf<Long,Long>();db.rawQuery("SELECT id FROM people",null).use{while(it.moveToNext())parent[it.getLong(0)]=it.getLong(0)}
        fun root(id:Long):Long{var r=id;while(parent[r]!=null && parent[r]!=r)r=parent[r]!!;var x=id;while(parent[x]!=null && parent[x]!=x){val next=parent[x]!!;parent[x]=r;x=next};return r}
        val photos=rows.filter{it.status=="known" && it.person!=null}.groupBy{it.person!!}.mapValues{it.value.map{row->row.key.uri}.toMutableSet()}.toMutableMap()
        val edges=relations();val negatives=edges.filter{it.active && it.type=="cannot"}
        for(edge in edges.filter{it.active && it.type!="cannot" && it.id!=skipRelation}){
            val a=root(edge.a);val b=root(edge.b);if(a==b)continue
            val overlap=photos[a].orEmpty().intersect(photos[b].orEmpty()).isNotEmpty()
            val negative=negatives.any{(root(it.a)==a && root(it.b)==b)||(root(it.a)==b && root(it.b)==a)}
            // A user-confirmed repeated appearance overrides occupancy, never an explicit separation.
            if((overlap && edge.source!="user-repeat") || negative){if(repair)db.execSQL("UPDATE relations SET active=0,reason=reason || ' · conflict veto' WHERE id=?",arrayOf(edge.id));continue}
            val low=minOf(a,b);val high=maxOf(a,b);parent[high]=low;photos.getOrPut(low){mutableSetOf()}.addAll(photos.remove(high).orEmpty())
        }
        return parent.keys.associateWith{root(it)}
    }
    fun capsules(rows:List<GroupRules.Member> = members(),roots:Map<Long,Long> = components(rows)):List<GroupRules.Capsule> {
        return rows.filter{it.status=="known" && it.person!=null}.groupBy{roots[it.person]?:it.person!!}.map{(root,group)->
            val leaves=roots.filterValues{it==root}.keys.toMutableSet()
            val anchors=group.filter{IdentityEvidence.reference(it)}
            val prototypes=GroupRules.diverseReferences(GroupRules.referenceCandidates(anchors).mapNotNull{member->vector(member.key)?.let{GroupRules.Prototype(member,it)}})
            GroupRules.Capsule(root,leaves,group.map{it.key.uri}.toMutableSet(),anchors.map{it.key.uri}.toMutableSet(),prototypes)
        }
    }
    /** Persist repeated clear co-occurrence, not a blind negative from one photo/reflection. */
    fun recordCooccurrence(){
        val all=members();val roots=components(all);val rows=all.filter{it.status=="known" && it.face.authority=="Anchor" && it.person!=null}
        val evidence=mutableMapOf<Pair<Long,Long>,MutableMap<String,Long>>()
        rows.groupBy{it.key.uri}.forEach{(uri,photo)->for(i in photo.indices)for(j in i+1 until photo.size){
            val a=roots[photo[i].person]?:photo[i].person!!;val b=roots[photo[j].person]?:photo[j].person!!
            if(a!=b)evidence.getOrPut(minOf(a,b) to maxOf(a,b)){mutableMapOf()}[uri]=photo[i].time
        }}
        evidence.forEach{(pair,photos)->val times=photos.values.filter{it>0};if(photos.size>=2 && times.size>=2 && times.max()-times.min()>=GroupRules.INDEPENDENT_MS)link(pair.first,pair.second,"cannot","auto","Repeated clear co-occurrence")}
    }
    fun automaticJoinFor(key:GroupRules.Key):Relation? {
        val rows=members();val person=rows.firstOrNull{it.key==key && it.status=="known"}?.person?:return null
        val current=components(rows);val root=current[person]?:person
        return relations().filter{it.active && it.source=="auto" && it.type=="merge" && current[it.a]==root}
            .sortedByDescending{it.id}.firstOrNull{edge->
                val split=components(rows,skipRelation=edge.id)
                split[edge.a]!=split[edge.b] && split[person] in setOf(split[edge.a],split[edge.b])
            }
    }
    fun undoAutomaticJoin(key:GroupRules.Key){
        val edge=automaticJoinFor(key)?:error("No automatic folder join remains for this face.")
        unlink(edge.id)
    }
    fun unlink(id:Long){
        val edge=relations().firstOrNull{it.id==id && it.type!="cannot"}?:return
        transaction{blockContactAutomation(edge.a);blockContactAutomation(edge.b);db.execSQL("UPDATE relations SET active=0 WHERE id=?",arrayOf(id));link(edge.a,edge.b,"cannot","user","Join reversed by user")}
    }
    fun separate(key:GroupRules.Key){
        val rows=members();val member=rows.firstOrNull{it.key==key}?:return;val person=member.person?:return
        val roots=components(rows);val group=roots.filterValues{it==roots[person]}.keys
        transaction{
            blockContactAutomation(person)
            if(member.face.authority=="Anchor"){
                db.execSQL("UPDATE relations SET active=0 WHERE type!='cannot' AND (a=? OR b=?)",arrayOf(person,person))
                group.filter{it!=person}.forEach{link(person,it,"cannot","user","Face kept separate by user")}
            }else db.execSQL("UPDATE membership SET status='excluded',person=NULL,suggested=NULL,reason='Kept separate by user' WHERE uri=? AND ordinal=?",arrayOf<Any>(key.uri,key.ordinal))
        }
    }
    fun merge(a:Long,b:Long,allowRepeated:Boolean=false){
        transaction{
            val groups=capsules();val first=groups.firstOrNull{a in it.leaves}?:error("First group is no longer available")
            val second=groups.firstOrNull{b in it.leaves}?:error("Second group is no longer available")
            if(first.id==second.id)return@transaction
            val overlap=first.photos.intersect(second.photos).isNotEmpty()
            check(!overlap || allowRepeated){"These groups share a photo. Confirm the repeated appearance first."}
            val crossed=relations().filter{it.active && it.type=="cannot" && ((it.a in first.leaves && it.b in second.leaves)||(it.b in first.leaves && it.a in second.leaves))}
            check(crossed.none{it.source=="user"} && (allowRepeated || crossed.isEmpty())){"These groups were marked as different people."}
            check(!contactConflict(first.leaves,second.leaves)){"These folders are linked to different contacts. Remove the incorrect contact link first."}
            if(allowRepeated)crossed.forEach{db.execSQL("UPDATE relations SET active=0 WHERE id=?",arrayOf(it.id))}
            PeopleCorrections(faces).feedback(first,second,1);link(first.id,second.id,"merge",if(overlap)"user-repeat"else"user","Same person confirmed by user");prioritize(first.id)
            // Confirmation covers these current folders, not future automatic additions.
            (first.leaves+second.leaves).forEach{person->
                db.execSQL("UPDATE membership SET manual=1 WHERE person=? AND status='known'",arrayOf(person))
            }
        }
    }
    fun reject(a:Long,b:Long){
        transaction{
            val roots=components();val first=roots[a]?:error("Group no longer available");val second=roots[b]?:error("Group no longer available")
            check(first!=second){"These groups are already joined. Undo the join first."}
            val groups=capsules();val aGroup=groups.firstOrNull{it.id==first};val bGroup=groups.firstOrNull{it.id==second};if(aGroup!=null && bGroup!=null)PeopleCorrections(faces).feedback(aGroup,bGroup,0)
            link(first,second,"cannot","user","Different people confirmed by user")
        }
    }
    fun names():Map<Long,String> = buildMap{db.rawQuery("SELECT id,label FROM people WHERE label!='' ORDER BY name_rank DESC,id",null).use{while(it.moveToNext())put(it.getLong(0),it.getString(1))}}
    /** Only a saved user name or contact link establishes a permanent display identity. */
    fun established(roots:Map<Long,Long> = components()):Set<Long> = (names().keys+contacts(roots).keys).map{roots[it]?:it}.toSet()
    fun labels(roots:Map<Long,Long>):Map<Long,String>{
        val result=roots.values.distinct().associateWith{"Person $it"}.toMutableMap();val named=mutableSetOf<Long>()
        names().forEach{(id,label)->val root=roots[id]?:id;if(named.add(root))result[root]=label};return result
    }
    fun label(id:Long):String{val roots=components();return labels(roots)[roots[id]?:id]?:"Person $id"}
    fun nameFace(key:GroupRules.Key,name:String){
        require(name.trim().isNotEmpty())
        transaction{
            val row=members().firstOrNull{it.key==key}?:error("Face is no longer available")
            if(row.status!="known" || row.person==null)correct(setOf(key),create=true)
            val person=members().first{it.key==key}.person?:error("Could not name face")
            rename(components()[person]?:person,name)
        }
    }
    private fun prioritize(id:Long){db.execSQL("UPDATE people SET name_rank=(SELECT COALESCE(MAX(name_rank),0)+1 FROM people) WHERE id=?",arrayOf(id))}
    fun rename(id:Long,name:String){val root=components()[id]?:id;blockContactAutomation(root);db.update("people",ContentValues().apply{put("label",name.trim().take(60))},"id=?",arrayOf(root.toString()));prioritize(root)}
    /** Removing/editing a name or reversing a join is a durable veto, even after future merges. */
    fun blockContactAutomation(id:Long){val roots=components();val root=roots[id]?:id;roots.filterValues{it==root}.keys.forEach{db.execSQL("UPDATE people SET contact_auto_blocked=1 WHERE id=?",arrayOf(it))}}
    fun contactAutomationBlocked():Set<Long> = buildSet{db.rawQuery("SELECT id FROM people WHERE contact_auto_blocked=1",null).use{while(it.moveToNext())add(it.getLong(0))}}
    fun autoContact(id:Long,contact:ContactNames.Contact,rows:List<GroupRules.Member> = members(),roots:Map<Long,Long> = components(rows),groups:List<GroupRules.Capsule> = capsules(rows,roots)):Boolean {
        require(ContactNames.valid(contact.lookup))
        var saved=false
        transaction{
            val group=groups.firstOrNull{id in it.leaves}?:return@transaction
            val names=names();val vetoes=contactAutomationBlocked();val contacts=contacts(roots)
            if(group.leaves.any{it in names || it in vetoes} || contacts[group.id]!=null || rows.any{it.person in group.leaves && it.manual})return@transaction
            val linked=groups.filter{contacts[it.id]?.lookup==contact.lookup}
            if(linked.any{blocked(group.leaves,it.leaves) || group.photos.intersect(it.photos).isNotEmpty()})return@transaction
            db.update("people",ContentValues().apply{put("label",contact.name.trim().take(60));put("contact_lookup",contact.lookup);put("contact_name",contact.name)},"id=?",arrayOf(group.id.toString()))
            prioritize(group.id);saved=true
        }
        if(saved)PeopleData.changed()
        return saved
    }
    fun contacts(roots:Map<Long,Long> = components()):Map<Long,ContactNames.Contact> = buildMap {
        db.rawQuery("SELECT id,contact_lookup,contact_name FROM people WHERE contact_lookup IS NOT NULL ORDER BY name_rank DESC,id",null).use{c->while(c.moveToNext()){val root=roots[c.getLong(0)]?:c.getLong(0);if(root !in this)put(root,ContactNames.Contact(c.getString(1),c.getString(2).orEmpty()))}}
    }
    fun contactKeys(leaves:Set<Long>):Set<String> = buildSet{db.rawQuery("SELECT id,contact_lookup FROM people WHERE contact_lookup IS NOT NULL",null).use{while(it.moveToNext())if(it.getLong(0) in leaves)add(it.getString(1))}}
    fun contactConflict(a:Set<Long>,b:Set<Long>):Boolean {val first=contactKeys(a);val second=contactKeys(b);return first.isNotEmpty() && second.isNotEmpty() && (first+second).size>1}
    fun updatePerson(id:Long,choice:ContactNames.Choice,allowRepeated:Boolean=false){
        require(choice.name.trim().isNotEmpty());choice.contact?.let{require(ContactNames.valid(it.lookup)){"Invalid contact link"}}
        transaction{
            val roots=components();val root=roots[id]?:error("Person is no longer available");val leaves=roots.filterValues{it==root}.keys
            blockContactAutomation(root)
            db.execSQL("UPDATE people SET contact_lookup=NULL,contact_name=NULL WHERE contact_lookup IS NOT NULL AND id IN (${leaves.joinToString(",")})")
            db.update("people",ContentValues().apply{put("label",choice.name.trim().take(60));put("contact_lookup",choice.contact?.lookup);put("contact_name",choice.contact?.name)},"id=?",arrayOf(root.toString()));prioritize(root)
            choice.contact?.let{contact->val live=capsules().map{it.id}.toSet();contacts().filter{it.key!=root && it.key in live && it.value.lookup==contact.lookup}.keys.forEach{main->merge(main,root,allowRepeated)}}
        }
    }
    fun nameFace(key:GroupRules.Key,choice:ContactNames.Choice,allowRepeated:Boolean=false){transaction{
        val row=members().firstOrNull{it.key==key}?:error("Face is no longer available")
        if(row.status!="known" || row.person==null)correct(setOf(key),create=true)
        updatePerson(members().first{it.key==key}.person?:error("Could not name face"),choice,allowRepeated)
    }}
    /** Confirmation means the complete source person, rather than moving only its one photo. */
    fun confirmIdentity(key:GroupRules.Key,target:Long,allowRepeated:Boolean=false){transaction{
        val row=members().firstOrNull{it.key==key}?:error("Face is no longer available")
        if(row.status=="known" && row.person!=null)merge(target,row.person,allowRepeated)
        else if(allowRepeated){correct(setOf(key),create=true);merge(target,members().first{it.key==key}.person!!,true)}
        else correct(setOf(key),target)
    }}
    fun syncContacts(context:android.content.Context,signal:android.os.CancellationSignal=android.os.CancellationSignal()){
        if(context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return
        val links=buildList{db.rawQuery("SELECT DISTINCT contact_lookup,contact_name FROM people WHERE contact_lookup IS NOT NULL",null).use{c->while(c.moveToNext())add(ContactNames.Contact(c.getString(0),c.getString(1).orEmpty()))}}.distinctBy{it.lookup}
        for(old in links){signal.throwIfCanceled();val next=runCatching{ContactNames.resolve(context,old,signal)}.getOrNull()?:continue
            if(next!=old)FaceWork.write{signal.throwIfCanceled();db.execSQL("UPDATE people SET label=CASE WHEN label=contact_name THEN ? ELSE label END,contact_name=?,contact_lookup=? WHERE contact_lookup=? AND contact_name=?",arrayOf(next.name,next.name,next.lookup,old.lookup,old.name));if(android.database.DatabaseUtils.longForQuery(db,"SELECT changes()",null)>0)PeopleData.changed()}
        }
    }
    /** Audit unsupported automatic folder edges; user joins and shared contact identity win. */
    fun repairJoins(keepGoing:()->Boolean):Int {
        val rows=members();var repaired=0
        for(edge in relations().filter{it.active && it.source=="auto" && it.type=="merge"}){
            if(!keepGoing())break
            val roots=components(rows,skipRelation=edge.id)
            if(roots[edge.a]==roots[edge.b])continue // Another valid path still supports this join.
            val groups=capsules(rows,roots);val a=groups.firstOrNull{edge.a in it.leaves}?:continue;val b=groups.firstOrNull{edge.b in it.leaves}?:continue
            if(contactKeys(a.leaves).intersect(contactKeys(b.leaves)).isNotEmpty())continue
            if(a.prototypes.size<2 || b.prototypes.size<2 || GroupRules.agreement(a,b)>=.50f)continue
            val best=a.prototypes.maxOfOrNull{GroupRules.rank(it.vector,b)?.score?:-1f}?:-1f
            if(best>=.60f || !keepGoing())continue
            db.execSQL("UPDATE relations SET active=0,reason=reason || ' · unsupported automatic folder join' WHERE id=?",arrayOf(edge.id));repaired++
        };return repaired
    }
    /** Release only unsupported automatic leaf attachments with a decisive alternative.
     * Explicit assignments, names, contact links and user joins are immutable here. */
    fun repairAssignments(keepGoing:()->Boolean):Int {
        val rows=members();val groups=capsules(rows);val edges=relations();val named=names().keys+contactKeysById()
        val byPerson=rows.filter{it.person!=null}.groupBy{it.person!!};val policy=policy();var repaired=0
        for(row in rows){
            if(!keepGoing())break
            val person=row.person?:continue
            val shared=row.face.authority=="Support"
            if(row.manual || row.status!="known" || !row.ready || (!shared && (person in named || byPerson[person]?.size!=1)))continue
            val incident=if(shared)emptyList()else edges.filter{it.active && (it.a==person || it.b==person)}
            if(!shared && (incident.isEmpty() || incident.any{it.source!="auto" || it.type!="attach"}))continue
            val own=groups.firstOrNull{person in it.leaves}?:continue
            if(shared && edges.any{it.active && it.source=="user" && it.type!="cannot" && (it.a in own.leaves || it.b in own.leaves)})continue
            val vector=vector(row.key)?:continue
            val references=own.prototypes.filter{it.member.key.uri!=row.key.uri}
            if(references.isEmpty() || references.maxOf{FaceVectors.cosine(vector,it.vector)}>=.55f)continue
            val candidates=groups.filter{it.id!=own.id && row.key.uri !in it.photos && !blocked(own.leaves,it.leaves) && !contactConflict(own.leaves,it.leaves)}.mapNotNull{GroupRules.rank(vector,it)}.sortedByDescending{it.score}
            val best=candidates.firstOrNull()?:continue
            if(best.score<=policy.anchor || best.score-(candidates.getOrNull(1)?.score?:-1f)<.06f)continue
            if(!keepGoing())break
            transaction{
                incident.forEach{db.execSQL("UPDATE relations SET active=0,reason=reason || ' · inconsistent automatic attachment' WHERE id=?",arrayOf(it.id))}
                db.execSQL("UPDATE membership SET person=NULL,suggested=?,status='tentative',score=?,reason='Automatic attachment needs reassessment' WHERE uri=? AND ordinal=? AND manual=0",arrayOf<Any>(best.capsule.id,best.score,row.key.uri,row.key.ordinal))
            };repaired++
        };return repaired
    }
    private fun contactKeysById()=buildSet<Long>{db.rawQuery("SELECT id FROM people WHERE contact_lookup IS NOT NULL",null).use{while(it.moveToNext())add(it.getLong(0))}}
    fun reset(){db.delete("identity_assertions",null,null);transaction{db.delete("contact_signatures",null,null);db.delete("contact_matches",null,null);db.delete("contact_face_matches",null,null);db.delete("face_edits",null,null);db.delete("correction_samples",null,null);db.delete("membership",null,null);db.delete("relations",null,null);db.delete("people",null,null)}}
}
