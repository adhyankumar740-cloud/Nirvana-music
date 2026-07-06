package com.example.data.repository

import com.example.data.database.ChatMessageDao
import com.example.data.model.ChatMessage
import com.example.data.model.MessageReaction
import com.example.data.model.MessageStatus
import com.example.data.model.Participant
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatRepository(
    private val chatMessageDao: ChatMessageDao
) {
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    // Real-time active participant states
    private val _participants = MutableStateFlow<List<Participant>>(emptyList())
    val participants: StateFlow<List<Participant>> = _participants.asStateFlow()

    init {
        // Initialize active Jam participants
        _participants.value = listOf(
            Participant("user_1", "You (Host)", "avatar_me", isHost = true),
            Participant("alice_id", "Alice", "avatar_alice", isHost = false),
            Participant("bob_id", "Bob", "avatar_bob", isHost = false),
            Participant("charlie_id", "Charlie", "avatar_charlie", isHost = false)
        )
    }

    fun getMessages(jamId: String): Flow<List<ChatMessage>> {
        return chatMessageDao.getMessagesForJam(jamId).map { entities ->
            entities.map { ChatMessage.fromEntity(it, moshi) }
        }
    }

    suspend fun saveMessage(message: ChatMessage) = withContext(Dispatchers.IO) {
        chatMessageDao.insertMessage(message.toEntity(moshi))
    }

    suspend fun clearHistory(jamId: String) = withContext(Dispatchers.IO) {
        chatMessageDao.clearMessagesForJam(jamId)
    }

    // Handles user sending a message
    suspend fun sendUserMessage(jamId: String, text: String, replyTo: ChatMessage? = null) {
        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            jamId = jamId,
            senderId = "user_1",
            senderName = "You",
            senderAvatarUrl = "avatar_me",
            text = text,
            timestamp = System.currentTimeMillis(),
            replyToId = replyTo?.id,
            replyToText = replyTo?.text,
            replyToSenderName = replyTo?.senderName,
            reactions = emptyList(),
            status = MessageStatus.SENT
        )
        saveMessage(userMsg)

        // Simulate delivery & read receipt sequence
        CoroutineScope(Dispatchers.IO).launch {
            delay(400)
            saveMessage(userMsg.copy(status = MessageStatus.DELIVERED))
            delay(600)
            saveMessage(userMsg.copy(status = MessageStatus.READ))

            // Trigger AI group chat responses to keep the room dynamic
            simulateGroupChatFeedback(jamId, text, userMsg)
        }
    }

    // Add emoji reaction to a message
    suspend fun toggleReaction(messageId: String, jamId: String, emoji: String, userId: String) = withContext(Dispatchers.IO) {
        val entity = chatMessageDao.getMessageById(messageId) ?: return@withContext
        val message = ChatMessage.fromEntity(entity, moshi)
        
        val existingReactionIndex = message.reactions.indexOfFirst { it.emoji == emoji }
        val updatedReactions = message.reactions.toMutableList()
        
        if (existingReactionIndex != -1) {
            val rx = message.reactions[existingReactionIndex]
            val updatedUserIds = rx.userIds.toMutableList()
            if (updatedUserIds.contains(userId)) {
                updatedUserIds.remove(userId)
            } else {
                updatedUserIds.add(userId)
            }
            if (updatedUserIds.isEmpty()) {
                updatedReactions.removeAt(existingReactionIndex)
            } else {
                updatedReactions[existingReactionIndex] = rx.copy(userIds = updatedUserIds)
            }
        } else {
            updatedReactions.add(MessageReaction(emoji = emoji, userIds = listOf(userId)))
        }
        
        val updatedMessage = message.copy(reactions = updatedReactions)
        saveMessage(updatedMessage)
    }

    // Set typing indicator
    fun setParticipantTyping(participantId: String, isTyping: Boolean) {
        _participants.value = _participants.value.map {
            if (it.id == participantId) it.copy(isTyping = isTyping) else it
        }
    }

    // Simulation engine for highly engaging group chat
    private fun simulateGroupChatFeedback(jamId: String, userText: String, parentMsg: ChatMessage) {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            // 1. Chance of immediate reaction from a participant
            val responder = _participants.value.filter { it.id != "user_1" }.random()
            delay(1000)
            
            // Add reaction to user's message
            val reactionEmoji = when {
                userText.contains("🔥", true) || userText.contains("lit", true) || userText.contains("insane", true) -> "🔥"
                userText.contains("love", true) || userText.contains("favorite", true) -> "❤️"
                userText.contains("haha", true) || userText.contains("lol", true) -> "😂"
                else -> listOf("👍", "🔥", "❤️", "🙌").random()
            }
            
            // Simulating reaction addition by updating the DB entry directly
            addReactionToMessage(jamId, parentMsg.id, reactionEmoji, responder.id)

            // 2. Chance of text response (replying to the user)
            delay(1200)
            val typer = _participants.value.filter { it.id != "user_1" && it.id != responder.id }.random()
            
            // Show typing indicator
            setParticipantTyping(typer.id, true)
            delay(1800) // typing duration
            setParticipantTyping(typer.id, false)

            val replyText = getSmartCommentary(userText, parentMsg.senderName)
            val replyMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                jamId = jamId,
                senderId = typer.id,
                senderName = typer.name,
                senderAvatarUrl = typer.avatarUrl,
                text = replyText,
                timestamp = System.currentTimeMillis(),
                replyToId = parentMsg.id,
                replyToText = parentMsg.text,
                replyToSenderName = parentMsg.senderName,
                reactions = emptyList(),
                status = MessageStatus.READ
            )
            saveMessage(replyMsg)
        }
    }

    private suspend fun addReactionToMessage(jamId: String, messageId: String, emoji: String, userId: String) {
        toggleReaction(messageId, jamId, emoji, userId)
    }

    private fun getSmartCommentary(text: String, sender: String): String {
        return when {
            text.contains("favorite", true) || text.contains("love", true) -> {
                listOf(
                    "Agreed! Added this to my playlist too.",
                    "Yesss, this track is on repeat!",
                    "Absolute masterclass of a track.",
                    "The bass line here is perfect."
                ).random()
            }
            text.contains("skip", true) -> {
                listOf(
                    "Aw, let's play some lo-fi next?",
                    "Haha alright, what should we queue next?",
                    "No worries, let's find a solid beat."
                ).random()
            }
            text.contains("vib", true) || text.contains("🔥", true) -> {
                listOf(
                    "The vibes are immaculate!",
                    "This Jam hits different tonight.",
                    "Preach! 🎵🔥",
                    "Perfect choice for the late night drive."
                ).random()
            }
            else -> {
                listOf(
                    "This is an absolute bop!",
                    "Wow, who queued this track? Respect.",
                    "That transition was clean.",
                    "Such a vibe, loving the rhythm here.",
                    "Adding this to my favorites right now."
                ).random()
            }
        }
    }

    // Simulate periodic comments about current playing track
    fun triggerPeriodicComment(jamId: String, songTitle: String, artistName: String) {
        CoroutineScope(Dispatchers.IO).launch {
            delay(3000) // Delay after song starts playing
            val talker = _participants.value.filter { it.id != "user_1" }.random()
            
            setParticipantTyping(talker.id, true)
            delay(1500)
            setParticipantTyping(talker.id, false)

            val comment = listOf(
                "Wow, '$songTitle' by $artistName is literal fire!",
                "Oh, I love $artistName! Good choice.",
                "This intro is so beautiful.",
                "Perfect choice for the Jam! 🎧✨",
                "This beat drop never gets old."
            ).random()

            val msg = ChatMessage(
                id = UUID.randomUUID().toString(),
                jamId = jamId,
                senderId = talker.id,
                senderName = talker.name,
                senderAvatarUrl = talker.avatarUrl,
                text = comment,
                timestamp = System.currentTimeMillis(),
                reactions = emptyList(),
                status = MessageStatus.READ
            )
            saveMessage(msg)
        }
    }
}
