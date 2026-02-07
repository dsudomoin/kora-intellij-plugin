package ru.dsudomoin.koraplugin.resolve

import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch

object KoraProviderResolver {

    private val LOG = Logger.getInstance(KoraProviderResolver::class.java)

    fun resolve(element: PsiElement): List<PsiElement> {
        val injectionPoint = InjectionPointDetector.detect(element) ?: return emptyList()
        val project = element.project

        val allProviders = resolveViaIndex(injectionPoint, project)
            ?: resolveViaFullScan(injectionPoint, project)

        val typeMatched = allProviders.filter { matchesType(injectionPoint, it) }
        LOG.info("After type filter: ${typeMatched.size} (required: ${injectionPoint.requiredType.canonicalText})")

        val result = typeMatched.filter { matchesTags(injectionPoint, it) }
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
        val typeMatched = providers.filter { matchesType(injectionPoint, it) }
        val result = typeMatched.filter { matchesTags(injectionPoint, it) }
        if (result.isNotEmpty()) {
            LOG.info("Fallback found ${result.size} providers")
            val elements = result.map { it.element }
            val methods = elements.filterIsInstance<PsiMethod>()
            return methods.ifEmpty { elements }
        }
        return emptyList()
    }

    private fun matchesType(injectionPoint: InjectionPoint, provider: KoraProvider): Boolean {
        val requiredType = injectionPoint.requiredType
        val providedType = provider.providedType

        // Full type check including generics
        if (requiredType.isAssignableFrom(providedType)) return true

        if (requiredType is PsiClassType && providedType is PsiClassType) {
            val requiredClass = requiredType.resolve() ?: return false
            val providedClass = providedType.resolve() ?: return false

            // Raw class must be compatible
            if (requiredClass != providedClass && !providedClass.isInheritor(requiredClass, true)) return false

            // When same raw class and both have type arguments, verify arguments match
            if (requiredClass == providedClass) {
                val reqArgs = requiredType.parameters
                val provArgs = providedType.parameters
                if (reqArgs.isNotEmpty() && provArgs.isNotEmpty() && reqArgs.size == provArgs.size) {
                    return reqArgs.zip(provArgs).all { (r, p) ->
                        // Type variable (e.g. <T>) matches anything
                        if (p is PsiClassType && p.resolve() is com.intellij.psi.PsiTypeParameter) return@all true
                        if (r is PsiClassType && r.resolve() is com.intellij.psi.PsiTypeParameter) return@all true
                        matchesTypeArgAssignable(r, p)
                    }
                }
            }

            return true
        }

        return false
    }

    private fun matchesTypeArgAssignable(requiredType: PsiType, providedType: PsiType): Boolean {
        if (requiredType.isAssignableFrom(providedType)) return true
        if (requiredType is PsiClassType && providedType is PsiClassType) {
            val reqClass = requiredType.resolve() ?: return false
            val provClass = providedType.resolve() ?: return false
            if (reqClass != provClass) return false
            // Recursively check nested generics
            val reqArgs = requiredType.parameters
            val provArgs = providedType.parameters
            if (reqArgs.isNotEmpty() && provArgs.isNotEmpty() && reqArgs.size == provArgs.size) {
                return reqArgs.zip(provArgs).all { (r, p) ->
                    if (p is PsiClassType && p.resolve() is com.intellij.psi.PsiTypeParameter) return@all true
                    if (r is PsiClassType && r.resolve() is com.intellij.psi.PsiTypeParameter) return@all true
                    matchesTypeArgAssignable(r, p)
                }
            }
            return true
        }
        return false
    }

    private fun matchesTags(injectionPoint: InjectionPoint, provider: KoraProvider): Boolean {
        val required = injectionPoint.tagInfo
        val provided = provider.tagInfo

        // @Tag.Any matches all providers
        if (required.isTagAny) return true

        // No tags required → only match providers with no tags
        if (required.tagFqns.isEmpty()) return provided.tagFqns.isEmpty()

        // Tags required → provider must have exactly matching tags
        return required.tagFqns == provided.tagFqns
    }
}
