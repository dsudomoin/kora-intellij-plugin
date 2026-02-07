package ru.dsudomoin.koraplugin.config.yaml

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue
import ru.dsudomoin.koraplugin.config.ConfigPathResolver

class YamlConfigGotoHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        if (sourceElement == null) return null

        val keyValue = PsiTreeUtil.getParentOfType(sourceElement, YAMLKeyValue::class.java) ?: return null
        // Only trigger on the key part, not the value
        val key = keyValue.key ?: return null
        if (!PsiTreeUtil.isAncestor(key, sourceElement, false)) return null

        val fullPath = buildYamlKeyPath(keyValue) ?: return null
        val target = ConfigPathResolver.resolveConfigKeyToMethod(sourceElement.project, fullPath) ?: return null

        return arrayOf(target)
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