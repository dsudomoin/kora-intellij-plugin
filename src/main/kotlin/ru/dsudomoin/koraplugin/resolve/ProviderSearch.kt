package ru.dsudomoin.koraplugin.resolve

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.toUElement
import ru.dsudomoin.koraplugin.KoraAnnotations
import ru.dsudomoin.koraplugin.index.ProviderKind
import ru.dsudomoin.koraplugin.index.getProviders

data class KoraProvider(
    val element: PsiElement,
    val providedType: PsiType,
    val tagInfo: TagInfo,
)

object ProviderSearch {

    private val LOG = Logger.getInstance(ProviderSearch::class.java)

    fun findAllProviders(project: Project): List<KoraProvider> {
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            val result = doFindAllProviders(project)
            CachedValueProvider.Result.create(
                result,
                PsiModificationTracker.getInstance(project).forLanguage(com.intellij.lang.java.JavaLanguage.INSTANCE),
                PsiModificationTracker.getInstance(project).forLanguage(org.jetbrains.kotlin.idea.KotlinLanguage.INSTANCE),
            )
        }
    }

    private fun doFindAllProviders(project: Project): List<KoraProvider> {
        val allScope = GlobalSearchScope.allScope(project)
        val projectScope = GlobalSearchScope.projectScope(project)
        val providers = mutableListOf<KoraProvider>()

        // @Component classes are always in project sources → use projectScope
        val componentProviders = findComponentProviders(project, projectScope)
        LOG.debug { "Found ${componentProviders.size} @Component providers" }
        providers.addAll(componentProviders)

        // Factory methods may be in @Generated interfaces → use allScope
        val factoryProviders = findFactoryMethodProviders(project, allScope)
        LOG.debug { "Found ${factoryProviders.size} factory method providers" }
        providers.addAll(factoryProviders)

        return providers
    }

    private fun findComponentProviders(project: Project, scope: GlobalSearchScope): List<KoraProvider> {
        val facade = JavaPsiFacade.getInstance(project)
        val allScope = GlobalSearchScope.allScope(project)
        val result = mutableListOf<KoraProvider>()
        val visited = mutableSetOf<String>()

        for (annotationFqn in KoraAnnotations.COMPONENT_LIKE) {
            val annotationClass = facade.findClass(annotationFqn, allScope) ?: continue
            AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).forEach { psiClass ->
                ProgressManager.checkCanceled()
                val qn = psiClass.qualifiedName ?: return@forEach
                if (!visited.add(qn)) return@forEach
                val classType = facade.elementFactory.createType(psiClass)
                val uClass = psiClass.toUElement() as? UClass ?: return@forEach
                val tagInfo = TagExtractor.extractTags(uClass)
                LOG.debug { "  Component-like: ${psiClass.qualifiedName} (type=${classType.canonicalText}, tags=$tagInfo)" }
                result.add(KoraProvider(psiClass, classType, tagInfo))
            }
        }
        return result
    }

    private fun findFactoryMethodProviders(project: Project, scope: GlobalSearchScope): List<KoraProvider> {
        val facade = JavaPsiFacade.getInstance(project)
        val moduleAnnotations = listOf(
            KoraAnnotations.KORA_APP,
            KoraAnnotations.MODULE,
            KoraAnnotations.KORA_SUBMODULE,
        )

        val result = mutableListOf<KoraProvider>()
        val visited = mutableSetOf<String>()

        for (annotationFqn in moduleAnnotations) {
            val annotationClass = facade.findClass(annotationFqn, scope)
            if (annotationClass == null) {
                LOG.debug { "Annotation $annotationFqn not found in scope" }
                continue
            }
            AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).forEach { psiClass ->
                ProgressManager.checkCanceled()
                LOG.debug { "  Module class: ${psiClass.qualifiedName} (annotation=$annotationFqn)" }
                collectFactoryMethodsRecursive(psiClass, result, visited)
            }
        }

        // @Generated interfaces — treat as module classes (collect factory methods)
        // Includes submodule implementations, config source modules, etc.
        // Concrete @Generated classes are handled separately in findGeneratedClassProviders.
        val generatedAnnotation = facade.findClass(KoraAnnotations.GENERATED, scope)
        if (generatedAnnotation != null) {
            AnnotatedElementsSearch.searchPsiClasses(generatedAnnotation, scope).forEach { psiClass ->
                ProgressManager.checkCanceled()
                if (psiClass.isInterface) {
                    LOG.debug { "  Generated module interface: ${psiClass.qualifiedName}" }
                    collectFactoryMethodsRecursive(psiClass, result, visited)
                }
            }
        }

        return result
    }

    private fun collectFactoryMethodsRecursive(
        psiClass: PsiClass,
        result: MutableList<KoraProvider>,
        visited: MutableSet<String>,
    ) {
        val fqn = psiClass.qualifiedName ?: return
        if (!visited.add(fqn)) return

        for (method in psiClass.methods) {
            val returnType = method.returnType ?: continue
            if (returnType == PsiTypes.voidType()) continue

            val uMethod = method.toUElement() as? UMethod ?: continue
            val tagInfo = TagExtractor.extractTags(uMethod)
            LOG.debug { "    Factory method: ${psiClass.qualifiedName}.${method.name} -> ${returnType.canonicalText} (tags=$tagInfo)" }
            result.add(KoraProvider(method, returnType, tagInfo))
        }

        // Recurse into super interfaces to find inherited factory methods
        for (superIface in psiClass.interfaces) {
            collectFactoryMethodsRecursive(superIface, result, visited)
        }
    }

    /**
     * Index-backed lookup: find providers whose raw provided-type FQN matches [typeFqn].
     * Reconstructs PSI from index entries, then supplements with factory methods from
     * unannotated module interfaces (not covered by the index).
     */
    fun findProvidersByType(project: Project, typeFqn: String): List<KoraProvider>? {
        val scope = GlobalSearchScope.allScope(project)
        val entries = getProviders(typeFqn, project, scope) ?: return null

        val facade = JavaPsiFacade.getInstance(project)
        val result = mutableListOf<KoraProvider>()

        for (entry in entries) {
            val psiClass = facade.findClass(entry.classFqn, scope) ?: continue
            when (entry.kind) {
                ProviderKind.COMPONENT_CLASS -> {
                    val classType = facade.elementFactory.createType(psiClass)
                    val uClass = psiClass.toUElement() as? UClass ?: continue
                    val tagInfo = TagExtractor.extractTags(uClass)
                    result.add(KoraProvider(psiClass, classType, tagInfo))
                }
                ProviderKind.FACTORY_METHOD -> {
                    val methodName = entry.methodName ?: continue
                    val method = psiClass.findMethodsByName(methodName, false).firstOrNull() ?: continue
                    val returnType = method.returnType ?: continue
                    if (returnType == PsiTypes.voidType()) continue
                    val uMethod = method.toUElement() as? UMethod ?: continue
                    val tagInfo = TagExtractor.extractTags(uMethod)
                    result.add(KoraProvider(method, returnType, tagInfo))
                }
            }
        }

        // Supplement from cached unannotated module providers (single pass, not per-typeFqn)
        val unannotatedByType = getUnannotatedModuleProvidersByType(project)
        val supplement = unannotatedByType[typeFqn]
        if (supplement != null) {
            val existingElements = result.mapTo(HashSet()) { it.element }
            for (provider in supplement) {
                if (provider.element !in existingElements) {
                    result.add(provider)
                }
            }
        }

        return result
    }

    /**
     * Batch index lookup for multiple type FQNs.
     */
    fun findProvidersByTypes(project: Project, typeFqns: Collection<String>): List<KoraProvider>? {
        val result = mutableListOf<KoraProvider>()
        val seen = mutableSetOf<String>()
        for (fqn in typeFqns) {
            if (seen.add(fqn)) {
                result.addAll(findProvidersByType(project, fqn) ?: return null)
            }
        }
        return result
    }

    /**
     * Cached map: typeFqn → providers from unannotated module interfaces.
     * Built once per modification, shared across all findProvidersByType calls.
     */
    private fun getUnannotatedModuleProvidersByType(project: Project): Map<String, List<KoraProvider>> {
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            val map = buildUnannotatedModuleProviderMap(project)
            CachedValueProvider.Result.create(
                map,
                PsiModificationTracker.getInstance(project).forLanguage(com.intellij.lang.java.JavaLanguage.INSTANCE),
                PsiModificationTracker.getInstance(project).forLanguage(org.jetbrains.kotlin.idea.KotlinLanguage.INSTANCE),
            )
        }
    }

    private fun buildUnannotatedModuleProviderMap(project: Project): Map<String, List<KoraProvider>> {
        val scope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)
        val moduleClassFqns = KoraModuleRegistry.getModuleClassFqns(project)
        val result = mutableMapOf<String, MutableList<KoraProvider>>()

        for (moduleFqn in moduleClassFqns) {
            ProgressManager.checkCanceled()
            val psiClass = facade.findClass(moduleFqn, scope) ?: continue
            if (KoraModuleRegistry.isDirectlyAnnotatedModule(psiClass)) continue

            for (method in psiClass.methods) {
                val returnType = method.returnType ?: continue
                if (returnType == PsiTypes.voidType()) continue
                val returnFqn = com.intellij.psi.util.TypeConversionUtil.erasure(returnType).canonicalText
                val uMethod = method.toUElement() as? UMethod ?: continue
                val tagInfo = TagExtractor.extractTags(uMethod)
                result.getOrPut(returnFqn) { mutableListOf() }
                    .add(KoraProvider(method, returnType, tagInfo))
            }
        }

        return result
    }
}
