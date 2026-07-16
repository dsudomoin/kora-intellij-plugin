package io.github.dsudomoin.koraplugin.query

import com.intellij.lang.Language
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.impl.FakePsiElement

/**
 * Lightweight declaration target for a `%{...}` macro, returned by [KoraQueryMacroGotoDeclarationHandler].
 * Makes the macro behave like a symbol under Cmd: [getNavigationElement] points at the resolved entity class
 * (so the cursor turns into a hand and Cmd-click navigates there), while the Cmd-hover tooltip is rendered by
 * [KoraQueryMacroQuickNavigateProvider] from this element directly.
 */
class KoraMacroTarget(
    private val host: PsiElement,
    val macro: KoraQueryMacro,
    val method: PsiMethod,
) : FakePsiElement() {

    override fun getParent(): PsiElement = host

    override fun getLanguage(): Language = host.language

    override fun getContainingFile(): PsiFile? = host.containingFile

    override fun getName(): String = macro.rawText

    override fun getNavigationElement(): PsiElement =
        KoraQueryMacroExpander.resolveTargetClass(method, macro.target) ?: this

    override fun isValid(): Boolean = host.isValid && method.isValid
}
