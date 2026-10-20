package com.mosaic.gallery

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import java.io.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Display-only launch data: one cover per person, no vectors, graph, or library scan. */
internal object PeopleLaunchCache {
    data class Profile(val id:Long,val title:String,val subtitle:String,val photo:PhotoRecord,
        val face:FaceObservation,val ordinal:Int)
    private data class Entry(val scope:String,val profiles:List<Profile>,val generation:Long)
    @Volatile private var entry:Entry?=null
    @Volatile private var invalidated=false
    private val generation=AtomicLong()
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private val diskLock=Any()
    private fun scope(c:Context)=PeopleData.access(c)+":"+GroupRules.VERSION
    private fun allowed(c:Context)=MediaAccess.cacheable(c) && MediaAccess.fullPhotos(c)
    private fun file(c:Context)=AtomicFile(File(c.noBackupFilesDir,"people-launch.bin"))
    fun peek(c:Context):List<Profile>?=entry?.takeIf{allowed(c) && it.scope==scope(c) && it.generation==generation.get()}?.profiles
    fun preload(context:Context,onReady:((List<Profile>)->Unit)?=null){
        val app=context.applicationContext;val token=generation.get();val access=scope(app)
        worker.execute {
            val profiles=peek(app)?:synchronized(diskLock){read(app)}
            if(profiles!=null && token==generation.get() && allowed(app) && access==scope(app)){
                entry=Entry(access,profiles,token)
                if(onReady!=null)main.post{if(token==generation.get())peek(app)?.let(onReady)}
                // Restore existing tiny JPEGs only. Never decode original media on the launch path.
                profiles.take(12).forEach{p->if(token==generation.get())runCatching{FaceThumbnails.load(app,p.photo,p.face){null}}}
            }
        }
    }
    fun save(c:Context,profiles:List<Profile>){
        if(!allowed(c))return
        val access=scope(c);val token=generation.get()
        synchronized(diskLock){
            if(token!=generation.get() || access!=scope(c))return
            val atomic=file(c);var stream:FileOutputStream?=null
            runCatching{
                stream=atomic.startWrite();val out=DataOutputStream(BufferedOutputStream(stream))
                out.writeInt(1);out.writeUTF(access);out.writeInt(profiles.size)
                profiles.forEach{p->
                    out.writeLong(p.id);out.writeUTF(p.title);out.writeUTF(p.subtitle);PhotoIndex.writePhoto(out,p.photo)
                    out.writeInt(p.ordinal)
                    val f=p.face
                    listOf(f.left,f.top,f.right,f.bottom,f.yaw,f.pitch,f.roll,f.sharpness,f.score).forEach(out::writeFloat)
                    out.writeUTF(f.authority);out.writeInt(f.landmarks.size);f.landmarks.forEach(out::writeFloat)
                }
                out.flush();atomic.finishWrite(stream)
                if(token==generation.get()){invalidated=false;entry=Entry(access,profiles.toList(),token)}else atomic.delete()
            }.onFailure{atomic.failWrite(stream)}
        }
    }
    private fun read(c:Context):List<Profile>? {
        if(!allowed(c) || invalidated)return null
        return runCatching{
            val atomic=file(c);check(atomic.baseFile.length() in 1L..16L*1024*1024)
            DataInputStream(BufferedInputStream(atomic.openRead())).use{input->
                check(input.readInt()==1);check(input.readUTF()==scope(c))
                val count=input.readInt();check(count in 0..20_000)
                val profiles=List(count){
                    val id=input.readLong();val title=input.readUTF();val subtitle=input.readUTF();val photo=PhotoIndex.readPhoto(input)
                    val ordinal=input.readInt();val values=FloatArray(9){input.readFloat()};check(values.all{it.isFinite()})
                    val authority=input.readUTF();val points=input.readInt();check(points in 0..100)
                    val face=FaceObservation(values[0],values[1],values[2],values[3],values[4],values[5],values[6],values[7],values[8],authority,List(points){input.readFloat()})
                    Profile(id,title,subtitle,photo,face,ordinal)
                }
                check(input.read()==-1);profiles
            }
        }.getOrNull()
    }
    fun clear(c:Context){
        val token=generation.incrementAndGet();entry=null;invalidated=true
        val remove={synchronized(diskLock){if(token==generation.get() && invalidated)file(c).delete()}}
        // Contact observers run on main. Never make them wait for a cache reader/writer.
        if(Looper.myLooper()==Looper.getMainLooper())worker.execute{remove()}else remove()
    }
    internal fun forgetMemory(){entry=null}
}
