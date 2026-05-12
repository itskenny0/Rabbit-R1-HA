package com.github.itskenny0.r1ha.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import okhttp3.OkHttpClient

@Composable
fun OnboardingScreen(
    settings: SettingsRepository,
    tokens: TokenStore,
    onComplete: () -> Unit,
    http: OkHttpClient = remember { OkHttpClient() },
) {
    val vm: OnboardingViewModel = viewModel(
        factory = OnboardingViewModel.factory(http = http, settings = settings, tokens = tokens),
    )
    val state by vm.state.collectAsStateWithLifecycle()

    // Navigate away as soon as tokens are stored.
    LaunchedEffect(state) {
        if (state is OnboardingViewModel.State.Done) onComplete()
    }

    when (val s = state) {
        is OnboardingViewModel.State.ReadyToAuth -> {
            // Back press inside the OAuth WebView should drop the user back to the URL
            // entry form instead of exiting the app.
            BackHandler { vm.resetError() }
            OAuthWebView(
                authorizeUrl = s.authorizeUrl,
                // Use the baseUrl the user originally probed so path-prefixed HA setups
                // (e.g. https://example.com/ha) keep their prefix on /auth/token.
                onCodeCaptured = { code -> vm.exchangeCode(code, s.baseUrl) },
                // If HA redirects without a `code` query parameter — typically because the
                // user tapped "Deny" — drop them back to the URL entry form with the HA
                // error surfaced as a visible message rather than leaving the WebView pinned
                // on HA's error page with no clear next step.
                onMissingCode = { errorMessage ->
                    vm.failOnboarding(errorMessage?.let { "Login was cancelled or rejected ($it)" }
                        ?: "Login didn't complete — please try again.")
                },
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
            )
        }

        is OnboardingViewModel.State.Exchanging -> {
            Box(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        is OnboardingViewModel.State.Done -> {
            // LaunchedEffect above handles navigation; show nothing while it fires.
            Box(Modifier.fillMaxSize())
        }

        else -> {
            // Idle / Probing / Error all show the URL entry form.
            UrlEntryForm(
                isProbing = s is OnboardingViewModel.State.Probing,
                error = (s as? OnboardingViewModel.State.Error)?.message,
                onProbe = { vm.probe(it) },
                onErrorDismiss = { vm.resetError() },
            )
        }
    }
}

@Composable
private fun UrlEntryForm(
    isProbing: Boolean,
    error: String?,
    onProbe: (String) -> Unit,
    onErrorDismiss: () -> Unit,
) {
    var urlText by rememberSaveable { mutableStateOf("http://") }
    val scrollState = androidx.compose.foundation.rememberScrollState()
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(com.github.itskenny0.r1ha.core.theme.R1.Bg)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 22.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        // ── Headline ───────────────────────────────────────────────────────────────
        // Tiny callout above the screen title — "SECTION/01 · LINK".
        Text(
            text = "01 · LINK",
            style = com.github.itskenny0.r1ha.core.theme.R1.labelMicro,
            color = com.github.itskenny0.r1ha.core.theme.R1.AccentWarm,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Point me at\nHome Assistant.",
            style = com.github.itskenny0.r1ha.core.theme.R1.screenTitle,
            color = com.github.itskenny0.r1ha.core.theme.R1.Ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Local IP or hostname. Default HA port is 8123.",
            style = com.github.itskenny0.r1ha.core.theme.R1.body,
            color = com.github.itskenny0.r1ha.core.theme.R1.InkMuted,
        )
        Spacer(Modifier.height(28.dp))

        // ── Field ──────────────────────────────────────────────────────────────────
        Text(
            text = "URL",
            style = com.github.itskenny0.r1ha.core.theme.R1.labelMicro,
            color = com.github.itskenny0.r1ha.core.theme.R1.InkMuted,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = urlText,
            onValueChange = {
                if (error != null) onErrorDismiss()
                urlText = it
            },
            placeholder = {
                Text(
                    "http://homeassistant.local:8123",
                    style = com.github.itskenny0.r1ha.core.theme.R1.body.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                    color = com.github.itskenny0.r1ha.core.theme.R1.InkMuted,
                )
            },
            textStyle = com.github.itskenny0.r1ha.core.theme.R1.body.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = com.github.itskenny0.r1ha.core.theme.R1.Ink,
            ),
            isError = error != null,
            shape = com.github.itskenny0.r1ha.core.theme.R1.ShapeS,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = com.github.itskenny0.r1ha.core.theme.R1.AccentWarm,
                unfocusedBorderColor = com.github.itskenny0.r1ha.core.theme.R1.Hairline,
                cursorColor = com.github.itskenny0.r1ha.core.theme.R1.AccentWarm,
                errorBorderColor = com.github.itskenny0.r1ha.core.theme.R1.StatusRed,
                errorCursorColor = com.github.itskenny0.r1ha.core.theme.R1.StatusRed,
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { onProbe(urlText) }),
            modifier = Modifier.fillMaxWidth(),
            enabled = !isProbing,
        )

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = error,
                style = com.github.itskenny0.r1ha.core.theme.R1.body,
                color = com.github.itskenny0.r1ha.core.theme.R1.StatusRed,
            )
        }

        Spacer(Modifier.height(28.dp))

        Button(
            onClick = { onProbe(urlText) },
            enabled = !isProbing,
            shape = com.github.itskenny0.r1ha.core.theme.R1.ShapeM,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = com.github.itskenny0.r1ha.core.theme.R1.AccentWarm,
                contentColor = com.github.itskenny0.r1ha.core.theme.R1.Bg,
                disabledContainerColor = com.github.itskenny0.r1ha.core.theme.R1.SurfaceMuted,
                disabledContentColor = com.github.itskenny0.r1ha.core.theme.R1.InkMuted,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isProbing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .height(16.dp)
                        .padding(end = 8.dp),
                    strokeWidth = 2.dp,
                    color = com.github.itskenny0.r1ha.core.theme.R1.Bg,
                )
                Text(
                    text = "PROBING…",
                    style = com.github.itskenny0.r1ha.core.theme.R1.labelMicro,
                )
            } else {
                Text(
                    text = "CONNECT",
                    style = com.github.itskenny0.r1ha.core.theme.R1.labelMicro,
                )
            }
        }
    }
}
