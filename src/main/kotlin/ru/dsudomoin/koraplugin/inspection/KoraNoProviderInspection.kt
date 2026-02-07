package ru.dsudomoin.koraplugin.inspection

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UClass
import org.jetbrains.uast.getParentOfType
import ru.dsudomoin.koraplugin.resolve.InjectionPointDetector
import ru.dsudomoin.koraplugin.resolve.KoraProviderResolver

class KoraNoProviderInspection : AbstractKoraInjectionInspection() {

    override fun checkMethod(method: UMethod, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val uClass = method.getParentOfType<UClass>() ?: return null
        if (!InjectionPointDetector.isKoraInjectionContext(method, uClass)) return null

        val problems = mutableListOf<ProblemDescriptor>()
        for (param in method.uastParameters) {
            val nameElement = getParameterNamePsi(param) ?: continue
            val result = KoraProviderResolver.resolveDetailed(nameElement) ?: continue

            // Skip All<T> — it collects all matching providers, zero is valid
            if (result.injectionPoint.isAllOf) continue

            when {
                // A3: tag mismatch — providers found by type but tags don't match
                result.tagMatched.isEmpty() && result.typeMatched.isNotEmpty() -> {
                    val requiredTags = result.injectionPoint.tagInfo.tagFqns
                        .joinToString(", ") { it.substringAfterLast('.') }
                    val message = "No provider with matching tags found for '${param.name}'. " +
                        "Required tags: [$requiredTags], " +
                        "${result.typeMatched.size} provider(s) found by type"
                    problems.add(
                        manager.createProblemDescriptor(
                            nameElement,
                            message,
                            isOnTheFly,
                            emptyArray(),
                            ProblemHighlightType.GENERIC_ERROR,
                        ),
                    )
                }
                // A1: no provider at all
                result.tagMatched.isEmpty() && result.typeMatched.isEmpty() -> {
                    val typeName = result.injectionPoint.requiredType.presentableText
                    val message = "No provider found for '$typeName'"
                    problems.add(
                        manager.createProblemDescriptor(
                            nameElement,
                            message,
                            isOnTheFly,
                            emptyArray(),
                            ProblemHighlightType.GENERIC_ERROR,
                        ),
                    )
                }
            }
        }

        return problems.toTypedArray().ifEmpty { null }
    }
}
