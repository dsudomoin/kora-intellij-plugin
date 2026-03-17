package io.github.dsudomoin.koraplugin.config.yaml

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue
import io.github.dsudomoin.koraplugin.config.ConfigPathResolver
import io.github.dsudomoin.koraplugin.config.KoraConfigAnnotationRegistry
import io.github.dsudomoin.koraplugin.util.KoraLibraryUtil

class YamlConfigGotoHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        if (sourceElement == null) return null
        val project = sourceElement.project
        if (DumbService.isDumb(project)) return null
        if (!KoraLibraryUtil.hasKoraLibrary(project)) return null

        val keyValue = PsiTreeUtil.getParentOfType(sourceElement, YAMLKeyValue::class.java) ?: return null
        // Only trigger on the key part, not the value
        val key = keyValue.key ?: return null
        if (!PsiTreeUtil.isAncestor(key, sourceElement, false)) return null

        val fullPath = buildYamlKeyPath(keyValue) ?: return null

        var result: Array<PsiElement>? = null
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                result = ReadAction.compute<Array<PsiElement>?, RuntimeException> {
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
        return result
    }

}

fun buildYamlKeyPath(keyValue: YAMLKeyValue): String? {
    val segments = mutableListOf<String>()
    var current: YAMLKeyValue? = keyValue
    while (current != null) {
        val key = current.keyText.takeIf { it.isNotEmpty() } ?: return null
        segments.add(key)
        current = current.parentMapping?.parent as? YAMLKeyValue
    }
    return segments.reversed().joinToString(".")
}
