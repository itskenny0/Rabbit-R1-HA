package com.github.itskenny0.r1ha.core.ha

import androidx.compose.runtime.Stable
import java.time.Instant

/**
 * One HA persistent notification — represented in HA as a
 * `persistent_notification.*` entity with `title`, `message`, and
 * `created_at` attributes. Used for "an integration broke", "a firmware
 * update is available", "the kitchen door was left open for 30 min"
 * style operator alerts.
 *
 * We don't add this to [Domain] because:
 *  - The wheel + tap behaviour doesn't fit any existing card type
 *    (read-only-but-dismissable doesn't map to sensor / action / select).
 *  - Adding to the Domain enum would cascade through 5+ exhaustive
 *    when-branches across the codebase for a feature that's better
 *    surfaced as its own dedicated list view.
 */
@Stable
data class PersistentNotification(
    /** The bit after `persistent_notification.` — the HA-side
     *  `notification_id` the dismiss service wants. */
    val notificationId: String,
    val title: String?,
    val message: String,
    val createdAt: Instant?,
)
