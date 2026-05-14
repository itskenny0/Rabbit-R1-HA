package com.github.itskenny0.r1ha.core.ha

/**
 * Result of a HA conversation/process call. Captures the plain-text response
 * (what HA "said back" — typically a confirmation like "Turned off the light"
 * or an answer to a query) plus the conversationId that threads multi-turn
 * context so the next prompt can refer back ("what about the kitchen?" after
 * "turn off the living room light"). [responseType] is HA's tagged variant
 * (`action_done`, `query_answer`, `error`) — surfaced so the UI can colour
 * the bubble red on error rather than printing a generic error string.
 */
data class ConversationResponse(
    val speech: String,
    val conversationId: String?,
    val responseType: String?,
)
