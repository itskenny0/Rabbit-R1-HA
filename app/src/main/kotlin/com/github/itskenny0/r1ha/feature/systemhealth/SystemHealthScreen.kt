package com.github.itskenny0.r1ha.feature.systemhealth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * System Health diagnostic screen. Renders `/api/config` (HA version,
 * timezone, components, URLs) and the tail of `/api/error_log` for at-
 * a-glance "is my HA install healthy?" inspection. The error log gets
 * a COPY-to-clipboard affordance for bug-report pasting.
 */
@Composable
fun SystemHealthScreen(
    haRepository: HaRepository,
    onBack: () -> Unit,
) {
    val vm: SystemHealthViewModel = viewModel(factory = SystemHealthViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) { vm.refresh() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "SYSTEM HEALTH", onBack = onBack)
        if (ui.loading && ui.config == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = R1.AccentWarm,
                )
            }
            return@Column
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(text = "SERVER", style = R1.labelMicro, color = R1.InkSoft)
            Spacer(Modifier.size(4.dp))
            val cfg = ui.config
            if (cfg != null) {
                ConfigPanel(cfg)
            } else if (ui.configError != null) {
                ErrorPanel(ui.configError!!)
            }
            Spacer(Modifier.size(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "ERROR LOG (tail)", style = R1.labelMicro, color = R1.InkSoft)
                Spacer(Modifier.weight(1f))
                if (ui.errorLog.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(R1.ShapeS)
                            .background(R1.SurfaceMuted)
                            .border(1.dp, R1.Hairline, R1.ShapeS)
                            .r1Pressable(onClick = {
                                clipboard.setText(AnnotatedString(ui.errorLog))
                                Toaster.show("Copied")
                            })
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(text = "COPY", style = R1.labelMicro, color = R1.InkSoft)
                    }
                }
            }
            Spacer(Modifier.size(4.dp))
            when {
                ui.errorLog.isNotBlank() -> ErrorLogPanel(ui.errorLog)
                ui.errorLogError != null -> ErrorPanel(ui.errorLogError!!)
                else -> Text(
                    text = "No log output (HA returned an empty body).",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ConfigPanel(cfg: com.github.itskenny0.r1ha.core.ha.HaConfig) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Pair("Version", cfg.version).render()
        Pair("Location", cfg.locationName).render()
        Pair("Time zone", cfg.timeZone).render()
        Pair("Elevation", cfg.elevation?.let { "${it.toInt()} m" }).render()
        Pair("Internal URL", cfg.internalUrl).render()
        Pair("External URL", cfg.externalUrl).render()
        if (cfg.unitSystem.isNotEmpty()) {
            Pair(
                "Units",
                cfg.unitSystem.entries.joinToString(" · ") { "${it.key}=${it.value}" },
            ).render()
        }
        if (cfg.components.isNotEmpty()) {
            Pair(
                "Components (${cfg.components.size})",
                cfg.components.joinToString(", "),
            ).render(multiline = true)
        }
    }
}

@Composable
private fun Pair<String, String?>.render(multiline: Boolean = false) {
    val value = second
    if (value.isNullOrBlank()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = first.uppercase(), style = R1.labelMicro, color = R1.InkMuted)
        Text(
            text = value,
            style = R1.body,
            color = R1.Ink,
            maxLines = if (multiline) Int.MAX_VALUE else 1,
        )
    }
}

@Composable
private fun ErrorLogPanel(body: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = body,
            style = R1.body.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
            ),
            color = R1.InkSoft,
        )
    }
}

@Composable
private fun ErrorPanel(msg: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.StatusRed.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(text = msg, style = R1.body, color = R1.StatusRed)
    }
}
