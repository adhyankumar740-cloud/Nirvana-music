package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.ChatMessage
import com.example.data.model.JamSession
import com.example.data.model.MessageReaction
import com.example.data.model.Participant
import com.example.data.model.Track
import com.example.data.repository.ChatRepository
import com.example.data.repository.MusicRepository
import com.example.player.MusicPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class JamViewModel(
    private val chatRepository: ChatRepository,
    private val musicRepository: MusicRepository,
    val musicPlayer: MusicPlayer
) : ViewModel() {

    private val jamId = "harmonix_default_jam"

    // Real-time group chat message flow from Room
    val messages: StateFlow<List<ChatMessage>> = chatRepository.getMessages(jamId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val participants: StateFlow<List<Participant>> = chatRepository.participants

    private val _activeJam = MutableStateFlow<JamSession?>(null)
    val activeJam = _activeJam.asStateFlow()

    // Interactive reply-to message holder
    private val _replyMessage = MutableStateFlow<ChatMessage?>(null)
    val replyMessage = _replyMessage.asStateFlow()

    private val _isUserTyping = MutableStateFlow(false)
    val isUserTyping = _isUserTyping.asStateFlow()

    init {
        joinDefaultJam()
        observePlayerAndTriggerChat()
    }

    private fun joinDefaultJam() {
        viewModelScope.launch {
            // Pre-load default comments if db is empty
            chatRepository.clearHistory(jamId)
            
            _activeJam.value = JamSession(
                id = jamId,
                name = "Acoustic Late-Night Vibe 🎧",
                hostId = "user_1",
                currentTrack = null,
                isPlaying = false,
                playbackPosition = 0L,
                participants = chatRepository.participants.value
            )
            
            // Insert introductory welcome messages from participants
            val welcomeMsgs = listOf(
                ChatMessage(
                    id = "welcome_1",
                    jamId = jamId,
                    senderId = "alice_id",
                    senderName = "Alice",
                    senderAvatarUrl = "avatar_alice",
                    text = "Hey guys! Welcome to the Jam room 🎵",
                    timestamp = System.currentTimeMillis() - 5000L,
                    status = com.example.data.model.MessageStatus.READ
                ),
                ChatMessage(
                    id = "welcome_2",
                    jamId = jamId,
                    senderId = "bob_id",
                    senderName = "Bob",
                    senderAvatarUrl = "avatar_bob",
                    text = "Yo! What track are we listening to first?",
                    timestamp = System.currentTimeMillis() - 3000L,
                    status = com.example.data.model.MessageStatus.READ
                )
            )
            for (msg in welcomeMsgs) {
                chatRepository.saveMessage(msg)
            }
        }
    }

    private fun observePlayerAndTriggerChat() {
        viewModelScope.launch {
            musicPlayer.currentTrack.collect { track ->
                track?.let {
                    // Update active jam track
                    _activeJam.value = _activeJam.value?.copy(currentTrack = it)
                    // Trigger interactive commentary from friends
                    chatRepository.triggerPeriodicComment(jamId, it.title, it.artist)
                }
            }
        }
    }

    fun setUserTyping(isTyping: Boolean) {
        _isUserTyping.value = isTyping
    }

    fun setReplyTo(message: ChatMessage?) {
        _replyMessage.value = message
    }

    fun sendMessage(text: String) {
        if (text.trim().isEmpty()) return
        viewModelScope.launch {
            val reply = _replyMessage.value
            chatRepository.sendUserMessage(jamId, text, reply)
            _replyMessage.value = null // clear reply container
        }
    }

    fun toggleReaction(messageId: String, emoji: String) {
        viewModelScope.launch {
            chatRepository.toggleReaction(messageId, jamId, emoji, "user_1")
        }
    }

    // Host skips or plays track inside the Jam
    fun hostPlayTrack(track: Track, tracksList: List<Track>) {
        musicPlayer.setQueue(tracksList, tracksList.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
        _activeJam.value = _activeJam.value?.copy(
            currentTrack = track,
            isPlaying = true
        )
    }

    class Factory(
        private val chatRepository: ChatRepository,
        private val musicRepository: MusicRepository,
        private val musicPlayer: MusicPlayer
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return JamViewModel(chatRepository, musicRepository, musicPlayer) as T
        }
    }
}
