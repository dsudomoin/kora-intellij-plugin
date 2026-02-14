package ru.dsudomoin.koraplugin.config

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.toUElement
import ru.dsudomoin.koraplugin.util.KoraLibraryUtil

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
        val project = sourceElement.project
        if (DumbService.isDumb(project)) return null
        if (!KoraLibraryUtil.hasKoraLibrary(project)) return null

        // Check we're inside a string literal (cheap PSI walk — OK on EDT)
        if (!isInsideStringLiteral(sourceElement)) return null

        // Walk up PSI tree to find the enclosing annotation
        val annotationPsi = findEnclosingAnnotation(sourceElement) ?: return null
        // Convert annotation PSI to UAnnotation
        val uAnnotation = annotationPsi.toUElement() as? UAnnotation ?: return null

        val fqn = uAnnotation.qualifiedName
        if (fqn == null || fqn !in KoraConfigAnnotationRegistry.allAnnotationFqns) return null

        val paths = KoraConfigAnnotationRegistry.resolveConfigPaths(uAnnotation)
        if (paths.isEmpty()) return null

        // Heavy: FilenameIndex + PSI traversal → run off EDT
        var targets: List<PsiElement> = emptyList()
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                targets = ReadAction.compute<List<PsiElement>, RuntimeException> {
                    paths.flatMap { findConfigKeyElements(project, it) }
                }
            },
            "Resolving config key...",
            true,
            project,
        )
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
