package ru.dsudomoin.koraplugin.config.hocon

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.hocon.psi.HKey
import ru.dsudomoin.koraplugin.config.ConfigPathResolver

class HoconConfigGotoHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        if (sourceElement == null) return null

        val hKey = PsiTreeUtil.getParentOfType(sourceElement, HKey::class.java) ?: return null
        if (!hKey.inField()) return null

        val fullPathOption = hKey.fullPathText()
        if (fullPathOption.isEmpty) return null
        val fullPath = fullPathOption.get() as String

        val target = ConfigPathResolver.resolveConfigKeyToMethod(sourceElement.project, fullPath) ?: return null
        return arrayOf(target)
    }
}
