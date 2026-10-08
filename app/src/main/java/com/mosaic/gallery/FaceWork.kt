package com.mosaic.gallery

import android.os.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** All graph writers share one lock; an edit cancels automatic inference at its next safe boundary. */
object FaceWork {
    private val lock=ReentrantLock()
    @Volatile var automatic=false;private set
    @Volatile var stopAutomatic=false;private set
    @Volatile var editingUntil=0L;private set
    private val main=Handler(Looper.getMainLooper())
    private val listeners=mutableSetOf<()->Unit>()
    fun observe(listener:()->Unit){listeners+=listener}
    fun remove(listener:()->Unit){listeners-=listener}
    fun pauseForEdit(){editingUntil=SystemClock.elapsedRealtime()+60_000;stopAutomatic=true}
    fun begin():Boolean {if(automatic || FaceJobs.state.busy || SystemClock.elapsedRealtime()<editingUntil)return false;automatic=true;stopAutomatic=false;return true}
    fun end(){automatic=false;main.post{listeners.toList().forEach{it()}}}
    fun <T> write(block:()->T):T=lock.withLock(block)
}
