package com.github.itskenny0.r1ha.feature.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Connect to Home Assistant",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Enter your Home Assistant URL to sign in.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = urlText,
            onValueChange = {
                if (error != null) onErrorDismiss()
                urlText = it
            },
            label = { Text("Home Assistant URL") },
            placeholder = { Text("http://homeassistant.local:8123") },
            isError = error != null,
            supportingText = if (error != null) {
                { Text(error, color = MaterialTheme.colorScheme.error) }
            } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { onProbe(urlText) }),
            modifier = Modifier.fillMaxWidth(),
            enabled = !isProbing,
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { onProbe(urlText) },
            enabled = !isProbing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isProbing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .height(20.dp)
                        .padding(end = 8.dp),
                    strokeWidth = 2.dp,
                )
                Text("Connecting…")
            } else {
                Text("Connect")
            }
        }
    }
}
