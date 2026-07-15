package io.github.dsudomoin.koraplugin.query

import com.intellij.model.Pointer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiMethod
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.dsudomoin.koraplugin.util.KoraLibraryUtil

class KoraQueryMacroDocumentationProvider : DocumentationTargetProvider {

    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        if (!KoraLibraryUtil.hasKoraLibrary(file.project)) return emptyList()
        val leaf = file.findElementAt(offset) ?: return emptyList()
        val host = PsiTreeUtil.getParentOfType(leaf, PsiLanguageInjectionHost::class.java, false) ?: return emptyList()
        val method = KoraQuerySupport.getQueryMethod(host) ?: return emptyList()

        val contentRange = KoraQuerySupport.contentRange(host)
        val text = contentRange.substring(host.text)
        val hostStart = host.textRange.startOffset
        val macro = KoraQueryMacroParser.parseMacros(text, contentRange.startOffset)
            .firstOrNull { it.rangeInHost.shiftRight(hostStart).contains(offset) }
            ?: return emptyList()

        return listOf(KoraMacroDocumentationTarget(macro, method))
    }
}

private class KoraMacroDocumentationTarget(
    private val macro: KoraQueryMacro,
    private val method: PsiMethod,
) : DocumentationTarget {

    override fun createPointer(): Pointer<out DocumentationTarget> {
        val macroCopy = macro
        val methodPtr = SmartPointerManager.createPointer(method)
        return Pointer { methodPtr.element?.let { KoraMacroDocumentationTarget(macroCopy, it) } }
    }

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(macro.rawText).presentation()

    override fun computeDocumentation(): DocumentationResult {
        val expansion = KoraQueryMacroExpander.expand(macro, method)
        val body = StringBuilder()
        body.append("<b>Kora SQL macro</b><br/>")
        body.append(StringUtil.escapeXmlEntities(KoraQueryMacroExpander.describe(macro)))
        if (expansion != null) {
            body.append("<br/><br/>Раскрывается в:<br/><code>")
            body.append(StringUtil.escapeXmlEntities(expansion))
            body.append("</code>")
        }
        body.append("<br/><br/>Target: <code>").append(StringUtil.escapeXmlEntities(macro.target)).append("</code>")
        val targetType = KoraQueryMacroExpander.resolveTargetClass(method, macro.target)?.name
        if (targetType != null) {
            body.append(" → <code>").append(StringUtil.escapeXmlEntities(targetType)).append("</code>")
        }
        return DocumentationResult.documentation(body.toString())
    }
}
