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
        val componentAnnotation = facade.findClass(KoraAnnotations.COMPONENT, scope)
        if (componentAnnotation == null) {
            LOG.info("@Component annotation class not found in scope")
            return emptyList()
        }

        val result = mutableListOf<KoraProvider>()
        AnnotatedElementsSearch.searchPsiClasses(componentAnnotation, scope).forEach { psiClass ->
            val classType = facade.elementFactory.createType(psiClass)
            val uClass = psiClass.toUElement() as? UClass ?: return@forEach
            val tagInfo = TagExtractor.extractTags(uClass)
            LOG.info("  @Component: ${psiClass.qualifiedName} (type=${classType.canonicalText}, tags=$tagInfo)")
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
}
