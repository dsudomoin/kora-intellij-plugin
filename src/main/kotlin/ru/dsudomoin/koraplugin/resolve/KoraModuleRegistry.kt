package ru.dsudomoin.koraplugin.resolve

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.uast.UClass
import org.jetbrains.uast.toUElement
import ru.dsudomoin.koraplugin.KoraAnnotations

/**
 * Cached registry of all Kora module class FQNs.
 *
 * Source of truth is the container structure: @KoraApp → full extends/implements chain
 * (including unannotated parent interfaces). This set is used to supplement index queries
 * at query time — the FileBasedIndex can only index directly annotated classes.
 */
object KoraModuleRegistry {

    private val LOG = Logger.getInstance(KoraModuleRegistry::class.java)

    private val MODULE_ANNOTATIONS = listOf(
        KoraAnnotations.KORA_APP,
        KoraAnnotations.MODULE,
        KoraAnnotations.KORA_SUBMODULE,
    )

    private val DIRECTLY_ANNOTATED = MODULE_ANNOTATIONS + listOf(KoraAnnotations.GENERATED)

    private const val KORA_SUBMODULE_PROCESSOR = "ru.tinkoff.kora.kora.app.ksp.KoraSubmoduleProcessor"

    /**
     * Cached set of ALL module class FQNs: annotated (@KoraApp/@Module/@KoraSubmodule/@Generated)
     * and unannotated parent interfaces reachable through extends/implements chains.
     * Invalidated on any PSI change.
     */
    fun getModuleClassFqns(project: Project): Set<String> {
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            val fqns = collectAllModuleClassFqns(project)
            CachedValueProvider.Result.create(
                fqns,
                PsiModificationTracker.getInstance(project).forLanguage(com.intellij.lang.java.JavaLanguage.INSTANCE),
                PsiModificationTracker.getInstance(project).forLanguage(org.jetbrains.kotlin.idea.KotlinLanguage.INSTANCE),
            )
        }
    }

    /**
     * Whether [psiClass] is directly annotated with a module annotation (and thus indexed
     * by KoraProviderIndex / KoraInjectionSiteIndex). Classes NOT directly annotated are
     * "unannotated modules" that the index misses.
     */
    fun isDirectlyAnnotatedModule(psiClass: PsiClass): Boolean {
        for (fqn in DIRECTLY_ANNOTATED) {
            if (psiClass.hasAnnotation(fqn)) return true
        }
        return false
    }

    private fun collectAllModuleClassFqns(project: Project): Set<String> {
        val result = mutableSetOf<String>()
        val scope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        for (annotationFqn in MODULE_ANNOTATIONS) {
            val annotationClass = facade.findClass(annotationFqn, scope)
            if (annotationClass == null) {
                LOG.debug { "Annotation class not found: $annotationFqn" }
                continue
            }
            AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).forEach { psiClass ->
                ProgressManager.checkCanceled()
                LOG.debug { "Found @${annotationFqn.substringAfterLast('.')}: ${psiClass.qualifiedName}" }
                addClassAndSupersFqns(psiClass, result)
            }
        }

        // @Generated Kora submodule interfaces
        val generatedAnnotation = facade.findClass(KoraAnnotations.GENERATED, scope)
        if (generatedAnnotation != null) {
            AnnotatedElementsSearch.searchPsiClasses(generatedAnnotation, scope).forEach { psiClass ->
                ProgressManager.checkCanceled()
                val uClass = psiClass.toUElement() as? UClass ?: return@forEach
                if (isKoraGenerated(uClass)) {
                    LOG.debug { "Found @Generated Kora submodule: ${psiClass.qualifiedName}" }
                    addClassAndSupersFqns(psiClass, result)
                }
            }
        }

        LOG.debug { "Module registry: ${result.size} FQNs collected: $result" }
        return result
    }

    private fun addClassAndSupersFqns(psiClass: PsiClass, result: MutableSet<String>) {
        val fqn = psiClass.qualifiedName ?: return
        if (fqn.startsWith("java.") || fqn.startsWith("kotlin.") || fqn.startsWith("javax.")) return
        if (!result.add(fqn)) return

        // Use superTypes (declared extends/implements) — more reliable for Kotlin light classes
        for (superType in psiClass.superTypes) {
            val superClass = (superType as? PsiClassType)?.resolve()
            if (superClass == null) {
                LOG.warn("Could not resolve superType of $fqn: ${superType.canonicalText}")
                continue
            }
            addClassAndSupersFqns(superClass, result)
        }
    }

    private fun isKoraGenerated(uClass: UClass): Boolean {
        val uAnnotation = uClass.findAnnotation(KoraAnnotations.GENERATED)
        if (uAnnotation != null) {
            val value = uAnnotation.findAttributeValue("value")?.evaluate() as? String
            if (value == KORA_SUBMODULE_PROCESSOR) return true
        }
        val psiAnnotation = uClass.javaPsi.getAnnotation(KoraAnnotations.GENERATED) ?: return false
        val psiValue = psiAnnotation.findAttributeValue("value")
        val text = (psiValue as? com.intellij.psi.PsiLiteralExpression)?.value as? String
            ?: return false
        return text == KORA_SUBMODULE_PROCESSOR
    }
}
