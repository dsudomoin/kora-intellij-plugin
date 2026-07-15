package io.github.dsudomoin.koraplugin.query

import com.intellij.model.Pointer
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.platform.backend.navigation.NavigationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.SmartPointerManager

/**
 * The Cmd/Ctrl-hover tooltip and Cmd-click target for a `%{...}` macro. The tooltip content is the
 * macro's real expansion (from [computePresentation]); Cmd-click navigates to the resolved entity class.
 */
class KoraMacroNavigationTarget(
    private val host: PsiElement,
    private val macro: KoraQueryMacro,
    private val method: PsiMethod,
) : NavigationTarget {

    override fun createPointer(): Pointer<out NavigationTarget> {
        val hostPtr = SmartPointerManager.createPointer(host)
        val methodPtr = SmartPointerManager.createPointer(method)
        val macroCopy = macro
        return Pointer {
            val h = hostPtr.element ?: return@Pointer null
            val m = methodPtr.element ?: return@Pointer null
            KoraMacroNavigationTarget(h, macroCopy, m)
        }
    }

    override fun computePresentation(): TargetPresentation {
        val expansion = KoraQueryMacroExpander.expand(macro, method)
        val main = expansion ?: KoraQueryMacroExpander.describe(macro)
        val targetType = KoraQueryMacroExpander.resolveTargetClass(method, macro.target)?.name
        val container = if (targetType != null) "Kora macro → $targetType" else "Kora SQL macro"
        return TargetPresentation.builder(main).containerText(container).presentation()
    }

    override fun navigationRequest(): NavigationRequest? {
        val element = KoraQueryMacroExpander.resolveTargetClass(method, macro.target) ?: host
        val file = element.containingFile ?: return null
        val range = element.textRange ?: return null
        return NavigationRequest.sourceNavigationRequest(file, range)
    }
}
