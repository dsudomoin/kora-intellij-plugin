package ru.dsudomoin.koraplugin.config

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.toUElement

/**
 * Handles Ctrl+Click on string literal values inside Kora config annotations.
 * For example, clicking on "test" in @Retry("test") navigates to resilient.retry.test in YAML/HOCON.
 */
class AnnotationValueGotoHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        if (sourceElement == null) return null

        // Check we're inside a string literal
        if (!isInsideStringLiteral(sourceElement)) return null

        // Walk up PSI tree to find the enclosing annotation
        val annotationPsi = findEnclosingAnnotation(sourceElement) ?: return null
        // Convert annotation PSI to UAnnotation
        val uAnnotation = annotationPsi.toUElement() as? UAnnotation ?: return null

        val fqn = uAnnotation.qualifiedName
        if (fqn == null || fqn !in KoraConfigAnnotationRegistry.allAnnotationFqns) return null

        val paths = KoraConfigAnnotationRegistry.resolveConfigPaths(uAnnotation)
        if (paths.isEmpty()) return null

        val targets = paths.flatMap { findConfigKeyElements(sourceElement.project, it) }
        return targets.toTypedArray().ifEmpty { null }
    }

    private fun isInsideStringLiteral(element: PsiElement): Boolean {
        var current: PsiElement? = element
        while (current != null) {
            if (current is PsiLiteralExpression && current.value is String) return true
            if (current is KtStringTemplateExpression) return true
            // Stop walking if we've left the annotation
            if (current is PsiAnnotation || current is KtAnnotationEntry) return false
            if (current is com.intellij.psi.PsiMember) return false
            if (current is org.jetbrains.kotlin.psi.KtDeclaration) return false
            current = current.parent
        }
        return false
    }

    private fun findEnclosingAnnotation(element: PsiElement): PsiElement? {
        var current: PsiElement? = element
        while (current != null) {
            if (current is PsiAnnotation) return current
            if (current is KtAnnotationEntry) return current
            // Don't walk past declarations
            if (current is com.intellij.psi.PsiMember) return null
            if (current is org.jetbrains.kotlin.psi.KtDeclaration) return null
            current = current.parent
        }
        return null
    }
}
