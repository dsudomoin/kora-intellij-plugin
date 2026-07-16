package io.github.dsudomoin.koraplugin.query

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement

/**
 * Renders the Cmd-hover tooltip for a [KoraMacroTarget]. `CtrlMouseHandler` calls `getQuickNavigateInfo` on
 * the target element directly (not its navigation element), so the tooltip shows the macro expansion here
 * even though the target navigates to the entity class.
 */
class KoraQueryMacroQuickNavigateProvider : AbstractDocumentationProvider() {

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        val target = element as? KoraMacroTarget ?: return null
        val expansion = KoraQueryMacroExpander.expand(target.macro, target.method)
        val type = KoraQueryMacroExpander.resolveTargetClass(target.method, target.macro.target)?.name
        val sb = StringBuilder()
        sb.append("<b>Kora SQL macro</b> ").append(StringUtil.escapeXmlEntities(target.macro.rawText))
        if (type != null) sb.append(" → ").append(StringUtil.escapeXmlEntities(type))
        sb.append("<br/>")
            .append(StringUtil.escapeXmlEntities(expansion ?: KoraQueryMacroExpander.describe(target.macro)))
        return sb.toString()
    }
}
