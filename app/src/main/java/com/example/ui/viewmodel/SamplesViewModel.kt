package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.Track
import com.example.data.repository.MusicRepository
import com.example.player.SamplesPlayerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SamplesViewModel(
    private val repository: MusicRepository,
    val playerManager: SamplesPlayerManager
) : ViewModel() {

    private val _samples = MutableStateFlow<List<Track>>(emptyList())
    val samples = _samples.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex = _currentIndex.asStateFlow()

    init {
        loadSamples()
    }

    fun loadSamples() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.getSamplesFeed("trending hit").collectLatest { tracks ->
                _samples.value = tracks
                _isLoading.value = false
                
                // Play first sample automatically
                if (tracks.isNotEmpty()) {
                    playSampleAtIndex(0)
                }
            }
        }
    }

    fun onSwipe(newIndex: Int) {
        if (newIndex in _samples.value.indices) {
            _currentIndex.value = newIndex
            playSampleAtIndex(newIndex)
        }
    }

    private fun playSampleAtIndex(index: Int) {
        val tracksList = _samples.value
        if (tracksList.isEmpty() || index !in tracksList.indices) return

        val currentTrack = tracksList[index]
        val nextTrack = if (index + 1 in tracksList.indices) tracksList[index + 1] else null
        playerManager.playTrack(currentTrack, nextTrack)
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            repository.toggleFavorite(track)
            // Update sample in place
            _samples.value = _samples.value.map {
                if (it.id == track.id) it.copy(isFavorite = !it.isFavorite) else it
            }
        }
    }

    fun toggleDownload(track: Track) {
        viewModelScope.launch {
            repository.toggleDownload(track)
            _samples.value = _samples.value.map {
                if (it.id == track.id) it.copy(isDownloaded = !it.isDownloaded) else it
            }
        }
    }

    // Opens track + artist in official YouTube app or browser fallback
    fun openOnYouTube(context: Context, track: Track) {
        val query = "${track.title} ${track.artist}"
        val encodedQuery = Uri.encode(query)
        val appUri = Uri.parse("vnd.youtube:results?search_query=$encodedQuery")
        val webUri = Uri.parse("https://www.youtube.com/results?search_query=$encodedQuery")

        val appIntent = Intent(Intent.ACTION_VIEW, appUri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            context.startActivity(appIntent)
        } catch (e: Exception) {
            // Fallback to web browser search
            context.startActivity(webIntent)
        }
    }

    override fun onCleared() {
        super.onCleared()
        playerManager.stop()
    }

    class Factory(
        private val repository: MusicRepository,
        private val playerManager: SamplesPlayerManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SamplesViewModel(repository, playerManager) as T
        }
    }
}
