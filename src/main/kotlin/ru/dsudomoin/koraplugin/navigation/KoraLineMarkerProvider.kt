package ru.dsudomoin.koraplugin.navigation

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.codeInsight.navigation.openFileWithPsiElement
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.ui.awt.RelativePoint
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.getUastParentOfType
import ru.dsudomoin.koraplugin.resolve.InjectionPointDetector
import ru.dsudomoin.koraplugin.resolve.KoraProviderResolver
import java.awt.event.MouseEvent

class KoraLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (!isParameterIdentifier(element)) return null

        val uParameter = element.getUastParentOfType<UParameter>() ?: return null
        val uMethod = uParameter.getParentOfType<UMethod>() ?: return null
        val uClass = uMethod.getParentOfType<org.jetbrains.uast.UClass>() ?: return null

        if (uParameter !in uMethod.uastParameters) return null
        if (!InjectionPointDetector.isKoraInjectionContext(uMethod, uClass)) return null

        val document = element.containingFile?.viewProvider?.document ?: return null
        val myLine = document.getLineNumber(element.textOffset)

        // Find all parameters on the same line
        val sameLineParams = uMethod.uastParameters.filter { p ->
            val psi = p.sourcePsi ?: return@filter false
            document.getLineNumber(psi.textOffset) == myLine
        }

        if (sameLineParams.size <= 1) {
            // Only parameter on this line → direct navigation
            return LineMarkerInfo(
                element,
                element.textRange,
                AllIcons.Nodes.Plugin,
                { "Navigate to Kora DI provider" },
                SingleParamNavigationHandler(),
                GutterIconRenderer.Alignment.RIGHT,
                { "Navigate to Kora DI provider" },
            )
        }

        // Multiple params on the same line → only show icon on the first one
        val firstOnLine = sameLineParams.minByOrNull { it.sourcePsi?.textOffset ?: Int.MAX_VALUE }
        if (uParameter != firstOnLine) return null

        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.Nodes.Plugin,
            { "Navigate to Kora DI providers" },
            MultiParamNavigationHandler(sameLineParams),
            GutterIconRenderer.Alignment.RIGHT,
            { "Navigate to Kora DI providers" },
        )
    }

    private fun isParameterIdentifier(element: PsiElement): Boolean {
        if (element is PsiIdentifier && element.parent is com.intellij.psi.PsiParameter) return true
        if (element.node?.elementType?.toString() == "IDENTIFIER" && element.parent is KtParameter) return true
        return false
    }

    private class SingleParamNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent, elt: PsiElement) {
            val targets = KoraProviderResolver.resolve(elt).distinct()
            when (targets.size) {
                0 -> return
                1 -> openFileWithPsiElement(targets.single(), true, true)
                else -> PsiTargetNavigator(targets).navigate(e, "Choose Kora DI Provider", elt.project)
            }
        }
    }

    private class MultiParamNavigationHandler(
        private val params: List<UParameter>,
    ) : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent, elt: PsiElement) {
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(params)
                .setTitle("Choose Parameter")
                .setRenderer(object : javax.swing.DefaultListCellRenderer() {
                    override fun getListCellRendererComponent(
                        list: javax.swing.JList<*>?,
                        value: Any?,
                        index: Int,
                        isSelected: Boolean,
                        cellHasFocus: Boolean,
                    ): java.awt.Component {
                        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                        if (value is UParameter) {
                            text = "${value.name}: ${value.type.presentableText}"
                        }
                        return this
                    }
                })
                .setItemChosenCallback { param ->
                    val psi = param.sourcePsi ?: return@setItemChosenCallback
                    val nameElement = when (psi) {
                        is com.intellij.psi.PsiParameter -> psi.nameIdentifier ?: psi
                        else -> psi
                    }
                    val targets = KoraProviderResolver.resolve(nameElement).distinct()
                    when (targets.size) {
                        0 -> return@setItemChosenCallback
                        1 -> openFileWithPsiElement(targets.single(), true, true)
                        else -> PsiTargetNavigator(targets).navigate(e, "Choose Kora DI Provider", elt.project)
                    }
                }
                .createPopup()
                .show(RelativePoint(e))
        }
    }
}
