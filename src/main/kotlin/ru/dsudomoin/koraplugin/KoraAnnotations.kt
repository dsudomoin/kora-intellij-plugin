package ru.dsudomoin.koraplugin

object KoraAnnotations {
    const val COMPONENT = "ru.tinkoff.kora.common.Component"
    const val REPOSITORY = "ru.tinkoff.kora.database.common.annotation.Repository"

    /** Annotations that mark a class as a DI-provided component. */
    val COMPONENT_LIKE = listOf(COMPONENT, REPOSITORY)

    const val KORA_APP = "ru.tinkoff.kora.common.KoraApp"
    const val MODULE = "ru.tinkoff.kora.common.Module"
    const val KORA_SUBMODULE = "ru.tinkoff.kora.common.KoraSubmodule"
    const val TAG = "ru.tinkoff.kora.common.Tag"
    const val TAG_ANY = "ru.tinkoff.kora.common.Tag.Any"
    const val ALL_TYPE = "ru.tinkoff.kora.application.graph.All"
    const val VALUE_OF_TYPE = "ru.tinkoff.kora.application.graph.ValueOf"
    const val GENERATED = "ru.tinkoff.kora.common.annotation.Generated"
    const val CONFIG_SOURCE = "ru.tinkoff.kora.config.common.annotation.ConfigSource"
    const val CONFIG_VALUE_EXTRACTOR_ANNOTATION = "ru.tinkoff.kora.config.common.annotation.ConfigValueExtractor"
    const val CONFIG_VALUE_EXTRACTOR_TYPE = "ru.tinkoff.kora.config.common.extractor.ConfigValueExtractor"

    // Kafka
    const val KAFKA_LISTENER = "ru.tinkoff.kora.kafka.common.annotation.KafkaListener"

    // Scheduling
    const val SCHEDULE_AT_FIXED_RATE = "ru.tinkoff.kora.scheduling.common.annotation.ScheduleAtFixedRate"
    const val SCHEDULE_WITH_FIXED_DELAY = "ru.tinkoff.kora.scheduling.common.annotation.ScheduleWithFixedDelay"
    const val SCHEDULE_ONCE = "ru.tinkoff.kora.scheduling.common.annotation.ScheduleOnce"
    const val SCHEDULE_WITH_CRON = "ru.tinkoff.kora.scheduling.common.annotation.ScheduleWithCron"

    // Resilience
    const val RETRY = "ru.tinkoff.kora.resilient.retry.annotation.Retry"
    const val CIRCUIT_BREAKER = "ru.tinkoff.kora.resilient.circuitbreaker.annotation.CircuitBreaker"
    const val TIMEOUT = "ru.tinkoff.kora.resilient.timeout.annotation.Timeout"
    const val FALLBACK = "ru.tinkoff.kora.resilient.fallback.annotation.Fallback"

    // Cache
    const val CACHEABLE = "ru.tinkoff.kora.cache.annotation.Cacheable"
    const val CACHE_PUT = "ru.tinkoff.kora.cache.annotation.CachePut"
    const val CACHE_INVALIDATE = "ru.tinkoff.kora.cache.annotation.CacheInvalidate"

    // HTTP Client
    const val HTTP_CLIENT = "ru.tinkoff.kora.http.client.common.annotation.HttpClient"

    /** Type FQN prefixes for which ClassInheritorsSearch is skipped (too many inheritors). */
    val COMMON_TYPE_PREFIXES = arrayOf("java.", "javax.", "kotlin.", "kotlinx.")
}
