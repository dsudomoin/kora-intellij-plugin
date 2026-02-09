package ru.dsudomoin.koraplugin.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import ru.dsudomoin.koraplugin.resolve.KoraProviderResolver
import ru.dsudomoin.koraplugin.util.KoraLibraryUtil
import ru.dsudomoin.koraplugin.util.isParameterIdentifier

class KoraGotoDeclarationHandler : GotoDeclarationHandler {

    private val LOG = Logger.getInstance(KoraGotoDeclarationHandler::class.java)

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        if (sourceElement == null) return null
        if (!KoraLibraryUtil.hasKoraLibrary(sourceElement.project)) return null
        if (!isParameterIdentifier(sourceElement)) return null

        LOG.info("KoraGotoDeclarationHandler invoked on: '${sourceElement.text}' (${sourceElement.javaClass.simpleName})")

        val targets = KoraProviderResolver.resolve(sourceElement)

        LOG.info("KoraGotoDeclarationHandler found ${targets.size} targets")

        if (targets.isEmpty()) return null

        return targets.toTypedArray()
    }
}
