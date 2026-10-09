package com.mosaic.gallery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.AtomicFile
import android.net.Uri
import java.io.*

/** Metadata only. Disk IO stays on the existing gallery worker; scoped access never reuses a full index. */
object PhotoIndex {
    private const val VERSION=3
    private var memory:GalleryRepository.Result?=null
    private var persisted:GalleryRepository.Result?=null
    private var owner="";private var scope=""
    private fun file(context:Context)=AtomicFile(File(context.filesDir,"photo-index.bin"))
    fun allowed(context:Context)=MediaAccess.cacheable(context)
    @Synchronized fun current(context:Context):GalleryRepository.Result?=if(allowed(context)&&owner==context.filesDir.absolutePath && scope==MediaAccess.scope(context))memory else null
    @Synchronized fun read(context:Context):GalleryRepository.Result? {
        if(!allowed(context)){clear(context);return null}
        current(context)?.let{return it}
        val result=runCatching {
            check(file(context).baseFile.length()<=64L*1024*1024)
            DataInputStream(BufferedInputStream(file(context).openRead())).use{input->
                check(input.readInt()==VERSION);check(input.readUTF()==MediaAccess.scope(context))
                val count=input.readInt();check(count in 0..1_000_000)
                val photos=List(count){PhotoRecord(input.readLong(),Uri.parse(input.readUTF()),input.readUTF(),input.readLong(),input.readInt(),input.readInt(),input.readUTF(),input.readLong(),input.readUTF(),input.readLong(),input.readUTF(),input.readLong())}
                check(input.read()==-1);GalleryRepository.Result(photos,0)
            }
        }.getOrNull()
        if(result!=null){memory=result;persisted=result;owner=context.filesDir.absolutePath;scope=MediaAccess.scope(context)}
        return result
    }
    @Synchronized fun save(context:Context,result:GalleryRepository.Result){
        if(!allowed(context)){clear(context);return}
        if(owner!=context.filesDir.absolutePath || scope!=MediaAccess.scope(context))persisted=null
        memory=result;owner=context.filesDir.absolutePath;scope=MediaAccess.scope(context)
        // Never replace a complete saved index with an unavailable storage volume.
        if(result.unreadableVolumes!=0 || persisted==result)return
        val atomic=file(context);var stream:FileOutputStream?=null
        runCatching {
            stream=atomic.startWrite()
            val output=DataOutputStream(BufferedOutputStream(stream))
            output.writeInt(VERSION);output.writeUTF(MediaAccess.scope(context));output.writeInt(result.photos.size)
            result.photos.forEach{p->output.writeLong(p.id);output.writeUTF(p.uri.toString());output.writeUTF(p.displayName);output.writeLong(p.dateTakenMillis);output.writeInt(p.width);output.writeInt(p.height);output.writeUTF(p.album);output.writeLong(p.sizeBytes);output.writeUTF(p.path);output.writeLong(p.modifiedMillis);output.writeUTF(p.mimeType);output.writeLong(p.durationMillis)}
            output.flush();atomic.finishWrite(stream);persisted=result
        }.onFailure{atomic.failWrite(stream)}
    }
    @Synchronized fun clear(context:Context){memory=null;persisted=null;owner="";file(context).delete()}
    @Synchronized fun forgetMemory(){memory=null;persisted=null;owner=""}
}
