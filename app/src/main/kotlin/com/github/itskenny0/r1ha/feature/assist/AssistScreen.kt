package com.github.itskenny0.r1ha.feature.assist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Text-mode HA Assist surface — pipes a typed prompt into
 * `/api/conversation/process` and renders the response as a chat-style
 * transcript. Multi-turn context is threaded via the conversation_id HA
 * returns, so the user can chain prompts ("turn off the light" → "and
 * the kitchen one too") and HA's intent engine keeps the device-class
 * carry-forward.
 *
 * Audio (STT/TTS via the Assist pipeline WS) is a later iteration — the
 * R1 has a mic + speaker, so we can layer it on without re-architecting
 * the transcript model. The text path is the foundation.
 */
@Composable
fun AssistScreen(
    haRepository: HaRepository,
    onBack: () -> Unit,
) {
    val vm: AssistViewModel = viewModel(factory = AssistViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    // Auto-scroll to the newest message whenever the transcript grows.
    LaunchedEffect(ui.messages.size) {
        if (ui.messages.isNotEmpty()) {
            listState.animateScrollToItem(ui.messages.size - 1)
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { focus.requestFocus() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        R1TopBar(title = "ASSIST", onBack = onBack)
        // Transcript — fills the remainder. Empty state shows a "How can I
        // help?" prompt mirroring HA's own Assist greeting so the screen
        // doesn't look broken before the first send.
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (ui.messages.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 22.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = "HA ASSIST", style = R1.sectionHeader, color = R1.AccentWarm)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Type a prompt below.\n\"turn off the kitchen light\", \"what's the temperature in the bedroom\", \"run the dinner scene\".",
                        style = R1.body,
                        color = R1.InkMuted,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                ) {
                    items(items = ui.messages, key = { it.id }) { msg ->
                        AssistBubble(msg)
                    }
                    if (ui.inFlight) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(R1.ShapeS)
                                        .background(R1.SurfaceMuted)
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Text(text = "…", style = R1.labelMicro, color = R1.InkMuted)
                                }
                            }
                        }
                    }
                }
            }
        }
        // Input row — text field + SEND button. Plus a small RESET chip on
        // the left so the user can drop the conversation_id and start fresh
        // without backing out.
        // Voice-input launcher — fires the system RecognizerIntent which
        // shows the standard Android mic dialog. Returns the recognised
        // text via the activity result; we drop it into the draft field
        // and immediately send. No RECORD_AUDIO permission needed by the
        // app because the recognition UI runs in the system process.
        val voiceLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val matches = result.data?.getStringArrayListExtra(
                android.speech.RecognizerIntent.EXTRA_RESULTS,
            )
            val best = matches?.firstOrNull()?.takeIf { it.isNotBlank() }
            if (best != null) {
                vm.setDraft(best)
                vm.send()
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onClick = { vm.reset() })
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Text(text = "↺", style = R1.labelMicro, color = R1.InkSoft)
            }
            Spacer(Modifier.width(4.dp))
            // 🎤 voice button — fires the system speech recognizer.
            // Disabled while a send is in flight so a quick voice tap
            // doesn't queue a second prompt over the first.
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onClick = {
                        if (ui.inFlight) return@r1Pressable
                        val intent = android.content.Intent(
                            android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH,
                        ).apply {
                            putExtra(
                                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                            )
                            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Ask HA…")
                            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                        }
                        // Some R1 ROMs (CipherOS especially) might not have a
                        // speech-recognition service installed — surface a toast
                        // rather than crashing on ActivityNotFoundException.
                        runCatching { voiceLauncher.launch(intent) }
                            .onFailure {
                                com.github.itskenny0.r1ha.core.util.Toaster.error(
                                    "No speech recognizer on this device",
                                )
                            }
                    })
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Text(text = "🎤", style = R1.labelMicro, color = R1.InkSoft)
            }
            Spacer(Modifier.width(6.dp))
            Box(modifier = Modifier.weight(1f)) {
                R1TextField(
                    value = ui.draft,
                    onValueChange = { vm.setDraft(it) },
                    placeholder = "ask HA…",
                    monospace = false,
                    focusRequester = focus,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.send() }),
                )
            }
            Spacer(Modifier.width(6.dp))
            R1Button(
                text = "SEND",
                onClick = { vm.send() },
                enabled = ui.draft.isNotBlank() && !ui.inFlight,
                modifier = Modifier.widthIn(min = 64.dp),
            )
        }
    }
}

@Composable
private fun AssistBubble(msg: AssistMessage) {
    val isUser = msg.fromUser
    val isError = msg.responseType == "error"
    val bg = when {
        isError -> R1.StatusRed.copy(alpha = 0.18f)
        isUser -> R1.AccentWarm.copy(alpha = 0.18f)
        else -> R1.SurfaceMuted
    }
    val textColor = when {
        isError -> R1.StatusRed
        else -> R1.Ink
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 240.dp)
                .clip(R1.ShapeS)
                .background(bg)
                .border(1.dp, if (isUser) R1.AccentWarm else R1.Hairline, R1.ShapeS)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(text = msg.text, style = R1.body, color = textColor)
        }
    }
}
