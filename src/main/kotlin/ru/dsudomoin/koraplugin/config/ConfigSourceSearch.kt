package ru.dsudomoin.koraplugin.config

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import ru.dsudomoin.koraplugin.KoraAnnotations

data class ConfigSourceEntry(
    val path: String,
    val psiClass: PsiClass,
)

object ConfigSourceSearch {

    fun findAllConfigSources(project: Project): List<ConfigSourceEntry> {
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            val result = doFindAllConfigSources(project)
            CachedValueProvider.Result.create(
                result,
                PsiModificationTracker.getInstance(project).forLanguage(com.intellij.lang.java.JavaLanguage.INSTANCE),
                PsiModificationTracker.getInstance(project).forLanguage(org.jetbrains.kotlin.idea.KotlinLanguage.INSTANCE),
            )
        }
    }

    private fun doFindAllConfigSources(project: Project): List<ConfigSourceEntry> {
        val allScope = GlobalSearchScope.allScope(project)
        val projectScope = GlobalSearchScope.projectScope(project)
        val facade = JavaPsiFacade.getInstance(project)
        // Annotation class is in JAR → allScope; annotated classes are in project → projectScope
        val annotationClass = facade.findClass(KoraAnnotations.CONFIG_SOURCE, allScope) ?: return emptyList()

        val result = mutableListOf<ConfigSourceEntry>()
        AnnotatedElementsSearch.searchPsiClasses(annotationClass, projectScope).forEach { psiClass ->
            val path = extractConfigSourcePath(psiClass) ?: return@forEach
            result.add(ConfigSourceEntry(path, psiClass))
        }
        return result
    }

    /**
     * Cached set of FQNs of all types used as return types (directly, or as List/Set/Map element)
     * by methods/fields in @ConfigSource classes. Used for fast early exit in isReferencedFromConfigSource.
     */
    fun getReferencedTypeFqns(project: Project): Set<String> {
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            val fqns = buildReferencedTypeFqns(project)
            CachedValueProvider.Result.create(
                fqns,
                PsiModificationTracker.getInstance(project).forLanguage(com.intellij.lang.java.JavaLanguage.INSTANCE),
                PsiModificationTracker.getInstance(project).forLanguage(org.jetbrains.kotlin.idea.KotlinLanguage.INSTANCE),
            )
        }
    }

    private val COLLECTION_FQNS = setOf(
        "java.util.List", "java.util.Set", "java.util.Collection", "java.lang.Iterable",
    )

    private fun buildReferencedTypeFqns(project: Project): Set<String> {
        val entries = findAllConfigSources(project)
        val result = mutableSetOf<String>()
        for (entry in entries) {
            collectReturnTypeFqns(entry.psiClass, result, mutableSetOf())
        }
        return result
    }

    private fun collectReturnTypeFqns(psiClass: PsiClass, result: MutableSet<String>, visited: MutableSet<String>) {
        val fqn = psiClass.qualifiedName ?: return
        if (!visited.add(fqn)) return

        for (method in psiClass.methods) {
            val returnType = method.returnType ?: continue
            addTypeFqn(returnType, result, visited)
        }
        for (field in psiClass.fields) {
            addTypeFqn(field.type, result, visited)
        }
    }

    private fun addTypeFqn(type: com.intellij.psi.PsiType, result: MutableSet<String>, visited: MutableSet<String>) {
        val classType = type as? PsiClassType ?: return
        val resolved = classType.resolve() ?: return
        val resolvedFqn = resolved.qualifiedName ?: return

        // Unwrap Map<K, V> → add V's FQN
        if (resolvedFqn == "java.util.Map") {
            val typeArgs = classType.parameters
            if (typeArgs.size >= 2) {
                addTypeFqn(typeArgs[1], result, visited)
            }
            return
        }

        // Unwrap List/Set/Collection<T> → add T's FQN
        if (resolvedFqn in COLLECTION_FQNS) {
            val typeArgs = classType.parameters
            if (typeArgs.isNotEmpty()) {
                addTypeFqn(typeArgs[0], result, visited)
            }
            return
        }

        result.add(resolvedFqn)
        // Recurse into nested config types
        collectReturnTypeFqns(resolved, result, visited)
    }

    private fun extractConfigSourcePath(psiClass: PsiClass): String? {
        val annotation = psiClass.getAnnotation(KoraAnnotations.CONFIG_SOURCE) ?: return null
        return AnnotationUtil.getStringAttributeValue(annotation, "value")
    }
}
