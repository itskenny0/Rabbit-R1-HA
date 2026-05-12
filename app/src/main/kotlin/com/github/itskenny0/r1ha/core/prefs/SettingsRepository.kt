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

        val theme = stringPreferencesKey("theme")
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
            // Server URL: prefer DataStore, fall back to shadow SharedPreferences.
            val urlFromStore = p[K.serverUrl]
            val urlFromShadow = shadow.getString(SHADOW_SERVER_URL, null)
            val url = urlFromStore ?: urlFromShadow
            val haVersion = p[K.haVersion] ?: shadow.getString(SHADOW_HA_VERSION, null)
            val server = url?.takeIf { it.isNotBlank() }?.let { ServerConfig(url = it, haVersion = haVersion) }
            if (urlFromStore == null && urlFromShadow != null) {
                R1Log.w(
                    "SettingsRepo",
                    "DataStore had no server.url but shadow did ($urlFromShadow); using shadow value"
                )
            }
            // Favorites: same shadow-fallback pattern as server.url.
            val favoritesFromStore = p[K.favorites]?.takeIf { it.isNotBlank() }?.split('\n')
            val favoritesFromShadow = shadow.getString(SHADOW_FAVORITES, null)?.takeIf { it.isNotBlank() }?.split('\n')
            val favorites = favoritesFromStore ?: favoritesFromShadow.orEmpty()
            if (favoritesFromStore == null && favoritesFromShadow != null) {
                R1Log.w(
                    "SettingsRepo",
                    "DataStore had no favorites but shadow did (${favoritesFromShadow.size}); using shadow value"
                )
            }
            AppSettings(
                server = server,
                favorites = favorites,
                wheel = WheelSettings(
                    stepPercent = (p[K.wheelStep] ?: 5).coerceIn(1, 10),
                    acceleration = p[K.wheelAccel] ?: true,
                    invertDirection = p[K.wheelInvert] ?: false,
                    keySource = p[K.wheelKeySource]?.let { runCatching { WheelKeySource.valueOf(it) }.getOrNull() } ?: WheelKeySource.AUTO,
                ),
                ui = UiOptions(
                    displayMode = p[K.uiDisplayMode]?.let { runCatching { DisplayMode.valueOf(it) }.getOrNull() } ?: DisplayMode.PERCENT,
                    showOnOffPill = p[K.uiShowPill] ?: true,
                    showAreaLabel = p[K.uiShowArea] ?: true,
                    showPositionDots = p[K.uiShowDots] ?: true,
                ),
                behavior = Behavior(
                    haptics = p[K.behaviorHaptics] ?: true,
                    keepScreenOn = p[K.behaviorKeepOn] ?: true,
                    tapToToggle = p[K.behaviorTapToggle] ?: true,
                ),
                theme = p[K.theme]?.let { runCatching { ThemeId.valueOf(it) }.getOrNull() } ?: ThemeId.PRAGMATIC_HYBRID,
            )
        }
        .onEach { s ->
            R1Log.d("SettingsRepo.settings.emit", "server=${s.server?.url ?: "null"} favorites=${s.favorites.size} theme=${s.theme}")
        }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
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
                p[K.theme] = next.theme.name
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
