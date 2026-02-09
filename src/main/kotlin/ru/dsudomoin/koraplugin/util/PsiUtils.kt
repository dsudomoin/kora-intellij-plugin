package ru.dsudomoin.koraplugin.util

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import org.jetbrains.kotlin.psi.KtParameter

fun isParameterIdentifier(element: PsiElement): Boolean {
    if (element is PsiIdentifier && element.parent is com.intellij.psi.PsiParameter) return true
    if (element.node?.elementType?.toString() == "IDENTIFIER" && element.parent is KtParameter) return true
    return false
}
