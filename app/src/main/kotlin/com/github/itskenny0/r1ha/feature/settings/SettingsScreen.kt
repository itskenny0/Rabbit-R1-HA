package com.github.itskenny0.r1ha.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.DisplayMode
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.prefs.WheelKeySource
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Switch
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.r1RowPressable

@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    tokens: TokenStore,
    haRepository: com.github.itskenny0.r1ha.core.ha.HaRepository,
    wheelInput: WheelInput,
    onOpenThemePicker: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenAssist: () -> Unit,
    onOpenScenes: () -> Unit,
    onOpenLogbook: () -> Unit,
    onOpenTemplate: () -> Unit,
    onOpenServiceCaller: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenCameras: () -> Unit,
    onOpenWeather: () -> Unit,
    onOpenPersons: () -> Unit,
    onOpenCalendars: () -> Unit,
    onOpenLongLivedToken: () -> Unit,
    onOpenSystemHealth: () -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenAreas: () -> Unit,
    onOpenServices: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAutomations: () -> Unit,
    onOpenHelpers: () -> Unit,
    onOpenEnergy: () -> Unit,
    onOpenZones: () -> Unit,
    onOpenLovelace: () -> Unit,
    onSignedOut: () -> Unit,
    onBack: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(settings = settings, tokens = tokens),
    )
    val s by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)

    // SAF launchers for backup export / import. Using CreateDocument / OpenDocument
    // routes through the Android system file picker, so the user can save to the
    // R1's local storage, a USB stick, or any cloud-storage app they have wired
    // up (Drive, Nextcloud, etc.) without us shipping permissions for direct FS
    // access. CreateDocument keeps the chosen MIME type as the file's display
    // type so a downstream viewer can open it; we use application/json so
    // editors recognise the format on a desktop too.
    val context = androidx.compose.ui.platform.LocalContext.current
    // Holds the JSON blob produced by exportBackupBlob until the user picks
    // the destination file via SAF. The launcher reads this when its
    // ActivityResult lands and writes to the picked URI.
    val pendingBackupBlob = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: android.net.Uri? ->
        val blob = pendingBackupBlob.value
        pendingBackupBlob.value = null
        if (uri == null || blob == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(blob.toByteArray(Charsets.UTF_8))
            } ?: error("couldn't open output stream")
            com.github.itskenny0.r1ha.core.util.Toaster.show("Backup saved")
        }.onFailure { t ->
            com.github.itskenny0.r1ha.core.util.R1Log.w(
                "Settings.exportBackup", "write failed: ${t.message}",
            )
            com.github.itskenny0.r1ha.core.util.Toaster.errorExpandable(
                shortText = "Backup save failed",
                fullText = "Couldn't write the backup file.\n\n${t.message ?: t.toString()}",
            )
        }
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri: android.net.Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            } ?: error("couldn't open input stream")
        }.fold(
            onSuccess = { raw -> vm.importBackupBlob(raw) },
            onFailure = { t ->
                com.github.itskenny0.r1ha.core.util.R1Log.w(
                    "Settings.importBackup", "read failed: ${t.message}",
                )
                com.github.itskenny0.r1ha.core.util.Toaster.errorExpandable(
                    shortText = "Backup read failed",
                    fullText = "Couldn't read the backup file.\n\n${t.message ?: t.toString()}",
                )
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "SETTINGS", onBack = onBack)

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {

            // ── Server ─────────────────────────────────────────────────────────────
            item { Section("SERVER") }
            item {
                InfoRow(
                    label = "URL",
                    value = s.server?.url ?: "(not connected)",
                    mono = true,
                )
            }
            item {
                s.server?.haVersion?.let { InfoRow(label = "HA version", value = it, mono = true) }
            }
            // Live connection state — collected from HaRepository.connection
            // and rendered as a small human label so the user can spot a
            // connection issue from the Settings screen without flipping back
            // to the main deck to read the chrome dot. Refreshes live as the
            // state machine moves.
            item {
                val conn by haRepository.connection.collectAsStateWithLifecycle()
                val label = when (val c = conn) {
                    is com.github.itskenny0.r1ha.core.ha.ConnectionState.Connected -> "Connected"
                    com.github.itskenny0.r1ha.core.ha.ConnectionState.Idle -> "Idle"
                    com.github.itskenny0.r1ha.core.ha.ConnectionState.Connecting -> "Connecting…"
                    com.github.itskenny0.r1ha.core.ha.ConnectionState.Authenticating -> "Authenticating…"
                    is com.github.itskenny0.r1ha.core.ha.ConnectionState.Disconnected ->
                        "Disconnected (attempt ${c.attempt})"
                    is com.github.itskenny0.r1ha.core.ha.ConnectionState.AuthLost ->
                        "Auth lost — sign in again"
                }
                InfoRow(label = "Status", value = label)
            }
            // App version line — pulls from BuildConfig so it always matches
            // the running APK. Pairs the marketing version + integer
            // versionCode so the user can tell which exact build this is
            // when filing an issue.
            item {
                InfoRow(
                    label = "App version",
                    value = "${com.github.itskenny0.r1ha.BuildConfig.VERSION_NAME} (${com.github.itskenny0.r1ha.BuildConfig.VERSION_CODE})",
                    mono = true,
                )
            }
            // Long-lived token entry — alternative to the OAuth flow for
            // kiosk-style setups or users who prefer pasting a token from
            // HA's profile page. Lives in the SERVER section so users
            // looking to change auth find it co-located with sign-out.
            item {
                NavRow(
                    label = "Use long-lived token",
                    value = "Paste instead of OAuth",
                    onClick = onOpenLongLivedToken,
                )
            }
            // RECONNECT NOW — force-flush the WS + re-fetch fresh state without
            // touching tokens. Useful when the connection has gone stale (HA
            // restarted, Wi-Fi dropped briefly, etc.) and the user wants live
            // updates back without going through the full sign-out cycle.
            // Outlined variant so it visually pairs with the destructive
            // SIGN-OUT below but doesn't compete for primary attention.
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 6.dp),
                ) {
                    com.github.itskenny0.r1ha.ui.components.R1Button(
                        text = "RECONNECT NOW",
                        onClick = {
                            haRepository.reconnectNow()
                            com.github.itskenny0.r1ha.core.util.Toaster.show("Reconnecting…")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
                    )
                }
            }
            // 'Open HA web UI' — fires an ACTION_VIEW intent with the
            // configured server URL. Useful when the user wants to drop
            // into the full HA web frontend for things this app doesn't
            // cover (long-form automation editor, area/device configs,
            // backups). No-op when server isn't configured.
            item {
                val url = s.server?.url
                if (url != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 6.dp),
                    ) {
                        com.github.itskenny0.r1ha.ui.components.R1Button(
                            text = "OPEN HA WEB UI",
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(url),
                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }.onFailure {
                                    com.github.itskenny0.r1ha.core.util.Toaster.error(
                                        "No browser to open $url",
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
                        )
                    }
                }
            }
            item {
                // Two-stage armed/commit — first tap arms (label changes
                // to CONFIRM …), second tap fires within 3 s; auto-reset
                // afterwards. Stops a thumb-fumble from accidentally
                // dropping tokens + landing the user back on the
                // onboarding URL form. Same pattern as the card-stack
                // Quick Actions TURN ALL OFF.
                val armed = androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(false)
                }
                androidx.compose.runtime.LaunchedEffect(armed.value) {
                    if (armed.value) {
                        kotlinx.coroutines.delay(3_000)
                        armed.value = false
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                ) {
                    DangerButton(
                        text = if (armed.value) "CONFIRM · SIGN OUT" else "SIGN OUT & RECONNECT",
                        onClick = {
                            if (armed.value) vm.signOut(onSignedOut) else armed.value = true
                        },
                    )
                }
            }

            item { SectionDivider() }

            // ── Scroll wheel ───────────────────────────────────────────────────────
            item { Section("SCROLL WHEEL") }
            item {
                LabeledControl(label = "Step size") {
                    SegmentedIntPicker(
                        options = listOf(1, 2, 5, 10),
                        selected = s.wheel.stepPercent,
                        label = { "$it%" },
                        onSelect = { vm.setWheelStep(it) },
                    )
                }
            }
            item {
                SwitchRow(
                    label = "Acceleration",
                    subtitle = "Spin faster to jump further",
                    checked = s.wheel.acceleration,
                    onCheckedChange = { vm.setWheelAcceleration(it) },
                )
            }
            if (s.wheel.acceleration) {
                item {
                    LabeledControl(label = "Acceleration curve") {
                        SegmentedEnumPicker(
                            options = com.github.itskenny0.r1ha.core.prefs.AccelerationCurve.entries,
                            selected = s.wheel.accelerationCurve,
                            label = {
                                when (it) {
                                    com.github.itskenny0.r1ha.core.prefs.AccelerationCurve.SUBTLE -> "SUBTLE"
                                    com.github.itskenny0.r1ha.core.prefs.AccelerationCurve.MEDIUM -> "MEDIUM"
                                    com.github.itskenny0.r1ha.core.prefs.AccelerationCurve.AGGRESSIVE -> "AGGRESSIVE"
                                }
                            },
                            onSelect = { vm.setAccelerationCurve(it) },
                        )
                    }
                }
            }
            item {
                SwitchRow(
                    label = "Invert direction",
                    checked = s.wheel.invertDirection,
                    onCheckedChange = { vm.setWheelInvert(it) },
                )
            }
            item {
                LabeledControl(label = "Key source") {
                    SegmentedEnumPicker(
                        options = WheelKeySource.entries,
                        selected = s.wheel.keySource,
                        label = {
                            when (it) {
                                WheelKeySource.AUTO -> "AUTO"
                                WheelKeySource.DPAD -> "D-PAD"
                                WheelKeySource.VOLUME -> "VOL"
                            }
                        },
                        onSelect = { vm.setWheelKeySource(it) },
                    )
                }
            }

            item { SectionDivider() }

            // ── Card UI ────────────────────────────────────────────────────────────
            item { Section("CARD UI") }
            item {
                LabeledControl(label = "Display mode") {
                    SegmentedEnumPicker(
                        options = DisplayMode.entries,
                        selected = s.ui.displayMode,
                        label = {
                            when (it) {
                                DisplayMode.PERCENT -> "PERCENT"
                                DisplayMode.RAW -> "RAW"
                            }
                        },
                        onSelect = { vm.setDisplayMode(it) },
                    )
                }
            }
            item {
                SwitchRow(
                    label = "Show on/off pill",
                    checked = s.ui.showOnOffPill,
                    onCheckedChange = { vm.setShowOnOffPill(it) },
                )
            }
            item {
                SwitchRow(
                    label = "Show area label",
                    checked = s.ui.showAreaLabel,
                    onCheckedChange = { vm.setShowAreaLabel(it) },
                )
            }
            item {
                SwitchRow(
                    label = "Show position pip",
                    subtitle = "Bar in the chrome that shows current card position",
                    checked = s.ui.showPositionDots,
                    onCheckedChange = { vm.setShowPositionDots(it) },
                )
            }
            item {
                SwitchRow(
                    label = "Hide card hint above current",
                    subtitle = "Solid chrome backdrop covers the previous card's tail",
                    checked = s.ui.hideCardTailAbove,
                    onCheckedChange = { vm.setHideCardTailAbove(it) },
                )
            }
            item {
                SwitchRow(
                    label = "Infinite scroll",
                    subtitle = "Wheel past the last card wraps to the first",
                    checked = s.ui.infiniteScroll,
                    onCheckedChange = { vm.setInfiniteScroll(it) },
                )
            }
            item {
                LabeledControl(label = "Sensor decimals") {
                    SegmentedIntPicker(
                        options = listOf(0, 1, 2, 3, 4),
                        selected = s.ui.maxDecimalPlaces,
                        label = { if (it == 0) "INT" else "$it" },
                        onSelect = { vm.setMaxDecimalPlaces(it) },
                    )
                }
            }
            item {
                LabeledControl(label = "Temperature unit") {
                    SegmentedEnumPicker(
                        options = com.github.itskenny0.r1ha.core.prefs.TemperatureUnit.entries,
                        selected = s.ui.tempUnit,
                        label = {
                            when (it) {
                                com.github.itskenny0.r1ha.core.prefs.TemperatureUnit.AUTO -> "AUTO"
                                com.github.itskenny0.r1ha.core.prefs.TemperatureUnit.CELSIUS -> "°C"
                                com.github.itskenny0.r1ha.core.prefs.TemperatureUnit.FAHRENHEIT -> "°F"
                            }
                        },
                        onSelect = { vm.setTempUnit(it) },
                    )
                }
            }

            item { SectionDivider() }

            // ── Behaviour ──────────────────────────────────────────────────────────
            item { Section("BEHAVIOUR") }
            item {
                SwitchRow(
                    label = "Haptic feedback",
                    checked = s.behavior.haptics,
                    onCheckedChange = { vm.setHaptics(it) },
                )
            }
            item {
                SwitchRow(
                    label = "Keep screen on",
                    checked = s.behavior.keepScreenOn,
                    onCheckedChange = { vm.setKeepScreenOn(it) },
                )
            }
            item {
                SwitchRow(
                    label = "Tap to toggle",
                    subtitle = "Off (default): the whole-card tap is inert so a miss " +
                        "while aiming for the chrome buttons doesn't accidentally turn " +
                        "the entity on. On: tap anywhere on the card to flip it.",
                    checked = s.behavior.tapToToggle,
                    onCheckedChange = { vm.setTapToToggle(it) },
                )
            }
            item {
                SwitchRow(
                    label = "Hide status bar",
                    subtitle = "Swipe down to peek the bar; auto-hides after release",
                    checked = s.behavior.hideStatusBar,
                    onCheckedChange = { vm.setHideStatusBar(it) },
                )
            }
            // Battery indicator sub-toggle — only meaningful when the status bar
            // is hidden (otherwise the system bar already shows battery). Indent
            // visually via a leading symbol so it reads as nested without needing
            // a fancy sub-section component.
            if (s.behavior.hideStatusBar) {
                item {
                    SwitchRow(
                        label = "↳ Show battery indicator",
                        subtitle = "Tiny percent pill on the right of the chrome row " +
                            "(polled every 30 s) — useful so a low R1 battery doesn't catch you off-guard.",
                        checked = s.behavior.showBatteryWhenStatusBarHidden,
                        onCheckedChange = { vm.setShowBatteryWhenStatusBarHidden(it) },
                    )
                }
            }
            item {
                SwitchRow(
                    label = "Start on Dashboard",
                    subtitle = "Open the app on the TODAY dashboard instead of the card stack. " +
                        "Useful for wall-mounted / kiosk R1s. Takes effect on next app launch.",
                    checked = s.behavior.startOnDashboard,
                    onCheckedChange = { vm.setStartOnDashboard(it) },
                )
            }
            item {
                SwitchRow(
                    label = "Wheel toggles switches",
                    subtitle = "On (default): wheel-up turns locks, covers, vacuums, plain " +
                        "switches on; wheel-down turns them off. Off: wheel does nothing on " +
                        "those cards — useful if a casual brush is accidentally relocking your door.",
                    checked = s.behavior.wheelTogglesSwitches,
                    onCheckedChange = { vm.setWheelTogglesSwitches(it) },
                )
            }
            item { ToastLogLevelRow(current = s.behavior.toastLogLevel, onSelect = { vm.setToastLogLevel(it) }) }

            item { SectionDivider() }

            // ── Backup & restore ───────────────────────────────────────────────────
            item { Section("BACKUP & RESTORE") }
            item {
                InfoRow(
                    label = "What's included",
                    value = "Server URL · pages · favourites · all settings (no tokens)",
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    com.github.itskenny0.r1ha.ui.components.R1Button(
                        text = "EXPORT",
                        onClick = {
                            vm.exportBackupBlob { blob ->
                                pendingBackupBlob.value = blob
                                val stamp = java.text.SimpleDateFormat(
                                    "yyyyMMdd-HHmm",
                                    java.util.Locale.US,
                                ).format(java.util.Date())
                                exportLauncher.launch("r1ha-backup-$stamp.json")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    com.github.itskenny0.r1ha.ui.components.R1Button(
                        text = "IMPORT",
                        onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                        modifier = Modifier.weight(1f),
                        variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
                    )
                }
            }

            // RESET TO DEFAULTS — wipes every user-tunable setting back to its
            // post-onboarding state. Preserves the server account (URL +
            // tokens), favourites, and pages so the user doesn't have to
            // re-onboard. Two-stage confirm: first tap arms the button
            // (label flips + warning text appears), second tap commits.
            // Auto-disarms after 3 s so a stray arm doesn't sit hot.
            item {
                val armed = androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(false)
                }
                androidx.compose.runtime.LaunchedEffect(armed.value) {
                    if (armed.value) {
                        kotlinx.coroutines.delay(3_000)
                        armed.value = false
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 6.dp),
                ) {
                    com.github.itskenny0.r1ha.ui.components.R1Button(
                        text = if (armed.value) "CONFIRM RESET · TAP AGAIN" else "RESET TO DEFAULTS",
                        onClick = {
                            if (armed.value) {
                                vm.resetToDefaults()
                                armed.value = false
                            } else {
                                armed.value = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        accent = R1.StatusAmber,
                    )
                    if (armed.value) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Drops every override, theme, wheel + UI + behaviour preference. Keeps your account, favourites, and pages.",
                            style = R1.labelMicro,
                            color = R1.InkMuted,
                        )
                    }
                }
            }

            item { SectionDivider() }

            // ── Dashboard layout — per-section visibility + thresholds ─────────
            item { Section("DASHBOARD") }
            item { SubGroupLabel("VISIBLE CARDS") }
            item {
                SwitchRow(
                    label = "Greeting",
                    subtitle = "GOOD MORNING / AFTERNOON / EVENING / NIGHT row",
                    checked = s.dashboard.showGreeting,
                    onCheckedChange = { v -> vm.updateDashboard { it.copy(showGreeting = v) } },
                )
            }
            item {
                SwitchRow(
                    label = "Weather card",
                    subtitle = "Current condition + temperature from your first weather.* entity",
                    checked = s.dashboard.showWeather,
                    onCheckedChange = { v -> vm.updateDashboard { it.copy(showWeather = v) } },
                )
            }
            item {
                SwitchRow(
                    label = "Sun card",
                    subtitle = "Above/below horizon, elevation, next rise/set",
                    checked = s.dashboard.showSun,
                    onCheckedChange = { v -> vm.updateDashboard { it.copy(showSun = v) } },
                )
            }
            item {
                SwitchRow(
                    label = "Timers",
                    subtitle = "Active timer.* entities with remaining time",
                    checked = s.dashboard.showTimers,
                    onCheckedChange = { v -> vm.updateDashboard { it.copy(showTimers = v) } },
                )
            }
            item {
                SwitchRow(
                    label = "Now Playing",
                    subtitle = "Currently-playing media_player entities with prev / play / next",
                    checked = s.dashboard.showMedia,
                    onCheckedChange = { v -> vm.updateDashboard { it.copy(showMedia = v) } },
                )
            }
            item {
                SwitchRow(
                    label = "People",
                    subtitle = "Home/away count + per-person state",
                    checked = s.dashboard.showPersons,
                    onCheckedChange = { v -> vm.updateDashboard { it.copy(showPersons = v) } },
                )
            }
            item {
                SwitchRow(
                    label = "Next event",
                    subtitle = "Earliest upcoming calendar event with NOW pill",
                    checked = s.dashboard.showNextEvent,
                    onCheckedChange = { v -> vm.updateDashboard { it.copy(showNextEvent = v) } },
                )
            }
            item {
                SwitchRow(
                    label = "DRAW (power)",
                    subtitle = "Sum of device_class=power sensors in watts",
                    checked = s.dashboard.showPower,
                    onCheckedChange = { v -> vm.updateDashboard { it.copy(showPower = v) } },
                )
            }
            item {
                SwitchRow(
                    label = "Metrics row",
                    subtitle = "LIGHTS ON / CAMERAS / ALERTS tiles",
                    checked = s.dashboard.showMetrics,
                    onCheckedChange = { v -> vm.updateDashboard { it.copy(showMetrics = v) } },
                )
            }
            item {
                SwitchRow(
                    label = "Low-battery alerts",
                    subtitle = "Surface battery sensors under the threshold",
                    checked = s.dashboard.showLowBattery,
                    onCheckedChange = { v -> vm.updateDashboard { it.copy(showLowBattery = v) } },
                )
            }
            item {
                SwitchRow(
                    label = "Inline alert previews",
                    subtitle = "Preview the first N HA persistent alerts on the dashboard",
                    checked = s.dashboard.showInlineAlerts,
                    onCheckedChange = { v -> vm.updateDashboard { it.copy(showInlineAlerts = v) } },
                )
            }
            item { SubGroupLabel("THRESHOLDS & INTERVALS") }
            item {
                NumberStepperRow(
                    label = "Dashboard refresh",
                    subtitle = "Auto-refresh cadence (0 = pull-down only · long-press −/+ for ×10)",
                    value = s.dashboard.refreshIntervalSec,
                    min = 0, max = 600, step = 15, suffix = " s",
                    onChange = { v -> vm.updateDashboard { it.copy(refreshIntervalSec = v) } },
                )
            }
            item {
                NumberStepperRow(
                    label = "Low-battery threshold",
                    subtitle = "Surface batteries below this percentage",
                    value = s.dashboard.lowBatteryThresholdPct,
                    min = 1, max = 100, step = 5, suffix = " %",
                    onChange = { v -> vm.updateDashboard { it.copy(lowBatteryThresholdPct = v) } },
                )
            }
            item {
                NumberStepperRow(
                    label = "DRAW amber above",
                    subtitle = "Power threshold where the DRAW tile turns amber",
                    value = s.dashboard.powerAmberThresholdW,
                    min = 50, max = 10_000, step = 50, suffix = " W",
                    onChange = { v -> vm.updateDashboard { it.copy(powerAmberThresholdW = v) } },
                )
            }
            item {
                NumberStepperRow(
                    label = "DRAW red above",
                    subtitle = "Power threshold where the DRAW tile turns red",
                    value = s.dashboard.powerRedThresholdW,
                    min = 200, max = 30_000, step = 100, suffix = " W",
                    onChange = { v -> vm.updateDashboard { it.copy(powerRedThresholdW = v) } },
                )
            }
            item {
                NumberStepperRow(
                    label = "Inline alerts shown",
                    subtitle = "Max HA persistent-alert previews under METRICS",
                    value = s.dashboard.inlineAlertsCount,
                    min = 0, max = 10, step = 1,
                    onChange = { v -> vm.updateDashboard { it.copy(inlineAlertsCount = v) } },
                )
            }
            item {
                NumberStepperRow(
                    label = "Media rows shown",
                    subtitle = "Max simultaneous media-player cards on the dashboard",
                    value = s.dashboard.mediaSummaryCount,
                    min = 1, max = 10, step = 1,
                    onChange = { v -> vm.updateDashboard { it.copy(mediaSummaryCount = v) } },
                )
            }

            item { SectionDivider() }

            // ── Integrations — per-surface refresh intervals + tuning ──────────
            item { Section("INTEGRATIONS") }
            item { SubGroupLabel("AUTO-REFRESH INTERVALS") }
            item {
                NumberStepperRow(
                    label = "Notifications refresh",
                    subtitle = "Auto-refresh the Notifications surface every…",
                    value = s.integrations.notificationsRefreshSec,
                    min = 0, max = 600, step = 15, suffix = " s",
                    onChange = { v -> vm.updateIntegrations { it.copy(notificationsRefreshSec = v) } },
                )
            }
            item {
                NumberStepperRow(
                    label = "Logbook refresh",
                    subtitle = "Auto-refresh the Recent Activity feed every…",
                    value = s.integrations.logbookRefreshSec,
                    min = 0, max = 900, step = 30, suffix = " s",
                    onChange = { v -> vm.updateIntegrations { it.copy(logbookRefreshSec = v) } },
                )
            }
            item {
                NumberStepperRow(
                    label = "Who's-home refresh",
                    subtitle = "Auto-refresh the Persons surface every…",
                    value = s.integrations.personsRefreshSec,
                    min = 0, max = 900, step = 30, suffix = " s",
                    onChange = { v -> vm.updateIntegrations { it.copy(personsRefreshSec = v) } },
                )
            }
            item {
                NumberStepperRow(
                    label = "Weather refresh",
                    subtitle = "Auto-refresh the Weather surface every…",
                    value = s.integrations.weatherRefreshSec,
                    min = 0, max = 3600, step = 60, suffix = " s",
                    onChange = { v -> vm.updateIntegrations { it.copy(weatherRefreshSec = v) } },
                )
            }
            item {
                NumberStepperRow(
                    label = "Calendars refresh",
                    subtitle = "Auto-refresh the Calendars surface every…",
                    value = s.integrations.calendarsRefreshSec,
                    min = 0, max = 3600, step = 60, suffix = " s",
                    onChange = { v -> vm.updateIntegrations { it.copy(calendarsRefreshSec = v) } },
                )
            }
            item { SubGroupLabel("CAMERAS") }
            item {
                NumberStepperRow(
                    label = "Camera overlay polling",
                    subtitle = "Snapshot fetch interval when viewing a camera fullscreen",
                    value = s.integrations.cameraOverlayPollSec,
                    min = 1, max = 60, step = 1, suffix = " s",
                    onChange = { v -> vm.updateIntegrations { it.copy(cameraOverlayPollSec = v) } },
                )
            }
            item {
                NumberStepperRow(
                    label = "Camera grid polling",
                    subtitle = "Snapshot fetch interval per tile in GRID view",
                    value = s.integrations.cameraGridPollSec,
                    min = 2, max = 120, step = 2, suffix = " s",
                    onChange = { v -> vm.updateIntegrations { it.copy(cameraGridPollSec = v) } },
                )
            }
            item {
                SwitchRow(
                    label = "Cameras open in GRID",
                    subtitle = "Default to the polling-tiles view rather than the text list",
                    checked = s.integrations.camerasDefaultGrid,
                    onCheckedChange = { v -> vm.updateIntegrations { it.copy(camerasDefaultGrid = v) } },
                )
            }
            item { SubGroupLabel("DEFAULTS & LIMITS") }
            item {
                NumberStepperRow(
                    label = "Logbook default window",
                    subtitle = "Time window applied on Recent Activity entry",
                    value = s.integrations.logbookDefaultWindowHours,
                    min = 1, max = 168, step = 1, suffix = " h",
                    onChange = { v -> vm.updateIntegrations { it.copy(logbookDefaultWindowHours = v) } },
                )
            }
            item {
                NumberStepperRow(
                    label = "Calendar look-ahead",
                    subtitle = "Days of events fetched when drilling into a calendar",
                    value = s.integrations.calendarLookaheadDays,
                    min = 1, max = 90, step = 1, suffix = " d",
                    onChange = { v -> vm.updateIntegrations { it.copy(calendarLookaheadDays = v) } },
                )
            }
            item {
                NumberStepperRow(
                    label = "Quick Search result cap",
                    subtitle = "Maximum entities shown for a search",
                    value = s.integrations.searchResultCap,
                    min = 10, max = 500, step = 10,
                    onChange = { v -> vm.updateIntegrations { it.copy(searchResultCap = v) } },
                )
            }
            item {
                NumberStepperRow(
                    label = "RECENT history depth",
                    subtitle = "Items kept in Templates / Service Caller RECENT lists",
                    value = s.integrations.recentHistoryDepth,
                    min = 0, max = 30, step = 1,
                    onChange = { v -> vm.updateIntegrations { it.copy(recentHistoryDepth = v) } },
                )
            }

            item { SectionDivider() }

            // ── Appearance ─────────────────────────────────────────────────────────
            item { Section("APPEARANCE") }
            item {
                NavRow(
                    label = "Theme",
                    value = s.theme.name
                        .replace('_', ' ')
                        .lowercase()
                        .replaceFirstChar { it.uppercase() },
                    onClick = onOpenThemePicker,
                )
            }

            item { SectionDivider() }

            // ── Today — at-a-glance dashboard + quick search ──────────────
            item { Section("TODAY") }
            item {
                NavRow(label = "Dashboard", value = "Weather · People · Next event", onClick = onOpenDashboard)
            }
            item {
                NavRow(label = "Quick Search", value = "Find any entity", onClick = onOpenSearch)
            }

            item { SectionDivider() }

            // ── Talk + Fire — high-frequency action surfaces ─────────────
            item { Section("TALK & FIRE") }
            item {
                NavRow(label = "Assist", value = "Talk to HA", onClick = onOpenAssist)
            }
            item {
                NavRow(label = "Scenes & Scripts", value = "Fire instantly", onClick = onOpenScenes)
            }
            item {
                NavRow(
                    label = "Automations",
                    value = "List, trigger, enable / disable",
                    onClick = onOpenAutomations,
                )
            }
            item {
                NavRow(
                    label = "Helpers",
                    value = "input_*, counter, timer",
                    onClick = onOpenHelpers,
                )
            }

            // ── Status views — read-only at-a-glance HA state ────────────
            item { SectionDivider() }
            item { Section("STATUS VIEWS") }
            item {
                NavRow(label = "Cameras", value = "Live snapshots", onClick = onOpenCameras)
            }
            item {
                NavRow(label = "Weather", value = "Conditions readout", onClick = onOpenWeather)
            }
            item {
                NavRow(label = "Who's home", value = "People + device trackers", onClick = onOpenPersons)
            }
            item {
                NavRow(label = "Calendars", value = "Next event preview", onClick = onOpenCalendars)
            }
            item {
                NavRow(label = "Recent Activity", value = "Logbook feed", onClick = onOpenLogbook)
            }
            item {
                NavRow(label = "Notifications", value = "HA persistent alerts", onClick = onOpenNotifications)
            }
            item {
                NavRow(label = "Areas", value = "HA area registry", onClick = onOpenAreas)
            }
            item {
                NavRow(
                    label = "Zones",
                    value = "Geographic zones + who's there",
                    onClick = onOpenZones,
                )
            }
            item {
                NavRow(
                    label = "Energy",
                    value = "Draw, production, today's kWh",
                    onClick = onOpenEnergy,
                )
            }

            // ── Power tools — diagnostic / advanced surfaces ─────────────
            item { SectionDivider() }
            item { Section("POWER TOOLS") }
            item {
                NavRow(label = "Templates", value = "Jinja2 evaluator", onClick = onOpenTemplate)
            }
            item {
                NavRow(label = "Service Caller", value = "Fire any service", onClick = onOpenServiceCaller)
            }
            item {
                NavRow(label = "Services Browser", value = "Discover available services", onClick = onOpenServices)
            }
            item {
                NavRow(label = "System Health", value = "HA version + error log", onClick = onOpenSystemHealth)
            }
            item {
                NavRow(
                    label = "Lovelace (WebView)",
                    value = "Open HA's frontend in-app",
                    onClick = onOpenLovelace,
                )
            }

            item { SectionDivider() }

            item {
                NavRow(label = "About", onClick = onOpenAbout)
            }

            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

// ── Building blocks ──────────────────────────────────────────────────────────────────────

@Composable
private fun Section(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = R1.sectionHeader, color = R1.AccentWarm)
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .height(1.dp)
                .weight(1f)
                .background(R1.Hairline),
        )
    }
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(2.dp))
}

/**
 * Smaller heading rendered inside a Section to split it into visual
 * groups (e.g. "VISIBLE CARDS" vs "THRESHOLDS & INTERVALS" under
 * DASHBOARD) without escalating to a full Section divider. Pairs
 * nicely with long lists of related rows that would otherwise blur
 * together at a glance.
 */
@Composable
private fun SubGroupLabel(text: String) {
    Spacer(Modifier.height(6.dp))
    androidx.compose.material3.Text(
        text = text,
        style = R1.labelMicro,
        color = R1.InkMuted,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 2.dp, bottom = 4.dp),
    )
}

/**
 * Horizontal-scroll chip row selecting the in-app toast log threshold. OFF is the
 * default (no diagnostic toasts); WARN is the friendly diagnostic level (failures
 * + decoder drops). Tap a chip to switch.
 */
@Composable
private fun ToastLogLevelRow(
    current: com.github.itskenny0.r1ha.core.prefs.ToastLogLevel,
    onSelect: (com.github.itskenny0.r1ha.core.prefs.ToastLogLevel) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 10.dp),
    ) {
        Text("Toast log level", style = R1.bodyEmph, color = R1.Ink)
        Text(
            text = "Off (default): no diagnostic toasts. Warn: surface failures and " +
                "decoder drops as tappable expanding toasts — useful for 'where's my " +
                "entity?' on devices without adb. Debug: everything R1Log emits.",
            style = R1.body,
            color = R1.InkMuted,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        val scroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll),
        ) {
            com.github.itskenny0.r1ha.core.prefs.ToastLogLevel.entries.forEach { level ->
                val active = level == current
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .clip(R1.ShapeS)
                        .background(if (active) R1.AccentWarm else R1.SurfaceMuted)
                        .r1Pressable({ onSelect(level) })
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = level.name,
                        style = R1.labelMicro,
                        color = if (active) R1.Bg else R1.InkSoft,
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // r1Pressable instead of bare clickable so the whole row dips on press AND fires
            // a CLOCK_TICK haptic to match the rest of the app. The inner R1Switch ignores
            // the synthetic click here — it'll fire its own haptic on the toggle thumb tap.
            .r1Pressable(onClick = { onCheckedChange(!checked) }, hapticOnClick = false)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = R1.bodyEmph, color = R1.Ink)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = R1.body, color = R1.InkMuted)
            }
        }
        R1Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/**
 * NumberStepperRow — label + subtitle + −/+ pills around the current
 * value. Used for the new dashboard / integrations settings where
 * thresholds (battery low %, power amber/red watts) and intervals
 * (refresh cadence, polling intervals) need granular tuning without
 * a slider's tap-imprecision penalty on the R1's small screen.
 *
 * Tap a pill = ±step. Long-press a pill = ±step×10 (fast-step for
 * wide ranges like power thresholds 50…10 000 W). Pills disable
 * themselves when the value is at the matching boundary so the user
 * doesn't waste taps.
 */
@Composable
private fun NumberStepperRow(
    label: String,
    subtitle: String? = null,
    value: Int,
    min: Int,
    max: Int,
    step: Int = 1,
    suffix: String = "",
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = R1.bodyEmph, color = R1.Ink)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = R1.body, color = R1.InkMuted)
            }
        }
        // −/value/+ cluster. Each pill is 28 dp tall, the value cell
        // sits between them as plain text — feels less busy than three
        // border-bordered pills in a row.
        Row(verticalAlignment = Alignment.CenterVertically) {
            val canDec = value > min
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(R1.ShapeS)
                    .background(if (canDec) R1.SurfaceMuted else R1.Bg)
                    .r1RowPressable(
                        onTap = {
                            if (canDec) onChange((value - step).coerceAtLeast(min))
                        },
                        onLongPress = {
                            if (canDec) onChange((value - step * 10).coerceAtLeast(min))
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "−", style = R1.body, color = if (canDec) R1.Ink else R1.InkMuted)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$value$suffix",
                style = R1.bodyEmph,
                color = R1.Ink,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.width(8.dp))
            val canInc = value < max
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(R1.ShapeS)
                    .background(if (canInc) R1.SurfaceMuted else R1.Bg)
                    .r1RowPressable(
                        onTap = {
                            if (canInc) onChange((value + step).coerceAtMost(max))
                        },
                        onLongPress = {
                            if (canInc) onChange((value + step * 10).coerceAtMost(max))
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "+", style = R1.body, color = if (canInc) R1.Ink else R1.InkMuted)
            }
        }
    }
}

@Composable
private fun LabeledControl(label: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 10.dp),
    ) {
        Text(label, style = R1.bodyEmph, color = R1.Ink)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun NavRow(
    label: String,
    value: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .r1Pressable(onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = R1.bodyEmph, color = R1.Ink, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(
                text = value,
                style = R1.body,
                color = R1.InkSoft,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        com.github.itskenny0.r1ha.ui.components.Chevron(
            direction = com.github.itskenny0.r1ha.ui.components.ChevronDirection.Right,
            size = 10.dp,
            tint = R1.InkMuted,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = R1.bodyEmph, color = R1.Ink)
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = if (mono) R1.body.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                else R1.body,
            color = R1.InkSoft,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun DangerButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            // Hairline border in StatusRed so the destructive intent reads at a glance — the
            // earlier flat `SurfaceMuted` fill didn't signal "danger" from across the screen.
            .r1Pressable(onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = R1.labelMicro, color = R1.StatusRed.copy(alpha = 0.92f))
    }
}

/**
 * Bespoke segmented picker — rectangular cells, hairline borders, selected = orange fill on
 * black text. Reads like a hardware mode selector instead of Material's pill chips.
 */
@Composable
private fun <T> SegmentedIntPicker(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) = Segmented(options = options, selected = selected, label = label, onSelect = onSelect)

@Composable
private fun <T> SegmentedEnumPicker(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) = Segmented(options = options, selected = selected, label = label, onSelect = onSelect)

@Composable
private fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted),
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(if (isSelected) R1.AccentWarm else R1.SurfaceMuted)
                    .r1Pressable(onClick = { onSelect(option) })
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = R1.labelMicro,
                    color = if (isSelected) R1.Bg else R1.InkSoft,
                )
            }
            // Hairline divider between cells (skip after last).
            if (index < options.lastIndex) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(36.dp)
                        .background(R1.Bg),
                )
            }
        }
    }
}
