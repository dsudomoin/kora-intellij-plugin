package io.github.dsudomoin.koraplugin.config.hocon

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.hocon.psi.HKey
import io.github.dsudomoin.koraplugin.config.ConfigPathResolver
import io.github.dsudomoin.koraplugin.config.KoraConfigAnnotationRegistry
import io.github.dsudomoin.koraplugin.util.KoraLibraryUtil

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

        // The platform already runs this handler off EDT, under a modal progress and inside a read
        // action (ActionUtil#underModalProgress). A progress of our own would invokeAndWait from that
        // read action — a deadlock risk the platform logs and blames on us.
        val configSourceTarget = ConfigPathResolver.resolveConfigKeyToMethod(project, fullPath)
        if (configSourceTarget != null) return arrayOf(configSourceTarget)

        val annotationTargets = KoraConfigAnnotationRegistry.findAnnotatedElements(project, fullPath)
        if (annotationTargets.isNotEmpty()) return annotationTargets.toTypedArray()

        return null
    }
}
