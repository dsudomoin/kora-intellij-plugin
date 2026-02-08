package ru.dsudomoin.koraplugin.config

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.JavaPsiFacade
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.toUElement
import ru.dsudomoin.koraplugin.KoraAnnotations

data class ConfigAnnotationMapping(
    val annotationFqn: String,
    val attributeName: String,
    val configPathPrefix: String?,
    val configPathTransform: (value: String) -> List<String>,
)

object KoraConfigAnnotationRegistry {

    private val mappings: List<ConfigAnnotationMapping> = listOf(
        // Kafka
        ConfigAnnotationMapping(KoraAnnotations.KAFKA_LISTENER, "value", null) { listOf(it) },

        // Scheduling
        ConfigAnnotationMapping(KoraAnnotations.SCHEDULE_AT_FIXED_RATE, "config", "scheduling") { listOf(it) },
        ConfigAnnotationMapping(KoraAnnotations.SCHEDULE_WITH_FIXED_DELAY, "config", "scheduling") { listOf(it) },
        ConfigAnnotationMapping(KoraAnnotations.SCHEDULE_ONCE, "config", "scheduling") { listOf(it) },
        ConfigAnnotationMapping(KoraAnnotations.SCHEDULE_WITH_CRON, "config", "scheduling") { listOf(it) },

        // Resilience
        ConfigAnnotationMapping(KoraAnnotations.RETRY, "value", "resilient.retry") { listOf("resilient.retry.$it") },
        ConfigAnnotationMapping(KoraAnnotations.CIRCUIT_BREAKER, "value", "resilient.circuitbreaker") { listOf("resilient.circuitbreaker.$it") },
        ConfigAnnotationMapping(KoraAnnotations.TIMEOUT, "value", "resilient.timeout") { listOf("resilient.timeout.$it") },
        ConfigAnnotationMapping(KoraAnnotations.FALLBACK, "value", "resilient.fallback") { listOf("resilient.fallback.$it") },

        // Cache
        ConfigAnnotationMapping(KoraAnnotations.CACHEABLE, "value", "cache") { listOf("cache.caffeine.$it", "cache.redis.$it") },
        ConfigAnnotationMapping(KoraAnnotations.CACHE_PUT, "value", "cache") { listOf("cache.caffeine.$it", "cache.redis.$it") },
        ConfigAnnotationMapping(KoraAnnotations.CACHE_INVALIDATE, "value", "cache") { listOf("cache.caffeine.$it", "cache.redis.$it") },

        // HTTP Client
        ConfigAnnotationMapping(KoraAnnotations.HTTP_CLIENT, "configPath", "httpClient") { listOf("httpClient.$it") },
    )

    val allAnnotationFqns: Set<String> = mappings.map { it.annotationFqn }.toSet()

    fun findMappingForAnnotation(annotationFqn: String): ConfigAnnotationMapping? =
        mappings.find { it.annotationFqn == annotationFqn }

    /**
     * Resolves config paths from a UAST annotation.
     * Returns empty list if the annotation is not in the registry or has no value.
     */
    fun resolveConfigPaths(annotation: UAnnotation): List<String> {
        val fqn = annotation.qualifiedName ?: return emptyList()
        val mapping = findMappingForAnnotation(fqn) ?: return emptyList()

        val value = annotation.findAttributeValue(mapping.attributeName)
            ?.evaluate() as? String
            ?: return emptyList()

        return mapping.configPathTransform(value)
    }

    /**
     * Reverse lookup: finds annotated elements (methods/classes) whose annotation
     * maps to the given config path.
     */
    fun findAnnotatedElements(project: Project, configPath: String): List<PsiElement> {
        val results = mutableListOf<PsiElement>()
        val scope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        for (mapping in mappings) {
            if (mapping.configPathPrefix != null && !configPath.startsWith(mapping.configPathPrefix)) continue

            val annotationClass = facade.findClass(mapping.annotationFqn, scope) ?: continue

            // Search annotated methods
            AnnotatedElementsSearch.searchPsiMethods(annotationClass, scope).forEach { psiMethod ->
                val uMethod = psiMethod.toUElement() as? UMethod ?: return@forEach
                val ann = uMethod.uAnnotations.find { it.qualifiedName == mapping.annotationFqn } ?: return@forEach
                val paths = resolveConfigPaths(ann)
                if (paths.any { matchesConfigPath(configPath, it) }) {
                    // Use sourcePsi for correct navigation (especially for Kotlin)
                    val target = uMethod.sourcePsi ?: psiMethod
                    results.add(target.navigationElement)
                }
            }

            // Search annotated classes (e.g. @HttpClient on interface)
            AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).forEach { psiClass ->
                val uClass = psiClass.toUElement() as? UClass ?: return@forEach
                val ann = uClass.uAnnotations.find { it.qualifiedName == mapping.annotationFqn } ?: return@forEach
                val paths = resolveConfigPaths(ann)
                if (paths.any { matchesConfigPath(configPath, it) }) {
                    // Use sourcePsi for correct navigation (especially for Kotlin)
                    val target = uClass.sourcePsi ?: psiClass
                    results.add(target.navigationElement)
                }
            }
        }

        return results
    }

    private fun matchesConfigPath(actualPath: String, candidatePath: String): Boolean {
        val actualNorm = actualPath.split(".").joinToString(".") { ConfigPathResolver.camelToKebab(it) }
        val candidateNorm = candidatePath.split(".").joinToString(".") { ConfigPathResolver.camelToKebab(it) }
        return actualNorm == candidateNorm || actualNorm.startsWith("$candidateNorm.")
    }
}
