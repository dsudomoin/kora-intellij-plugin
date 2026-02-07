package ru.dsudomoin.koraplugin.resolve

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.toUElement
import ru.dsudomoin.koraplugin.KoraAnnotations
import ru.dsudomoin.koraplugin.index.KoraInjectionSiteIndex

data class KoraInjectionSite(
    val element: PsiElement,
    val requiredType: PsiType,
    val tagInfo: TagInfo,
    val isAllOf: Boolean,
)

object InjectionSiteSearch {

    private val LOG = Logger.getInstance(InjectionSiteSearch::class.java)

    fun findAllInjectionSites(project: Project): List<KoraInjectionSite> {
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            val result = doFindAllInjectionSites(project)
            CachedValueProvider.Result.create(result, PsiModificationTracker.getInstance(project))
        }
    }

    private fun doFindAllInjectionSites(project: Project): List<KoraInjectionSite> {
        // allScope for annotation class lookup (annotations are in library JARs)
        // projectScope for AnnotatedElementsSearch (injection sites are in project sources)
        val allScope = GlobalSearchScope.allScope(project)
        val projectScope = GlobalSearchScope.projectScope(project)
        val sites = mutableListOf<KoraInjectionSite>()

        // 1. @Component class constructors
        val componentSites = findComponentConstructorSites(project, allScope, projectScope)
        LOG.info("Found ${componentSites.size} @Component constructor injection sites")
        sites.addAll(componentSites)

        // 2. Factory method parameters in module classes
        val factorySites = findFactoryMethodParamSites(project, allScope, projectScope)
        LOG.info("Found ${factorySites.size} factory method parameter injection sites")
        sites.addAll(factorySites)

        return sites
    }

    private fun findComponentConstructorSites(project: Project, allScope: GlobalSearchScope, projectScope: GlobalSearchScope): List<KoraInjectionSite> {
        val facade = JavaPsiFacade.getInstance(project)
        val componentAnnotation = facade.findClass(KoraAnnotations.COMPONENT, allScope) ?: return emptyList()

        val result = mutableListOf<KoraInjectionSite>()
        AnnotatedElementsSearch.searchPsiClasses(componentAnnotation, projectScope).forEach { psiClass ->
            val uClass = psiClass.toUElement() as? UClass ?: return@forEach
            for (method in uClass.methods) {
                if (!method.isConstructor) continue
                collectParameterSites(method, result)
            }
        }
        return result
    }

    private fun findFactoryMethodParamSites(project: Project, allScope: GlobalSearchScope, projectScope: GlobalSearchScope): List<KoraInjectionSite> {
        val facade = JavaPsiFacade.getInstance(project)
        val moduleAnnotations = listOf(
            KoraAnnotations.KORA_APP,
            KoraAnnotations.MODULE,
            KoraAnnotations.KORA_SUBMODULE,
        )

        val result = mutableListOf<KoraInjectionSite>()
        val visited = mutableSetOf<String>()

        for (annotationFqn in moduleAnnotations) {
            val annotationClass = facade.findClass(annotationFqn, allScope) ?: continue
            AnnotatedElementsSearch.searchPsiClasses(annotationClass, projectScope).forEach { psiClass ->
                collectModuleMethodParamsRecursive(psiClass, result, visited)
            }
        }

        // @Generated interfaces — treat as module classes (collect method params)
        // Concrete @Generated classes are not modules; they don't have injectable factory methods.
        val generatedAnnotation = facade.findClass(KoraAnnotations.GENERATED, allScope)
        if (generatedAnnotation != null) {
            AnnotatedElementsSearch.searchPsiClasses(generatedAnnotation, projectScope).forEach { psiClass ->
                if (psiClass.isInterface) {
                    collectModuleMethodParamsRecursive(psiClass, result, visited)
                }
            }
        }

        return result
    }

    private fun collectModuleMethodParamsRecursive(
        psiClass: PsiClass,
        result: MutableList<KoraInjectionSite>,
        visited: MutableSet<String>,
    ) {
        val fqn = psiClass.qualifiedName ?: return
        if (!visited.add(fqn)) return

        for (method in psiClass.methods) {
            val returnType = method.returnType ?: continue
            if (returnType == PsiTypes.voidType()) continue

            val uMethod = method.toUElement() as? UMethod ?: continue
            collectParameterSites(uMethod, result)
        }

        for (superIface in psiClass.interfaces) {
            collectModuleMethodParamsRecursive(superIface, result, visited)
        }
    }

    private fun collectParameterSites(uMethod: UMethod, result: MutableList<KoraInjectionSite>) {
        for (param in uMethod.uastParameters) {
            val paramPsi = param.sourcePsi ?: continue
            val nameElement = when (paramPsi) {
                is com.intellij.psi.PsiParameter -> paramPsi.nameIdentifier ?: paramPsi
                else -> paramPsi
            }
            val (resolvedType, isAllOf) = InjectionPointDetector.unwrapType(param.type)
            val tagInfo = TagExtractor.extractTags(param)
            result.add(KoraInjectionSite(nameElement, resolvedType, tagInfo, isAllOf))
        }
    }

    /**
     * Index-backed lookup: find injection sites whose raw required-type FQN matches [typeFqn].
     * Reconstructs PSI from index entries, extracts tags at query time.
     */
    fun findInjectionSitesByType(project: Project, typeFqn: String): List<KoraInjectionSite>? {
        val scope = GlobalSearchScope.projectScope(project)
        val entries = KoraInjectionSiteIndex.getSites(typeFqn, project, scope) ?: return null
        if (entries.isEmpty()) return emptyList()

        val allScope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)
        val result = mutableListOf<KoraInjectionSite>()

        for (entry in entries) {
            val psiClass = facade.findClass(entry.classFqn, allScope) ?: continue
            val uMethod: UMethod? = if (entry.isConstructor) {
                psiClass.constructors.firstOrNull()?.toUElement() as? UMethod
            } else {
                val methodName = entry.methodName ?: continue
                psiClass.findMethodsByName(methodName, false).firstOrNull()?.toUElement() as? UMethod
            }
            if (uMethod == null) continue

            val param = uMethod.uastParameters.find { it.name == entry.paramName } ?: continue
            val paramPsi = param.sourcePsi ?: continue
            val nameElement = when (paramPsi) {
                is com.intellij.psi.PsiParameter -> paramPsi.nameIdentifier ?: paramPsi
                else -> paramPsi
            }
            val (resolvedType, isAllOf) = InjectionPointDetector.unwrapType(param.type)
            val tagInfo = TagExtractor.extractTags(param)
            result.add(KoraInjectionSite(nameElement, resolvedType, tagInfo, isAllOf))
        }

        return result
    }

    /**
     * Index-backed lookup for multiple type FQNs (e.g., provided type + its supertypes).
     */
    fun findInjectionSitesByTypes(project: Project, typeFqns: Collection<String>): List<KoraInjectionSite>? {
        val result = mutableListOf<KoraInjectionSite>()
        val seen = mutableSetOf<String>()
        for (fqn in typeFqns) {
            if (seen.add(fqn)) {
                result.addAll(findInjectionSitesByType(project, fqn) ?: return null)
            }
        }
        return result
    }
}
