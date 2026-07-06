package com.example.di

import android.content.Context
import com.example.data.database.MusicDatabase
import com.example.data.network.ITunesService
import com.example.data.repository.ChatRepository
import com.example.data.repository.MusicRepository
import com.example.player.MusicPlayer
import com.example.player.SamplesPlayerManager

class AppContainer(private val context: Context) {
    
    val database: MusicDatabase by lazy {
        MusicDatabase.getDatabase(context)
    }

    val apiService: ITunesService by lazy {
        ITunesService.create()
    }

    val musicRepository: MusicRepository by lazy {
        MusicRepository(apiService, database.savedTrackDao())
    }

    val chatRepository: ChatRepository by lazy {
        ChatRepository(database.chatMessageDao())
    }

    val musicPlayer: MusicPlayer by lazy {
        MusicPlayer(context)
    }

    val samplesPlayerManager: SamplesPlayerManager by lazy {
        SamplesPlayerManager(context)
    }
}
