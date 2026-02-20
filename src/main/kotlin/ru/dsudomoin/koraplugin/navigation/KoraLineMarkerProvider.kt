package ru.dsudomoin.koraplugin.navigation

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.daemon.MergeableLineMarkerInfo
import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiTypes
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
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
import ru.dsudomoin.koraplugin.util.KoraLibraryUtil
import ru.dsudomoin.koraplugin.util.isParameterIdentifier
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JList

class KoraLineMarkerProvider : LineMarkerProvider, DumbAware {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        val first = elements.firstOrNull() ?: return
        val project = first.project
        if (DumbService.isDumb(project)) return
        if (!KoraLibraryUtil.hasKoraLibrary(project)) return

        for (element in elements) {
            ProgressManager.checkCanceled()

            // 1. @Component class name → usages icon (combined with constructor params if on same line)
            val componentFqn = getComponentClassFqn(element)
            if (componentFqn != null) {
                result.add(createComponentClassMarker(element, componentFqn))
                continue
            }

            // 2. Factory method name → combined icon (usages + same-line params)
            val factoryInfo = getFactoryMethodInfo(element)
            if (factoryInfo != null) {
                result.add(createFactoryMethodMarker(element, factoryInfo))
                continue
            }

            // 3. Injection point parameter → providers icon
            if (isParameterIdentifier(element)) {
                val marker = getInjectionPointLineMarker(element)
                if (marker != null) {
                    result.add(marker)
                }
            }
        }
    }

    // --- Identifier checks ---

    private data class FactoryMethodInfo(val classFqn: String, val methodName: String)

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

    // --- @Component class marker (combined with constructor params if on same line) ---

    private fun createComponentClassMarker(element: PsiElement, classFqn: String): LineMarkerInfo<PsiElement> {
        val uClass = element.getUastParentOfType<UClass>()
        val document = element.containingFile?.viewProvider?.document

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

    // --- Factory method marker ---

    private fun createFactoryMethodMarker(element: PsiElement, info: FactoryMethodInfo): LineMarkerInfo<PsiElement> {
        val uMethod = element.getUastParentOfType<UMethod>()
        val document = element.containingFile?.viewProvider?.document

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

        // If @Component/@Repository class name is on the same line → skip
        if (uMethod.isConstructor && KoraAnnotations.COMPONENT_LIKE.any { uClass.javaPsi.hasAnnotation(it) }) {
            val classNameElement = getClassNameIdentifier(uClass)
            if (classNameElement != null && document.getLineNumber(classNameElement.textOffset) == myLine) {
                return null
            }
        }

        // If factory method name is on the same line → skip
        if (!uMethod.isConstructor) {
            val methodNameElement = getMethodNameIdentifier(uMethod)
            if (methodNameElement != null && document.getLineNumber(methodNameElement.textOffset) == myLine) {
                val returnType = uMethod.javaPsi.returnType
                if (returnType != null && returnType != PsiTypes.voidType()) {
                    return null
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

        val paramInfos = sameLineParams.map { ParamInfo(it.name, it.type.presentableText) }
        val classFqn = uClass.qualifiedName ?: return null
        val methodName = if (uMethod.isConstructor) null else uMethod.name
        return createLineMarkerInfo(element, INJECTION_TOOLTIP, MultiParamNavigationHandler(paramInfos, classFqn, methodName))
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
        return KoraMergeableLineMarkerInfo(element, tooltip, handler)
    }

    private class KoraMergeableLineMarkerInfo(
        element: PsiElement,
        private val tooltip: String,
        handler: GutterIconNavigationHandler<PsiElement>,
    ) : MergeableLineMarkerInfo<PsiElement>(
        element,
        element.textRange,
        AllIcons.Nodes.Plugin,
        { tooltip },
        handler,
        GutterIconRenderer.Alignment.RIGHT,
        { tooltip },
    ) {
        override fun canMergeWith(info: MergeableLineMarkerInfo<*>): Boolean {
            return info is KoraMergeableLineMarkerInfo
        }

        override fun getCommonIcon(infos: MutableList<out MergeableLineMarkerInfo<*>>): Icon {
            return AllIcons.Nodes.Plugin
        }
    }

    companion object {
        const val INJECTION_TOOLTIP = "Navigate to Kora DI provider"
        const val PROVIDER_TOOLTIP = "Navigate to Kora DI usages"
        const val TOOLTIP_TEXT = "Kora DI navigation"
    }

    // --- Navigation handlers ---

    private class ProviderUsagesNavigationHandler(
        private val classFqn: String,
        private val methodName: String?,
    ) : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent, elt: PsiElement) {
            val project = elt.project
            var targets: List<InjectionSiteTarget> = emptyList()

            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                {
                    targets = ReadAction.compute<List<InjectionSiteTarget>, RuntimeException> {
                        val usages = if (methodName != null) {
                            KoraBeanNavigator.resolveFactoryMethodUsages(project, classFqn, methodName)
                        } else {
                            KoraBeanNavigator.resolveComponentUsages(project, classFqn)
                        }
                        buildInjectionSiteTargets(usages)
                    }
                },
                "Searching for Kora DI usages...",
                true,
                project,
            )

            navigateToInjectionSites(e, project, targets, "Choose Kora DI Usage")
        }
    }

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

        private fun navigateToUsagesLazy(e: MouseEvent, project: Project) {
            var targets: List<InjectionSiteTarget> = emptyList()
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                {
                    targets = ReadAction.compute<List<InjectionSiteTarget>, RuntimeException> {
                        val usages = if (methodName != null) {
                            KoraBeanNavigator.resolveFactoryMethodUsages(project, classFqn, methodName)
                        } else {
                            KoraBeanNavigator.resolveComponentUsages(project, classFqn)
                        }
                        buildInjectionSiteTargets(usages)
                    }
                },
                "Searching for injection sites...",
                true,
                project,
            )
            navigateToInjectionSites(e, project, targets, "Choose Injection Site")
        }

        private fun navigateToProvidersLazy(e: MouseEvent, project: Project) {
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

    private class SingleParamNavigationHandler : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent, elt: PsiElement) {
            val project = elt.project
            var targets: List<PsiElement> = emptyList()
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                {
                    targets = ReadAction.compute<List<PsiElement>, RuntimeException> {
                        KoraProviderResolver.resolve(elt).distinct()
                    }
                },
                "Searching for Kora DI providers...",
                true,
                project,
            )
            navigateToElements(e, project, targets, "Choose Kora DI Provider", "No providers found")
        }
    }

    /**
     * Stores param info as Strings to avoid holding UParameter references
     * past PSI modification (prevents PsiInvalidElementAccessException).
     */
    private data class ParamInfo(val name: String, val typeText: String)

    private class MultiParamNavigationHandler(
        private val paramInfos: List<ParamInfo>,
        private val classFqn: String,
        private val methodName: String?,
    ) : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent, elt: PsiElement) {
            val project = elt.project
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(paramInfos)
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
                        if (value is ParamInfo) {
                            text = "${value.name}: ${value.typeText}"
                        }
                        return this
                    }
                })
                .setItemChosenCallback { paramInfo ->
                    var targets: List<PsiElement> = emptyList()
                    ProgressManager.getInstance().runProcessWithProgressSynchronously(
                        {
                            targets = ReadAction.compute<List<PsiElement>, RuntimeException> {
                                // Re-lookup PSI fresh to avoid stale references
                                val nameElement = findParamElement(project, classFqn, methodName, paramInfo.name)
                                    ?: return@compute emptyList()
                                KoraProviderResolver.resolve(nameElement).distinct()
                            }
                        },
                        "Searching for Kora DI providers...",
                        true,
                        project,
                    )
                    navigateToElements(e, project, targets, "Choose Kora DI Provider", "No providers found")
                }
                .createPopup()
                .show(RelativePoint(e))
        }

        private fun findParamElement(project: Project, classFqn: String, methodName: String?, paramName: String): PsiElement? {
            val psiClass = com.intellij.psi.JavaPsiFacade.getInstance(project)
                .findClass(classFqn, com.intellij.psi.search.GlobalSearchScope.allScope(project)) ?: return null
            val method = if (methodName != null) {
                psiClass.findMethodsByName(methodName, false).firstOrNull()
            } else {
                psiClass.constructors.firstOrNull()
            } ?: return null
            val param = method.parameterList.parameters.find { it.name == paramName } ?: return null
            return param.nameIdentifier ?: param
        }
    }
}

private data class InjectionSiteTarget(
    val element: PsiElement,
    val paramName: String,
    val containingClassName: String,
    val methodSignature: String,
    val isGenerated: Boolean,
)

private fun buildInjectionSiteTargets(elements: List<PsiElement>): List<InjectionSiteTarget> {
    return elements.mapNotNull { element ->
        // element can be PsiIdentifier (Java nameIdentifier) or KtParameter (Kotlin param).
        // For KtParameter, toUElement() converts it directly to UParameter;
        // getUastParentOfType would skip self and return null.
        val uParam = element.toUElement() as? UParameter
            ?: element.getUastParentOfType<UParameter>()
            ?: return@mapNotNull null
        val uMethod = uParam.getParentOfType<UMethod>() ?: return@mapNotNull null
        val uClass = uMethod.getParentOfType<UClass>() ?: return@mapNotNull null

        val paramName = uParam.name
        val className = uClass.name ?: return@mapNotNull null

        val methodDisplayName = if (uMethod.isConstructor) className else uMethod.name
        val methodSignature = "$className.$methodDisplayName"

        val isGenerated = uClass.javaPsi.hasAnnotation(KoraAnnotations.GENERATED)

        InjectionSiteTarget(element, paramName, className, methodSignature, isGenerated)
    }.sortedBy { it.isGenerated } // non-generated first (false < true)
}

private class InjectionSiteRenderer : ColoredListCellRenderer<InjectionSiteTarget>() {
    override fun customizeCellRenderer(
        list: JList<out InjectionSiteTarget>,
        value: InjectionSiteTarget?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        if (value == null) return
        icon = AllIcons.Nodes.Parameter
        append(value.paramName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        append("  (${value.methodSignature})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
}

private fun navigateToInjectionSites(
    e: MouseEvent,
    project: Project,
    targets: List<InjectionSiteTarget>,
    title: String,
    emptyMessage: String = "No injection sites found",
) {
    when (targets.size) {
        0 -> {
            JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder(emptyMessage, com.intellij.openapi.ui.MessageType.WARNING, null)
                .createBalloon()
                .show(RelativePoint(e), com.intellij.openapi.ui.popup.Balloon.Position.above)
        }
        1 -> navigateToElement(targets.single().element)
        else -> {
            val hasGenerated = targets.any { it.isGenerated }
            if (hasGenerated) {
                val step = object : BaseListPopupStep<InjectionSiteTarget>(title, targets) {
                    override fun getTextFor(value: InjectionSiteTarget): String {
                        return "${value.paramName}  (${value.methodSignature})"
                    }

                    override fun getIconFor(value: InjectionSiteTarget): Icon {
                        return AllIcons.Nodes.Parameter
                    }

                    override fun getSeparatorAbove(value: InjectionSiteTarget): ListSeparator? {
                        val index = values.indexOf(value)
                        if (index <= 0) return null
                        val prev = values[index - 1]
                        if (!prev.isGenerated && value.isGenerated) {
                            return ListSeparator("Generated")
                        }
                        return null
                    }

                    override fun onChosen(selectedValue: InjectionSiteTarget, finalChoice: Boolean): PopupStep<*>? {
                        if (finalChoice) {
                            navigateToElement(selectedValue.element)
                        }
                        return FINAL_CHOICE
                    }
                }
                JBPopupFactory.getInstance().createListPopup(step).show(RelativePoint(e))
            } else {
                JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(targets)
                    .setTitle(title)
                    .setRenderer(InjectionSiteRenderer())
                    .setNamerForFiltering { it.paramName }
                    .setItemChosenCallback { navigateToElement(it.element) }
                    .createPopup()
                    .show(RelativePoint(e))
            }
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
    project: Project,
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
