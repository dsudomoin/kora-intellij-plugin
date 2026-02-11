package ru.dsudomoin.koraplugin.resolve

import com.intellij.openapi.diagnostic.Logger
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
import ru.dsudomoin.koraplugin.util.KoraAnnotationSearch

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
            CachedValueProvider.Result.create(result, PsiModificationTracker.getInstance(project))
        }
    }

    private fun doFindAllProviders(project: Project): List<KoraProvider> {
        val scope = GlobalSearchScope.allScope(project)
        val providers = mutableListOf<KoraProvider>()

        val componentProviders = findComponentProviders(project, scope)
        LOG.info("Found ${componentProviders.size} @Component providers")
        providers.addAll(componentProviders)

        val factoryProviders = findFactoryMethodProviders(project, scope)
        LOG.info("Found ${factoryProviders.size} factory method providers")
        providers.addAll(factoryProviders)

        return providers
    }

    private fun findComponentProviders(project: Project, scope: GlobalSearchScope): List<KoraProvider> {
        val facade = JavaPsiFacade.getInstance(project)
        val result = mutableListOf<KoraProvider>()

        for (psiClass in KoraAnnotationSearch.findAnnotatedClasses(KoraAnnotations.COMPONENT_LIKE, project, scope)) {
            val classType = facade.elementFactory.createType(psiClass)
            val uClass = psiClass.toUElement() as? UClass ?: continue
            val tagInfo = TagExtractor.extractTags(uClass)
            LOG.info("  Component-like: ${psiClass.qualifiedName} (type=${classType.canonicalText}, tags=$tagInfo)")
            result.add(KoraProvider(psiClass, classType, tagInfo))
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
                LOG.info("Annotation $annotationFqn not found in scope")
                continue
            }
            AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).forEach { psiClass ->
                LOG.info("  Module class: ${psiClass.qualifiedName} (annotation=$annotationFqn)")
                collectFactoryMethodsRecursive(psiClass, result, visited)
            }
        }

        // @Generated interfaces — treat as module classes (collect factory methods)
        // Includes submodule implementations, config source modules, etc.
        // Concrete @Generated classes are handled separately in findGeneratedClassProviders.
        val generatedAnnotation = facade.findClass(KoraAnnotations.GENERATED, scope)
        if (generatedAnnotation != null) {
            AnnotatedElementsSearch.searchPsiClasses(generatedAnnotation, scope).forEach { psiClass ->
                if (psiClass.isInterface) {
                    LOG.info("  Generated module interface: ${psiClass.qualifiedName}")
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
            LOG.info("    Factory method: ${psiClass.qualifiedName}.${method.name} -> ${returnType.canonicalText} (tags=$tagInfo)")
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

        // Supplement: factory methods in unannotated module interfaces (not indexed)
        supplementProvidersFromUnannotatedModules(project, typeFqn, result, facade, scope)

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
     * For module classes that are NOT directly annotated (@Module/@KoraApp/etc.) but participate
     * in the container via extends/implements chain, the FileBasedIndex misses their factory methods.
     * Supplement index results with a targeted PSI lookup for these classes.
     */
    private fun supplementProvidersFromUnannotatedModules(
        project: Project,
        typeFqn: String,
        result: MutableList<KoraProvider>,
        facade: JavaPsiFacade,
        scope: GlobalSearchScope,
    ) {
        val moduleClassFqns = KoraModuleRegistry.getModuleClassFqns(project)
        val existingElements = result.mapTo(HashSet()) { it.element }

        for (moduleFqn in moduleClassFqns) {
            val psiClass = facade.findClass(moduleFqn, scope) ?: continue
            if (KoraModuleRegistry.isDirectlyAnnotatedModule(psiClass)) continue

            for (method in psiClass.methods) {
                if (method in existingElements) continue
                val returnType = method.returnType ?: continue
                if (returnType == PsiTypes.voidType()) continue
                val returnFqn = com.intellij.psi.util.TypeConversionUtil.erasure(returnType).canonicalText
                if (returnFqn == typeFqn) {
                    val uMethod = method.toUElement() as? UMethod ?: continue
                    val tagInfo = TagExtractor.extractTags(uMethod)
                    result.add(KoraProvider(method, returnType, tagInfo))
                }
            }
        }
    }
}
