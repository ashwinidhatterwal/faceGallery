package com.mosaic.gallery

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** One decoder for the selected page. Local playback never outlives the visible viewer. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class GalleryVideoView(context:Context):FrameLayout(context) {
    data class State(val uri:String,val position:Long,val playing:Boolean)
    private val screen=LayoutInflater.from(context).inflate(R.layout.gallery_video,this,false) as PlayerView
    private val controls=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(GalleryStyle.dp(context,8),0,GalleryStyle.dp(context,8),0);background=android.graphics.drawable.GradientDrawable().apply{setColor(GalleryStyle.surface(context));cornerRadius=GalleryStyle.dp(context,18).toFloat()};elevation=GalleryStyle.dp(context,2).toFloat()}
    private val play=ImageButton(context).apply{background=GalleryStyle.action(context,"play","Play",compact=true){}.background;contentDescription="Pause video"}
    private val elapsed=GalleryStyle.text(context,"0:00",12f)
    private val remaining=GalleryStyle.text(context,"0:00",12f,GalleryStyle.muted(context))
    private val seek=SeekBar(context).apply{max=1000;progressTintList=ColorStateList.valueOf(GalleryStyle.accent(context));thumbTintList=progressTintList;progressBackgroundTintList=ColorStateList.valueOf(GalleryStyle.dividerColor(context));contentDescription="Video position";minimumHeight=GalleryStyle.dp(context,48);splitTrack=false}
    private val error=GalleryStyle.button(context,"Retry video"){stopPlayback();wantsPlay=true;activate()}.apply{visibility=View.GONE}
    private var player:ExoPlayer?=null
    private var uri="";private var position=0L;private var wantsPlay=true
    private var controlsShown=true;private var scrubbing=false;private var resumeAfterSeek=false;private var selected=false
    private val ticker=object:Runnable{override fun run(){update();if(selected)postDelayed(this,250)}}
    var onTap:()->Unit={}
    init {
        addView(screen,LayoutParams(-1,-1))
        controls.addView(play,LinearLayout.LayoutParams(GalleryStyle.dp(context,48),GalleryStyle.dp(context,48)))
        controls.addView(elapsed);controls.addView(seek,LinearLayout.LayoutParams(0,GalleryStyle.dp(context,48),1f));controls.addView(remaining)
        addView(controls,LayoutParams(-1,GalleryStyle.dp(context,48),Gravity.BOTTOM).apply{setMargins(GalleryStyle.dp(context,16),0,GalleryStyle.dp(context,16),GalleryStyle.dp(context,12))})
        addView(error,LayoutParams(-2,-2,Gravity.CENTER))
        screen.setOnClickListener{onTap()}
        play.setOnClickListener{player?.let{p->if(p.playWhenReady && p.playbackState!=Player.STATE_ENDED)p.pause()else{if(p.playbackState==Player.STATE_ENDED)p.seekTo(0);p.play()};wantsPlay=p.playWhenReady;update()}}
        seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onStartTrackingTouch(bar:SeekBar){scrubbing=true;resumeAfterSeek=player?.playWhenReady==true;player?.pause();parent?.requestDisallowInterceptTouchEvent(true)}
            override fun onProgressChanged(bar:SeekBar,value:Int,fromUser:Boolean){if(fromUser){val d=duration();elapsed.text=GalleryMedia.time(d*value/1000)}}
            override fun onStopTrackingTouch(bar:SeekBar){val p=player;val d=duration();if(p!=null && d>0){position=d*bar.progress/1000;p.seekTo(position);if(resumeAfterSeek)p.play()};scrubbing=false;wantsPlay=p?.let{it.playWhenReady && it.playbackState!=Player.STATE_ENDED}?:wantsPlay;parent?.requestDisallowInterceptTouchEvent(false);update()}
        })
    }
    fun bind(photo:PhotoRecord,state:State?=null){
        if(uri!=photo.uri.toString()){stopPlayback();uri=photo.uri.toString();position=0;wantsPlay=true}
        if(state?.uri==uri){position=state.position.coerceAtLeast(0);wantsPlay=state.playing}
        remaining.text=GalleryMedia.time(photo.durationMillis);seek.isEnabled=false
    }
    fun state():State {val p=player;return State(uri,p?.currentPosition?:position,if(scrubbing)resumeAfterSeek else p?.let{it.playWhenReady && it.playbackState!=Player.STATE_ENDED}?:wantsPlay)}
    fun activate(){
        if(uri.isEmpty())return
        selected=true;visibility=View.VISIBLE;error.visibility=View.GONE;controlsVisible(controlsShown)
        if(player==null){
            val p=ExoPlayer.Builder(context).setHandleAudioBecomingNoisy(true).build();player=p
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
            p.addListener(object:Player.Listener{
                override fun onPlaybackStateChanged(state:Int){if(state==Player.STATE_ENDED)wantsPlay=false;update()}
                override fun onIsPlayingChanged(playing:Boolean){keepScreenOn=playing;update()}
                override fun onPlayerError(e:PlaybackException){keepScreenOn=false;error.visibility=View.VISIBLE;error.contentDescription="Video unavailable. Retry playback";controls.visibility=View.GONE}
            })
            screen.player=p;p.setMediaItem(MediaItem.fromUri(uri),position);p.prepare();p.playWhenReady=wantsPlay
        }
        removeCallbacks(ticker);post(ticker)
    }
    fun stopPlayback(){
        val saved=state();position=saved.position;wantsPlay=saved.playing
        selected=false;removeCallbacks(ticker);keepScreenOn=false;scrubbing=false
        screen.player=null;player?.release();player=null;visibility=View.GONE
    }
    fun controlsVisible(value:Boolean){controlsShown=value;controls.visibility=if(value && error.visibility!=View.VISIBLE)View.VISIBLE else View.GONE}
    private fun duration()=player?.duration?.takeIf{it>0 && it!=C.TIME_UNSET}?:0
    private fun update(){
        val p=player;val d=duration();seek.isEnabled=d>0 && p?.isCurrentMediaItemSeekable==true
        val playing=p?.playWhenReady==true && p.playbackState!=Player.STATE_ENDED
        play.setImageDrawable(GalleryStyle.icon(context,if(playing)"pause"else"play"));play.contentDescription=if(playing)"Pause video"else if(p?.playbackState==Player.STATE_ENDED)"Replay video"else"Play video"
        if(!scrubbing){val now=p?.currentPosition?:position;elapsed.text=GalleryMedia.time(now);if(d>0){seek.progress=(now*1000/d).toInt().coerceIn(0,1000);remaining.text=GalleryMedia.time(d)}}
    }
}
