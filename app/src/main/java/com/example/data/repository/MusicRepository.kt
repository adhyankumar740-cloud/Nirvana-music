package com.example.data.repository

import com.example.data.database.SavedTrackDao
import com.example.data.database.SavedTrackEntity
import com.example.data.model.Track
import com.example.data.network.ITunesService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class MusicRepository(
    private val apiService: ITunesService,
    private val savedTrackDao: SavedTrackDao
) {
    // Cache for Samples feed to ensure instant load times
    private val samplesCache = mutableListOf<Track>()

    fun getSamplesFeed(term: String = "top hit"): Flow<List<Track>> = flow {
        if (samplesCache.isNotEmpty() && term == "top hit") {
            emit(samplesCache)
        }

        try {
            val response = apiService.search(term = term, limit = 40)
            val tracks = response.results
                .filter { !it.previewUrl.isNullOrEmpty() }
                .map { it.toTrack() }
            
            // Check database to see if already saved
            val enrichedTracks = tracks.map { track ->
                val localEntity = savedTrackDao.getSavedTrackById(track.id)
                track.copy(
                    isDownloaded = localEntity?.isDownloaded ?: false,
                    isFavorite = localEntity?.isFavorite ?: false
                )
            }

            if (term == "top hit") {
                samplesCache.clear()
                samplesCache.addAll(enrichedTracks)
            }
            emit(enrichedTracks)
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to cache or local downloads if network fails
            if (samplesCache.isNotEmpty()) {
                emit(samplesCache)
            } else {
                // Emit empty or empty list, caller can fetch from DB
                emit(emptyList())
            }
        }
    }.flowOn(Dispatchers.IO)

    fun searchTracks(query: String): Flow<List<Track>> = flow {
        try {
            val response = apiService.search(term = query, limit = 30)
            val tracks = response.results.map { it.toTrack() }
            val enriched = tracks.map { track ->
                val localEntity = savedTrackDao.getSavedTrackById(track.id)
                track.copy(
                    isDownloaded = localEntity?.isDownloaded ?: false,
                    isFavorite = localEntity?.isFavorite ?: false
                )
            }
            emit(enriched)
        } catch (e: Exception) {
            emit(emptyList())
        }
    }.flowOn(Dispatchers.IO)

    fun getSavedTracks(): Flow<List<Track>> {
        return savedTrackDao.getAllSavedTracks().map { list ->
            list.map { it.toTrack() }
        }
    }

    fun getDownloadedTracks(): Flow<List<Track>> {
        return savedTrackDao.getDownloadedTracks().map { list ->
            list.map { it.toTrack() }
        }
    }

    fun getFavoriteTracks(): Flow<List<Track>> {
        return savedTrackDao.getFavoriteTracks().map { list ->
            list.map { it.toTrack() }
        }
    }

    suspend fun toggleFavorite(track: Track) = withContext(Dispatchers.IO) {
        val existing = savedTrackDao.getSavedTrackById(track.id)
        if (existing != null) {
            val updated = existing.copy(isFavorite = !existing.isFavorite)
            if (!updated.isFavorite && !updated.isDownloaded) {
                // Remove entirely if neither favorited nor downloaded
                savedTrackDao.deleteSavedTrackById(track.id)
            } else {
                savedTrackDao.insertSavedTrack(updated)
            }
        } else {
            val entity = SavedTrackEntity.fromTrack(track, isFavorite = true)
            savedTrackDao.insertSavedTrack(entity)
        }
    }

    suspend fun toggleDownload(track: Track) = withContext(Dispatchers.IO) {
        val existing = savedTrackDao.getSavedTrackById(track.id)
        if (existing != null) {
            val updated = existing.copy(isDownloaded = !existing.isDownloaded)
            if (!updated.isFavorite && !updated.isDownloaded) {
                savedTrackDao.deleteSavedTrackById(track.id)
            } else {
                savedTrackDao.insertSavedTrack(updated)
            }
        } else {
            val entity = SavedTrackEntity.fromTrack(track, isDownloaded = true)
            savedTrackDao.insertSavedTrack(entity)
        }
    }

    suspend fun isTrackFavorite(trackId: Long): Boolean = withContext(Dispatchers.IO) {
        savedTrackDao.getSavedTrackById(trackId)?.isFavorite ?: false
    }

    suspend fun isTrackDownloaded(trackId: Long): Boolean = withContext(Dispatchers.IO) {
        savedTrackDao.getSavedTrackById(trackId)?.isDownloaded ?: false
    }
}
