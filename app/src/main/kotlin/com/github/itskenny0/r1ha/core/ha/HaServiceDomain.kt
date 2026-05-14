package com.github.itskenny0.r1ha.core.ha

import androidx.compose.runtime.Stable

/**
 * One domain in HA's service registry — e.g. "light" with its
 * "turn_on" / "turn_off" / "toggle" services. Each service has a
 * human-readable description plus a fields map; we keep just the
 * description for the Services Browser's at-a-glance display and
 * leave deeper field introspection for a follow-up.
 */
@Stable
data class HaServiceDomain(
    val domain: String,
    val services: List<HaService>,
)

@Stable
data class HaService(
    val name: String,
    val description: String?,
    /** Names of the parameters this service accepts (e.g. brightness_pct,
     *  entity_id). Surfaced as a comma-separated chip below the
     *  description so the user can see at a glance what data shapes
     *  are valid. */
    val fieldNames: List<String>,
)
