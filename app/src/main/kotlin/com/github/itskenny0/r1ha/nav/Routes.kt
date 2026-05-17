package com.github.itskenny0.r1ha.nav

/** All top-level navigation destinations as stable string routes. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val CARD_STACK = "card_stack"
    const val FAVORITES_PICKER = "favorites_picker"
    const val SETTINGS = "settings"
    const val THEME_PICKER = "theme_picker"
    const val ABOUT = "about"
    const val DEV_MENU = "dev_menu"
    const val ASSIST = "assist"
    const val SCENES = "scenes"
    const val LOGBOOK = "logbook"
    const val TEMPLATE = "template"
    const val SERVICE_CALLER = "service_caller"
    const val NOTIFICATIONS = "notifications"
    const val CAMERAS = "cameras"
    const val WEATHER = "weather"
    const val PERSONS = "persons"
    const val CALENDARS = "calendars"
    const val LONG_LIVED_TOKEN = "long_lived_token"
    const val SYSTEM_HEALTH = "system_health"
    const val DASHBOARD = "dashboard"
    const val AREAS = "areas"
    const val SERVICES = "services"
    const val SEARCH = "search"
    const val AUTOMATIONS = "automations"
    const val HELPERS = "helpers"
    const val ENERGY = "energy"
    const val DEVICES = "devices"
    const val ZONES = "zones"
    const val LOVELACE = "lovelace"
    const val DEVICE = "device"

    /** History drill-in route — carries the entity_id as a path
     *  segment. Use [historyRoute] from call sites so the encoding
     *  rule lives in one place. */
    const val HISTORY = "history/{entityId}"

    /** Build a concrete history-screen route for [entityId]. The
     *  entity_id stays unescaped because Compose Navigation parses
     *  StringType path segments as raw strings — `.` and `_` are
     *  allowed in route paths. */
    fun historyRoute(entityId: String): String = "history/$entityId"
}
