package ru.dsudomoin.koraplugin.resolve

import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod

object KoraProviderResolver {

    private val LOG = Logger.getInstance(KoraProviderResolver::class.java)

    fun resolve(element: PsiElement): List<PsiElement> {
        val injectionPoint = InjectionPointDetector.detect(element) ?: return emptyList()

        val providers = ProviderSearch.findAllProviders(element.project)
        LOG.info("Found ${providers.size} total providers")

        val typeMatched = providers.filter { matchesType(injectionPoint, it) }
        LOG.info("After type filter: ${typeMatched.size} (required: ${injectionPoint.requiredType.canonicalText})")
        for (p in typeMatched) {
            val name = when (val el = p.element) {
                is PsiClass -> el.qualifiedName
                is PsiMethod -> "${el.containingClass?.qualifiedName}.${el.name}"
                else -> el.toString()
            }
            LOG.info("  type-matched provider: $name (${p.providedType.canonicalText}, tags=${p.tagInfo})")
        }

        val result = typeMatched.filter { matchesTags(injectionPoint, it) }
        LOG.info("After tag filter: ${result.size}")

        val elements = result.map { it.element }
        val methods = elements.filterIsInstance<PsiMethod>()
        return methods.ifEmpty { elements }
    }

    private fun matchesType(injectionPoint: InjectionPoint, provider: KoraProvider): Boolean {
        val requiredType = injectionPoint.requiredType
        val providedType = provider.providedType

        // Direct type assignability check
        if (requiredType.isAssignableFrom(providedType)) return true

        // For class types, compare raw types (handles generic factory methods like
        // fun <T> factory(): SomeType<T> matching SomeType<ConcreteArg>)
        if (requiredType is PsiClassType && providedType is PsiClassType) {
            val requiredClass = requiredType.resolve() ?: return false
            val providedClass = providedType.resolve() ?: return false
            return requiredClass == providedClass || providedClass.isInheritor(requiredClass, true)
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
