package ru.dsudomoin.koraplugin.resolve

import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch

object KoraProviderResolver {

    private val LOG = Logger.getInstance(KoraProviderResolver::class.java)

    fun resolve(element: PsiElement): List<PsiElement> {
        val injectionPoint = InjectionPointDetector.detect(element) ?: return emptyList()
        val project = element.project

        val allProviders = resolveViaIndex(injectionPoint, project)
            ?: resolveViaFullScan(injectionPoint, project)

        val typeMatched = allProviders.filter { KoraTypeMatching.isTypeAssignable(injectionPoint.requiredType, it.providedType) }
        LOG.info("After type filter: ${typeMatched.size} (required: ${injectionPoint.requiredType.canonicalText})")

        val result = typeMatched.filter { KoraTypeMatching.isTagMatch(injectionPoint.tagInfo, it.tagInfo) }
        LOG.info("After tag filter: ${result.size}")

        // If index-backed search found nothing, fall back to full scan (for unannotated parent modules)
        if (result.isEmpty()) {
            LOG.info("No results, falling back to full scan")
            return resolveWithFullScan(injectionPoint, project)
        }

        val elements = result.map { it.element }
        val methods = elements.filterIsInstance<PsiMethod>()
        return methods.ifEmpty { elements }
    }

    /**
     * Try index-backed lookup. Returns null if the index is not available.
     */
    private fun resolveViaIndex(injectionPoint: InjectionPoint, project: com.intellij.openapi.project.Project): List<KoraProvider>? {
        val requiredType = injectionPoint.requiredType
        val rawFqn = if (requiredType is PsiClassType) {
            requiredType.resolve()?.qualifiedName
        } else {
            requiredType.canonicalText
        } ?: return null

        // 1. Query index for direct type match
        val directProviders = ProviderSearch.findProvidersByType(project, rawFqn) ?: return null
        LOG.info("Index lookup for '$rawFqn': ${directProviders.size} direct providers")

        // 2. Find subtypes and query index for each
        val subtypeProviders = mutableListOf<KoraProvider>()
        if (requiredType is PsiClassType) {
            val requiredClass = requiredType.resolve()
            if (requiredClass != null) {
                val scope = GlobalSearchScope.allScope(project)
                ClassInheritorsSearch.search(requiredClass, scope, true).forEach { inheritor ->
                    val inheritorFqn = inheritor.qualifiedName ?: return@forEach
                    val providers = ProviderSearch.findProvidersByType(project, inheritorFqn) ?: return@forEach
                    subtypeProviders.addAll(providers)
                }
                LOG.info("Subtype index lookup: ${subtypeProviders.size} subtype providers")
            }
        }

        return directProviders + subtypeProviders
    }

    private fun resolveViaFullScan(injectionPoint: InjectionPoint, project: com.intellij.openapi.project.Project): List<KoraProvider> {
        LOG.info("Using full scan fallback")
        return ProviderSearch.findAllProviders(project)
    }

    private fun resolveWithFullScan(injectionPoint: InjectionPoint, project: com.intellij.openapi.project.Project): List<PsiElement> {
        val providers = ProviderSearch.findAllProviders(project)
        val typeMatched = providers.filter { KoraTypeMatching.isTypeAssignable(injectionPoint.requiredType, it.providedType) }
        val result = typeMatched.filter { KoraTypeMatching.isTagMatch(injectionPoint.tagInfo, it.tagInfo) }
        if (result.isNotEmpty()) {
            LOG.info("Fallback found ${result.size} providers")
            val elements = result.map { it.element }
            val methods = elements.filterIsInstance<PsiMethod>()
            return methods.ifEmpty { elements }
        }
        return emptyList()
    }
}
