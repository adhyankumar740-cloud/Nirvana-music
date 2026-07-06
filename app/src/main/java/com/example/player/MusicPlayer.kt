package com.example.player

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import com.example.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MusicPlayer(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    
    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var queue = listOf<Track>()
    private var currentIndex = -1

    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        queue = tracks
        currentIndex = startIndex
        if (queue.isNotEmpty() && currentIndex in queue.indices) {
            play(queue[currentIndex])
        }
    }

    fun play(track: Track) {
        scope.launch {
            try {
                if (_currentTrack.value?.id == track.id && mediaPlayer != null) {
                    resume()
                    return@launch
                }

                _isBuffering.value = true
                _isPlaying.value = false
                stop()

                _currentTrack.value = track
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(context, Uri.parse(track.previewUrl))
                    setOnPreparedListener { mp ->
                        _isBuffering.value = false
                        _duration.value = mp.duration.toLong()
                        mp.start()
                        _isPlaying.value = true
                        startProgressTracker()
                    }
                    setOnCompletionListener {
                        _isPlaying.value = false
                        skipNext()
                    }
                    setOnErrorListener { _, _, _ ->
                        _isBuffering.value = false
                        false
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                _isBuffering.value = false
                Log.e("MusicPlayer", "Error playing track", e)
            }
        }
    }

    fun pause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                _isPlaying.value = false
                stopProgressTracker()
            }
        }
    }

    fun resume() {
        mediaPlayer?.let {
            if (!it.isPlaying) {
                it.start()
                _isPlaying.value = true
                startProgressTracker()
            }
        }
    }

    fun stop() {
        stopProgressTracker()
        mediaPlayer?.let {
            try {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            } catch (e: Exception) {
                // ignore
            }
        }
        mediaPlayer = null
        _isPlaying.value = false
        _playbackPosition.value = 0L
    }

    fun seekTo(position: Long) {
        mediaPlayer?.let {
            it.seekTo(position.toInt())
            _playbackPosition.value = position
        }
    }

    fun skipNext() {
        if (queue.isNotEmpty()) {
            currentIndex = (currentIndex + 1) % queue.size
            play(queue[currentIndex])
        }
    }

    fun skipPrevious() {
        if (queue.isNotEmpty()) {
            currentIndex = if (currentIndex - 1 < 0) queue.size - 1 else currentIndex - 1
            play(queue[currentIndex])
        }
    }

    private fun startProgressTracker() {
        stopProgressTracker()
        progressJob = scope.launch {
            while (true) {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        _playbackPosition.value = it.currentPosition.toLong()
                    }
                }
                delay(500)
            }
        }
    }

    private fun stopProgressTracker() {
        progressJob?.cancel()
        progressJob = null
    }
}

/**
 * Optimized Player Manager for the Samples Vertical Swipe Feed.
 * Uses a preloading system where the next video/audio is buffered in the background
 * to ensure that swipe transitions feel completely instant.
 */
class SamplesPlayerManager(private val context: Context) {
    private var activePlayer: MediaPlayer? = null
    private var preloadedPlayer: MediaPlayer? = null

    private var activeTrackId = -1L
    private var preloadedTrackId = -1L

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Plays the selected track. If it was preloaded, starts instantly!
    fun playTrack(track: Track, nextTrack: Track?) {
        scope.launch {
            try {
                _isPlaying.value = false
                _playbackPosition.value = 0L

                // If this track is already preloaded, promote it to active!
                if (preloadedTrackId == track.id && preloadedPlayer != null) {
                    activePlayer?.release()
                    activePlayer = preloadedPlayer
                    activeTrackId = preloadedTrackId

                    preloadedPlayer = null
                    preloadedTrackId = -1L

                    activePlayer?.let { mp ->
                        _duration.value = mp.duration.toLong()
                        mp.start()
                        _isPlaying.value = true
                        _isBuffering.value = false
                        startProgressTracker()
                    }
                    Log.d("SamplesPlayer", "Instant autoplay via promoted preloaded player for: ${track.title}")
                } else {
                    // Standard preparation
                    _isBuffering.value = true
                    activePlayer?.release()
                    activePlayer = MediaPlayer().apply {
                        setDataSource(context, Uri.parse(track.previewUrl))
                        setOnPreparedListener { mp ->
                            _isBuffering.value = false
                            _duration.value = mp.duration.toLong()
                            mp.start()
                            _isPlaying.value = true
                            startProgressTracker()
                        }
                        setOnErrorListener { _, _, _ ->
                            _isBuffering.value = false
                            false
                        }
                        setOnCompletionListener {
                            _isPlaying.value = false
                        }
                        prepareAsync()
                    }
                    activeTrackId = track.id
                    Log.d("SamplesPlayer", "Standard buffer/play for: ${track.title}")
                }

                // Proactively preload the next track in the feed
                if (nextTrack != null) {
                    preloadNextTrack(nextTrack)
                }
            } catch (e: Exception) {
                _isBuffering.value = false
                Log.e("SamplesPlayer", "Error in samples playback", e)
            }
        }
    }

    private fun preloadNextTrack(track: Track) {
        if (preloadedTrackId == track.id) return // Already preloaded

        scope.launch(Dispatchers.IO) {
            try {
                preloadedPlayer?.release()
                preloadedPlayer = MediaPlayer().apply {
                    setDataSource(context, Uri.parse(track.previewUrl))
                    setOnPreparedListener {
                        Log.d("SamplesPlayer", "Successfully preloaded next track in background: ${track.title}")
                    }
                    setOnErrorListener { _, _, _ -> false }
                    prepareAsync()
                }
                preloadedTrackId = track.id
            } catch (e: Exception) {
                Log.e("SamplesPlayer", "Failed background preload of next track: ${track.title}", e)
            }
        }
    }

    fun pause() {
        activePlayer?.let {
            if (it.isPlaying) {
                it.pause()
                _isPlaying.value = false
                stopProgressTracker()
            }
        }
    }

    fun resume() {
        activePlayer?.let {
            if (!it.isPlaying) {
                it.start()
                _isPlaying.value = true
                startProgressTracker()
            }
        }
    }

    fun stop() {
        stopProgressTracker()
        activePlayer?.release()
        activePlayer = null
        activeTrackId = -1L

        preloadedPlayer?.release()
        preloadedPlayer = null
        preloadedTrackId = -1L

        _isPlaying.value = false
        _playbackPosition.value = 0L
    }

    private fun startProgressTracker() {
        stopProgressTracker()
        progressJob = scope.launch {
            while (true) {
                activePlayer?.let {
                    if (it.isPlaying) {
                        _playbackPosition.value = it.currentPosition.toLong()
                    }
                }
                delay(500)
            }
        }
    }

    private fun stopProgressTracker() {
        progressJob?.cancel()
        progressJob = null
    }
}
