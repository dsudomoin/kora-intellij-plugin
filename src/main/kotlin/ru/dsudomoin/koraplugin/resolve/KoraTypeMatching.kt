package ru.dsudomoin.koraplugin.resolve

import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter

object KoraTypeMatching {

    /**
     * Checks if [providedType] is assignable to [requiredType], including generic type argument matching.
     */
    fun isTypeAssignable(requiredType: PsiType, providedType: PsiType): Boolean {
        if (requiredType.isAssignableFrom(providedType)) return true

        if (requiredType is PsiClassType && providedType is PsiClassType) {
            val requiredClass = requiredType.resolve() ?: return false
            val providedClass = providedType.resolve() ?: return false

            if (requiredClass != providedClass && !providedClass.isInheritor(requiredClass, true)) return false

            if (requiredClass == providedClass) {
                val reqArgs = requiredType.parameters
                val provArgs = providedType.parameters
                if (reqArgs.isNotEmpty() && provArgs.isNotEmpty() && reqArgs.size == provArgs.size) {
                    return reqArgs.zip(provArgs).all { (r, p) ->
                        if (p is PsiClassType && p.resolve() is PsiTypeParameter) return@all true
                        if (r is PsiClassType && r.resolve() is PsiTypeParameter) return@all true
                        isTypeAssignable(r, p)
                    }
                }
            }

            return true
        }

        return false
    }

    /**
     * Tag matching: @Tag.Any matches everything, empty requires empty, otherwise exact set match.
     */
    fun isTagMatch(required: TagInfo, provided: TagInfo): Boolean {
        if (required.isTagAny) return true
        if (required.tagFqns.isEmpty()) return provided.tagFqns.isEmpty()
        return required.tagFqns == provided.tagFqns
    }

    /**
     * Symmetric tag matching for finding sibling injection sites.
     */
    fun isTagMatchSymmetric(tags1: TagInfo, tags2: TagInfo): Boolean {
        if (tags1.isTagAny || tags2.isTagAny) return true
        return tags1.tagFqns == tags2.tagFqns
    }

    /**
     * Exact tag matching (both sets and isTagAny flag must be equal).
     */
    fun isTagMatchExact(tags1: TagInfo, tags2: TagInfo): Boolean {
        return tags1.tagFqns == tags2.tagFqns && tags1.isTagAny == tags2.isTagAny
    }

    /**
     * Type equality for finding sibling injection sites.
     */
    fun isTypeEqual(type1: PsiType, type2: PsiType): Boolean {
        if (type1 == type2) return true
        if (type1 is PsiClassType && type2 is PsiClassType) {
            val class1 = type1.resolve()
            val class2 = type2.resolve()
            return class1 != null && class1 == class2
        }
        return false
    }
}
