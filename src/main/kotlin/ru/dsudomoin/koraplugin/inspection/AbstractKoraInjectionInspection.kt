package ru.dsudomoin.koraplugin.inspection

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.psi.PsiElement
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter

abstract class AbstractKoraInjectionInspection : AbstractBaseUastLocalInspectionTool(UMethod::class.java) {

    /**
     * Get the PSI element for the parameter name identifier.
     * Used as the anchor for highlighting.
     */
    protected fun getParameterNamePsi(param: UParameter): PsiElement? {
        return param.sourcePsi?.let { sourcePsi ->
            // Try to get the name identifier for precise highlighting
            when (sourcePsi) {
                is com.intellij.psi.PsiParameter -> sourcePsi.nameIdentifier
                is org.jetbrains.kotlin.psi.KtParameter -> sourcePsi.nameIdentifier
                else -> null
            } ?: sourcePsi
        }
    }
}
