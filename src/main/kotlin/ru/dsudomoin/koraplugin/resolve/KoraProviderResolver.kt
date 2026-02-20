package ru.dsudomoin.koraplugin.resolve

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import ru.dsudomoin.koraplugin.KoraAnnotations

object KoraProviderResolver {

    private val LOG = Logger.getInstance(KoraProviderResolver::class.java)

    private val COMMON_TYPE_PREFIXES = KoraAnnotations.COMMON_TYPE_PREFIXES

    fun resolve(element: PsiElement): List<PsiElement> {
        val injectionPoint = InjectionPointDetector.detect(element) ?: return emptyList()
        return resolve(injectionPoint, element.project)
    }

    fun resolve(injectionPoint: InjectionPoint, project: com.intellij.openapi.project.Project): List<PsiElement> {
        // Index-backed lookup; falls back to full scan only if index is unavailable (null)
        val allProviders = resolveViaIndex(injectionPoint, project)
            ?: resolveViaFullScan(injectionPoint, project)

        // Single pass: filter by type+tags, partition into methods vs non-methods
        val methods = mutableListOf<PsiElement>()
        val others = mutableListOf<PsiElement>()
        for (provider in allProviders) {
            if (!KoraTypeMatching.isTypeAssignable(injectionPoint.requiredType, provider.providedType)) continue
            if (!KoraTypeMatching.isTagMatch(injectionPoint.tagInfo, provider.tagInfo)) continue
            val element = provider.element
            if (element is PsiMethod) methods.add(element) else others.add(element)
        }
        LOG.debug { "Resolve: ${methods.size} methods + ${others.size} others (required: ${injectionPoint.requiredType.canonicalText})" }
        val result = methods.ifEmpty { others }
        if (result.isNotEmpty()) return result

        // Fallback: annotation-generated providers (e.g., @ConfigValueExtractor on class)
        return findAnnotationGeneratedProviders(injectionPoint.requiredType, injectionPoint.tagInfo, project)
    }

    /**
     * Try index-backed lookup. Returns null if the index is not available.
     */
    private fun resolveViaIndex(injectionPoint: InjectionPoint, project: com.intellij.openapi.project.Project): List<KoraProvider>? {
        val requiredType = injectionPoint.requiredType
        // Resolve once, reuse for both FQN extraction and ClassInheritorsSearch
        val requiredClass = if (requiredType is PsiClassType) requiredType.resolve() else null
        val rawFqn = requiredClass?.qualifiedName ?: requiredType.canonicalText

        // 1. Query index for direct type match
        val directProviders = ProviderSearch.findProvidersByType(project, rawFqn) ?: return null
        LOG.debug { "Index lookup for '$rawFqn': ${directProviders.size} direct providers" }

        // 2. Find subtypes and query index for each (skip for common JDK/Kotlin types)
        val subtypeProviders = mutableListOf<KoraProvider>()
        if (requiredClass != null && !isCommonType(rawFqn)) {
            val scope = GlobalSearchScope.projectScope(project)
            ClassInheritorsSearch.search(requiredClass, scope, true).forEach { inheritor ->
                ProgressManager.checkCanceled()
                val inheritorFqn = inheritor.qualifiedName ?: return@forEach
                val providers = ProviderSearch.findProvidersByType(project, inheritorFqn) ?: return@forEach
                subtypeProviders.addAll(providers)
            }
            LOG.debug { "Subtype index lookup: ${subtypeProviders.size} subtype providers" }
        }

        return directProviders + subtypeProviders
    }

    private fun isCommonType(fqn: String): Boolean {
        return COMMON_TYPE_PREFIXES.any { fqn.startsWith(it) }
    }

    /**
     * Fallback for annotation-generated providers.
     * E.g., @ConfigValueExtractor on class Foo → generates ConfigValueExtractor<Foo>.
     */
    fun findAnnotationGeneratedProviders(requiredType: PsiType, tagInfo: TagInfo, project: com.intellij.openapi.project.Project): List<PsiElement> {
        if (requiredType !is PsiClassType) return emptyList()
        // Generated extractors have no tags
        if (tagInfo.tagFqns.isNotEmpty() && !tagInfo.isTagAny) return emptyList()

        val requiredClass = requiredType.resolve() ?: return emptyList()
        val requiredFqn = requiredClass.qualifiedName ?: return emptyList()

        val typeArgs = requiredType.parameters
        if (typeArgs.size != 1 || typeArgs[0] !is PsiClassType) return emptyList()
        val targetClass = (typeArgs[0] as PsiClassType).resolve() ?: return emptyList()

        return when (requiredFqn) {
            KoraAnnotations.CONFIG_VALUE_EXTRACTOR_TYPE -> {
                if (targetClass.hasAnnotation(KoraAnnotations.CONFIG_VALUE_EXTRACTOR_ANNOTATION) ||
                    targetClass.hasAnnotation(KoraAnnotations.CONFIG_SOURCE)
                ) listOf(targetClass) else emptyList()
            }
            // @Json generates both JsonReader<T> and JsonWriter<T>
            // @JsonReader generates only JsonReader<T>
            KoraAnnotations.JSON_READER_TYPE -> {
                if (targetClass.hasAnnotation(KoraAnnotations.JSON) ||
                    targetClass.hasAnnotation(KoraAnnotations.JSON_READER_ANNOTATION)
                ) listOf(targetClass) else emptyList()
            }
            // @Json generates both JsonReader<T> and JsonWriter<T>
            // @JsonWriter generates only JsonWriter<T>
            KoraAnnotations.JSON_WRITER_TYPE -> {
                if (targetClass.hasAnnotation(KoraAnnotations.JSON) ||
                    targetClass.hasAnnotation(KoraAnnotations.JSON_WRITER_ANNOTATION)
                ) listOf(targetClass) else emptyList()
            }
            else -> emptyList()
        }
    }

    private fun resolveViaFullScan(injectionPoint: InjectionPoint, project: com.intellij.openapi.project.Project): List<KoraProvider> {
        LOG.debug { "Using full scan fallback" }
        return ProviderSearch.findAllProviders(project)
    }

}
