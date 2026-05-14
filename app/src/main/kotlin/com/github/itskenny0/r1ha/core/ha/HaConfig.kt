package com.github.itskenny0.r1ha.core.ha

import androidx.compose.runtime.Stable

/**
 * Subset of HA's `/api/config` response. Only the fields the System
 * Health diagnostic screen actually displays — extending this is a
 * one-liner in [com.github.itskenny0.r1ha.core.ha.DefaultHaRepository.fetchHaConfig].
 *
 * HA exposes many more fields (allowlist_external_dirs, currency,
 * country, language, …); these are the ones with at-a-glance value
 * for "is my HA install healthy?" diagnostics.
 */
@Stable
data class HaConfig(
    val version: String?,
    val locationName: String?,
    val timeZone: String?,
    val elevation: Double?,
    val unitSystem: Map<String, String>,
    val internalUrl: String?,
    val externalUrl: String?,
    /** Loaded HA components (e.g. "light", "mqtt", "shopping_list"). HA
     *  reports the full list every config call; useful for "does this
     *  install have the integration I expect?" sanity checks. */
    val components: List<String>,
)
