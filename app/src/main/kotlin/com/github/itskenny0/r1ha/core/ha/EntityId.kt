package com.github.itskenny0.r1ha.core.ha

@JvmInline
value class EntityId(val value: String) {
    init {
        val dot = value.indexOf('.')
        require(dot > 0 && dot < value.length - 1) { "entity_id must be 'domain.object_id': '$value'" }
        require(Domain.isSupportedPrefix(value.substring(0, dot))) {
            "entity_id has unsupported domain: '$value'"
        }
    }
    val domain: Domain get() = Domain.fromPrefix(value.substringBefore('.'))
    val objectId: String get() = value.substringAfter('.')
    override fun toString(): String = value
}
