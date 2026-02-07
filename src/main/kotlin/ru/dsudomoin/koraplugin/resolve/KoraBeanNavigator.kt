package ru.dsudomoin.koraplugin.resolve

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.getUastParentOfType
import org.jetbrains.uast.toUElement
import ru.dsudomoin.koraplugin.KoraAnnotations
import ru.dsudomoin.koraplugin.index.KoraIndexUtil

data class BeanNavigationTargets(
    val providers: List<PsiElement>,
    val usages: List<PsiElement>,
)

data class ParamProviders(
    val paramName: String,
    val paramTypeText: String,
    val providers: List<PsiElement>,
)

object KoraBeanNavigator {

    private val LOG = Logger.getInstance(KoraBeanNavigator::class.java)

    fun resolve(element: PsiElement): BeanNavigationTargets? {
        // Try as injection point parameter first
        val injectionResult = resolveForInjectionPoint(element)
        if (injectionResult != null) return injectionResult

        // Try as provider element (@Component class or factory method)
        val providerResult = resolveForProvider(element)
        if (providerResult != null) return providerResult

        return null
    }

    /**
     * Resolve usages for a provider element. Works from either the name identifier
     * or its parent (PsiClass/KtClass/PsiMethod/KtNamedFunction).
     * Does NOT require element == parent.nameIdentifier (safe after SmartPointer restoration).
     */
    fun resolveProviderUsages(element: PsiElement): List<PsiElement> {
        val parent = element.parent
        // Determine the actual provider declaration from the element or its parent
        val declaration: PsiElement = when {
            parent is PsiClass -> parent
            parent is KtClass -> parent
            parent is PsiMethod -> parent
            parent is KtNamedFunction -> parent
            element is PsiClass -> element
            element is KtClass -> element
            element is PsiMethod -> element
            element is KtNamedFunction -> element
            else -> return emptyList()
        }
        return resolveDeclarationUsages(declaration)
    }

    /**
     * Resolve usages for a @Component class by its FQN.
     * Looks up the PsiClass fresh via JavaPsiFacade — safe regardless of SmartPointer issues.
     */
    fun resolveComponentUsages(project: Project, classFqn: String): List<PsiElement> {
        val scope = GlobalSearchScope.allScope(project)
        val psiClass = JavaPsiFacade.getInstance(project).findClass(classFqn, scope) ?: return emptyList()
        return resolveDeclarationUsages(psiClass)
    }

    /**
     * Resolve usages for a factory method by containing class FQN and method name.
     * Looks up the PsiClass and PsiMethod fresh — safe regardless of SmartPointer issues.
     */
    fun resolveFactoryMethodUsages(project: Project, classFqn: String, methodName: String): List<PsiElement> {
        val scope = GlobalSearchScope.allScope(project)
        val psiClass = JavaPsiFacade.getInstance(project).findClass(classFqn, scope) ?: return emptyList()
        val method = psiClass.findMethodsByName(methodName, false).firstOrNull() ?: return emptyList()
        return resolveDeclarationUsages(method)
    }

    /**
     * Resolve providers for specific parameters of a factory method.
     * Looks up the method fresh by FQN, then resolves providers for each named parameter.
     */
    fun resolveMethodParamProviders(
        project: Project,
        classFqn: String,
        methodName: String,
        paramNames: List<String>,
    ): List<ParamProviders> {
        val scope = GlobalSearchScope.allScope(project)
        val psiClass = JavaPsiFacade.getInstance(project).findClass(classFqn, scope) ?: return emptyList()
        val method = psiClass.findMethodsByName(methodName, false).firstOrNull() ?: return emptyList()
        val uMethod = method.toUElement() as? UMethod ?: return emptyList()
        return resolveParamProviders(project, uMethod, paramNames)
    }

    /**
     * Resolve providers for specific parameters of a @Component class constructor.
     * Looks up the class fresh by FQN, finds its constructor, then resolves providers for each named parameter.
     */
    fun resolveConstructorParamProviders(
        project: Project,
        classFqn: String,
        paramNames: List<String>,
    ): List<ParamProviders> {
        val scope = GlobalSearchScope.allScope(project)
        val psiClass = JavaPsiFacade.getInstance(project).findClass(classFqn, scope) ?: return emptyList()
        val constructor = psiClass.constructors.firstOrNull() ?: return emptyList()
        val uMethod = constructor.toUElement() as? UMethod ?: return emptyList()
        return resolveParamProviders(project, uMethod, paramNames)
    }

    private fun resolveParamProviders(project: Project, uMethod: UMethod, paramNames: List<String>): List<ParamProviders> {
        val result = mutableListOf<ParamProviders>()
        val scope = GlobalSearchScope.allScope(project)

        for (param in uMethod.uastParameters) {
            val name = param.name
            if (name !in paramNames) continue

            val (resolvedType, _) = InjectionPointDetector.unwrapType(param.type)
            val tagInfo = TagExtractor.extractTags(param)

            // Use index-backed lookup
            val providers = findProvidersForType(project, resolvedType, scope)

            val matching = providers.filter { provider ->
                matchesTypeAssignable(resolvedType, provider.providedType) && matchesProviderTags(tagInfo, provider.tagInfo)
            }.map { it.element }.distinct()

            // Prefer factory methods over class declarations
            val methods = matching.filterIsInstance<PsiMethod>()
            val targets = methods.ifEmpty { matching }

            if (targets.isNotEmpty()) {
                result.add(ParamProviders(name, param.type.presentableText, targets))
            }
        }

        return result
    }

    private fun matchesProviderTags(required: TagInfo, provided: TagInfo): Boolean {
        if (required.isTagAny) return true
        if (required.tagFqns.isEmpty()) return provided.tagFqns.isEmpty()
        return required.tagFqns == provided.tagFqns
    }

    private fun resolveDeclarationUsages(declaration: PsiElement): List<PsiElement> {
        val project = declaration.project
        val providedType: PsiType
        val tagInfo: TagInfo

        when (declaration) {
            is PsiClass -> {
                if (!declaration.hasAnnotation(KoraAnnotations.COMPONENT)) return emptyList()
                val uClass = declaration.toUElement() as? UClass ?: return emptyList()
                val facade = com.intellij.psi.JavaPsiFacade.getInstance(project)
                providedType = facade.elementFactory.createType(declaration)
                tagInfo = TagExtractor.extractTags(uClass)
            }
            is KtClass -> {
                val uClass = declaration.toUElement() as? UClass ?: return emptyList()
                val psiClass = uClass.javaPsi
                if (!psiClass.hasAnnotation(KoraAnnotations.COMPONENT)) return emptyList()
                val facade = com.intellij.psi.JavaPsiFacade.getInstance(project)
                providedType = facade.elementFactory.createType(psiClass)
                tagInfo = TagExtractor.extractTags(uClass)
            }
            is PsiMethod -> {
                if (declaration.isConstructor) return emptyList()
                val returnType = declaration.returnType ?: return emptyList()
                if (returnType == com.intellij.psi.PsiTypes.voidType()) return emptyList()
                val uMethod = declaration.toUElement() as? UMethod ?: return emptyList()
                providedType = returnType
                tagInfo = TagExtractor.extractTags(uMethod)
            }
            is KtNamedFunction -> {
                val uMethod = declaration.toUElement() as? UMethod ?: return emptyList()
                if (uMethod.isConstructor) return emptyList()
                val returnType = uMethod.javaPsi.returnType ?: return emptyList()
                if (returnType == com.intellij.psi.PsiTypes.voidType()) return emptyList()
                providedType = returnType
                tagInfo = TagExtractor.extractTags(uMethod)
            }
            else -> return emptyList()
        }

        // Use index-backed lookup: query by provided type FQN + its supertypes
        val sites = findInjectionSitesForProvidedType(project, providedType)
        return sites.filter { site ->
            matchesProviderToSite(providedType, tagInfo, site)
        }.map { it.element }.distinct()
    }

    private fun resolveForInjectionPoint(element: PsiElement): BeanNavigationTargets? {
        val injectionPoint = InjectionPointDetector.detect(element) ?: return null
        val project = element.project

        val providers = KoraProviderResolver.resolve(element).distinct()

        // Use index to find sibling injection sites for the same type (with fallback)
        val rawFqn = KoraIndexUtil.getRawTypeFqn(injectionPoint.requiredType)
        val sites = if (rawFqn != null) {
            InjectionSiteSearch.findInjectionSitesByType(project, rawFqn)
                ?: InjectionSiteSearch.findAllInjectionSites(project)
        } else {
            InjectionSiteSearch.findAllInjectionSites(project)
        }
        val usages = sites.filter { site ->
            site.element != element &&
                matchesTypeForSite(injectionPoint.requiredType, site.requiredType) &&
                matchesTagsSymmetric(injectionPoint.tagInfo, site.tagInfo)
        }.map { it.element }.distinct()

        LOG.info("InjectionPoint resolve: providers=${providers.size}, usages=${usages.size}")
        return BeanNavigationTargets(providers, usages)
    }

    private fun resolveForProvider(element: PsiElement): BeanNavigationTargets? {
        val project = element.project

        // Check if this is a @Component class identifier
        val componentResult = resolveComponentClass(element)
        if (componentResult != null) return componentResult

        // Check if this is a factory method name identifier
        val factoryResult = resolveFactoryMethod(element)
        if (factoryResult != null) return factoryResult

        return null
    }

    private fun resolveComponentClass(element: PsiElement): BeanNavigationTargets? {
        val parent = element.parent
        val uClass: UClass
        val psiClass: PsiClass
        when (parent) {
            is PsiClass -> {
                if (element != parent.nameIdentifier) return null
                psiClass = parent
                uClass = parent.toUElement() as? UClass ?: return null
            }
            is KtClass -> {
                if (element != parent.nameIdentifier) return null
                uClass = parent.toUElement() as? UClass ?: return null
                psiClass = uClass.javaPsi
            }
            else -> return null
        }
        if (!psiClass.hasAnnotation(KoraAnnotations.COMPONENT)) return null

        val project = element.project
        val facade = com.intellij.psi.JavaPsiFacade.getInstance(project)
        val providedType = facade.elementFactory.createType(psiClass)
        val tagInfo = TagExtractor.extractTags(uClass)

        return resolveForProviderTypeAndTags(element, providedType, tagInfo, project)
    }

    private fun resolveFactoryMethod(element: PsiElement): BeanNavigationTargets? {
        val parent = element.parent
        val uMethod: UMethod
        when (parent) {
            is PsiMethod -> {
                if (element != parent.nameIdentifier) return null
                if (parent.isConstructor) return null
                uMethod = parent.toUElement() as? UMethod ?: return null
            }
            is KtNamedFunction -> {
                if (element != parent.nameIdentifier) return null
                uMethod = parent.toUElement() as? UMethod ?: return null
                if (uMethod.isConstructor) return null
            }
            else -> return null
        }

        val returnType = uMethod.javaPsi.returnType ?: return null
        if (returnType == com.intellij.psi.PsiTypes.voidType()) return null

        val uClass = uMethod.getParentOfType<UClass>() ?: return null
        if (!InjectionPointDetector.isKoraInjectionContext(uMethod, uClass) &&
            !uClass.javaPsi.hasAnnotation(KoraAnnotations.COMPONENT)
        ) {
            return null
        }

        val tagInfo = TagExtractor.extractTags(uMethod)
        val project = element.project

        return resolveForProviderTypeAndTags(element, returnType, tagInfo, project)
    }

    private fun resolveForProviderTypeAndTags(
        selfElement: PsiElement,
        providedType: PsiType,
        tagInfo: TagInfo,
        project: com.intellij.openapi.project.Project,
    ): BeanNavigationTargets {
        val scope = GlobalSearchScope.allScope(project)

        // Find usages: injection sites where our type/tags match (index-backed)
        val sites = findInjectionSitesForProvidedType(project, providedType)
        val usages = sites.filter { site ->
            matchesProviderToSite(providedType, tagInfo, site)
        }.map { it.element }.distinct()

        // Find other providers of the same type (excluding self) (index-backed)
        val providers = findProvidersForType(project, providedType, scope)
        val otherProviders = providers.filter { provider ->
            provider.element != selfElement &&
                matchesTypeAssignable(providedType, provider.providedType) &&
                matchesTagsExact(tagInfo, provider.tagInfo)
        }.map { it.element }.distinct()

        LOG.info("Provider resolve: otherProviders=${otherProviders.size}, usages=${usages.size}")
        return BeanNavigationTargets(otherProviders, usages)
    }

    /**
     * Index-backed: find providers whose raw type FQN matches [requiredType] or any of its subtypes.
     */
    private fun findProvidersForType(project: Project, requiredType: PsiType, scope: GlobalSearchScope): List<KoraProvider> {
        val rawFqn = KoraIndexUtil.getRawTypeFqn(requiredType) ?: return ProviderSearch.findAllProviders(project)

        val typeFqns = mutableListOf(rawFqn)
        if (requiredType is PsiClassType) {
            val requiredClass = requiredType.resolve()
            if (requiredClass != null) {
                ClassInheritorsSearch.search(requiredClass, scope, true).forEach { inheritor ->
                    inheritor.qualifiedName?.let { typeFqns.add(it) }
                }
            }
        }

        val result = ProviderSearch.findProvidersByTypes(project, typeFqns)
        // Fallback to full scan if index not available or returns nothing
        return result?.ifEmpty { ProviderSearch.findAllProviders(project) }
            ?: ProviderSearch.findAllProviders(project)
    }

    /**
     * Index-backed: find injection sites that require [providedType] or any of its supertypes.
     */
    private fun findInjectionSitesForProvidedType(project: Project, providedType: PsiType): List<KoraInjectionSite> {
        val rawFqn = KoraIndexUtil.getRawTypeFqn(providedType) ?: return InjectionSiteSearch.findAllInjectionSites(project)

        val typeFqns = mutableListOf(rawFqn)
        if (providedType is PsiClassType) {
            val providedClass = providedType.resolve()
            if (providedClass != null) {
                // Injection sites may require supertypes of the provided type
                for (superType in providedClass.supers) {
                    superType.qualifiedName?.let { typeFqns.add(it) }
                }
            }
        }

        val result = InjectionSiteSearch.findInjectionSitesByTypes(project, typeFqns)
        // Fallback to full scan if index not available or returns nothing
        return result?.ifEmpty { InjectionSiteSearch.findAllInjectionSites(project) }
            ?: InjectionSiteSearch.findAllInjectionSites(project)
    }

    private fun matchesProviderToSite(
        providedType: PsiType,
        providerTags: TagInfo,
        site: KoraInjectionSite,
    ): Boolean {
        val requiredType = site.requiredType
        val requiredTags = site.tagInfo

        // Type check: provider type must be assignable to required type
        if (!matchesTypeAssignable(requiredType, providedType)) return false

        // Tag check
        if (requiredTags.isTagAny) return true
        if (requiredTags.tagFqns.isEmpty()) return providerTags.tagFqns.isEmpty()
        return requiredTags.tagFqns == providerTags.tagFqns
    }

    private fun matchesTypeAssignable(requiredType: PsiType, providedType: PsiType): Boolean {
        // Full type check including generics
        if (requiredType.isAssignableFrom(providedType)) return true

        if (requiredType is PsiClassType && providedType is PsiClassType) {
            val requiredClass = requiredType.resolve() ?: return false
            val providedClass = providedType.resolve() ?: return false

            // Raw class must be compatible
            if (requiredClass != providedClass && !providedClass.isInheritor(requiredClass, true)) return false

            // When same raw class and both have type arguments, verify arguments match
            if (requiredClass == providedClass) {
                val reqArgs = requiredType.parameters
                val provArgs = providedType.parameters
                if (reqArgs.isNotEmpty() && provArgs.isNotEmpty() && reqArgs.size == provArgs.size) {
                    return reqArgs.zip(provArgs).all { (r, p) ->
                        // Type variable (e.g. <T>) matches anything
                        if (p is PsiClassType && p.resolve() is com.intellij.psi.PsiTypeParameter) return@all true
                        if (r is PsiClassType && r.resolve() is com.intellij.psi.PsiTypeParameter) return@all true
                        matchesTypeAssignable(r, p)
                    }
                }
            }

            return true
        }

        return false
    }

    private fun matchesTypeForSite(type1: PsiType, type2: PsiType): Boolean {
        // For finding sibling injection points, check if they request the same type
        if (type1 == type2) return true
        if (type1 is PsiClassType && type2 is PsiClassType) {
            val class1 = type1.resolve()
            val class2 = type2.resolve()
            return class1 != null && class1 == class2
        }
        return false
    }

    private fun matchesTagsSymmetric(tags1: TagInfo, tags2: TagInfo): Boolean {
        if (tags1.isTagAny || tags2.isTagAny) return true
        return tags1.tagFqns == tags2.tagFqns
    }

    private fun matchesTagsExact(tags1: TagInfo, tags2: TagInfo): Boolean {
        return tags1.tagFqns == tags2.tagFqns && tags1.isTagAny == tags2.isTagAny
    }
}
