package ru.dsudomoin.koraplugin.config

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
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

    private val mappingsByFqn: Map<String, ConfigAnnotationMapping> = mappings.associateBy { it.annotationFqn }

    fun findMappingForAnnotation(annotationFqn: String): ConfigAnnotationMapping? =
        mappingsByFqn[annotationFqn]

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
     * maps to the given config path. Uses a cached map built once per code modification.
     */
    fun findAnnotatedElements(project: Project, configPath: String): List<PsiElement> {
        val cache = getAnnotatedElementsCache(project)
        val normalizedPath = normalizePath(configPath)

        val results = mutableListOf<PsiElement>()
        // Exact match
        cache[normalizedPath]?.let { results.addAll(it) }
        // Prefix match: configPath starts with a candidate path (e.g., "resilient.retry.myRetry" matches "resilient.retry.myRetry")
        // This is already covered by exact. Also check if any candidate is a prefix of configPath.
        for ((candidatePath, elements) in cache) {
            if (candidatePath != normalizedPath && normalizedPath.startsWith("$candidatePath.")) {
                results.addAll(elements)
            }
        }
        return results.distinct()
    }

    /**
     * Cached map: normalizedConfigPath → List<PsiElement>.
     * Built once, annotation searches happen only during cache build.
     */
    private fun getAnnotatedElementsCache(project: Project): Map<String, List<PsiElement>> {
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            val map = buildAnnotatedElementsMap(project)
            CachedValueProvider.Result.create(
                map,
                PsiModificationTracker.getInstance(project).forLanguage(com.intellij.lang.java.JavaLanguage.INSTANCE),
                PsiModificationTracker.getInstance(project).forLanguage(org.jetbrains.kotlin.idea.KotlinLanguage.INSTANCE),
            )
        }
    }

    private fun buildAnnotatedElementsMap(project: Project): Map<String, List<PsiElement>> {
        val allScope = GlobalSearchScope.allScope(project)
        val projectScope = GlobalSearchScope.projectScope(project)
        val facade = JavaPsiFacade.getInstance(project)
        val result = mutableMapOf<String, MutableList<PsiElement>>()

        for (mapping in mappings) {
            ProgressManager.checkCanceled()
            // Annotation class is in JAR → allScope; annotated elements are in project → projectScope
            val annotationClass = facade.findClass(mapping.annotationFqn, allScope) ?: continue

            // Search annotated methods
            AnnotatedElementsSearch.searchPsiMethods(annotationClass, projectScope).forEach { psiMethod ->
                ProgressManager.checkCanceled()
                val uMethod = psiMethod.toUElement() as? UMethod ?: return@forEach
                val ann = uMethod.uAnnotations.find { it.qualifiedName == mapping.annotationFqn } ?: return@forEach
                val paths = resolveConfigPaths(ann)
                val target = (uMethod.sourcePsi ?: psiMethod).navigationElement
                for (path in paths) {
                    result.getOrPut(normalizePath(path)) { mutableListOf() }.add(target)
                }
            }

            // Search annotated classes (e.g. @HttpClient on interface)
            AnnotatedElementsSearch.searchPsiClasses(annotationClass, projectScope).forEach { psiClass ->
                ProgressManager.checkCanceled()
                val uClass = psiClass.toUElement() as? UClass ?: return@forEach
                val ann = uClass.uAnnotations.find { it.qualifiedName == mapping.annotationFqn } ?: return@forEach
                val paths = resolveConfigPaths(ann)
                val target = (uClass.sourcePsi ?: psiClass).navigationElement
                for (path in paths) {
                    result.getOrPut(normalizePath(path)) { mutableListOf() }.add(target)
                }
            }
        }

        return result
    }

    private fun normalizePath(path: String): String {
        return path.split(".").joinToString(".") { ConfigPathResolver.camelToKebab(it) }
    }
}
