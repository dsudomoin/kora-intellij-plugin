package io.github.dsudomoin.koraplugin.query

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import io.github.dsudomoin.koraplugin.util.KoraLibraryUtil

/**
 * Makes a `%{...}` macro in a Kora `@Query` a Cmd-hoverable / Cmd-clickable declaration (like a method name):
 * the cursor turns into a hand, the hover tooltip shows the expansion, and Cmd-click jumps to the entity.
 */
class KoraQueryMacroGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        val project = element.project
        if (DumbService.isDumb(project)) return null
        if (!KoraLibraryUtil.hasKoraLibrary(project)) return null

        val host = PsiTreeUtil.getParentOfType(element, PsiLanguageInjectionHost::class.java, false) ?: return null
        val method = KoraQuerySupport.getQueryMethod(host) ?: return null

        val contentRange = KoraQuerySupport.contentRange(host)
        val text = contentRange.substring(host.text)
        val hostStart = host.textRange.startOffset
        val macro = KoraQueryMacroParser.parseMacros(text, contentRange.startOffset)
            .firstOrNull { it.rangeInHost.shiftRight(hostStart).contains(offset) } ?: return null

        return arrayOf(KoraMacroTarget(host, macro, method))
    }
}
