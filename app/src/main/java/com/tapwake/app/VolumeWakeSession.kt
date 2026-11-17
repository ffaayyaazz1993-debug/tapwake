package com.tapwake.app

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent

/**
 * Screen-off volume key wake via an active MediaSession with a remote volume
 * provider and a silent track on STREAM_MUSIC.
 *
 * Why: with the display blanked, PhoneWindowManager consumes the hardware key
 * before the accessibility input pipeline and may skip the volume change if
 * nothing is playing on the music stream. An active media session that claims
 * remote volume control is how the system is told to route those keys to us.
 *
 * The session is only active while the screen is off, so normal volume button
 * behaviour is untouched when the screen is on.
 */
class VolumeWakeSession(
    private val context: Context,
    private val onWake: (source: String) -> Unit,
) {
    private var session: MediaSession? = null
    private var silentTrack: AudioTrack? = null
    private var active = false

    fun isActive(): Boolean = active

    fun activate() {
        if (active) return
        try {
            // A tiny buffer of digital silence: keeps the music stream "in use"
            // so volume key events are not dropped as irrelevant.
            val sampleRate = 44100
            val channel = AudioFormat.CHANNEL_OUT_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channel, encoding)
            val bufferSize = if (minBuffer > 0) minBuffer else 8820
            val buffer = ByteArray(bufferSize) // 16-bit mono, all zero
            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(channel)
                .build()
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            try {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(buffer.size)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    track.write(buffer, 0, buffer.size)
                    track.play()
                    silentTrack = track
                    Log.d("TAPWAK", "Silent media track playing")
                } else {
                    track.release()
                    Log.w("TAPWAK", "Silent media track unavailable (state=${track.state}) - session still active")
                }
            } catch (e: Exception) {
                Log.w("TAPWAK", "Silent media track failed - session still active", e)
            }

            val provider = object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 1, 0) {
                override fun onAdjustVolume(direction: Int) {
                    if (direction == AudioManager.ADJUST_SAME) return
                    Log.d("TAPWAK", "Volume key via media session direction=$direction")
                    onWake("volume")
                    setCurrentVolume(0)
                }

                override fun onSetVolumeTo(volume: Int) {
                    Log.d("TAPWAK", "Volume set via media session value=$volume")
                    onWake("volume")
                    setCurrentVolume(0)
                }
            }

            session = MediaSession(context, "TapWakeVolume").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                        val key = mediaButtonEvent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                        if (key?.action == KeyEvent.ACTION_DOWN &&
                            (key.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                                key.keyCode == KeyEvent.KEYCODE_HEADSETHOOK)
                        ) {
                            Log.d("TAPWAK", "Headset media button via media session")
                            onWake("media button")
                            return true
                        }
                        return super.onMediaButtonEvent(mediaButtonEvent)
                    }
                })
                setPlaybackState(
                    PlaybackState.Builder()
                        .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                        .build()
                )
                setPlaybackToRemote(provider)
                isActive = true
            }
            active = true
            Log.d("TAPWAK", "Volume media session active (screen off)")
        } catch (e: Exception) {
            Log.w("TAPWAK", "Cannot start volume media session", e)
            deactivate()
        }
    }

    fun deactivate() {
        try {
            session?.let {
                it.isActive = false
                it.release()
            }
            silentTrack?.let {
                try { it.stop() } catch (_: IllegalStateException) { }
                it.release()
            }
        } catch (e: Exception) {
            Log.w("TAPWAK", "Error releasing volume media session", e)
        } finally {
            session = null
            silentTrack = null
            active = false
        }
    }

    companion object {
        fun screenIsOff(context: Context): Boolean {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            return power?.isInteractive != true
        }
    }
}
