package com.github.itskenny0.r1ha.core.ha

enum class Domain(val prefix: String) {
    LIGHT("light"),
    FAN("fan"),
    COVER("cover"),
    MEDIA_PLAYER("media_player"),
    ;

    companion object {
        private val byPrefix = entries.associateBy { it.prefix }
        fun fromPrefix(prefix: String): Domain =
            byPrefix[prefix] ?: throw IllegalArgumentException("unknown domain prefix: '$prefix'")
        fun isSupportedPrefix(prefix: String): Boolean = prefix in byPrefix
    }
}
