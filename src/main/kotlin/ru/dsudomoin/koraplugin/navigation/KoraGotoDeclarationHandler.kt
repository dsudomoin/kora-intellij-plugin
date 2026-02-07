package ru.dsudomoin.koraplugin.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiParameter
import org.jetbrains.kotlin.psi.KtParameter
import ru.dsudomoin.koraplugin.resolve.KoraProviderResolver

class KoraGotoDeclarationHandler : GotoDeclarationHandler {

    private val LOG = Logger.getInstance(KoraGotoDeclarationHandler::class.java)

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        if (sourceElement == null) return null
        if (!isParameterIdentifier(sourceElement)) return null

        LOG.info("KoraGotoDeclarationHandler invoked on: '${sourceElement.text}' (${sourceElement.javaClass.simpleName})")

        val targets = KoraProviderResolver.resolve(sourceElement)

        LOG.info("KoraGotoDeclarationHandler found ${targets.size} targets")

        if (targets.isEmpty()) return null

        return targets.toTypedArray()
    }

    private fun isParameterIdentifier(element: PsiElement): Boolean {
        // Java: PsiIdentifier whose parent is PsiParameter
        if (element is PsiIdentifier && element.parent is PsiParameter) return true
        // Kotlin: IDENTIFIER token whose parent is KtParameter
        if (element.node?.elementType?.toString() == "IDENTIFIER" && element.parent is KtParameter) return true
        return false
    }
}
