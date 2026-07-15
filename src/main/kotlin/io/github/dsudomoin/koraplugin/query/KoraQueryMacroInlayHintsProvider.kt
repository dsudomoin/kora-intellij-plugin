package io.github.dsudomoin.koraplugin.query

import com.intellij.codeInsight.hints.declarative.HintColorKind
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import io.github.dsudomoin.koraplugin.util.KoraLibraryUtil

class KoraQueryMacroInlayHintsProvider : InlayHintsProvider {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (!KoraLibraryUtil.hasKoraLibrary(file.project)) return null
        return Collector()
    }

    private class Collector : SharedBypassCollector {
        private val format = HintFormat.default.withColorKind(HintColorKind.TextWithoutBackground)

        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            if (element !is PsiLanguageInjectionHost) return
            val method = KoraQuerySupport.getQueryMethod(element) ?: return
            val contentRange = KoraQuerySupport.contentRange(element)
            val text = contentRange.substring(element.text)
            val hostStart = element.textRange.startOffset
            for (macro in KoraQueryMacroParser.parseMacros(text, contentRange.startOffset)) {
                val expansion = KoraQueryMacroExpander.expand(macro, method) ?: continue
                val anchor = hostStart + macro.rangeInHost.endOffset
                sink.addPresentation(InlineInlayPosition(anchor, relatedToPrevious = true), hintFormat = format) {
                    text(" ⟨$expansion⟩")
                }
            }
        }
    }
}
