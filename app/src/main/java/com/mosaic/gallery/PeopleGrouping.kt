package com.mosaic.gallery

/** User-selected cosine threshold; explicit separations and same-photo conflicts still win. */
object GroupRules {
    const val VERSION="${FaceVectors.MODEL}-groups-v1"
    const val ANCHOR_JOIN=.80f;const val SUPPORT_JOIN=.80f
    const val MERGE=.80f;const val INDEPENDENT_MS=60_000L
    const val AGREEMENT=.72f;const val GROUP_MARGIN=.04f;const val FACE_MARGIN=.06f
    data class Key(val uri:String,val ordinal:Int)
    data class Member(val key:Key,val face:FaceObservation,val time:Long,val person:Long?,val suggested:Long?,val status:String,val score:Float,val reason:String,val ready:Boolean,val manual:Boolean=false)
    data class Prototype(val member:Member,val vector:FloatArray)
    class Capsule(val id:Long,val leaves:MutableSet<Long>,val photos:MutableSet<String>,val anchors:MutableSet<String>,val prototypes:MutableList<Prototype>)
    data class Candidate(val capsule:Capsule,val score:Float,val agreement:Float,val witness:Long)
    data class Decision(val target:Long?=null,val seed:Boolean=false,val status:String="tentative",val score:Float=0f,val reason:String="Not enough reliable evidence",val suggested:Long?=null)
    fun select(members:List<Member>):List<Member> {
        val sorted=members.filter{it.face.authority=="Anchor"}.sortedWith(compareByDescending<Member>{it.face.score}.thenBy{it.key.uri}.thenBy{it.key.ordinal})
        val chosen=linkedMapOf<Key,Member>()
        fun add(member:Member?){if(member!=null && chosen.values.none{it.key.uri==member.key.uri})chosen[member.key]=member}
        add(sorted.firstOrNull());add(sorted.minByOrNull{it.face.yaw});add(sorted.maxByOrNull{it.face.yaw})
        add(sorted.minByOrNull{it.time});add(sorted.maxByOrNull{it.time});sorted.forEach{if(chosen.size<6)add(it)}
        return chosen.values.take(6)
    }
    /** A bounded candidate pool samples appearance across the person's photo history. */
    fun referenceCandidates(members:List<Member>):List<Member>{
        val clear=members.filter{IdentityEvidence.reference(it)}.sortedWith(compareByDescending<Member>{it.face.score}.thenBy{it.key.uri}).distinctBy{it.key.uri}
        if(clear.size<=24)return clear
        val pool=linkedMapOf<Key,Member>();select(clear).forEach{pool[it.key]=it}
        val time=clear.sortedWith(compareBy<Member>{it.time}.thenBy{it.key.uri})
        for(i in 0..17){val member=time[i*(time.size-1)/17];pool[member.key]=member}
        return pool.values.take(24)
    }
    fun diverseReferences(pool:List<Prototype>):MutableList<Prototype>{
        return IdentityEvidence.references(pool)
    }
    fun rank(vector:FloatArray,capsule:Capsule):Candidate? {
        val matches=capsule.prototypes.distinctBy{it.member.key.uri}.map{it to FaceVectors.cosine(vector,it.vector)}.sortedByDescending{it.second}
        val best=matches.firstOrNull()?:return null
        return Candidate(capsule,best.second,matches.firstOrNull{IdentityEvidence.independent(best.first.member,it.first.member)}?.second?:-1f,best.first.member.person?:capsule.id)
    }
    fun decide(member:Member,candidates:List<Candidate>,policy:PeopleCalibration.Policy=PeopleCalibration.Policy()):Decision {
        if(member.face.authority=="Shadow")return Decision(status="unknown",reason="Weak faces do not establish or merge people")
        val overall=candidates.maxByOrNull{it.score}
        val best=candidates.filter{member.key.uri !in it.capsule.photos}.maxByOrNull{it.score}
        val threshold=(if(member.face.authority=="Anchor")policy.anchor else policy.support)+IdentityEvidence.penalty(member)
        if(overall!=null && member.key.uri in overall.capsule.photos && (best==null || overall.score>=best.score)){
            // A plausible alternative remains uncertain; occupancy is not permission to ignore ambiguity.
            if(best!=null && best.score>=policy.agreement+IdentityEvidence.penalty(member))return Decision(suggested=best.capsule.id,score=best.score,reason="Competing same-photo match")
            return if(member.face.authority=="Anchor")Decision(seed=true,status="known",reason="Separate clear face in the same photo")else Decision(suggested=overall.capsule.id,score=overall.score,reason="Same-photo conflict")
        }
        val margin=best?.score?.minus(candidates.filter{it.capsule.id!=best.capsule.id}.maxOfOrNull{it.score}?:-1f)?:0f
        if(best!=null && best.score>threshold && margin>=.03f)return Decision(target=best.witness,status="known",score=best.score,reason="Strong face match")
        // Two distinct reference photos must agree; a singleton's repeated score is not evidence.
        if(best!=null && best.capsule.prototypes.map{it.member.key.uri}.distinct().size>=2 && best.agreement>=policy.agreement+IdentityEvidence.penalty(member) &&
            best.score-(candidates.filter{it.capsule.id!=best.capsule.id}.maxOfOrNull{it.score}?:-1f)>=policy.faceMargin)
            return Decision(target=best.witness,status="known",score=best.score,reason="Several reference photos agree")
        if(member.face.authority=="Anchor" && (best==null || best.score<.70f))return Decision(seed=true,status="known",reason="New clear face")
        return Decision(suggested=best?.capsule?.id,score=best?.score?:0f,reason="Match needs confirmation")
    }
    fun canJoin(a:Capsule,b:Capsule,threshold:Float=MERGE):Boolean {
        if(a.photos.intersect(b.photos).isNotEmpty())return false
        return a.prototypes.any{x->b.prototypes.any{y->x.member.face.authority=="Anchor" && y.member.face.authority=="Anchor" && FaceVectors.cosine(x.vector,y.vector)>threshold}}
    }
    /** Best two-pair agreement, requiring different source photos on BOTH sides. */
    fun agreement(a:Capsule,b:Capsule,count:Int=2):Float {
        require(count in 2..3)
        data class PairScore(val a:Member,val b:Member,val score:Float)
        val pairs=a.prototypes.filter{it.member.face.authority=="Anchor"}.flatMap{x->b.prototypes.filter{it.member.face.authority=="Anchor"}.map{y->PairScore(x.member,y.member,FaceVectors.cosine(x.vector,y.vector))}}.sortedByDescending{it.score}
        var score=-1f
        for(pair in pairs){
            if(pair.score<=score)break
            for(other in pairs){
                if(other.score<=score)break
                if(!IdentityEvidence.independent(other.a,pair.a) || !IdentityEvidence.independent(other.b,pair.b))continue
                val third=if(count==3)pairs.firstOrNull{
                    IdentityEvidence.independent(it.a,pair.a) && IdentityEvidence.independent(it.b,pair.b) &&
                    IdentityEvidence.independent(it.a,other.a) && IdentityEvidence.independent(it.b,other.b)
                }?.score?:continue else 1f
                score=maxOf(score,minOf(pair.score,other.score,third))
            }
        };return score
    }
    /** Mutual best groups with room above the runner-up; no single-link chain inference. */
    fun agreedPair(groups:List<Capsule>,allowed:(Capsule,Capsule)->Boolean,keepGoing:()->Boolean={true},policy:PeopleCalibration.Policy=PeopleCalibration.Policy(),cache:IdentityComparisonCache?=null):Pair<Capsule,Capsule>? {
        val scan=cache?:IdentityComparisonCache(groups.map{it.id},policy.agreement-policy.groupMargin)
        while(scan.i<groups.size){
            if(scan.j>=groups.size){scan.i++;scan.j=scan.i+1;continue}
            if(!keepGoing()){scan.saveScan();return null}
            val a=groups[scan.i];val b=groups[scan.j]
            if(a.photos.intersect(b.photos).isEmpty() && allowed(a,b)){
                fun compute()=IdentityComparisonCache.Evidence(a.prototypes.maxOfOrNull{rank(it.vector,b)?.score?:-1f}?:-1f,agreement(a,b),agreement(a,b,3))
                val evidence=scan.get(a.id,b.id,::compute);val best=evidence.best;val repeated=evidence.agreement
                val direct=best>policy.merge && (a.prototypes.size<2 || b.prototypes.size<2 || repeated>=.66f)
                val score=if(direct || repeated<0f)best else repeated
                if(score>=policy.agreement-policy.groupMargin)scan.offer(IdentityComparisonCache.Match(a.id,b.id,score,direct || repeated>=policy.agreement || evidence.corroborated>=maxOf(.68f,policy.agreement-.04f),direct))
            }
            scan.j++
        }
        val byId=groups.associateBy{it.id}
        fun decisive(id:Long,match:IdentityComparisonCache.Match):Boolean {
            val ranked=scan.rankings[id]?:return false
            if(ranked.first()!=match)return false
            val competitor=ranked.getOrNull(1)?:return true
            if(match.score-competitor.score>=(if(match.direct).03f else policy.groupMargin))return true
            val other=byId[if(match.a==id)match.b else match.a]?:return false
            val rival=byId[if(competitor.a==id)competitor.b else competitor.a]?:return false
            // A near tie can mean three folders of one person. Require cross-evidence;
            // A-B and A-C alone never imply that B and C are the same person.
            if(other.photos.intersect(rival.photos).isNotEmpty() || !allowed(other,rival))return false
            val gate=maxOf(.68f,policy.agreement-.04f)
            return (match.eligible && competitor.eligible &&
                (agreement(other,rival)>=policy.agreement || agreement(other,rival,3)>=gate)) ||
                (match.score>=.98f && competitor.score>=.98f && canJoin(other,rival,.98f))
        }
        val pair=scan.rankings.values.mapNotNull{it.firstOrNull()}.distinct().filter{it.eligible && decisive(it.a,it) && decisive(it.b,it)}.maxByOrNull{it.score}
        scan.saveScan()
        return pair?.let{a->val first=byId[a.a];val second=byId[a.b];if(first!=null && second!=null)first to second else null}

    }
}

/** Uses bounded capsules, never compares each new face with every historical face. */
object PeopleGrouping {
    private var comparisonKey:Long?=null;private var comparisons:IdentityComparisonCache?=null
    /** Reuse unfinished pair evidence across cool-down batches, invalidating when references change. */
    @Synchronized private fun resumable(store:PeopleStore,groups:List<GroupRules.Capsule>,policy:PeopleCalibration.Policy):IdentityComparisonCache {
        var key=(policy.hashCode().toLong()*31+"auto-merge-v2".hashCode())*31+store.relations().hashCode();key=key*31+store.names().hashCode();key=key*31+store.contacts().entries.sortedBy{it.key}.map{it.key to it.value.lookup}.hashCode()
        key=key*31+store.contactAutomationBlocked().sorted().hashCode();key=key*31+store.contactHintRejections().hashCode()
        for(group in groups.sortedBy{it.id}){key=key*31+group.id;key=key*31+group.photos.sorted().hashCode();key=key*31+group.leaves.sorted().hashCode();for(ref in group.prototypes){key=key*31+ref.member.key.hashCode();key=key*31+ref.vector.contentHashCode()}}
        if(key!=comparisonKey || comparisons==null){comparisonKey=key;comparisons=IdentityComparisonCache(groups.map{it.id},policy.agreement-policy.groupMargin,store.comparisonCheckpoint(),key)}
        return comparisons!!
    }

    fun run(store:PeopleStore,keepGoing:()->Boolean,reconsider:Boolean=true,reuseComparisons:Boolean=false,phase:(String)->Unit={},progress:(Int,Int)->Unit={_,_->}) {
        if(!keepGoing())return
        phase("Checking saved matches…")
        var changed=store.repairJoins(keepGoing)>0
        changed=store.repairAssignments(keepGoing)>0 || changed
        val groups=store.capsules().sortedBy{it.id}.toMutableList()
        val policy=store.policy()
        val rejected=store.contactHintRejections()
        val contactLinks=store.contacts()
        val rejectedByLeaf=mutableMapOf<Long,Set<String>>()
        store.members().forEach{m->m.person?.let{p->rejectedByLeaf[p]=rejectedByLeaf[p].orEmpty()+rejected[m.key].orEmpty()}}
        fun groupRejections(group:GroupRules.Capsule)=group.leaves.flatMap{rejectedByLeaf[it].orEmpty()}.toSet()
        fun groupContacts(group:GroupRules.Capsule)=group.leaves.mapNotNull{contactLinks[it]?.lookup}.toSet()
        phase("Matching faces…")
        var referencesChanged=false
        var completed=0;val pending=store.pending();val total=pending.size
        for(member in pending){
            if(!keepGoing()){if(changed)PeopleData.changed();return}
            val vector=store.vector(member.key)?:continue
            val ranked=groups.filter{g->groupContacts(g).none{it in rejected[member.key].orEmpty()}}.mapNotNull{GroupRules.rank(vector,it)}
            val decision=GroupRules.decide(member,ranked,policy)
            if(!keepGoing()){if(changed)PeopleData.changed();return}
            if(!decision.seed && decision.target==member.person && decision.suggested==member.suggested && decision.status==member.status && decision.reason==member.reason && decision.score==member.score){completed++;progress(completed,total);continue}
            val person=store.record(member,decision);changed=true
            if(person!=null)rejectedByLeaf[person]=rejectedByLeaf[person].orEmpty()+rejected[member.key].orEmpty()
            if(person!=null)referencesChanged=true
            if(person!=null && member.face.authority=="Anchor"){
                val old=groups.firstOrNull{decision.target in it.leaves}
                val assigned=member.copy(person=person,status="known")
                val prototype=GroupRules.Prototype(assigned,vector)
                if(old==null)groups+=GroupRules.Capsule(person,mutableSetOf(person),mutableSetOf(member.key.uri),mutableSetOf(member.key.uri),mutableListOf(prototype))
                else {old.leaves+=person;old.photos+=member.key.uri;old.anchors+=member.key.uri;old.prototypes+=prototype;val diverse=GroupRules.diverseReferences(old.prototypes);old.prototypes.clear();old.prototypes.addAll(diverse)}
            }else if(person!=null)groups.firstOrNull{person in it.leaves}?.photos?.add(member.key.uri)
            completed++;progress(completed,total)
        }
        if(changed)PeopleData.changed()
        if(!keepGoing())return
        phase("Comparing people…")
        store.recordCooccurrence()
        // Merge separate capsules with a matching reference above the threshold and no veto.
        val negatives=store.relations().filter{it.active && it.type=="cannot"}
        val contacts=store.contacts()
        val names=store.names()
        val contactVetoes=store.contactAutomationBlocked()
        fun contactPropagation(a:GroupRules.Capsule,b:GroupRules.Capsule):Boolean {
            val aLinked=a.leaves.any{contacts[it]!=null};val bLinked=b.leaves.any{contacts[it]!=null}
            return (!aLinked && bLinked && a.leaves.any{it in contactVetoes}) || (!bLinked && aLinked && b.leaves.any{it in contactVetoes})
        }
        fun nameConflict(a:GroupRules.Capsule,b:GroupRules.Capsule):Boolean {
            fun labels(group:GroupRules.Capsule)=group.leaves.mapNotNull{names[it]?.trim()?.lowercase(java.util.Locale.ROOT)}.toSet()
            val first=labels(a);val second=labels(b)
            return first.isNotEmpty() && second.isNotEmpty() && first.intersect(second).isEmpty()
        }
        fun allowed(a:GroupRules.Capsule,b:GroupRules.Capsule)=
            (a.leaves+b.leaves).mapNotNull{contacts[it]?.lookup}.toSet().size<=1 &&
            !contactPropagation(a,b) &&
            groupContacts(a).none{it in groupRejections(b)} && groupContacts(b).none{it in groupRejections(a)} &&
            !nameConflict(a,b) &&
            !negatives.any{(it.a in a.leaves && it.b in b.leaves)||(it.b in a.leaves && it.a in b.leaves)}
        fun join(a:GroupRules.Capsule,b:GroupRules.Capsule,reason:String){
            referencesChanged=true
            store.link(a.id,b.id,"merge","auto",reason);PeopleData.changed()
            a.leaves+=b.leaves;a.photos+=b.photos;a.anchors+=b.anchors
            val diverse=GroupRules.diverseReferences(a.prototypes+b.prototypes);a.prototypes.clear();a.prototypes.addAll(diverse)
            groups.remove(b)
        }
        val cache=if(reuseComparisons)resumable(store,groups,policy)else IdentityComparisonCache(groups.map{it.id},policy.agreement-policy.groupMargin)
        while(keepGoing()){
            val pair=GroupRules.agreedPair(groups,::allowed,keepGoing,policy,cache)?:break
            if(!keepGoing())return
            val (a,b)=pair
            cache.invalidate(a.id,b.id)
            val strong=a.prototypes.any{GroupRules.rank(it.vector,b)?.score?.let{score->score>policy.merge}==true}
            join(a,b,if(strong)"Strong face evidence; clear best match"else"Several independent photos agree; mutual best match")
        }
        // One bounded second pass: stronger/merged capsules may resolve earlier tentative faces.
        if(reconsider && referencesChanged && keepGoing() && store.pending().isNotEmpty())run(store,keepGoing,false,reuseComparisons,phase,progress)

    }
}
