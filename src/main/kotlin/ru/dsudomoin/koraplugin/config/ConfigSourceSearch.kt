package ru.dsudomoin.koraplugin.config

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.uast.UClass
import org.jetbrains.uast.toUElement
import ru.dsudomoin.koraplugin.KoraAnnotations

data class ConfigSourceEntry(
    val path: String,
    val psiClass: PsiClass,
)

object ConfigSourceSearch {

    fun findAllConfigSources(project: Project): List<ConfigSourceEntry> {
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            val result = doFindAllConfigSources(project)
            CachedValueProvider.Result.create(result, PsiModificationTracker.getInstance(project))
        }
    }

    private fun doFindAllConfigSources(project: Project): List<ConfigSourceEntry> {
        val scope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)
        val annotationClass = facade.findClass(KoraAnnotations.CONFIG_SOURCE, scope) ?: return emptyList()

        val result = mutableListOf<ConfigSourceEntry>()
        AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).forEach { psiClass ->
            val uClass = psiClass.toUElement() as? UClass ?: return@forEach
            val path = extractConfigSourcePath(uClass) ?: return@forEach
            result.add(ConfigSourceEntry(path, psiClass))
        }
        return result
    }

    private fun extractConfigSourcePath(uClass: UClass): String? {
        val annotation = uClass.uAnnotations.find {
            it.qualifiedName == KoraAnnotations.CONFIG_SOURCE
        } ?: return null
        val value = annotation.findAttributeValue("value")
        return value?.evaluate() as? String
    }
}
