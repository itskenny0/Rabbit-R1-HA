package com.github.itskenny0.r1ha.feature.quicktile

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.github.itskenny0.r1ha.App
import com.github.itskenny0.r1ha.R
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Android Quick-Settings tile that mirrors one HA entity's on/off state
 * and dispatches a toggle service call when the user taps it. The
 * bound entity_id is configured via Settings → BEHAVIOUR → Quick
 * Settings tile; empty/null = the tile shows a 'tap to set up' label
 * and opens the app on tap instead of toggling.
 *
 * Lifecycle:
 *  - `onTileAdded` — the user dragged the tile into their quick-
 *    settings panel for the first time. We don't do anything special;
 *    `onStartListening` follows almost immediately.
 *  - `onStartListening` — the panel is open and the tile is visible.
 *    Fetch the current entity state and update the tile label + mode.
 *  - `onStopListening` — the panel closed. We don't tear down the
 *    coroutine scope because the next listen window will need it
 *    again.
 *  - `onClick` — the user tapped. Dispatch a toggle and refresh the
 *    tile after a short settle delay so the displayed state matches
 *    what HA echoed back.
 *
 * Why we go via App.graph: TileService is a system-instantiated
 * Service with no DI hook of its own. The App singleton (constructed
 * on process start) exposes a lazy AppGraph that already wraps
 * SettingsRepository + HaRepository, so we just reach for it through
 * applicationContext. Same pattern MainActivity uses.
 */
class HaQuickTileService : TileService() {

    /** SupervisorJob so a single failed toggle doesn't cascade into
     *  killing the next refresh. Lives across listen/click cycles. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Eagerly warm the dependency graph — the lazy `app.graph`
        // construction would otherwise happen on the first onClick,
        // adding ~100-300 ms of disk-read latency to the tile tap
        // (DataStore's first access reads from disk). Touching it
        // here lets onClick run with a warm graph and feel responsive.
        runCatching { (applicationContext as App).graph }
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        // Reach for the graph each click rather than caching it — the
        // App singleton's `graph` is itself lazy, so the first reach
        // pays the construction cost once and every subsequent click
        // returns the same instance.
        val graph = (applicationContext as App).graph
        scope.launch {
            try {
                val settings = graph.settings.settings.first()
                val rawId = settings.behavior.quickTileEntityId?.takeIf { it.isNotBlank() }
                if (rawId == null) {
                    // No entity bound — open the app to the Settings
                    // screen so the user can pick one. startActivityAndCollapse
                    // dismisses the quick-settings panel as it launches.
                    R1Log.i("HaQuickTile", "no entity bound; opening app")
                    launchAppForSetup()
                    return@launch
                }
                val entityId = runCatching { EntityId(rawId) }.getOrNull()
                if (entityId == null) {
                    R1Log.w("HaQuickTile", "stored entity_id has unsupported domain: $rawId")
                    return@launch
                }
                // Cache lookup — HaRepository keeps a live entity map
                // from observe(); the first() pull gives us today's
                // state. For non-toggleable domains (sensor, etc.) the
                // tap is a no-op.
                val live = graph.haRepository.listAllEntities().getOrNull()
                    ?.firstOrNull { it.id == entityId }
                if (live == null) {
                    R1Log.w("HaQuickTile", "${entityId.value} not in entity map yet")
                    return@launch
                }
                val call = if (live.id.domain.isAction) {
                    // Scenes / scripts / buttons: 'turn_on' is the
                    // fire-and-forget activation service.
                    ServiceCall(target = live.id, service = "turn_on", data = JsonObject(emptyMap()))
                } else {
                    ServiceCall.tapAction(live.id, live.isOn)
                }
                graph.haRepository.call(call)
                R1Log.i("HaQuickTile", "tapped ${entityId.value} (was on=${live.isOn})")
                // Settle delay before refresh so HA's state echo lands
                // before we re-read the cache.
                kotlinx.coroutines.delay(600L)
                refreshTile()
            } catch (t: Throwable) {
                R1Log.w("HaQuickTile", "click failed: ${t.message}")
            }
        }
    }

    private fun refreshTile() {
        val graph = (applicationContext as App).graph
        scope.launch {
            try {
                val tile = qsTile ?: return@launch
                val settings = graph.settings.settings.first()
                val rawId = settings.behavior.quickTileEntityId?.takeIf { it.isNotBlank() }
                if (rawId.isNullOrBlank()) {
                    tile.state = Tile.STATE_INACTIVE
                    tile.label = "HA — set entity"
                    tile.subtitle = "Tap to open app"
                    tile.icon = Icon.createWithResource(applicationContext, R.mipmap.ic_launcher)
                    tile.updateTile()
                    return@launch
                }
                val entityId = runCatching { EntityId(rawId) }.getOrNull()
                if (entityId == null) {
                    tile.state = Tile.STATE_UNAVAILABLE
                    tile.label = "HA — bad entity_id"
                    tile.icon = Icon.createWithResource(applicationContext, R.mipmap.ic_launcher)
                    tile.updateTile()
                    return@launch
                }
                val live = graph.haRepository.listAllEntities().getOrNull()
                    ?.firstOrNull { it.id == entityId }
                if (live == null) {
                    tile.state = Tile.STATE_UNAVAILABLE
                    tile.label = entityId.value
                    tile.subtitle = "not loaded yet"
                    tile.icon = Icon.createWithResource(applicationContext, R.mipmap.ic_launcher)
                    tile.updateTile()
                    return@launch
                }
                tile.label = live.friendlyName
                tile.subtitle = if (live.isOn) "ON" else "OFF"
                tile.state = if (live.isOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                tile.icon = Icon.createWithResource(applicationContext, R.mipmap.ic_launcher)
                tile.updateTile()
            } catch (t: Throwable) {
                R1Log.w("HaQuickTile", "refresh failed: ${t.message}")
            }
        }
    }

    private fun launchAppForSetup() {
        val ctx = applicationContext
        val launchIntent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?: return
        launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        // startActivityAndCollapse is the Quick-Settings panel idiom
        // for launching a foreground activity — collapses the panel
        // so the launched activity is visible. Two overloads:
        //   API ≥ 34: takes a PendingIntent (the non-PendingIntent
        //     overload is deprecated in that release)
        //   API 33: only the Intent overload exists
        // Fork on Build.VERSION so each branch picks the form
        // available on its API level.
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val pi = android.app.PendingIntent.getActivity(
                ctx,
                0,
                launchIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launchIntent)
        }
    }
}
