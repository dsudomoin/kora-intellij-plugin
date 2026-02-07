package ru.dsudomoin.koraplugin.inspection

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UClass
import org.jetbrains.uast.getParentOfType
import ru.dsudomoin.koraplugin.resolve.InjectionPointDetector
import ru.dsudomoin.koraplugin.resolve.KoraProviderResolver

class KoraMultipleProvidersInspection : AbstractKoraInjectionInspection() {

    override fun checkMethod(method: UMethod, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val uClass = method.getParentOfType<UClass>() ?: return null
        if (!InjectionPointDetector.isKoraInjectionContext(method, uClass)) return null

        val problems = mutableListOf<ProblemDescriptor>()
        for (param in method.uastParameters) {
            val nameElement = getParameterNamePsi(param) ?: continue
            val result = KoraProviderResolver.resolveDetailed(nameElement) ?: continue

            // Skip All<T> — collects all providers, multiple is expected
            if (result.injectionPoint.isAllOf) continue

            // Skip @Tag.Any — matches all providers, multiple is expected
            if (result.injectionPoint.tagInfo.isTagAny) continue

            if (result.tagMatched.size > 1) {
                val typeName = result.injectionPoint.requiredType.presentableText
                val message = "Multiple providers found for '$typeName': ${result.tagMatched.size} candidates"
                problems.add(
                    manager.createProblemDescriptor(
                        nameElement,
                        message,
                        isOnTheFly,
                        emptyArray(),
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    ),
                )
            }
        }

        return problems.toTypedArray().ifEmpty { null }
    }
}
