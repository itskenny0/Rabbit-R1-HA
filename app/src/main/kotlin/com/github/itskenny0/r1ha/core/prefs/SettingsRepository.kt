package com.github.itskenny0.r1ha.core.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.preferencesDataStoreFile
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Datastore-singleton property delegate. Using the `preferencesDataStore` delegate (instead of
 * `PreferenceDataStoreFactory.create`) guarantees one DataStore instance per file, per process,
 * regardless of how many SettingsRepository instances exist.
 */
private val Context.r1haSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "r1ha_settings")

/**
 * SharedPreferences shadow store. DataStore is the canonical source of truth, but if it ever
 * returns a stale or empty read (which has been observed on some custom-ROM device builds),
 * the SharedPreferences shadow provides a bulletproof fallback for the few critical fields —
 * the server URL above all, since losing it strands the user.
 */
private const val SHADOW_PREFS = "r1ha_shadow"
private const val SHADOW_SERVER_URL = "server.url"
private const val SHADOW_HA_VERSION = "server.ha_version"
private const val SHADOW_FAVORITES = "favorites" // newline-separated, same format as DataStore

/**
 * Marker set on every successful shadow write so reads can distinguish "shadow never written
 * yet — fall back to DataStore" from "shadow explicitly says no server URL" (which must take
 * priority over a stale DataStore value, otherwise sign-out doesn't stick when the DataStore
 * delete silently fails).
 */
private const val SHADOW_INITIALIZED = "_initialized"

class SettingsRepository private constructor(
    private val store: DataStore<Preferences>,
    private val shadow: SharedPreferences,
) {

    /**
     * Tick channel that fires whenever the shadow store is written. Combined with
     * `store.data` so the public `settings` Flow re-emits even when a write only landed
     * in the shadow (DataStore commit failed for whatever reason on the device).
     */
    private val shadowChanges = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ).also { it.tryEmit(Unit) }

    /**
     * Serialises [update] so concurrent callers don't read-modify-write on top of each other.
     * Without this, two fast taps on a favourites toggle would both read the pre-tap value,
     * each apply their delta to it, and the second write would clobber the first.
     */
    private val updateMutex = Mutex()

    /** Production constructor: uses the singleton DataStore delegate and a stable shadow file. */
    constructor(context: Context) : this(
        store = context.applicationContext.r1haSettingsStore,
        shadow = context.applicationContext.getSharedPreferences(SHADOW_PREFS, Context.MODE_PRIVATE),
    )

    companion object {
        /**
         * Test-only factory. Each invocation gets an isolated DataStore file plus an isolated
         * SharedPreferences instance, so tests don't share state with production or with each
         * other. Not intended for production callers.
         */
        fun forTesting(
            context: Context,
            datastoreName: String,
            shadowName: String = "${datastoreName}_shadow",
        ): SettingsRepository {
            val appContext = context.applicationContext
            return SettingsRepository(
                store = PreferenceDataStoreFactory.create(
                    produceFile = { appContext.preferencesDataStoreFile(datastoreName) },
                ),
                shadow = appContext.getSharedPreferences(shadowName, Context.MODE_PRIVATE),
            )
        }
    }

    private object K {
        val serverUrl = stringPreferencesKey("server.url")
        val haVersion = stringPreferencesKey("server.ha_version")
        val favorites = stringPreferencesKey("favorites")

        val wheelStep = intPreferencesKey("wheel.step")
        val wheelAccel = booleanPreferencesKey("wheel.accel")
        val wheelInvert = booleanPreferencesKey("wheel.invert")
        val wheelKeySource = stringPreferencesKey("wheel.key_source")

        val uiDisplayMode = stringPreferencesKey("ui.display_mode")
        val uiShowPill = booleanPreferencesKey("ui.show_pill")
        val uiShowArea = booleanPreferencesKey("ui.show_area")
        val uiShowDots = booleanPreferencesKey("ui.show_dots")

        val behaviorHaptics = booleanPreferencesKey("behavior.haptics")
        val behaviorKeepOn = booleanPreferencesKey("behavior.keep_on")
        val behaviorTapToggle = booleanPreferencesKey("behavior.tap_toggle")
        val behaviorHideStatus = booleanPreferencesKey("behavior.hide_status_bar")
        val uiTextHistoryLen = intPreferencesKey("ui.text_history_length")
        val uiHideCardTail = booleanPreferencesKey("ui.hide_card_tail")
        val uiMaxDecimals = intPreferencesKey("ui.max_decimals")

        val theme = stringPreferencesKey("theme")
        /**
         * Encoded as a single newline-separated string of `entityId=customName` pairs;
         * names are URL-encoded so newlines/equals inside a name can't break the
         * separator scheme. Kept in one preference key (vs a key per entity) so the
         * preference file stays manageable and migrations are easy.
         */
        val nameOverrides = stringPreferencesKey("name_overrides")
        /**
         * Per-entity customization map. Same newline-separated URL-encoded encoding as
         * [nameOverrides], but each value is `scale|pill|area|longpress` (with `?` for
         * "inherit" on the nullable fields). Kept compact so the preference file stays
         * small even with hundreds of customized cards.
         */
        val entityOverrides = stringPreferencesKey("entity_overrides")
    }

    val settings: Flow<AppSettings> = combine(
        store.data
            .catch { t ->
                R1Log.e("SettingsRepo", "store.data threw, emitting emptyPreferences()", t)
                emit(emptyPreferences())
            },
        shadowChanges,
    ) { p, _ -> p }
        .map { p ->
            // Once the shadow has been written at least once it becomes the authoritative
            // source for `server` and `favorites`. update() writes the shadow synchronously
            // before kicking off the asynchronous DataStore write, so a shadow with the
            // initialized marker is always at-least-as-fresh as DataStore — and crucially the
            // shadow ALSO authoritatively reports "no server" / "no favourites" when the user
            // signed out, even if the DataStore delete silently failed.
            val shadowInit = shadow.getBoolean(SHADOW_INITIALIZED, false)
            val url = if (shadowInit) {
                shadow.getString(SHADOW_SERVER_URL, null)
            } else {
                p[K.serverUrl] ?: shadow.getString(SHADOW_SERVER_URL, null)
            }
            val haVersion = if (shadowInit) {
                shadow.getString(SHADOW_HA_VERSION, null)
            } else {
                p[K.haVersion] ?: shadow.getString(SHADOW_HA_VERSION, null)
            }
            val server = url?.takeIf { it.isNotBlank() }?.let { ServerConfig(url = it, haVersion = haVersion) }
            val favorites = if (shadowInit) {
                shadow.getString(SHADOW_FAVORITES, null)?.takeIf { it.isNotBlank() }?.split('\n').orEmpty()
            } else {
                p[K.favorites]?.takeIf { it.isNotBlank() }?.split('\n')
                    ?: shadow.getString(SHADOW_FAVORITES, null)?.takeIf { it.isNotBlank() }?.split('\n').orEmpty()
            }
            AppSettings(
                server = server,
                favorites = favorites,
                wheel = WheelSettings(
                    stepPercent = (p[K.wheelStep] ?: 2).coerceIn(1, 10),
                    acceleration = p[K.wheelAccel] ?: true,
                    invertDirection = p[K.wheelInvert] ?: false,
                    keySource = p[K.wheelKeySource]?.let { runCatching { WheelKeySource.valueOf(it) }.getOrNull() } ?: WheelKeySource.AUTO,
                ),
                ui = UiOptions(
                    displayMode = p[K.uiDisplayMode]?.let { runCatching { DisplayMode.valueOf(it) }.getOrNull() } ?: DisplayMode.PERCENT,
                    showOnOffPill = p[K.uiShowPill] ?: true,
                    showAreaLabel = p[K.uiShowArea] ?: true,
                    showPositionDots = p[K.uiShowDots] ?: true,
                    textHistoryLength = (p[K.uiTextHistoryLen] ?: 20).coerceIn(5, 100),
                    hideCardTailAbove = p[K.uiHideCardTail] ?: true,
                    maxDecimalPlaces = (p[K.uiMaxDecimals] ?: 2).coerceIn(0, 6),
                ),
                behavior = Behavior(
                    haptics = p[K.behaviorHaptics] ?: true,
                    keepScreenOn = p[K.behaviorKeepOn] ?: true,
                    tapToToggle = p[K.behaviorTapToggle] ?: true,
                    hideStatusBar = p[K.behaviorHideStatus] ?: false,
                ),
                theme = p[K.theme]?.let { runCatching { ThemeId.valueOf(it) }.getOrNull() } ?: ThemeId.PRAGMATIC_HYBRID,
                nameOverrides = decodeNameOverrides(p[K.nameOverrides]),
                entityOverrides = decodeEntityOverrides(p[K.entityOverrides]),
            )
        }
        .onEach { s ->
            R1Log.d("SettingsRepo.settings.emit", "server=${s.server?.url ?: "null"} favorites=${s.favorites.size} theme=${s.theme}")
        }

    suspend fun update(transform: (AppSettings) -> AppSettings): Unit = updateMutex.withLock {
        val current = currentBlocking()
        val next = transform(current)
        R1Log.i("SettingsRepo.update", "current.server=${current.server?.url ?: "null"} -> next.server=${next.server?.url ?: "null"}")

        // Write shadow synchronously FIRST so a SharedPreferences commit lands even if the
        // DataStore edit below fails for any reason. The synchronous commit() can block on
        // disk I/O so we move it off whatever dispatcher the caller is on.
        withContext(Dispatchers.IO) { writeShadow(next.server, next.favorites) }

        try {
            store.edit { p ->
                next.server?.let { server ->
                    p[K.serverUrl] = server.url
                    if (server.haVersion != null) p[K.haVersion] = server.haVersion
                    else p.remove(K.haVersion)
                } ?: run {
                    p.remove(K.serverUrl); p.remove(K.haVersion)
                }
                p[K.favorites] = next.favorites.joinToString("\n")
                p[K.wheelStep] = next.wheel.stepPercent
                p[K.wheelAccel] = next.wheel.acceleration
                p[K.wheelInvert] = next.wheel.invertDirection
                p[K.wheelKeySource] = next.wheel.keySource.name
                p[K.uiDisplayMode] = next.ui.displayMode.name
                p[K.uiShowPill] = next.ui.showOnOffPill
                p[K.uiShowArea] = next.ui.showAreaLabel
                p[K.uiShowDots] = next.ui.showPositionDots
                p[K.behaviorHaptics] = next.behavior.haptics
                p[K.behaviorKeepOn] = next.behavior.keepScreenOn
                p[K.behaviorTapToggle] = next.behavior.tapToToggle
                p[K.behaviorHideStatus] = next.behavior.hideStatusBar
                p[K.uiTextHistoryLen] = next.ui.textHistoryLength
                p[K.uiHideCardTail] = next.ui.hideCardTailAbove
                p[K.uiMaxDecimals] = next.ui.maxDecimalPlaces
                p[K.theme] = next.theme.name
                p[K.nameOverrides] = encodeNameOverrides(next.nameOverrides)
                p[K.entityOverrides] = encodeEntityOverrides(next.entityOverrides)
            }
            R1Log.i("SettingsRepo.update", "DataStore edit completed; next.server=${next.server?.url ?: "null"}")
        } catch (t: Throwable) {
            R1Log.e("SettingsRepo.update", "DataStore edit threw; shadow has the value as a fallback", t)
            // Only toast on failure (and the shadow store will keep things working).
            Toaster.show("Settings save failed — using fallback storage", long = true)
            // Don't rethrow — the shadow store has the critical bits, and the caller (typically
            // OnboardingViewModel) should not be forced to error out the user's flow if only the
            // DataStore commit failed.
        }
    }

    private fun writeShadow(server: ServerConfig?, favorites: List<String>) {
        val editor = shadow.edit()
        if (server != null) {
            editor.putString(SHADOW_SERVER_URL, server.url)
            if (server.haVersion != null) editor.putString(SHADOW_HA_VERSION, server.haVersion)
            else editor.remove(SHADOW_HA_VERSION)
        } else {
            editor.remove(SHADOW_SERVER_URL)
            editor.remove(SHADOW_HA_VERSION)
        }
        if (favorites.isNotEmpty()) {
            editor.putString(SHADOW_FAVORITES, favorites.joinToString("\n"))
        } else {
            editor.remove(SHADOW_FAVORITES)
        }
        // Mark the shadow as initialized so the read path treats "absence of values" as
        // intentional (signed out / no favourites) rather than "fall back to DataStore".
        editor.putBoolean(SHADOW_INITIALIZED, true)
        val ok = editor.commit() // synchronous; we want to know if it actually wrote
        R1Log.i("SettingsRepo.writeShadow", "server=${server?.url ?: "null"} favorites=${favorites.size} commit=$ok")
        if (!ok) {
            // Only toast on FAILURE; success would otherwise spam the user on every settings edit.
            Toaster.show("Storage failed — please reboot the device", long = true)
        }
        // Tick the settings Flow so observers re-read — even if the DataStore commit below
        // fails, observers see the updated shadow values.
        shadowChanges.tryEmit(Unit)
    }

    private suspend fun currentBlocking(): AppSettings = settings.first()
}

/**
 * Serialise the override map to a single newline-separated string of `entityId=name`
 * pairs, with both the entity_id and the name URL-encoded so the separators can't appear
 * inside a value. URL-encoding is far cheaper than a real serializer here — the map
 * stays small (one entry per renamed entity, never more than a few dozen) and the
 * format round-trips cleanly via [decodeNameOverrides].
 */
private fun encodeNameOverrides(map: Map<String, String>): String {
    if (map.isEmpty()) return ""
    return map.entries.joinToString("\n") { (id, name) ->
        "${java.net.URLEncoder.encode(id, "UTF-8")}=${java.net.URLEncoder.encode(name, "UTF-8")}"
    }
}

private fun decodeNameOverrides(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split('\n').mapNotNull { line ->
        val eq = line.indexOf('=')
        if (eq < 0) return@mapNotNull null
        runCatching {
            val id = java.net.URLDecoder.decode(line.substring(0, eq), "UTF-8")
            val name = java.net.URLDecoder.decode(line.substring(eq + 1), "UTF-8")
            if (id.isBlank() || name.isBlank()) null else id to name
        }.getOrNull()
    }.toMap()
}

/**
 * Per-entity customization map. Format per line: `urlEncodedId=scale|pill|area|longpress`
 * where `pill` and `area` are "0"/"1"/"?" (false / true / inherit) and `longpress` is
 * URL-encoded (or empty for "no action"). Parser is forgiving — missing trailing fields
 * default to inherit, malformed lines are skipped with a log. Future fields append after
 * `longpress` and stay backward-compatible by virtue of being absent on old saves.
 */
// Visible to tests so we can round-trip the encoding format without going through
// DataStore. Kept package-private (file-level) so production callers still go through
// SettingsRepository.update / settings to read/write.
internal fun encodeEntityOverrides_visibleForTesting(map: Map<String, EntityOverride>): String =
    encodeEntityOverrides(map)
internal fun decodeEntityOverrides_visibleForTesting(raw: String?): Map<String, EntityOverride> =
    decodeEntityOverrides(raw)

private fun encodeEntityOverrides(map: Map<String, EntityOverride>): String {
    if (map.isEmpty()) return ""
    return map.entries.joinToString("\n") { (id, o) ->
        val idEnc = java.net.URLEncoder.encode(id, "UTF-8")
        val pillStr = when (o.showOnOffPill) { true -> "1"; false -> "0"; null -> "?" }
        val areaStr = when (o.showAreaLabel) { true -> "1"; false -> "0"; null -> "?" }
        val lpEnc = o.longPressTarget?.let { java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
        val decStr = o.maxDecimalPlaces?.toString() ?: "?"
        val accStr = o.accentColor?.toString() ?: "?"
        val ctStr = o.lightColorTempK?.toString() ?: "?"
        "$idEnc=${o.textScale}|$pillStr|$areaStr|$lpEnc|$decStr|$accStr|$ctStr"
    }
}

private fun decodeEntityOverrides(raw: String?): Map<String, EntityOverride> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split('\n').mapNotNull { line ->
        val eq = line.indexOf('=')
        if (eq < 0) return@mapNotNull null
        runCatching {
            val id = java.net.URLDecoder.decode(line.substring(0, eq), "UTF-8")
            if (id.isBlank()) return@runCatching null
            val parts = line.substring(eq + 1).split('|')
            val scale = parts.getOrNull(0)?.toFloatOrNull()?.coerceIn(0.5f, 2.0f) ?: 1.0f
            val pill = when (parts.getOrNull(1)) { "1" -> true; "0" -> false; else -> null }
            val area = when (parts.getOrNull(2)) { "1" -> true; "0" -> false; else -> null }
            val lpRaw = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
            val lp = lpRaw?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            val dec = parts.getOrNull(4)?.toIntOrNull()?.coerceIn(0, 6)
            val acc = parts.getOrNull(5)?.toIntOrNull()
            val ct = parts.getOrNull(6)?.toIntOrNull()?.coerceIn(1000, 10000)
            id to EntityOverride(
                textScale = scale,
                showOnOffPill = pill,
                showAreaLabel = area,
                longPressTarget = lp?.takeIf { it.isNotBlank() },
                maxDecimalPlaces = dec,
                accentColor = acc,
                lightColorTempK = ct,
            )
        }.getOrNull()
    }.toMap()
}
