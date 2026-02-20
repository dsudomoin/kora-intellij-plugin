package ru.dsudomoin.koraplugin.resolve

import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.util.TypeConversionUtil

object KoraTypeMatching {

    /**
     * Checks if [providedType] is assignable to [requiredType], including generic type argument matching.
     */
    fun isTypeAssignable(requiredType: PsiType, providedType: PsiType): Boolean {
        // For PsiClassType: always do our own generic-aware check
        // (isAssignableFrom uses erasure and ignores type arguments for subclasses)
        if (requiredType is PsiClassType && providedType is PsiClassType) {
            val reqClass = requiredType.resolve()
            val provClass = providedType.resolve()

            // If we can't resolve, fall back to IntelliJ's check
            if (reqClass == null || provClass == null) {
                return requiredType.isAssignableFrom(providedType)
            }

            // Same class: compare type arguments
            if (reqClass == provClass) {
                return areTypeArgsCompatible(requiredType.parameters, providedType.parameters)
            }

            // Subclass relationship
            if (!provClass.isInheritor(reqClass, true)) return false

            val reqArgs = requiredType.parameters
            if (reqArgs.isEmpty()) return true // Raw required type → any subclass matches

            // Check generics via supertype substitution
            val derivedResult = providedType.resolveGenerics()
            if (derivedResult.element != null) {
                val substitutor = TypeConversionUtil.getSuperClassSubstitutor(
                    reqClass, derivedResult.element!!, derivedResult.substitutor
                )
                return reqClass.typeParameters.withIndex().all { (i, tp) ->
                    val substituted = substitutor.substitute(tp) ?: return@all true
                    if (i >= reqArgs.size) return@all true
                    isTypeArgCompatible(reqArgs[i], substituted)
                }
            }

            return true // Can't determine → accept
        }

        // Non-class types (primitives, arrays, wildcards): delegate to IntelliJ
        return requiredType.isAssignableFrom(providedType)
    }

    /**
     * Checks if type arguments are compatible (same-class case).
     * If one or both sides are raw (no args), accept.
     */
    private fun areTypeArgsCompatible(reqArgs: Array<PsiType>, provArgs: Array<PsiType>): Boolean {
        if (reqArgs.isEmpty() || provArgs.isEmpty()) return true
        if (reqArgs.size != provArgs.size) return true
        return reqArgs.zip(provArgs).all { (r, p) -> isTypeArgCompatible(r, p) }
    }

    /**
     * Checks if a provided type argument is compatible with a required type argument.
     * For type parameters (e.g., T extends Enum<T>), checks that required satisfies bounds.
     */
    private fun isTypeArgCompatible(required: PsiType, provided: PsiType): Boolean {
        // Provided is a type parameter (e.g., factory returns Foo<T>): check bounds
        if (provided is PsiClassType) {
            val resolved = provided.resolve()
            if (resolved is PsiTypeParameter) {
                val bounds = resolved.extendsListTypes
                if (bounds.isEmpty()) return true
                // Required must satisfy all bounds of T (i.e., required <: bound)
                return bounds.all { bound -> isTypeAssignable(bound, required) }
            }
        }
        // Required is a type parameter (injection site has unresolved generic): accept
        if (required is PsiClassType && required.resolve() is PsiTypeParameter) return true
        return isTypeAssignable(required, provided)
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
            if (class1 == null || class1 != class2) return false
            val args1 = type1.parameters
            val args2 = type2.parameters
            if (args1.size != args2.size) return false
            return args1.zip(args2).all { (a, b) -> isTypeEqual(a, b) }
        }
        return false
    }
}
