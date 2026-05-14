package com.github.itskenny0.r1ha.feature.assist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Assist (HA Conversation) surface. Holds the conversation
 * transcript and the [conversationId] threaded across HA's
 * `conversation/process` calls so multi-turn context survives (so the
 * user can say "turn off the light" then "the kitchen one" and HA's
 * intent engine carries the device-class state forward).
 *
 * Text-only for now — no STT / TTS. The R1 has a microphone and the HA
 * Assist pipeline can do server-side STT, so adding voice is a follow-
 * up rather than a refactor: the audio frames pump into the same
 * /api/conversation/process endpoint or the dedicated Assist pipeline
 * websocket.
 */
@androidx.compose.runtime.Stable
data class AssistMessage(
    val text: String,
    val fromUser: Boolean,
    /** HA's response_type ("action_done" / "query_answer" / "error") for
     *  the bubble's accent colour; null for user-side messages. */
    val responseType: String? = null,
    /** Monotonically-increasing id so LazyColumn can key the rows
     *  stably even when two identical-text messages land back-to-back. */
    val id: Long = System.nanoTime(),
)

@androidx.compose.runtime.Stable
data class AssistUiState(
    val messages: List<AssistMessage> = emptyList(),
    val inFlight: Boolean = false,
    val draft: String = "",
)

class AssistViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(AssistUiState())
    val ui: StateFlow<AssistUiState> = _ui

    /** Threaded across calls so HA's intent engine can carry context. */
    private var conversationId: String? = null

    fun setDraft(value: String) {
        _ui.value = _ui.value.copy(draft = value)
    }

    fun send() {
        val text = _ui.value.draft.trim()
        if (text.isEmpty() || _ui.value.inFlight) return
        val userMsg = AssistMessage(text = text, fromUser = true)
        _ui.value = _ui.value.copy(
            messages = _ui.value.messages + userMsg,
            draft = "",
            inFlight = true,
        )
        viewModelScope.launch {
            val result = haRepository.conversationProcess(text = text, conversationId = conversationId)
            result.fold(
                onSuccess = { response ->
                    R1Log.i(
                        "Assist",
                        "type=${response.responseType} convId=${response.conversationId} speech=${response.speech}",
                    )
                    conversationId = response.conversationId ?: conversationId
                    _ui.value = _ui.value.copy(
                        messages = _ui.value.messages + AssistMessage(
                            text = response.speech,
                            fromUser = false,
                            responseType = response.responseType,
                        ),
                        inFlight = false,
                    )
                },
                onFailure = { t ->
                    R1Log.w("Assist", "process failed: ${t.message}")
                    _ui.value = _ui.value.copy(
                        messages = _ui.value.messages + AssistMessage(
                            text = "(error: ${t.message ?: "unknown"})",
                            fromUser = false,
                            responseType = "error",
                        ),
                        inFlight = false,
                    )
                },
            )
        }
    }

    /** Start a fresh conversation — drops the threaded id so the next
     *  send re-anchors HA's context. The transcript is also cleared so
     *  the UI doesn't pretend the previous turn is still live. */
    fun reset() {
        conversationId = null
        _ui.value = AssistUiState()
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { AssistViewModel(haRepository) }
        }
    }
}
