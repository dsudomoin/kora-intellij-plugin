package io.github.dsudomoin.koraplugin.endpoints

import com.intellij.psi.PsiField
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UReferenceExpression

/**
 * Resolves an annotation attribute to a string value. Handles plain literals ("GET"),
 * references to string constants — Java [PsiField] and Kotlin `const val` — and, when
 * explicitly allowed, falls back to the last identifier of the source text
 * (`HttpMethod.GET` → "GET") for verb-shaped attributes where the constant's name
 * is the value.
 */
object AttributeValueResolver {

    fun resolveStringAttributeValue(
        uAnnotation: UAnnotation,
        name: String,
        allowConstantNameFallback: Boolean = false,
    ): String? {
        val attribute = uAnnotation.findAttributeValue(name) ?: return null
        (attribute.evaluate() as? String)?.let { return it }

        if (attribute is UReferenceExpression) {
            when (val referenced = attribute.resolve()) {
                is PsiField -> (referenced.computeConstantValue() as? String)?.let { return it }

                is KtProperty -> if (referenced.hasModifier(KtTokens.CONST_KEYWORD)) {
                    referenced.initializer?.let(::constStringLiteral)?.let { return it }
                }
            }
        }

        if (!allowConstantNameFallback) return null
        return attribute.sourcePsi?.text
            ?.substringAfterLast('.')
            ?.trim()
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotEmpty() }
    }

    /** `const val PETS = "/pets"` — the initializer must be a single plain string literal. */
    private fun constStringLiteral(initializer: KtExpression): String? {
        val template = initializer as? KtStringTemplateExpression ?: return null
        if (template.entries.size != 1) return null
        return (template.entries.single() as? KtLiteralStringTemplateEntry)
            ?.text
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotEmpty() }
    }
}
