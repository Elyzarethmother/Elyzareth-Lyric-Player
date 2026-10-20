package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.util.Log
import com.example.model.DspSettings
import com.example.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Phase 1 foundation: one MediaPlayer owner for the current app playback path.
 *
 * All MediaPlayer/Equalizer operations and position polling are confined to Main.
 * Public method signatures are preserved for the existing ViewModel and Compose UI.
 */
class AudioPlayerEngine(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    private var mediaPlayer: MediaPlayer? = null
    private var equalizer: Equalizer? = null
    private var tickerJob: Job? = null
    private var released = false

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _isEqSupported = MutableStateFlow(false)
    val isEqSupported: StateFlow<Boolean> = _isEqSupported.asStateFlow()

    private var currentSong: Song? = null
    private var currentDsp: DspSettings = DspSettings()
    private var onCompletionCallback: (() -> Unit)? = null

    init {
        startTicker()
    }

    fun setOnCompletionListener(listener: () -> Unit) {
        if (!released) onCompletionCallback = listener
    }

    /** Replaces the currently owned player; failed loads leave a stopped, empty player. */
    fun loadSong(song: Song) {
        if (released) return
        stop()
        currentSong = song
        _durationMs.value = 0L
        _currentPositionMs.value = 0L

        val uri = song.fileUri ?: return
        try {
            val player = MediaPlayer()
            mediaPlayer = player
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            player.setDataSource(appContext, uri)
            player.setOnCompletionListener {
                _isPlaying.value = false
                _currentPositionMs.value = _durationMs.value
                onCompletionCallback?.invoke()
            }
            player.prepare()
            _durationMs.value = player.duration.toLong().coerceAtLeast(0L)
            setupEqualizer(player.audioSessionId)
        } catch (e: Exception) {
            Log.e("AudioPlayerEngine", "Failed to load audio from URI ${song.fileUri}", e)
            releasePlayerOnly()
            _isPlaying.value = false
            _currentPositionMs.value = 0L
            _durationMs.value = 0L
            _isEqSupported.value = false
        }
    }

    fun play() {
        if (released) return
        val player = mediaPlayer ?: return
        try {
            if (!player.isPlaying) player.start()
            _isPlaying.value = true
        } catch (e: Exception) {
            _isPlaying.value = false
            Log.e("AudioPlayerEngine", "Error starting playback", e)
        }
    }

    fun pause() {
        if (released) return
        try {
            mediaPlayer?.takeIf { it.isPlaying }?.pause()
        } catch (e: Exception) {
            Log.e("AudioPlayerEngine", "Error pausing playback", e)
        } finally {
            _isPlaying.value = false
        }
    }

    fun togglePlayPause() {
        if (_isPlaying.value) pause() else play()
    }

    fun seekTo(positionMs: Long) {
        if (released) return
        val target = positionMs.coerceIn(0L, _durationMs.value)
        try {
            mediaPlayer?.seekTo(target.toInt())
            _currentPositionMs.value = target
        } catch (e: Exception) {
            Log.e("AudioPlayerEngine", "Error seeking to $target", e)
        }
    }

    /** Stops and releases the active media item, but keeps the engine reusable. */
    fun stop() {
        if (released) return
        _isPlaying.value = false
        releaseEqualizer()
        releasePlayerOnly()
        _currentPositionMs.value = 0L
    }

    private fun releasePlayerOnly() {
        val player = mediaPlayer
        mediaPlayer = null
        if (player != null) {
            try {
                if (player.isPlaying) player.stop()
            } catch (_: Exception) {
                // A player that failed prepare/start may not accept stop().
            }
            try {
                player.reset()
            } catch (_: Exception) {}
            try {
                player.release()
            } catch (_: Exception) {}
        }
    }

    private fun setupEqualizer(audioSessionId: Int) {
        releaseEqualizer()
        if (audioSessionId == 0) {
            _isEqSupported.value = false
            return
        }
        try {
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = currentDsp.isEnabled
                applyEqualizerBands(this, currentDsp)
            }
            _isEqSupported.value = true
        } catch (e: Exception) {
            Log.w("AudioPlayerEngine", "Hardware Equalizer unavailable for this session", e)
            _isEqSupported.value = false
            equalizer = null
        }
    }

    private fun releaseEqualizer() {
        try {
            equalizer?.enabled = false
            equalizer?.release()
        } catch (_: Exception) {}
        equalizer = null
        _isEqSupported.value = false
    }

    fun updateDsp(settings: DspSettings) {
        if (released) return
        currentDsp = settings
        equalizer?.let { eq ->
            try {
                eq.enabled = settings.isEnabled
                if (settings.isEnabled) applyEqualizerBands(eq, settings)
            } catch (e: Exception) {
                Log.e("AudioPlayerEngine", "Error applying DSP settings", e)
            }
        }
    }

    private fun applyEqualizerBands(eq: Equalizer, settings: DspSettings) {
        val numBands = eq.numberOfBands.toInt()
        val minLevel = eq.bandLevelRange[0]
        val maxLevel = eq.bandLevelRange[1]
        val bandGains = listOf(
            settings.band60Hz,
            settings.band230Hz,
            settings.band910Hz,
            settings.band3kHz,
            settings.band14kHz
        )
        for (i in 0 until numBands) {
            val gainDb = bandGains.getOrElse(i) { 0f }
            val millibels = (gainDb * 100).toInt().coerceIn(minLevel.toInt(), maxLevel.toInt())
            try {
                eq.setBandLevel(i.toShort(), millibels.toShort())
            } catch (_: Exception) {}
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive && !released) {
                val player = mediaPlayer
                if (_isPlaying.value && player != null) {
                    try {
                        _currentPositionMs.value = player.currentPosition.toLong().coerceAtLeast(0L)
                    } catch (_: Exception) {
                        // MediaPlayer may transition state between callbacks.
                    }
                }
                delay(100L)
            }
        }
    }

    /** Terminal lifecycle call; subsequent commands are ignored. */
    fun release() {
        if (released) return
        stop()
        released = true
        tickerJob?.cancel()
        tickerJob = null
        onCompletionCallback = null
    }
}
