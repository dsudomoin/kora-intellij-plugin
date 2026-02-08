package ru.dsudomoin.koraplugin.resolve

import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiType
import org.jetbrains.uast.*
import ru.dsudomoin.koraplugin.KoraAnnotations

data class InjectionPoint(
    val requiredType: PsiType,
    val tagInfo: TagInfo,
    val isAllOf: Boolean,
)

object InjectionPointDetector {

    private val LOG = Logger.getInstance(InjectionPointDetector::class.java)

    fun detect(element: PsiElement): InjectionPoint? {
        // Use getUastParentOfType which walks up PSI tree and converts to UAST
        // toUElement() returns null for leaf PSI elements like identifiers
        val uParameter = element.getUastParentOfType<UParameter>()
        if (uParameter == null) {
            LOG.info("No UParameter found for element: ${element.text} (${element.javaClass.simpleName})")
            return null
        }

        val uMethod = uParameter.getParentOfType<UMethod>()
        if (uMethod == null) {
            LOG.info("No UMethod found for parameter: ${uParameter.name}")
            return null
        }

        // Skip lambda parameters — only handle direct method/constructor parameters
        if (uParameter !in uMethod.uastParameters) {
            LOG.info("Parameter ${uParameter.name} is not a direct parameter of method ${uMethod.name} (likely lambda)")
            return null
        }

        val uClass = uMethod.getParentOfType<UClass>()
        if (uClass == null) {
            LOG.info("No UClass found for method: ${uMethod.name}")
            return null
        }

        if (!isKoraInjectionContext(uMethod, uClass)) {
            LOG.info("Not a Kora injection context: ${uClass.qualifiedName}.${uMethod.name}")
            return null
        }

        val paramType = uParameter.type
        val (resolvedType, isAllOf) = unwrapType(paramType)

        val tagInfo = TagExtractor.extractTags(uParameter)

        LOG.info("Detected injection point: type=${resolvedType.canonicalText}, tags=$tagInfo, allOf=$isAllOf")
        return InjectionPoint(resolvedType, tagInfo, isAllOf)
    }

    internal fun isKoraInjectionContext(uMethod: UMethod, uClass: UClass): Boolean {
        // Case 1: Constructor of a @Component / @Repository class
        if (uMethod.isConstructor) {
            if (KoraAnnotations.COMPONENT_LIKE.any { hasAnnotation(uClass, it) }) {
                return true
            }
        }

        // Case 2: Method in a @KoraApp / @Module / @KoraSubmodule interface
        if (isKoraModuleClass(uClass)) {
            return true
        }

        return false
    }

    private val MODULE_ANNOTATIONS = setOf(
        KoraAnnotations.KORA_APP,
        KoraAnnotations.MODULE,
        KoraAnnotations.KORA_SUBMODULE,
    )

    private fun isKoraModuleClass(uClass: UClass): Boolean {
        // Check the class itself
        if (hasAnyModuleAnnotation(uClass)) return true
        if (isKoraGenerated(uClass)) return true

        val psiClass = uClass.javaPsi

        // Check all super interfaces transitively (for extends chains going UP)
        if (hasAnyModuleAnnotationInSupers(psiClass, mutableSetOf())) return true

        // Check via cached module registry (going DOWN — e.g., @KoraApp class inheriting this interface)
        val moduleClassFqns = KoraModuleRegistry.getModuleClassFqns(psiClass.project)
        val fqn = psiClass.qualifiedName
        if (fqn != null && fqn in moduleClassFqns) return true

        return false
    }

    private const val KORA_SUBMODULE_PROCESSOR = "ru.tinkoff.kora.kora.app.ksp.KoraSubmoduleProcessor"

    private fun isKoraGenerated(uClass: UClass): Boolean {
        // Try UAST first
        val uAnnotation = uClass.findAnnotation(KoraAnnotations.GENERATED)
        if (uAnnotation != null) {
            val value = uAnnotation.findAttributeValue("value")?.evaluate() as? String
            if (value == KORA_SUBMODULE_PROCESSOR) return true
        }

        // Fallback: PSI (more reliable for some Kotlin/K2 cases)
        val psiAnnotation = uClass.javaPsi.getAnnotation(KoraAnnotations.GENERATED) ?: return false
        val psiValue = psiAnnotation.findAttributeValue("value")
        val text = (psiValue as? com.intellij.psi.PsiLiteralExpression)?.value as? String
            ?: return false
        return text == KORA_SUBMODULE_PROCESSOR
    }

    private fun hasAnyModuleAnnotation(uClass: UClass): Boolean {
        return MODULE_ANNOTATIONS.any { hasAnnotation(uClass, it) }
    }

    private fun hasAnyModuleAnnotationInSupers(psiClass: PsiClass, visited: MutableSet<String>): Boolean {
        for (superIface in psiClass.supers) {
            val fqn = superIface.qualifiedName ?: continue
            if (!visited.add(fqn)) continue
            val superUClass = superIface.toUElement() as? UClass ?: continue
            if (hasAnyModuleAnnotation(superUClass)) return true
            if (hasAnyModuleAnnotationInSupers(superIface, visited)) return true
        }
        return false
    }

    private fun hasAnnotation(uClass: UClass, annotationFqn: String): Boolean {
        // Check via UAST
        if (uClass.findAnnotation(annotationFqn) != null) return true

        // Fallback: check via PSI (more reliable for some Kotlin/K2 cases)
        val psiClass = uClass.javaPsi
        return psiClass.hasAnnotation(annotationFqn)
    }

    internal fun unwrapType(type: PsiType): Pair<PsiType, Boolean> {
        if (type is com.intellij.psi.PsiClassType) {
            val resolved = type.resolve()
            if (resolved != null) {
                val typeArgs = type.parameters
                if (typeArgs.isNotEmpty()) {
                    when (resolved.qualifiedName) {
                        KoraAnnotations.ALL_TYPE -> return unwrapType(typeArgs[0]).copy(second = true)
                        KoraAnnotations.VALUE_OF_TYPE -> return unwrapType(typeArgs[0])
                    }
                }
            }
        }
        return type to false
    }
}
