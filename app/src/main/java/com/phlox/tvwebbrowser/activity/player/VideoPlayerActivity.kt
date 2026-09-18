package com.phlox.tvwebbrowser.activity.player

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.phlox.tvwebbrowser.R

/**
 * Native player for direct video links (mp4/m3u8/mpd) that WebView can't play inline -
 * TV Bro's own download flow used to just save these to disk instead of playing them.
 * Remote control: OK = play/pause, Left/Right = 10s seek.
 */
class VideoPlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var videoUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: ""
        playerView = findViewById(R.id.player_view)
        playerView.useController = true
        playerView.controllerShowTimeoutMs = 5000

        findViewById<android.widget.ImageButton>(R.id.btnClose).setOnClickListener { finish() }

        initPlayer()
    }

    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)))
            exo.prepare()
            exo.playWhenReady = true
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    error.printStackTrace()
                }
            })
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        player?.let { p ->
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (p.isPlaying) p.pause() else p.play()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    p.seekTo((p.currentPosition + 10000).coerceAtMost(p.duration.coerceAtLeast(0)))
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    p.seekTo((p.currentPosition - 10000).coerceAtLeast(0))
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (playerView.isControllerFullyVisible) {
                        playerView.hideController()
                        return true
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        if (!isInPictureInPictureMode) {
            player?.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isInPictureInPictureMode) {
            player?.play()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    //Picture-in-Picture: pressing Home while a video is playing shrinks it into a small
    //window instead of stopping playback, matching how e.g. YouTube behaves on Android TV.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true) {
            enterPipMode()
        }
    }

    private fun enterPipMode() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> {
                @Suppress("DEPRECATION")
                enterPictureInPictureMode()
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerView.useController = !isInPictureInPictureMode
        findViewById<View>(R.id.btnClose).visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }

    companion object {
        const val EXTRA_VIDEO_URL = "VIDEO_URL"
    }
}
