package ru.dsudomoin.koraplugin.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.uast.UastHintedVisitorAdapter
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.UElement
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor
import ru.dsudomoin.koraplugin.resolve.InjectionPointDetector
import ru.dsudomoin.koraplugin.resolve.KoraProviderResolver
import ru.dsudomoin.koraplugin.util.KoraLibraryUtil

class KoraMissingProviderInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!KoraLibraryUtil.hasKoraLibrary(holder.project)) return PsiElementVisitor.EMPTY_VISITOR
        return UastHintedVisitorAdapter.create(
            holder.file.language,
            object : AbstractUastNonRecursiveVisitor() {
                override fun visitParameter(node: UParameter): Boolean {
                    checkParameter(node, holder)
                    return true
                }
            },
            arrayOf<Class<out UElement>>(UParameter::class.java),
            true,
        )
    }

    private fun checkParameter(node: UParameter, holder: ProblemsHolder) {
        val nameIdentifier = node.uastAnchor?.sourcePsi ?: return

        val injectionPoint = InjectionPointDetector.detect(nameIdentifier) ?: return

        // All<T> allows empty collection — skip
        if (injectionPoint.isAllOf) return

        // Pass pre-computed InjectionPoint to avoid redundant detect() inside resolve()
        val providers = KoraProviderResolver.resolve(injectionPoint, holder.project)
        if (providers.isEmpty()) {
            val typeText = injectionPoint.requiredType.presentableText
            holder.registerProblem(
                nameIdentifier,
                "No Kora DI provider found for type '$typeText'",
                ProblemHighlightType.WEAK_WARNING,
            )
        }
    }
}
