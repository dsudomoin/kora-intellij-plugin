package io.github.dsudomoin.koraplugin.query

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiExternalReferenceHost
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.model.psi.PsiSymbolReferenceHints
import com.intellij.model.psi.PsiSymbolReferenceProvider
import com.intellij.model.search.SearchRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiMethod
import io.github.dsudomoin.koraplugin.util.KoraLibraryUtil

class KoraQueryMacroSymbolReferenceProvider : PsiSymbolReferenceProvider {

    override fun getReferences(
        element: PsiExternalReferenceHost,
        hints: PsiSymbolReferenceHints
    ): Collection<PsiSymbolReference> {
        if (element !is PsiLanguageInjectionHost) return emptyList()
        if (!KoraLibraryUtil.hasKoraLibrary(element.project)) return emptyList()
        val method = KoraQuerySupport.getQueryMethod(element) ?: return emptyList()
        val contentRange = KoraQuerySupport.contentRange(element)
        if (contentRange.isEmpty) return emptyList()
        val text = contentRange.substring(element.text)
        return KoraQueryMacroParser.parseMacros(text, contentRange.startOffset)
            .map { KoraQueryMacroSymbolReference(element, it, method) }
    }

    override fun getSearchRequests(project: Project, target: Symbol): Collection<SearchRequest> = emptyList()
}

private class KoraQueryMacroSymbolReference(
    private val host: PsiElement,
    private val macro: KoraQueryMacro,
    private val method: PsiMethod,
) : PsiSymbolReference {

    override fun getElement(): PsiElement = host

    override fun getRangeInElement(): TextRange = macro.rangeInHost

    override fun resolveReference(): Collection<Symbol> = listOf(KoraQueryMacroSymbol(host, macro, method))
}
