package com.github.itskenny0.r1ha.ui.components

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1
import kotlinx.coroutines.delay

/**
 * Tiny battery-percent pill for the chrome row. Used only when the user
 * has hidden the Android system status bar AND opted into this
 * indicator — duplicating the system bar's own battery readout would be
 * busy.
 *
 * Reads the BatteryManager **sticky broadcast** rather than registering
 * a real receiver. ACTION_BATTERY_CHANGED is sticky on every Android
 * version we care about, so `registerReceiver(null, filter)` returns
 * the most recently-broadcast intent without us having to manage
 * unregister-on-dispose lifecycle paperwork. We re-poll every 30 s
 * which is fine — the R1's battery doesn't change measurably faster
 * than that, and the alternative (a real BroadcastReceiver) wakes the
 * app on every percentage change which is wasteful.
 *
 * Tinting:
 *  - ≥40 %  → InkSoft (calm)
 *  - 15-39% → StatusAmber
 *  - <15 %  → StatusRed
 *
 * Charging state is encoded as a leading bolt glyph rather than a
 * colour change so a charging-at-10% phone still shouts at the user
 * with the red tint.
 */
@Composable
fun BatteryIndicator(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var pct by remember { mutableStateOf<Int?>(null) }
    var charging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                pct = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            }
            // 30s poll — slow enough that we don't churn battery ourselves, fast
            // enough that a "running low" warning shows up within a card-stack
            // session. Drops to no-op early if the LaunchedEffect is cancelled.
            delay(30_000L)
        }
    }
    val p = pct
    val tint: Color = when {
        p == null -> R1.InkMuted
        p < 15 -> R1.StatusRed
        p < 40 -> R1.StatusAmber
        else -> R1.InkSoft
    }
    Box(
        modifier = modifier
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = when {
                p == null -> "—"
                charging -> "⚡${p}%"
                else -> "${p}%"
            },
            style = R1.labelMicro,
            color = tint,
        )
    }
}
