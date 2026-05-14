package com.github.itskenny0.r1ha.feature.template

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar

/**
 * Templates evaluator — type a Jinja2 template, tap RENDER, see HA's
 * output (or syntax error). Mirrors HA's frontend template editor in
 * function while staying inside the R1 idiom.
 *
 * Output panel renders below the editor; on syntax error HA's
 * traceback is shown verbatim so the user can iterate without
 * leaving the screen. The default template (`{{ now().isoformat() }}`)
 * is a one-keystroke "is this connected?" smoke test.
 */
@Composable
fun TemplateScreen(
    haRepository: HaRepository,
    onBack: () -> Unit,
) {
    val vm: TemplateViewModel = viewModel(factory = TemplateViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        R1TopBar(title = "TEMPLATES", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(text = "TEMPLATE (JINJA2)", style = R1.labelMicro, color = R1.InkSoft)
            Spacer(Modifier.padding(top = 4.dp))
            // Multi-line monospace editor. heightIn keeps a sensible minimum
            // even when the field is empty so the tap target is generous.
            R1TextField(
                value = ui.template,
                onValueChange = { vm.setTemplate(it) },
                placeholder = "{{ states.sun.sun.attributes.elevation }}",
                monospace = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp),
            )
            Spacer(Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                R1Button(
                    text = if (ui.inFlight) "RENDERING…" else "RENDER",
                    onClick = { vm.render() },
                    enabled = ui.template.isNotBlank() && !ui.inFlight,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "POSTs /api/template",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
            Spacer(Modifier.padding(top = 12.dp))
            // Output panel — switches between OK / ERROR styling. Empty
            // string while we're waiting for the first render so the
            // panel doesn't show "OK" with no body.
            when {
                ui.error != null -> ResultPanel(
                    heading = "ERROR",
                    body = ui.error!!,
                    accent = R1.StatusRed,
                )
                ui.rendered.isNotEmpty() -> ResultPanel(
                    heading = "RENDERED",
                    body = ui.rendered,
                    accent = R1.AccentWarm,
                )
                else -> Text(
                    text = "Hit RENDER to evaluate against live HA state.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            }
            Spacer(Modifier.padding(top = 24.dp))
        }
    }
}

@Composable
private fun ResultPanel(heading: String, body: String, accent: androidx.compose.ui.graphics.Color) {
    Column {
        Text(text = heading, style = R1.labelMicro, color = accent)
        Spacer(Modifier.padding(top = 4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            // SelectionContainer would be nicer (copy-to-clipboard) but
            // monospace + readable body is the priority. Tap-to-toast
            // could be a follow-up.
            Text(text = body, style = R1.body, color = R1.Ink)
        }
    }
}
