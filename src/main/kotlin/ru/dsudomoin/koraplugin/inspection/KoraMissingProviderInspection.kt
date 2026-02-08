package ru.dsudomoin.koraplugin.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiParameter
import org.jetbrains.kotlin.psi.KtParameter
import ru.dsudomoin.koraplugin.resolve.InjectionPointDetector
import ru.dsudomoin.koraplugin.resolve.KoraProviderResolver

class KoraMissingProviderInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitElement(element: com.intellij.psi.PsiElement) {
                if (element is PsiParameter || element is KtParameter) {
                    checkParameter(element as PsiNameIdentifierOwner, holder)
                }
            }
        }
    }

    private fun checkParameter(element: PsiNameIdentifierOwner, holder: ProblemsHolder) {
        val nameIdentifier = element.nameIdentifier ?: return

        val injectionPoint = InjectionPointDetector.detect(nameIdentifier) ?: return

        // All<T> allows empty collection — skip
        if (injectionPoint.isAllOf) return

        val providers = KoraProviderResolver.resolve(nameIdentifier)
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
