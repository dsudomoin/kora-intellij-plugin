package ru.dsudomoin.koraplugin.util

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.AnnotatedElementsSearch

/**
 * Wrapper around [AnnotatedElementsSearch] that also handles Kotlin import aliases.
 *
 * [AnnotatedElementsSearch] uses the stub index keyed by annotation short name.
 * When a Kotlin file uses an import alias (`import ...Component as KoraComponent`),
 * the stub index stores the aliased name, so the search by original short name misses it.
 * [PsiClass.hasAnnotation] on light classes resolves aliases correctly.
 */
object KoraAnnotationSearch {

    /**
     * Finds project classes annotated with any of [annotationFqns].
     * Handles Kotlin import aliases that [AnnotatedElementsSearch] misses.
     */
    fun findAnnotatedClasses(
        annotationFqns: List<String>,
        project: Project,
        scope: GlobalSearchScope,
    ): Set<PsiClass> {
        val allScope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)
        val result = mutableSetOf<PsiClass>()
        val visited = mutableSetOf<String>()

        // Fast path: AnnotatedElementsSearch (works for Java + Kotlin without aliases)
        for (fqn in annotationFqns) {
            val annotationClass = facade.findClass(fqn, allScope) ?: continue
            AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).forEach { psiClass ->
                val qn = psiClass.qualifiedName ?: return@forEach
                if (visited.add(qn)) {
                    result.add(psiClass)
                }
            }
        }

        // Supplement: catch Kotlin import aliases.
        // Iterate all project classes and check hasAnnotation() which resolves aliases.
        val cache = PsiShortNamesCache.getInstance(project)
        for (name in cache.allClassNames) {
            for (psiClass in cache.getClassesByName(name, scope)) {
                val qn = psiClass.qualifiedName ?: continue
                if (qn in visited) continue
                if (annotationFqns.any { psiClass.hasAnnotation(it) }) {
                    visited.add(qn)
                    result.add(psiClass)
                }
            }
        }

        return result
    }
}
