package ru.dsudomoin.koraplugin.resolve

import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType

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
