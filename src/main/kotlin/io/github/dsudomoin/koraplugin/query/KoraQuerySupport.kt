package io.github.dsudomoin.koraplugin.query

import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiMethod
import io.github.dsudomoin.koraplugin.KoraAnnotations
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.toUElement

object KoraQuerySupport {

    fun getQueryMethod(host: PsiElement): PsiMethod? {
        val uElement = host.toUElement() ?: return null
        val annotation = uElement.getParentOfType(UAnnotation::class.java) ?: return null
        if (annotation.qualifiedName != KoraAnnotations.QUERY) return null
        return annotation.getParentOfType(UMethod::class.java)?.javaPsi
    }

    fun contentRange(host: PsiLanguageInjectionHost): TextRange =
        ElementManipulators.getManipulator(host).getRangeInElement(host)
}
