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
    data class State(val uri:String,val position:Long,val playing:Boolean,val muted:Boolean=false)
    private val screen=LayoutInflater.from(context).inflate(R.layout.gallery_video,this,false) as PlayerView
    private val controls=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(GalleryStyle.dp(context,8),0,GalleryStyle.dp(context,8),0);isClickable=true}
    private fun control(label:String)=ImageButton(context).apply{background=GalleryStyle.action(context,"play",label,compact=true){}.background;contentDescription=label;setPadding(GalleryStyle.dp(context,14),GalleryStyle.dp(context,14),GalleryStyle.dp(context,14),GalleryStyle.dp(context,14));scaleType=ImageView.ScaleType.FIT_CENTER}
    private val play=control("Play video")
    private val mute=control("Mute video")
    private val time=GalleryStyle.text(context,"0:00/0:00",13f,android.graphics.Color.WHITE).apply{maxLines=1;gravity=Gravity.CENTER_VERTICAL;fontFeatureSettings="tnum";setShadowLayer(3f,0f,1f,android.graphics.Color.BLACK)}
    private val seek=VideoSeekBar(context)
    private var knownDuration=0L
    private val error=GalleryStyle.button(context,"Retry video"){stopPlayback();wantsPlay=true;activate()}.apply{visibility=View.GONE}
    private var player:ExoPlayer?=null
    private var uri="";private var position=0L;private var wantsPlay=true
    private var muted=false
    private var playIcon="";private var muteIcon=""
    private var controlsShown=true;private var scrubbing=false;private var resumeAfterSeek=false;private var selected=false
    private val ticker=object:Runnable{override fun run(){update();if(selected && controlsShown && controls.visibility==View.VISIBLE)postDelayed(this,if(player?.isPlaying==true && !scrubbing)100 else 500)}}
    var onTap:()->Unit={}
    init {
        addView(screen,LayoutParams(-1,-1))
        controls.addView(seek,LinearLayout.LayoutParams(-1,GalleryStyle.dp(context,48)))
        val buttons=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL}
        buttons.addView(play,LinearLayout.LayoutParams(GalleryStyle.dp(context,56),GalleryStyle.dp(context,48)))
        buttons.addView(time,LinearLayout.LayoutParams(0,-1,1f).apply{leftMargin=GalleryStyle.dp(context,4)})
        buttons.addView(mute,LinearLayout.LayoutParams(GalleryStyle.dp(context,48),GalleryStyle.dp(context,48)))
        controls.addView(buttons,LinearLayout.LayoutParams(-1,GalleryStyle.dp(context,48)))
        addView(controls,LayoutParams(-1,GalleryStyle.dp(context,96),Gravity.BOTTOM).apply{setMargins(GalleryStyle.dp(context,8),0,GalleryStyle.dp(context,8),GalleryStyle.dp(context,8))})
        addView(error,LayoutParams(-2,-2,Gravity.CENTER))
        screen.setOnClickListener{onTap()}
        play.setOnClickListener{player?.let{p->if(p.playWhenReady && p.playbackState!=Player.STATE_ENDED)p.pause()else{if(p.playbackState==Player.STATE_ENDED)p.seekTo(0);p.play()};wantsPlay=p.playWhenReady;update()}}
        mute.setOnClickListener{muted=!muted;player?.volume=if(muted)0f else 1f;update()}
        seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onStartTrackingTouch(bar:SeekBar){scrubbing=true;resumeAfterSeek=player?.playWhenReady==true;player?.pause();parent?.requestDisallowInterceptTouchEvent(true)}
            override fun onProgressChanged(bar:SeekBar,value:Int,fromUser:Boolean){if(fromUser){val d=duration();showTime(d*value/10000,d);if(!scrubbing)player?.seekTo(d*value/10000)}}
            override fun onStopTrackingTouch(bar:SeekBar){val p=player;val d=duration();if(p!=null && d>0 && !seek.cancelled){position=d*bar.progress/10000;p.seekTo(position);};if(resumeAfterSeek)p?.play();scrubbing=false;wantsPlay=p?.let{it.playWhenReady && it.playbackState!=Player.STATE_ENDED}?:wantsPlay;parent?.requestDisallowInterceptTouchEvent(false);update()}
        })
    }
    fun bind(photo:PhotoRecord,state:State?=null){
        if(uri!=photo.uri.toString()){stopPlayback();uri=photo.uri.toString();position=0;wantsPlay=true}
        if(state?.uri==uri){position=state.position.coerceAtLeast(0);wantsPlay=state.playing;muted=state.muted}
        knownDuration=photo.durationMillis;seek.isEnabled=false;update()
    }
    fun state():State {val p=player;return State(uri,p?.currentPosition?:position,if(scrubbing)resumeAfterSeek else p?.let{it.playWhenReady && it.playbackState!=Player.STATE_ENDED}?:wantsPlay,muted)}
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
            screen.player=p;p.setMediaItem(MediaItem.fromUri(uri),position);p.volume=if(muted)0f else 1f;p.prepare();p.playWhenReady=wantsPlay
        }
        removeCallbacks(ticker);if(controlsShown)post(ticker)
    }
    fun stopPlayback(){
        val saved=state();position=saved.position;wantsPlay=saved.playing
        selected=false;removeCallbacks(ticker);keepScreenOn=false;scrubbing=false
        screen.player=null;player?.release();player=null;visibility=View.GONE
    }
    fun controlsVisible(value:Boolean){controlsShown=value;controls.visibility=if(value && error.visibility!=View.VISIBLE)View.VISIBLE else View.GONE;removeCallbacks(ticker);if(selected && value)post(ticker)}
    private fun duration()=player?.duration?.takeIf{it>0 && it!=C.TIME_UNSET}?:0
    private fun update(){
        val p=player;val d=duration();seek.isEnabled=d>0 && p?.isCurrentMediaItemSeekable==true
        val playing=p?.playWhenReady==true && p.playbackState!=Player.STATE_ENDED
        val nextPlay=if(playing)"pause"else"play";if(playIcon!=nextPlay){playIcon=nextPlay;play.setImageDrawable(GalleryStyle.icon(context,nextPlay,android.graphics.Color.WHITE))};play.contentDescription=if(playing)"Pause video"else if(p?.playbackState==Player.STATE_ENDED)"Replay video"else"Play video"
        val nextMute=if(muted)"volumeOff"else"volumeOn";if(muteIcon!=nextMute){muteIcon=nextMute;mute.setImageDrawable(GalleryStyle.icon(context,nextMute,android.graphics.Color.WHITE))};mute.contentDescription=if(muted)"Unmute video"else"Mute video";mute.isSelected=muted
        if(!scrubbing){val now=p?.currentPosition?:position;val total=if(d>0)d else knownDuration;showTime(now,total);seek.playback(now,total,p?.bufferedPosition?:now,p?.isPlaying==true,p?.playbackParameters?.speed?:1f)}
    }
    private fun showTime(position:Long,duration:Long){val label="${GalleryMedia.time(position)}/${GalleryMedia.time(duration)}";if(time.text.toString()!=label)time.text=label}
    fun controlsHit(x:Float,y:Float)=visibility==View.VISIBLE && controls.visibility==View.VISIBLE && x>=controls.left && x<controls.right && y>=controls.top && y<controls.bottom
}
