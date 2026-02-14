package ru.dsudomoin.koraplugin.resolve

import com.intellij.psi.PsiClass
import org.jetbrains.uast.*
import ru.dsudomoin.koraplugin.KoraAnnotations

data class TagInfo(
    val tagFqns: Set<String>,
    val isTagAny: Boolean,
) {
    companion object {
        val EMPTY = TagInfo(emptySet(), false)
    }
}

object TagExtractor {

    fun extractTags(element: UAnnotated): TagInfo {
        val tagAnnotation = element.findAnnotation(KoraAnnotations.TAG)
        if (tagAnnotation != null) {
            return extractFromTagAnnotation(tagAnnotation)
        }

        // Check for custom tag annotations (annotations that are themselves annotated with @Tag)
        val customTags = mutableSetOf<String>()
        var isAny = false
        for (annotation in element.uAnnotations) {
            val annotationFqn = annotation.qualifiedName ?: continue
            // Skip well-known non-Kora annotations to avoid expensive resolve()
            if (isKnownNonKoraAnnotation(annotationFqn)) continue
            val annotationClass = annotation.resolve() ?: continue
            val metaTag = annotationClass.getAnnotation(KoraAnnotations.TAG) ?: continue
            val metaUAnnotation = metaTag.toUElement() as? UAnnotation ?: continue
            val metaInfo = extractFromTagAnnotation(metaUAnnotation)
            customTags.addAll(metaInfo.tagFqns)
            if (metaInfo.isTagAny) isAny = true
        }

        if (customTags.isNotEmpty() || isAny) {
            return TagInfo(customTags, isAny)
        }

        return TagInfo.EMPTY
    }

    private fun extractFromTagAnnotation(tagAnnotation: UAnnotation): TagInfo {
        val tags = mutableSetOf<String>()
        var isAny = false

        val valueAttr = tagAnnotation.findAttributeValue("value") ?: return TagInfo.EMPTY

        val classRefs = mutableListOf<UClassLiteralExpression>()
        collectClassLiterals(valueAttr, classRefs)

        for (classLiteral in classRefs) {
            val type = classLiteral.type
            val fqn = type?.canonicalText
                ?: // Fallback: try to resolve from the expression
                (classLiteral.expression as? UReferenceExpression)
                    ?.let { ref ->
                        (ref.resolve() as? PsiClass)?.qualifiedName
                    }
            if (fqn != null) {
                if (fqn == KoraAnnotations.TAG_ANY) {
                    isAny = true
                } else {
                    tags.add(fqn)
                }
            }
        }

        return TagInfo(tags, isAny)
    }

    private val NON_KORA_PREFIXES = arrayOf(
        "java.", "javax.", "jakarta.",
        "kotlin.", "kotlinx.",
        "org.jetbrains.annotations.",
        "org.intellij.",
        "org.springframework.",
    )

    private fun isKnownNonKoraAnnotation(fqn: String): Boolean {
        return NON_KORA_PREFIXES.any { fqn.startsWith(it) }
    }

    private fun collectClassLiterals(expr: UExpression, result: MutableList<UClassLiteralExpression>) {
        when (expr) {
            is UClassLiteralExpression -> result.add(expr)
            is UCallExpression -> {
                // Array initializer: arrayOf(...) or {X.class, Y.class}
                for (arg in expr.valueArguments) {
                    collectClassLiterals(arg, result)
                }
            }
            else -> {
                // Try children for nested expressions
            }
        }
    }
}
