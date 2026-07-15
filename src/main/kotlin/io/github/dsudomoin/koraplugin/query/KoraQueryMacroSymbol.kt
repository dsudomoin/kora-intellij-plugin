package io.github.dsudomoin.koraplugin.query

import com.intellij.model.Pointer
import com.intellij.model.Symbol
import com.intellij.navigation.NavigatableSymbol
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.navigation.NavigationTarget
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.SmartPointerManager

/**
 * Symbol a `%{...}` macro reference resolves to. Makes the macro Cmd-hoverable and Cmd-clickable like a
 * regular symbol; the tooltip/navigation come from [KoraMacroNavigationTarget].
 */
class KoraQueryMacroSymbol(
    private val host: PsiElement,
    val macro: KoraQueryMacro,
    val method: PsiMethod,
) : NavigatableSymbol {

    override fun getNavigationTargets(project: Project): Collection<NavigationTarget> =
        listOf(KoraMacroNavigationTarget(host, macro, method))

    override fun createPointer(): Pointer<out Symbol> {
        val hostPtr = SmartPointerManager.createPointer(host)
        val methodPtr = SmartPointerManager.createPointer(method)
        val macroCopy = macro
        return Pointer {
            val h = hostPtr.element ?: return@Pointer null
            val m = methodPtr.element ?: return@Pointer null
            KoraQueryMacroSymbol(h, macroCopy, m)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is KoraQueryMacroSymbol && other.host == host && other.macro == macro && other.method == method

    override fun hashCode(): Int = macro.hashCode() * 31 + method.hashCode()
}
