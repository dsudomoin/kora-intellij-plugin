package io.github.dsudomoin.koraplugin.config

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.codeInsight.navigation.openFileWithPsiElement
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.ui.awt.RelativePoint
import io.github.dsudomoin.koraplugin.KoraAnnotations
import io.github.dsudomoin.koraplugin.util.KoraLibraryUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.getUastParentOfType
import java.awt.event.MouseEvent

/**
 * Gutter icon on the @KafkaListener annotation line: navigates to the config key the
 * annotation value points to. Path resolution is shared with the annotation-value
 * goto handler ([KoraConfigAnnotationRegistry.resolveConfigPaths]).
 */
class KafkaListenerLineMarkerProvider : LineMarkerProvider, DumbAware {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        val first = elements.firstOrNull() ?: return
        val project = first.project
        if (DumbService.isDumb(project)) return
        if (!KoraLibraryUtil.hasKoraLibrary(project)) return

        for (element in elements) {
            ProgressManager.checkCanceled()
            if (!isKafkaListenerAnnotationName(element)) continue

            result.add(
                LineMarkerInfo(
                    element,
                    element.textRange,
                    AllIcons.FileTypes.Config,
                    { TOOLTIP },
                    KafkaConfigNavigationHandler(),
                    GutterIconRenderer.Alignment.LEFT,
                    { TOOLTIP },
                ),
            )
        }
    }

    /** The annotation name identifier (Java PsiIdentifier / Kotlin IDENTIFIER leaf) of @KafkaListener. */
    private fun isKafkaListenerAnnotationName(element: PsiElement): Boolean {
        if (element !is PsiIdentifier && element.node?.elementType != KtTokens.IDENTIFIER) return false
        // Cheap structural pre-filter: the annotation-name identifier sits within five
        // plain PSI levels of the annotation node (Kotlin goes through a type reference:
        // KtNameReferenceExpression → KtUserType → KtTypeReference → KtConstructorCallee
        // Expression → KtAnnotationEntry; Java needs two), so identifiers elsewhere
        // (method bodies, imports, etc.) bail out here instead of walking the whole
        // ancestor chain through UAST on every pass.
        var current: PsiElement = element
        repeat(5) {
            current = current.parent ?: return false
            // Only the annotation's own name: argument names (`value = ...`) and referenced
            // constants sit under the same annotation node and must not get a second icon.
            when (current) {
                is PsiAnnotation ->
                    return current.nameReferenceElement == element.parent && isKafkaListener(element)

                is KtAnnotationEntry ->
                    return current.calleeExpression?.constructorReferenceExpression == element.parent &&
                        isKafkaListener(element)

                else -> {}
            }
        }
        return false
    }

    private fun isKafkaListener(element: PsiElement): Boolean =
        element.getUastParentOfType<UAnnotation>()?.qualifiedName == KoraAnnotations.KAFKA_LISTENER

    private class KafkaConfigNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent, elt: PsiElement) {
            val project = elt.project
            object : Task.Backgroundable(project, "Resolving Kafka config key...", true) {
                private var configPaths: List<String> = emptyList()
                private var targets: List<PsiElement> = emptyList()

                override fun run(indicator: ProgressIndicator) {
                    ReadAction.compute<Unit, RuntimeException> {
                        val uAnnotation = elt.getUastParentOfType<UAnnotation>() ?: return@compute
                        configPaths = KoraConfigAnnotationRegistry.resolveConfigPaths(uAnnotation)
                        if (configPaths.isNotEmpty()) {
                            targets = configPaths.flatMap { findConfigKeyElements(project, it) }.distinct()
                        }
                    }
                }

                override fun onSuccess() {
                    when {
                        configPaths.isEmpty() ->
                            showBalloon(e, "No config path in @KafkaListener value")

                        targets.isEmpty() ->
                            showBalloon(e, "Config key not found: <b>${configPaths.first()}</b>")

                        targets.size == 1 -> openFileWithPsiElement(targets.single(), true, true)
                        else -> PsiTargetNavigator(targets).navigate(e, "Choose Config Key", project)
                    }
                }
            }.queue()
        }

        private fun showBalloon(e: MouseEvent, html: String) {
            JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder(html, MessageType.WARNING, null)
                .createBalloon()
                .show(RelativePoint(e), Balloon.Position.above)
        }
    }

    private companion object {
        const val TOOLTIP = "Navigate to Kafka config key"
    }
}
