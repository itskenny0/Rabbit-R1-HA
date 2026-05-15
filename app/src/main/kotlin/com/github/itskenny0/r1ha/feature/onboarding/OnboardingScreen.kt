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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@Composable
fun OnboardingScreen(
    settings: SettingsRepository,
    tokens: TokenStore,
    onComplete: () -> Unit,
    /** Optional escape hatch — when set, the URL entry form shows a
     *  small "Use long-lived token instead" link below CONNECT that
     *  jumps to the LLAT setup screen. Lets kiosk-style installs skip
     *  OAuth entirely without first having to OAuth in to reach the
     *  Settings → LLAT screen (chicken-and-egg). Null disables. */
    onOpenLongLivedToken: (() -> Unit)? = null,
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

    // The LLAT escape hatch saves directly to TokenStore without
    // running the OAuth state machine, so OnboardingViewModel never
    // transitions to Done. Observe the activity lifecycle and re-check
    // token presence on ON_RESUME — when the user comes back from the
    // LLAT screen with a freshly-saved token, fire onComplete so they
    // land on the card stack instead of being stranded on the URL form.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val resumeScope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                resumeScope.launch {
                    if (tokens.load() != null) onComplete()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
            // Labelled progress state — bare CircularProgressIndicator on a black screen
            // doesn't communicate that the app is doing anything specific; users sometimes
            // think it's hung. The screen sequence (01 LINK → 02 AUTHORISE → 03 READY) makes
            // the onboarding feel like a guided flow with a known end.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(com.github.itskenny0.r1ha.core.theme.R1.Bg)
                    .systemBarsPadding()
                    .padding(horizontal = 22.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = "02 · AUTHORISE",
                    style = com.github.itskenny0.r1ha.core.theme.R1.labelMicro,
                    color = com.github.itskenny0.r1ha.core.theme.R1.AccentWarm,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Exchanging tokens…",
                    style = com.github.itskenny0.r1ha.core.theme.R1.screenTitle,
                    color = com.github.itskenny0.r1ha.core.theme.R1.Ink,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Swapping the authorisation code for an access token. This\nis a one-time round-trip; it usually takes a second.",
                    style = com.github.itskenny0.r1ha.core.theme.R1.body,
                    color = com.github.itskenny0.r1ha.core.theme.R1.InkMuted,
                )
                Spacer(Modifier.height(28.dp))
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    strokeWidth = 2.dp,
                    color = com.github.itskenny0.r1ha.core.theme.R1.AccentWarm,
                )
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
                onUseLongLivedToken = onOpenLongLivedToken,
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
    onUseLongLivedToken: (() -> Unit)? = null,
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
        com.github.itskenny0.r1ha.ui.components.R1TextField(
            value = urlText,
            onValueChange = {
                if (error != null) onErrorDismiss()
                urlText = it
            },
            placeholder = "http://homeassistant.local:8123",
            isError = error != null,
            enabled = !isProbing,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { onProbe(urlText) }),
            modifier = Modifier.fillMaxWidth(),
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

        com.github.itskenny0.r1ha.ui.components.R1Button(
            text = if (isProbing) "PROBING…" else "CONNECT",
            onClick = { onProbe(urlText) },
            enabled = !isProbing,
            modifier = Modifier.fillMaxWidth(),
            leadingContent = if (isProbing) {
                {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .height(14.dp)
                            .padding(end = 8.dp),
                        strokeWidth = 2.dp,
                        color = com.github.itskenny0.r1ha.core.theme.R1.Bg,
                    )
                }
            } else null,
        )

        // ── LLAT escape hatch ──────────────────────────────────────────────────────
        // Without this link the user has to OAuth first to reach the
        // Settings → LLAT screen — pointless if they specifically don't
        // want OAuth in the first place. The link is muted so it doesn't
        // compete with CONNECT as the primary action.
        if (onUseLongLivedToken != null) {
            Spacer(Modifier.height(18.dp))
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    text = "Use a long-lived token instead",
                    style = com.github.itskenny0.r1ha.core.theme.R1.labelMicro,
                    color = com.github.itskenny0.r1ha.core.theme.R1.AccentWarm,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .r1Pressable(onClick = onUseLongLivedToken)
                        .padding(8.dp),
                )
            }
        }
    }
}
