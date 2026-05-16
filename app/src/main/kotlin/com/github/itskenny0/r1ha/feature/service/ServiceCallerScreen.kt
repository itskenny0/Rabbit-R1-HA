package com.github.itskenny0.r1ha.feature.service

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.Toaster
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Service Caller power-user surface — fire any `domain.service` pair
 * with an optional JSON `data` body. The natural complement to
 * [com.github.itskenny0.r1ha.feature.template.TemplateScreen]: one
 * surface evaluates Jinja against live state, the other dispatches
 * arbitrary services. Together they cover most "I just want to poke
 * HA from the device" power-user intents.
 *
 * Three editable fields (domain, service, JSON data), a FIRE button,
 * and a result panel that swaps between OK / ERROR styling. Example
 * chips at the top seed common calls (`automation.reload`,
 * `homeassistant.check_config`, `persistent_notification.create`)
 * with one tap.
 */
@Composable
fun ServiceCallerScreen(
    haRepository: HaRepository,
    settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
    wheelInput: com.github.itskenny0.r1ha.core.input.WheelInput,
    onBack: () -> Unit,
) {
    val vm: ServiceCallerViewModel = viewModel(
        factory = ServiceCallerViewModel.factory(haRepository, settings),
    )
    val ui by vm.ui.collectAsState()
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        R1TopBar(title = "SERVICE CALLER", onBack = onBack)
        val scrollState = rememberScrollState()
        com.github.itskenny0.r1ha.ui.components.WheelScrollForScrollState(
            wheelInput = wheelInput,
            scrollState = scrollState,
            settings = settings,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .verticalScroll(scrollState),
        ) {
            ExampleChips(onPick = vm::load)
            Spacer(Modifier.padding(top = 10.dp))
            FieldLabel("DOMAIN")
            R1TextField(
                value = ui.domain,
                onValueChange = { vm.setDomain(it) },
                placeholder = "homeassistant",
                monospace = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.padding(top = 6.dp))
            FieldLabel("SERVICE")
            R1TextField(
                value = ui.service,
                onValueChange = { vm.setService(it) },
                placeholder = "check_config",
                monospace = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.padding(top = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "DATA (JSON, optional)", style = R1.labelMicro, color = R1.InkSoft)
                Spacer(Modifier.weight(1f))
                // PASTE chip — pulls the clipboard contents into the DATA
                // field. Common workflow: copy a service_data JSON snippet
                // from HA's developer-tools page on a tablet, paste it
                // here on the R1.
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(onClick = {
                            val text = clipboard.getText()?.toString().orEmpty().trim()
                            if (text.isBlank()) {
                                Toaster.show("Clipboard empty")
                            } else {
                                // Try pretty-printing JSON for readability.
                                // Anything that doesn't parse drops through as
                                // raw text — paste-as-is for non-JSON snippets.
                                // Reuses ServiceCallerViewModel.prettyJson so
                                // the formatter is not rebuilt per paste.
                                val pretty = runCatching {
                                    val parsed = kotlinx.serialization.json.Json
                                        .parseToJsonElement(text)
                                    ServiceCallerViewModel.prettyJson.encodeToString(
                                        kotlinx.serialization.json.JsonElement.serializer(),
                                        parsed,
                                    )
                                }.getOrNull()
                                vm.setData(pretty ?: text)
                            }
                        })
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(text = "PASTE", style = R1.labelMicro, color = R1.InkSoft)
                }
            }
            Spacer(Modifier.padding(top = 4.dp))
            R1TextField(
                value = ui.data,
                onValueChange = { vm.setData(it) },
                placeholder = """{"entity_id":"light.kitchen"}""",
                monospace = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp),
            )
            Spacer(Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                R1Button(
                    text = if (ui.inFlight) "FIRING…" else "FIRE",
                    onClick = { vm.fire() },
                    enabled = !ui.inFlight && ui.domain.isNotBlank() && ui.service.isNotBlank(),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "POST /api/services/${ui.domain}/${ui.service}",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
            Spacer(Modifier.padding(top = 12.dp))
            when {
                ui.error != null -> ResultPanel(
                    heading = "ERROR",
                    body = ui.error!!,
                    accent = R1.StatusRed,
                    onCopy = { copy(clipboard, ui.error!!) },
                )
                ui.result.isNotEmpty() -> ResultPanel(
                    heading = "RESULT",
                    body = ui.result,
                    accent = R1.AccentWarm,
                    onCopy = { copy(clipboard, ui.result) },
                )
                else -> Text(
                    text = "Tap FIRE to dispatch the service. State changes (if any) are listed here.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            }
            // Recent fires — newest first; tap to recall into the editor.
            // Useful for re-firing the same service while iterating data.
            if (ui.recent.isNotEmpty()) {
                Spacer(Modifier.padding(top = 16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "RECENT", style = R1.labelMicro, color = R1.InkSoft)
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clip(R1.ShapeS)
                            .background(R1.SurfaceMuted)
                            .r1Pressable(onClick = { vm.clearRecent() })
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(text = "CLEAR", style = R1.labelMicro, color = R1.InkSoft)
                    }
                }
                Spacer(Modifier.padding(top = 4.dp))
                for (call in ui.recent) {
                    RecentRow(call, onPick = { vm.load(call.domain, call.service, call.data) })
                    Spacer(Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.padding(top = 24.dp))
        }
    }
}

@Composable
private fun RecentRow(
    call: ServiceCallerViewModel.RecentCall,
    onPick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .r1Pressable(onClick = onPick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Column {
            Text(
                text = "${call.domain}.${call.service}",
                style = R1.body,
                color = R1.Ink,
                maxLines = 1,
            )
            if (call.data.isNotBlank()) {
                Text(
                    text = call.data,
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun FieldLabel(label: String) {
    Text(text = label, style = R1.labelMicro, color = R1.InkSoft)
    Spacer(Modifier.padding(top = 4.dp))
}

private fun copy(clipboard: androidx.compose.ui.platform.ClipboardManager, text: String) {
    clipboard.setText(AnnotatedString(text))
    Toaster.show("Copied")
}

@Composable
private fun ExampleChips(onPick: (String, String, String) -> Unit) {
    // Common diagnostic dispatches — each one a real "I wish I could
    // do this without a laptop" intent.
    val examples = listOf(
        Triple("Check config", "homeassistant", "check_config") to "",
        Triple("Reload automations", "automation", "reload") to "",
        Triple("Reload scripts", "script", "reload") to "",
        Triple("Reload scenes", "scene", "reload") to "",
        Triple("Reload templates", "template", "reload") to "",
        Triple("HA notify", "persistent_notification", "create")
            to """{"title":"From R1","message":"hello"}""",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "TRY", style = R1.labelMicro, color = R1.InkMuted)
        Spacer(Modifier.width(4.dp))
        for ((trip, data) in examples) {
            val (label, domain, service) = trip
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onClick = { onPick(domain, service, data) })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(text = label, style = R1.labelMicro, color = R1.InkSoft)
            }
        }
    }
}

@Composable
private fun ResultPanel(
    heading: String,
    body: String,
    accent: androidx.compose.ui.graphics.Color,
    onCopy: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = heading, style = R1.labelMicro, color = accent)
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onClick = onCopy)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(text = "COPY", style = R1.labelMicro, color = R1.InkSoft)
            }
        }
        Spacer(Modifier.padding(top = 4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(text = body, style = R1.body, color = R1.Ink)
        }
    }
}
