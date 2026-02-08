package ru.dsudomoin.koraplugin.navigation

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiTypes
import com.intellij.ui.awt.RelativePoint
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.getUastParentOfType
import org.jetbrains.uast.toUElement
import ru.dsudomoin.koraplugin.KoraAnnotations
import ru.dsudomoin.koraplugin.resolve.InjectionPointDetector
import ru.dsudomoin.koraplugin.resolve.KoraBeanNavigator
import ru.dsudomoin.koraplugin.resolve.KoraProviderResolver
import ru.dsudomoin.koraplugin.resolve.ParamProviders
import java.awt.event.MouseEvent

class KoraLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // 1. @Component class name → usages icon (combined with constructor params if on same line)
        val componentFqn = getComponentClassFqn(element)
        if (componentFqn != null) {
            return createComponentClassMarker(element, componentFqn)
        }

        // 2. Factory method name → combined icon (usages + same-line params)
        val factoryInfo = getFactoryMethodInfo(element)
        if (factoryInfo != null) {
            return createFactoryMethodMarker(element, factoryInfo)
        }

        // 3. Injection point parameter → providers icon
        //    (but skip if factory method or @Component class name is on the same line — handled by #1/#2)
        if (isParameterIdentifier(element)) {
            return getInjectionPointLineMarker(element)
        }

        return null
    }

    // --- Identifier checks ---

    private data class FactoryMethodInfo(val classFqn: String, val methodName: String)

    /**
     * If element is the name identifier of a @Component / @Repository class, returns the class FQN.
     */
    private fun getComponentClassFqn(element: PsiElement): String? {
        val parent = element.parent
        when (parent) {
            is com.intellij.psi.PsiClass -> {
                if (element != parent.nameIdentifier) return null
                if (KoraAnnotations.COMPONENT_LIKE.none { parent.hasAnnotation(it) }) return null
                return parent.qualifiedName
            }
            is KtClass -> {
                if (element != parent.nameIdentifier) return null
                val uClass = parent.toUElement() as? UClass ?: return null
                if (KoraAnnotations.COMPONENT_LIKE.none { uClass.javaPsi.hasAnnotation(it) }) return null
                return uClass.qualifiedName
            }
            else -> return null
        }
    }

    /**
     * If element is the name identifier of a factory method in a Kora module, returns the
     * containing class FQN and method name.
     */
    private fun getFactoryMethodInfo(element: PsiElement): FactoryMethodInfo? {
        val parent = element.parent
        when (parent) {
            is PsiMethod -> {
                if (element != parent.nameIdentifier) return null
                if (parent.isConstructor) return null
                val returnType = parent.returnType ?: return null
                if (returnType == PsiTypes.voidType()) return null
                val uMethod = parent.toUElement() as? UMethod ?: return null
                val uClass = uMethod.getParentOfType<UClass>() ?: return null
                if (!InjectionPointDetector.isKoraInjectionContext(uMethod, uClass)) return null
                val classFqn = uClass.qualifiedName ?: return null
                return FactoryMethodInfo(classFqn, parent.name)
            }
            is KtNamedFunction -> {
                if (element != parent.nameIdentifier) return null
                val uMethod = parent.toUElement() as? UMethod ?: return null
                if (uMethod.isConstructor) return null
                val returnType = uMethod.javaPsi.returnType ?: return null
                if (returnType == PsiTypes.voidType()) return null
                val uClass = uMethod.getParentOfType<UClass>() ?: return null
                if (!InjectionPointDetector.isKoraInjectionContext(uMethod, uClass)) return null
                val classFqn = uClass.qualifiedName ?: return null
                val methodName = parent.name ?: return null
                return FactoryMethodInfo(classFqn, methodName)
            }
            else -> return null
        }
    }

    private fun isParameterIdentifier(element: PsiElement): Boolean {
        if (element is PsiIdentifier && element.parent is com.intellij.psi.PsiParameter) return true
        if (element.node?.elementType?.toString() == "IDENTIFIER" && element.parent is KtParameter) return true
        return false
    }

    // --- @Component class marker (combined with constructor params if on same line) ---

    private fun createComponentClassMarker(element: PsiElement, classFqn: String): LineMarkerInfo<PsiElement> {
        val uClass = element.getUastParentOfType<UClass>()
        val document = element.containingFile?.viewProvider?.document

        // Find constructor params on the same line as the class name
        val sameLineParamNames = if (uClass != null && document != null) {
            val myLine = document.getLineNumber(element.textOffset)
            val constructor = uClass.methods.firstOrNull { it.isConstructor }
            constructor?.uastParameters?.filter { p ->
                val psi = p.sourcePsi ?: return@filter false
                document.getLineNumber(psi.textOffset) == myLine
            }?.mapNotNull { it.name } ?: emptyList()
        } else {
            emptyList()
        }

        return if (sameLineParamNames.isEmpty()) {
            createLineMarkerInfo(element, PROVIDER_TOOLTIP, ProviderUsagesNavigationHandler(classFqn, null))
        } else {
            createLineMarkerInfo(element, TOOLTIP_TEXT, CombinedNavigationHandler(classFqn, null, sameLineParamNames))
        }
    }

    // --- Factory method marker (combined: usages of the method + providers for params) ---

    private fun createFactoryMethodMarker(element: PsiElement, info: FactoryMethodInfo): LineMarkerInfo<PsiElement> {
        val uMethod = element.getUastParentOfType<UMethod>()
        val document = element.containingFile?.viewProvider?.document

        // Collect parameter names on the same line as the method name
        val sameLineParamNames = if (uMethod != null && document != null) {
            val myLine = document.getLineNumber(element.textOffset)
            uMethod.uastParameters.filter { p ->
                val psi = p.sourcePsi ?: return@filter false
                document.getLineNumber(psi.textOffset) == myLine
            }.mapNotNull { it.name }
        } else {
            emptyList()
        }

        return if (sameLineParamNames.isEmpty()) {
            createLineMarkerInfo(element, PROVIDER_TOOLTIP, ProviderUsagesNavigationHandler(info.classFqn, info.methodName))
        } else {
            createLineMarkerInfo(element, TOOLTIP_TEXT, CombinedNavigationHandler(info.classFqn, info.methodName, sameLineParamNames))
        }
    }

    // --- Injection point parameter marker ---

    private fun getInjectionPointLineMarker(element: PsiElement): LineMarkerInfo<*>? {
        val uParameter = element.getUastParentOfType<UParameter>() ?: return null
        val uMethod = uParameter.getParentOfType<UMethod>() ?: return null
        val uClass = uMethod.getParentOfType<UClass>() ?: return null

        if (uParameter !in uMethod.uastParameters) return null
        if (!InjectionPointDetector.isKoraInjectionContext(uMethod, uClass)) return null

        val document = element.containingFile?.viewProvider?.document ?: return null
        val myLine = document.getLineNumber(element.textOffset)

        // If @Component/@Repository class name is on the same line → skip (component class marker handles it)
        if (uMethod.isConstructor && KoraAnnotations.COMPONENT_LIKE.any { uClass.javaPsi.hasAnnotation(it) }) {
            val classNameElement = getClassNameIdentifier(uClass)
            if (classNameElement != null && document.getLineNumber(classNameElement.textOffset) == myLine) {
                return null // component class marker on this line will handle params
            }
        }

        // If factory method name is on the same line → skip (factory method marker handles it)
        if (!uMethod.isConstructor) {
            val methodNameElement = getMethodNameIdentifier(uMethod)
            if (methodNameElement != null && document.getLineNumber(methodNameElement.textOffset) == myLine) {
                val returnType = uMethod.javaPsi.returnType
                if (returnType != null && returnType != PsiTypes.voidType()) {
                    return null // factory method marker on this line will handle params
                }
            }
        }

        // Find all parameters on the same line
        val sameLineParams = uMethod.uastParameters.filter { p ->
            val psi = p.sourcePsi ?: return@filter false
            document.getLineNumber(psi.textOffset) == myLine
        }

        if (sameLineParams.size <= 1) {
            return createLineMarkerInfo(element, INJECTION_TOOLTIP, SingleParamNavigationHandler())
        }

        // Multiple params on the same line → only show icon on the first one
        val firstOnLine = sameLineParams.minByOrNull { it.sourcePsi?.textOffset ?: Int.MAX_VALUE }
        if (uParameter != firstOnLine) return null

        return createLineMarkerInfo(element, INJECTION_TOOLTIP, MultiParamNavigationHandler(sameLineParams))
    }

    private fun getMethodNameIdentifier(uMethod: UMethod): PsiElement? {
        return when (val src = uMethod.sourcePsi) {
            is PsiMethod -> src.nameIdentifier
            is KtNamedFunction -> src.nameIdentifier
            else -> null
        }
    }

    private fun getClassNameIdentifier(uClass: UClass): PsiElement? {
        return when (val src = uClass.sourcePsi) {
            is com.intellij.psi.PsiClass -> src.nameIdentifier
            is KtClass -> src.nameIdentifier
            else -> null
        }
    }

    // --- Shared ---

    private fun createLineMarkerInfo(
        element: PsiElement,
        tooltip: String,
        handler: GutterIconNavigationHandler<PsiElement>,
    ): LineMarkerInfo<PsiElement> {
        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.Nodes.Plugin,
            { tooltip },
            handler,
            GutterIconRenderer.Alignment.RIGHT,
            { tooltip },
        )
    }

    companion object {
        const val INJECTION_TOOLTIP = "Navigate to Kora DI provider"
        const val PROVIDER_TOOLTIP = "Navigate to Kora DI usages"
        const val TOOLTIP_TEXT = "Kora DI navigation"
    }

    // --- Navigation handlers ---

    /**
     * For provider elements (@Component class or factory method): show usages.
     * Stores classFqn (and optional methodName) at creation time to avoid SmartPointer issues.
     */
    private class ProviderUsagesNavigationHandler(
        private val classFqn: String,
        private val methodName: String?,
    ) : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent, elt: PsiElement) {
            val project = elt.project
            var usages: List<PsiElement> = emptyList()

            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                {
                    usages = ReadAction.compute<List<PsiElement>, RuntimeException> {
                        if (methodName != null) {
                            KoraBeanNavigator.resolveFactoryMethodUsages(project, classFqn, methodName)
                        } else {
                            KoraBeanNavigator.resolveComponentUsages(project, classFqn)
                        }
                    }
                },
                "Searching for Kora DI usages...",
                true,
                project,
            )

            navigateToElements(e, project, usages, "Choose Kora DI Usage", "No injection sites found")
        }
    }

    /**
     * Combined handler for provider element + same-line params.
     * Two-level popup: first choose category, then drill down lazily.
     * Works for both @Component classes (methodName=null) and factory methods (methodName!=null).
     */
    private class CombinedNavigationHandler(
        private val classFqn: String,
        private val methodName: String?,
        private val sameLineParamNames: List<String>,
    ) : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent, elt: PsiElement) {
            val project = elt.project
            val categories = listOf(LABEL_INJECTION_SITES, LABEL_DEPENDENCY_PROVIDERS)
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(categories)
                .setTitle("Kora DI Navigation")
                .setItemChosenCallback { chosen ->
                    if (chosen == LABEL_INJECTION_SITES) {
                        navigateToUsagesLazy(e, project)
                    } else {
                        navigateToProvidersLazy(e, project)
                    }
                }
                .createPopup()
                .show(RelativePoint(e))
        }

        private fun navigateToUsagesLazy(e: MouseEvent, project: com.intellij.openapi.project.Project) {
            var usages: List<PsiElement> = emptyList()
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                {
                    usages = ReadAction.compute<List<PsiElement>, RuntimeException> {
                        if (methodName != null) {
                            KoraBeanNavigator.resolveFactoryMethodUsages(project, classFqn, methodName)
                        } else {
                            KoraBeanNavigator.resolveComponentUsages(project, classFqn)
                        }
                    }
                },
                "Searching for injection sites...",
                true,
                project,
            )
            navigateToElements(e, project, usages, "Choose Injection Site", "No injection sites found")
        }

        private fun navigateToProvidersLazy(e: MouseEvent, project: com.intellij.openapi.project.Project) {
            var paramProviders: List<ParamProviders> = emptyList()
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                {
                    paramProviders = ReadAction.compute<List<ParamProviders>, RuntimeException> {
                        if (methodName != null) {
                            KoraBeanNavigator.resolveMethodParamProviders(project, classFqn, methodName, sameLineParamNames)
                        } else {
                            KoraBeanNavigator.resolveConstructorParamProviders(project, classFqn, sameLineParamNames)
                        }
                    }
                },
                "Searching for dependency providers...",
                true,
                project,
            )

            if (paramProviders.isEmpty()) {
                JBPopupFactory.getInstance()
                    .createHtmlTextBalloonBuilder("No dependency providers found", com.intellij.openapi.ui.MessageType.WARNING, null)
                    .createBalloon()
                    .show(RelativePoint(e), com.intellij.openapi.ui.popup.Balloon.Position.above)
                return
            }
            if (paramProviders.size == 1) {
                val single = paramProviders.single()
                navigateToElements(e, project, single.providers, "Choose Dependency Provider", "No providers found")
                return
            }

            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(paramProviders)
                .setTitle("Choose Dependency")
                .setRenderer(object : javax.swing.DefaultListCellRenderer() {
                    override fun getListCellRendererComponent(
                        list: javax.swing.JList<*>?,
                        value: Any?,
                        index: Int,
                        isSelected: Boolean,
                        cellHasFocus: Boolean,
                    ): java.awt.Component {
                        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                        if (value is ParamProviders) text = "${value.paramName}: ${value.paramTypeText}"
                        return this
                    }
                })
                .setItemChosenCallback { pp ->
                    navigateToElements(e, project, pp.providers, "Choose Dependency Provider", "No providers found")
                }
                .createPopup()
                .show(RelativePoint(e))
        }

        companion object {
            const val LABEL_INJECTION_SITES = "Where this component is injected"
            const val LABEL_DEPENDENCY_PROVIDERS = "Where dependencies are created"
        }
    }

    /** For a single injection point parameter: show providers. */
    private class SingleParamNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent, elt: PsiElement) {
            val targets = KoraProviderResolver.resolve(elt).distinct()
            navigateToElements(e, elt.project, targets, "Choose Kora DI Provider", "No providers found")
        }
    }

    /** For multiple parameters on the same line (no factory method): choose parameter, then show providers. */
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
                    navigateToElements(e, elt.project, targets, "Choose Kora DI Provider", "No providers found")
                }
                .createPopup()
                .show(RelativePoint(e))
        }
    }
}

private fun navigateToElement(element: PsiElement) {
    val file = element.containingFile?.virtualFile ?: return
    val descriptor = com.intellij.openapi.fileEditor.OpenFileDescriptor(
        element.project, file, element.textOffset
    )
    descriptor.navigate(true)
}

private fun navigateToElements(
    e: MouseEvent,
    project: com.intellij.openapi.project.Project,
    elements: List<PsiElement>,
    title: String,
    emptyMessage: String = "Nothing found",
) {
    when (elements.size) {
        0 -> {
            JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder(emptyMessage, com.intellij.openapi.ui.MessageType.WARNING, null)
                .createBalloon()
                .show(RelativePoint(e), com.intellij.openapi.ui.popup.Balloon.Position.above)
        }
        1 -> navigateToElement(elements.single())
        else -> PsiTargetNavigator(elements).navigate(e, title, project)
    }
}
