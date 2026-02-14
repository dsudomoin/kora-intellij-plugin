package ru.dsudomoin.koraplugin.config.hocon

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.hocon.psi.HKey
import ru.dsudomoin.koraplugin.config.ConfigPathResolver
import ru.dsudomoin.koraplugin.config.KoraConfigAnnotationRegistry
import ru.dsudomoin.koraplugin.util.KoraLibraryUtil

class HoconConfigGotoHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        if (sourceElement == null) return null
        val project = sourceElement.project
        if (DumbService.isDumb(project)) return null
        if (!KoraLibraryUtil.hasKoraLibrary(project)) return null

        val hKey = PsiTreeUtil.getParentOfType(sourceElement, HKey::class.java) ?: return null
        if (!hKey.inField()) return null

        val fullPathOption = hKey.fullPathText()
        if (fullPathOption.isEmpty) return null
        val fullPath = fullPathOption.get() as String

        // Heavy: AnnotatedElementsSearch + ConfigSource scan → run off EDT
        var targets: Array<PsiElement>? = null
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                targets = ReadAction.compute<Array<PsiElement>?, RuntimeException> {
                    val configSourceTarget = ConfigPathResolver.resolveConfigKeyToMethod(project, fullPath)
                    if (configSourceTarget != null) return@compute arrayOf(configSourceTarget)

                    val annotationTargets = KoraConfigAnnotationRegistry.findAnnotatedElements(project, fullPath)
                    if (annotationTargets.isNotEmpty()) return@compute annotationTargets.toTypedArray()

                    null
                }
            },
            "Resolving config key...",
            true,
            project,
        )
        return targets
    }
}
